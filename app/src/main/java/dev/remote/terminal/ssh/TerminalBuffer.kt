package dev.remote.terminal.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

data class TerminalCell(val text: String = " ", val foreground: Int = 7, val background: Int = 0)
data class TerminalCursor(val column: Int, val row: Int, val visible: Boolean)

/** Small VT-style screen model for ANSI interactive CLIs over a mobile SSH PTY. */
class TerminalBuffer(initialColumns: Int = 80, initialRows: Int = 32) {
    private data class ScreenState(
        val cells: Array<Array<TerminalCell>>,
        val x: Int,
        val y: Int,
        val savedX: Int,
        val savedY: Int,
        val foreground: Int,
        val background: Int,
        val cursorVisible: Boolean,
    )

    private var columns = initialColumns
    private var rows = initialRows
    private var cells = Array(rows) { Array(columns) { TerminalCell() } }
    private var resizeRestore: ScreenState? = null
    private var resizeRestoreOffset = 0
    private var primaryScreen: ScreenState? = null
    private var alternateScreen: ScreenState? = null
    private var inAlternateScreen = false
    private var x = 0
    private var y = 0
    private var savedX = 0
    private var savedY = 0
    private var fg = 7
    private var bg = 0
    private var cursorVisible = true
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var applicationCursorKeys = false
    private var bracketedPaste = false
    private var mouseTracking = false
    private var sgrMouse = false
    private var autoWrap = true
    private var wrapPending = false
    private var originMode = false
    private var insertMode = false
    private var parser = 0
    private val escape = StringBuilder()
    private var pendingHighSurrogate: Char? = null
    private val scrollbackRows = ArrayDeque<List<TerminalCell>>()
    private var discardedScrollbackRows = 0L
    private val mutableScreen = MutableStateFlow(snapshot())
    val screen: StateFlow<List<List<TerminalCell>>> = mutableScreen
    private val mutableScrollback = MutableStateFlow<List<List<TerminalCell>>>(emptyList())
    val scrollback: StateFlow<List<List<TerminalCell>>> = mutableScrollback
    private val mutableScrollbackBase = MutableStateFlow(0L)
    val scrollbackBase: StateFlow<Long> = mutableScrollbackBase
    private val mutableCursor = MutableStateFlow(TerminalCursor(x, y, cursorVisible))
    val cursor: StateFlow<TerminalCursor> = mutableCursor

    @Synchronized fun isAlternateScreen(): Boolean = inAlternateScreen

    @Synchronized fun pasteSequence(text: String): String =
        if (bracketedPaste) "\u001b[200~" + text.replace("\u001b[201~", "") + "\u001b[201~"
        else text.replace("\r\n", "\r").replace('\n', '\r')

    /** Returns a wheel report only while the remote application has enabled mouse tracking. */
    @Synchronized fun mouseWheelSequence(up: Boolean, column: Int, row: Int): String? {
        if (!mouseTracking) return null
        val button = if (up) 64 else 65
        val col = column.coerceIn(1, columns)
        val line = row.coerceIn(1, rows)
        return if (sgrMouse) "\u001b[<$button;$col;${line}M"
        else "\u001b[M${(button + 32).toChar()}${(col.coerceAtMost(223) + 32).toChar()}${(line.coerceAtMost(223) + 32).toChar()}"
    }

    /** Send a left-button press and release only when the application requested mouse input. */
    @Synchronized fun mouseClickSequence(column: Int, row: Int): String? {
        if (!mouseTracking) return null
        val col = column.coerceIn(1, columns)
        val line = row.coerceIn(1, rows)
        return if (sgrMouse) "\u001b[<0;$col;${line}M\u001b[<0;$col;${line}m"
        else {
            val position = "${(col.coerceAtMost(223) + 32).toChar()}${(line.coerceAtMost(223) + 32).toChar()}"
            "\u001b[M ${position}\u001b[M#${position}"
        }
    }

    @Synchronized fun cursorSequence(direction: Char, control: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
        if (!control && !alt && !shift && applicationCursorKeys) return "\u001bO$direction"
        if (control || alt || shift) {
            val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (control) 4 else 0)
            return "\u001b[1;${modifier}$direction"
        }
        return "\u001b[$direction"
    }

    /** Text currently visible on the terminal, used for prompt checks that must not match stale output. */
    @Synchronized fun visibleTextTail(maxRows: Int = 8): String {
        val firstRow = (cells.size - maxRows.coerceAtLeast(1)).coerceAtLeast(0)
        return cells.drop(firstRow)
            .joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }
            .trimEnd()
    }

    @Synchronized fun resize(newColumns: Int, newRows: Int) {
        val width = newColumns.coerceIn(24, 200)
        val height = newRows.coerceIn(8, 100)
        if (width == columns && height == rows) return
        val sameWidth = width == columns
        if (!sameWidth) {
            resizeRestore = null
            resizeRestoreOffset = 0
            val resizedHistory = scrollbackRows.map { row ->
                List(width) { column -> row.getOrNull(column) ?: TerminalCell() }
            }
            scrollbackRows.clear()
            resizedHistory.forEach(scrollbackRows::addLast)
        }
        if (!inAlternateScreen && height != rows) {
            // Preserve primary-screen rows in history while the viewport changes.
            resizeRestore = null
            resizeRestoreOffset = 0
            if (height < rows) {
                val shift = (y - height + 1).coerceIn(0, rows - height)
                repeat(shift) { index -> appendHistory(cells[index].toList()) }
                cells = Array(height) { r ->
                    Array(width) { c -> cells[r + shift].getOrElse(c) { TerminalCell() } }
                }
                y = (y - shift).coerceAtLeast(0)
                savedY = (savedY - shift).coerceAtLeast(0)
            } else {
                val restored = minOf(height - rows, scrollbackRows.size)
                val history = ArrayList<List<TerminalCell>>(restored)
                repeat(restored) { history.add(scrollbackRows.removeLast()) }
                history.reverse()
                val oldCells = cells
                cells = Array(height) { r ->
                    Array(width) { c ->
                        if (r < restored) history[r].getOrElse(c) { TerminalCell() }
                        else oldCells.getOrNull(r - restored)?.getOrNull(c) ?: TerminalCell()
                    }
                }
                y += restored
                savedY += restored
            }
        } else if (height < rows) {
            val shift = (y - height + 1).coerceIn(0, rows - height)
            if (sameWidth) {
                if (resizeRestore == null) resizeRestore = captureScreen()
                resizeRestoreOffset += shift
            }
            cells = Array(height) { r ->
                val sourceRow = r + shift
                Array(width) { c -> if (sourceRow < rows && c < columns) cells[sourceRow][c] else TerminalCell() }
            }
            y = (y - shift).coerceIn(0, height - 1)
            savedY = (savedY - shift).coerceIn(0, height - 1)
        } else if (height > rows && sameWidth && resizeRestore != null && height >= resizeRestore!!.cells.size) {
            val restore = resizeRestore!!
            val expanded = resizeCells(restore.cells, width, height)
            for (r in 0 until rows) {
                val destination = r + resizeRestoreOffset
                if (destination in 0 until height) {
                    expanded[destination] = Array(width) { c -> if (c < columns) cells[r][c] else TerminalCell() }
                }
            }
            cells = expanded
            y = (y + resizeRestoreOffset).coerceIn(0, height - 1)
            savedY = (savedY + resizeRestoreOffset).coerceIn(0, height - 1)
            resizeRestore = null
            resizeRestoreOffset = 0
        } else {
            cells = resizeCells(cells, width, height)
        }
        columns = width; rows = height
        x = x.coerceAtMost(columns - 1); y = y.coerceAtMost(rows - 1)
        savedX = savedX.coerceIn(0, columns - 1); savedY = savedY.coerceIn(0, rows - 1)
        scrollTop = 0; scrollBottom = rows - 1
        wrapPending = false
        primaryScreen = primaryScreen?.let { resizeScreen(it, width, height) }
        alternateScreen = alternateScreen?.let { resizeScreen(it, width, height) }
        publish()
    }

    @Synchronized fun feed(chunk: String) {
        chunk.forEach { char ->
            when (parser) {
                1 -> when (char) {
                    '[' -> { parser = 2; escape.setLength(0) }
                    ']' -> parser = 3
                    '(', ')', '*', '+', '-', '.', '/' -> parser = 5
                    'D' -> { lineFeed(); parser = 0 }
                    'E' -> { x = 0; lineFeed(); parser = 0 }
                    'M' -> { reverseIndex(); parser = 0 }
                    '7' -> { savedX = x; savedY = y; parser = 0 }
                    '8' -> { x = savedX.coerceIn(0, columns - 1); y = savedY.coerceIn(0, rows - 1); parser = 0 }
                    else -> parser = 0
                }
                2 -> if (char in '@'..'~') { executeCsi(escape.toString(), char); parser = 0 } else escape.append(char)
                3 -> when (char) { '\u0007' -> parser = 0; '\u001b' -> parser = 4 }
                4 -> parser = if (char == '\\') 0 else 3
                5 -> parser = 0 // Character-set designation consumes one more byte.
                else -> when (char) {
                    '\u001b' -> parser = 1
                    '\r' -> { x = 0; wrapPending = false }
                    '\n' -> { lineFeed(); wrapPending = false }
                    '\b' -> { x = (x - 1).coerceAtLeast(0); wrapPending = false }
                    '\t' -> x = ((x / 8 + 1) * 8).coerceAtMost(columns - 1)
                    else -> if (char >= ' ') putUtf16(char)
                }
            }
        }
        publish()
    }

    private fun executeCsi(raw: String, command: Char) {
        wrapPending = false
        val values = raw.trimStart('?').trimStart('>').split(';').map { it.toIntOrNull() ?: 0 }
        fun param(index: Int, fallback: Int = 1) = values.getOrNull(index)?.takeIf { it != 0 } ?: fallback
        val cursorTop = if (originMode) scrollTop else 0
        val cursorBottom = if (originMode) scrollBottom else rows - 1
        when (command) {
            'A' -> y = (y - param(0)).coerceAtLeast(cursorTop)
            'B' -> y = (y + param(0)).coerceAtMost(cursorBottom)
            'C' -> x = (x + param(0)).coerceAtMost(columns - 1)
            'D' -> x = (x - param(0)).coerceAtLeast(0)
            'E' -> { y = (y + param(0)).coerceAtMost(cursorBottom); x = 0 }
            'F' -> { y = (y - param(0)).coerceAtLeast(cursorTop); x = 0 }
            'G' -> x = (param(0) - 1).coerceIn(0, columns - 1)
            'd' -> y = (cursorTop + param(0) - 1).coerceIn(cursorTop, cursorBottom)
            'H', 'f' -> { y = (cursorTop + param(0) - 1).coerceIn(cursorTop, cursorBottom); x = (param(1) - 1).coerceIn(0, columns - 1) }
            'r' -> {
                scrollTop = (param(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = (values.getOrNull(1) ?: rows).minus(1).coerceIn(scrollTop, rows - 1)
                x = 0; y = 0
            }
            'S' -> repeat(param(0)) { scrollUp(keepHistory = scrollTop == 0 && !inAlternateScreen) }
            'T' -> repeat(param(0)) { scrollDown() }
            '@' -> repeat(param(0)) { insertCharacter() }
            'P' -> repeat(param(0)) { deleteCharacter() }
            'X' -> for (column in x until (x + param(0)).coerceAtMost(columns)) cells[y][column] = TerminalCell()
            'L' -> repeat(param(0)) { if (y in scrollTop..scrollBottom) scrollDownFrom(y) }
            'M' -> repeat(param(0)) { if (y in scrollTop..scrollBottom) scrollUpFrom(y) }
            'J' -> when (param(0, 0)) {
                2, 3 -> {
                    cells = Array(rows) { Array(columns) { TerminalCell() } }
                    resizeRestore = null; resizeRestoreOffset = 0
                    if (param(0, 0) == 3) {
                        scrollbackRows.clear()
                        discardedScrollbackRows = 0
                    }
                }
                0 -> { clearLineFromCursor(); for (r in y + 1 until rows) clearLine(r) }
                1 -> { for (r in 0 until y) clearLine(r); clearLineToCursor() }
            }
            'K' -> when (param(0, 0)) { 0 -> clearLineFromCursor(); 1 -> clearLineToCursor(); 2 -> clearLine(y) }
            'm' -> applySgr(values)
            's' -> { savedX = x; savedY = y }
            'u' -> { x = savedX.coerceIn(0, columns - 1); y = savedY.coerceIn(0, rows - 1) }
            'h', 'l' -> if (!raw.startsWith('?')) {
                if (4 in values) insertMode = command == 'h'
            } else {
                val enable = command == 'h'
                if (25 in values) cursorVisible = enable
                if (1 in values) applicationCursorKeys = enable
                if (2004 in values) bracketedPaste = enable
                if (values.any { it == 1000 || it == 1002 || it == 1003 }) mouseTracking = enable
                if (1006 in values) sgrMouse = enable
                if (7 in values) autoWrap = enable
                if (6 in values) { originMode = enable; x = 0; y = if (enable) scrollTop else 0 }
                when {
                    1049 in values || 1047 in values -> switchAlternateScreen(enable, clearOnEnter = true)
                    47 in values -> switchAlternateScreen(enable, clearOnEnter = false)
                }
            }
        }
    }

    private fun applySgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            val code = params[i]
            when {
            code == 0 -> { fg = 7; bg = 0 }
            code == 1 -> if (fg < 8) fg += 8
            code == 22 -> if (fg in 8..15) fg -= 8
            code in 30..37 -> fg = code - 30
            code in 90..97 -> fg = code - 90 + 8
            code == 39 -> fg = 7
            code in 40..47 -> bg = code - 40
            code in 100..107 -> bg = code - 100 + 8
            code == 49 -> bg = 0
            code == 38 || code == 48 -> {
                val mode = params.getOrNull(i + 1)
                val color = when (mode) {
                    5 -> params.getOrNull(i + 2)?.takeIf { it in 0..255 }?.let(::indexedColor)
                    2 -> if (i + 4 < params.size) {
                        val components = params.subList(i + 2, i + 5)
                        if (components.all { it in 0..255 }) 0x1000000 or (components[0] shl 16) or (components[1] shl 8) or components[2] else null
                    } else null
                    else -> null
                }
                if (color != null) { if (code == 38) fg = color else bg = color }
                i += when (mode) { 5 -> minOf(2, params.lastIndex - i); 2 -> minOf(4, params.lastIndex - i); else -> 0 }
            }
            }
            i++
        }
    }

    private fun putUtf16(char: Char) {
        val high = pendingHighSurrogate
        if (high != null) {
            pendingHighSurrogate = null
            if (Character.isLowSurrogate(char)) {
                val codePoint = Character.toCodePoint(high, char)
                putText(String(Character.toChars(codePoint)), if (isWide(codePoint)) 2 else 1)
                return
            }
            putText(high.toString(), 1)
        }
        if (Character.isHighSurrogate(char)) pendingHighSurrogate = char
        else {
            val codePoint = char.code
            if (isCombining(codePoint)) {
                val previous = (x - 1).coerceAtLeast(0)
                cells[y][previous] = cells[y][previous].copy(text = cells[y][previous].text.trimEnd() + char)
            } else putText(char.toString(), if (isWide(codePoint)) 2 else 1)
        }
    }

    private fun putText(text: String, width: Int) {
        if (wrapPending) {
            if (autoWrap) { x = 0; lineFeed() }
            wrapPending = false
        }
        var cellWidth = width.coerceIn(1, 2)
        if (cellWidth == 2 && x == columns - 1) {
            if (autoWrap) { x = 0; lineFeed() } else cellWidth = 1
        }
        if (insertMode) repeat(cellWidth) { insertCharacter() }
        cells[y][x] = TerminalCell(text, fg, bg)
        if (cellWidth == 2 && x + 1 < columns) cells[y][x + 1] = TerminalCell("", fg, bg)
        if (x + cellWidth >= columns) {
            x = columns - 1
            wrapPending = autoWrap
        } else {
            x += cellWidth
        }
    }

    private fun isCombining(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F || codePoint in 0x2329..0x232A || codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 || codePoint in 0xF900..0xFAFF || codePoint in 0xFE10..0xFE6F ||
            codePoint in 0xFF01..0xFF60 || codePoint in 0xFFE0..0xFFE6 || codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD

    private fun lineFeed() {
        if (y == scrollBottom) scrollUp(keepHistory = scrollTop == 0 && scrollBottom == rows - 1 && !inAlternateScreen)
        else y = (y + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        if (y == scrollTop) scrollDown() else y = (y - 1).coerceAtLeast(0)
    }

    private fun scrollUp(keepHistory: Boolean = false) {
        if (keepHistory) {
            appendHistory(cells[scrollTop].toList())
        }
        for (r in scrollTop until scrollBottom) cells[r] = cells[r + 1]
        cells[scrollBottom] = Array(columns) { TerminalCell() }
    }

    private fun appendHistory(row: List<TerminalCell>) {
        scrollbackRows.addLast(row)
    }

    private fun scrollDown() {
        for (r in scrollBottom downTo scrollTop + 1) cells[r] = cells[r - 1]
        cells[scrollTop] = Array(columns) { TerminalCell() }
    }

    private fun scrollUpFrom(startRow: Int) {
        for (r in startRow until scrollBottom) cells[r] = cells[r + 1]
        cells[scrollBottom] = Array(columns) { TerminalCell() }
    }

    private fun scrollDownFrom(startRow: Int) {
        for (r in scrollBottom downTo startRow + 1) cells[r] = cells[r - 1]
        cells[startRow] = Array(columns) { TerminalCell() }
    }

    private fun insertCharacter() {
        if (x !in 0 until columns) return
        for (column in columns - 1 downTo x + 1) cells[y][column] = cells[y][column - 1]
        cells[y][x] = TerminalCell()
    }

    private fun deleteCharacter() {
        if (x !in 0 until columns) return
        for (column in x until columns - 1) cells[y][column] = cells[y][column + 1]
        cells[y][columns - 1] = TerminalCell()
    }

    private fun switchAlternateScreen(enable: Boolean, clearOnEnter: Boolean) {
        if (enable == inAlternateScreen) {
            if (enable && clearOnEnter) {
                cells = Array(rows) { Array(columns) { TerminalCell() } }
                x = 0; y = 0; savedX = 0; savedY = 0
            }
            return
        }
        resizeRestore = null
        resizeRestoreOffset = 0
        if (enable) {
            primaryScreen = captureScreen()
            val savedAlternate = alternateScreen
            if (clearOnEnter || savedAlternate == null) {
                cells = Array(rows) { Array(columns) { TerminalCell() } }
                x = 0; y = 0; savedX = 0; savedY = 0; fg = 7; bg = 0; cursorVisible = true
            } else restoreScreen(resizeScreen(savedAlternate, columns, rows))
            inAlternateScreen = true
        } else {
            alternateScreen = captureScreen()
            primaryScreen?.let { restoreScreen(resizeScreen(it, columns, rows)) }
            primaryScreen = null
            inAlternateScreen = false
        }
        scrollTop = 0; scrollBottom = rows - 1
    }

    private fun captureScreen() = ScreenState(cells, x, y, savedX, savedY, fg, bg, cursorVisible)

    private fun restoreScreen(state: ScreenState) {
        cells = state.cells
        x = state.x.coerceIn(0, columns - 1); y = state.y.coerceIn(0, rows - 1)
        savedX = state.savedX.coerceIn(0, columns - 1); savedY = state.savedY.coerceIn(0, rows - 1)
        fg = state.foreground; bg = state.background; cursorVisible = state.cursorVisible
    }

    private fun resizeScreen(state: ScreenState, width: Int, height: Int) = state.copy(
        cells = resizeCells(state.cells, width, height),
        x = state.x.coerceIn(0, width - 1), y = state.y.coerceIn(0, height - 1),
        savedX = state.savedX.coerceIn(0, width - 1), savedY = state.savedY.coerceIn(0, height - 1),
    )

    private fun resizeCells(source: Array<Array<TerminalCell>>, width: Int, height: Int) =
        Array(height) { r -> Array(width) { c -> if (r < source.size && c < source[r].size) source[r][c] else TerminalCell() } }

    private fun clearLine(row: Int) { cells[row] = Array(columns) { TerminalCell() } }
    private fun clearLineFromCursor() { for (c in x until columns) cells[y][c] = TerminalCell() }
    private fun clearLineToCursor() { for (c in 0..x.coerceAtMost(columns - 1)) cells[y][c] = TerminalCell() }
    private fun snapshot(): List<List<TerminalCell>> = cells.map { it.toList() }
    private fun publish() {
        mutableScreen.value = snapshot()
        mutableScrollback.value = if (inAlternateScreen) emptyList() else scrollbackRows.toList()
        mutableScrollbackBase.value = discardedScrollbackRows
        mutableCursor.value = TerminalCursor(x.coerceIn(0, columns - 1), y.coerceIn(0, rows - 1), cursorVisible)
    }

}

private fun indexedColor(index: Int): Int = when (index) {
    in 0..15 -> index
    in 16..231 -> {
        val value = index - 16
        fun component(n: Int) = if (n == 0) 0 else 55 + n * 40
        0x1000000 or (component(value / 36) shl 16) or (component(value / 6 % 6) shl 8) or component(value % 6)
    }
    else -> { val gray = 8 + (index - 232) * 10; 0x1000000 or (gray shl 16) or (gray shl 8) or gray }
}

fun terminalColor(index: Int): Long = when (index) {
    0 -> 0xFF151B24; 1, 9 -> 0xFFF07178; 2, 10 -> 0xFF8BD49C; 3, 11 -> 0xFFE5C07B
    4, 12 -> 0xFF78A9FF; 5, 13 -> 0xFFC792EA; 6, 14 -> 0xFF62C6DA
    7, 15 -> 0xFFDCE4EF; 8 -> 0xFF788393; else -> if (index and 0x1000000 != 0) 0xFF000000 or (index.toLong() and 0xFFFFFF) else 0xFFF5F7FA
}
