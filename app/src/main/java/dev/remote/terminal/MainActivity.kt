package dev.remote.terminal

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.viewinterop.AndroidView
import dev.remote.terminal.data.AgentPreset
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.ProjectDirectory
import dev.remote.terminal.data.TerminalTask
import dev.remote.terminal.ssh.SshTerminalController
import dev.remote.terminal.ssh.HostKeyApprovalRequiredException
import dev.remote.terminal.ssh.RemoteDirectoryBrowser
import dev.remote.terminal.ssh.RemoteDirectoryEntry
import dev.remote.terminal.ssh.TerminalBuffer
import dev.remote.terminal.ssh.TerminalConnectionRegistry
import dev.remote.terminal.ssh.TerminalKeepAliveService
import dev.remote.terminal.ssh.terminalColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val Ink = Color(0xFF0A0F15)
private val Panel = Color(0xFF121923)
private val PanelRaised = Color(0xFF192331)
private val Edge = Color(0xFF283646)
private val White = Color(0xFFEAF0F6)
private val Muted = Color(0xFF91A0B2)
private val Mint = Color(0xFF83E2BD)
private val Blue = Color(0xFF84B5FF)
private val Coral = Color(0xFFFF8278)
private val ActionRequestRegex = Regex("(?i)(approve (this|the)? ?(command|action)?|allow (this|the)? ?(command|action)?|would you like to|run this command\\?|continue\\? ?\\[|\\[(y/n|yes/no)\\]|\\((y/n|yes/no)\\)|press\\s+(enter|return)\\s+to\\s+(continue|confirm|approve)|permission required|needs your approval|批准|是否允许|需要确认|是否继续)")
private val AutoEnterPromptRegex = Regex("(?:\\[Y/n\\]|\\(Y/n\\)|\\[Yes/no\\]|(?i:press\\s+(?:enter|return)\\s+to\\s+(?:continue|confirm|approve)))\\s*$")

private fun autoEnterPromptSignature(text: String): String? {
    val visibleText = text.trimEnd()
    if (!AutoEnterPromptRegex.containsMatchIn(visibleText)) return null
    return visibleText.lineSequence().filter { it.isNotBlank() }.toList().takeLast(2).joinToString("\n")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { RemoteTerminalApp(LocalStore(applicationContext)) }
    }
}

private class TerminalSessionState(val task: TerminalTask, private val mainHandler: Handler) {
    val buffer = TerminalBuffer()
    var status by mutableStateOf("等待连接")
    var unread by mutableStateOf(false)
    var needsAction by mutableStateOf(false)
    var autoEnterEligible by mutableStateOf(false)
    var autoEnterPromptSignature by mutableStateOf<String?>(null)
    var handledPromptSignature by mutableStateOf<String?>(null)
    var lastOutputAt by mutableStateOf(0L)
    var isStreaming by mutableStateOf(false)
    var hostFingerprint by mutableStateOf<String?>(null)
    var resolveHostKey: ((Boolean) -> Unit)? = null
    private var autoEnterRunnable: Runnable? = null
    lateinit var controller: SshTerminalController

    fun cancelAutoEnter() {
        autoEnterRunnable?.let(mainHandler::removeCallbacks)
        autoEnterRunnable = null
    }

    fun pauseAutoEnter() {
        cancelAutoEnter()
        autoEnterEligible = false
        autoEnterPromptSignature = null
    }

    fun onConnectionUnavailable() {
        autoEnterPromptSignature?.let { handledPromptSignature = it }
        pauseAutoEnter()
    }

    fun clearAttention() {
        autoEnterPromptSignature?.let { handledPromptSignature = it }
        cancelAutoEnter()
        needsAction = false
        autoEnterEligible = false
        autoEnterPromptSignature = null
        unread = false
    }

    fun scheduleAutoEnter(delaySeconds: Int, promptSignature: String, isPromptStillActive: () -> Boolean) {
        cancelAutoEnter()
        autoEnterPromptSignature = promptSignature
        lateinit var pending: Runnable
        pending = Runnable {
            if (autoEnterRunnable !== pending) return@Runnable
            autoEnterRunnable = null
            if (!needsAction || !autoEnterEligible || autoEnterPromptSignature != promptSignature || !isPromptStillActive()) {
                pauseAutoEnter()
                return@Runnable
            }
            if (controller.sendIfConnected("\r")) {
                handledPromptSignature = promptSignature
                clearAttention()
            } else pauseAutoEnter()
        }
        autoEnterRunnable = pending
        mainHandler.postDelayed(pending, delaySeconds.coerceIn(1, 30) * 1000L)
    }
}

private enum class AppRoute { HOME, MANAGE, TERMINAL, AGENTS }

@Composable
private fun RemoteTerminalApp(store: LocalStore) {
    val appContext = LocalContext.current.applicationContext
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, onPrimary = Ink, secondary = Blue, background = Ink, surface = Panel,
        onBackground = White, onSurface = White, error = Coral,
    )) {
        val hosts = remember { mutableStateListOf<HostProfile>().apply { addAll(store.loadHosts()) } }
        val agents = remember { mutableStateListOf<AgentPreset>().apply { addAll(store.loadAgents()) } }
        val sessions = remember { mutableStateListOf<TerminalSessionState>() }
        var selectedHostId by rememberSaveable { mutableStateOf(hosts.firstOrNull()?.id.orEmpty()) }
        var selectedAgentId by rememberSaveable { mutableStateOf(agents.firstOrNull()?.id.orEmpty()) }
        var route by rememberSaveable { mutableStateOf(AppRoute.HOME) }
        var activePage by rememberSaveable { mutableIntStateOf(0) }
        var autoEnterEnabled by remember { mutableStateOf(store.loadAutoEnterEnabled()) }
        var autoEnterDelaySeconds by remember { mutableIntStateOf(store.loadAutoEnterDelaySeconds()) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var editingHost by remember { mutableStateOf<HostProfile?>(null) }
        var addingHost by remember { mutableStateOf(false) }
        var editingDirectory by remember { mutableStateOf<Pair<String, ProjectDirectory?>?>(null) }
        var editingAgent by remember { mutableStateOf<AgentPreset?>(null) }
        var addingAgent by remember { mutableStateOf(false) }
        var deletingHost by remember { mutableStateOf<HostProfile?>(null) }
        var deletingDirectory by remember { mutableStateOf<Pair<String, ProjectDirectory>?>(null) }
        var deletingAgent by remember { mutableStateOf<AgentPreset?>(null) }
        var closingSession by remember { mutableStateOf<TerminalSessionState?>(null) }
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val selectedHost = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()
        val selectedAgent = agents.firstOrNull { it.id == selectedAgentId } ?: agents.firstOrNull()

        fun saveHosts(updated: List<HostProfile>) {
            hosts.clear(); hosts.addAll(updated); store.saveHosts(updated)
            if (selectedHostId !in updated.map { it.id }) selectedHostId = updated.firstOrNull()?.id.orEmpty()
        }

        fun closeSession(session: TerminalSessionState) {
            val index = sessions.indexOf(session)
            if (index < 0) return
            session.cancelAutoEnter()
            TerminalConnectionRegistry.unregister(session.controller)
            session.controller.close()
            sessions.removeAt(index)
            if (sessions.isEmpty()) {
                route = AppRoute.HOME
                TerminalKeepAliveService.stop(appContext)
            } else {
                activePage = (index - 1).coerceIn(0, sessions.lastIndex)
            }
        }

        fun openTask(host: HostProfile, directory: ProjectDirectory, agent: AgentPreset) {
            runCatching { TerminalKeepAliveService.start(appContext) }
                .onFailure { error -> scope.launch { snackbars.showSnackbar("后台 SSH 保活未能启动：${error.message ?: "系统拒绝启动服务"}") } }
            val same = sessions.count { it.task.host.id == host.id && it.task.directory.id == directory.id && it.task.agent.id == agent.id }
            val task = TerminalTask(UUID.randomUUID().toString(), host, directory, agent,
                if (same == 0) agent.name else "${agent.name} ${same + 1}")
            val state = TerminalSessionState(task, mainHandler)
            state.controller = SshTerminalController(
                task = task, store = store,
                onStatus = { value -> mainHandler.post {
                    state.status = value
                    if (value.contains("重连") || value.contains("恢复 SSH") || value == "已断开" || value.startsWith("远端终端已结束")) {
                        state.onConnectionUnavailable()
                    }
                } },
                onBytes = { bytes ->
                    state.buffer.feed(bytes)
                    mainHandler.post {
                        state.lastOutputAt = android.os.SystemClock.elapsedRealtime()
                        state.isStreaming = true
                        mainHandler.postDelayed({
                            if (android.os.SystemClock.elapsedRealtime() - state.lastOutputAt >= 1_100L) state.isStreaming = false
                        }, 1_150L)
                        val visibleText = state.buffer.visibleTextTail()
                        val hasRequest = ActionRequestRegex.containsMatchIn(visibleText)
                        val promptSignature = if (hasRequest) autoEnterPromptSignature(visibleText) else null
                        val previousPromptSignature = state.autoEnterPromptSignature
                        state.needsAction = hasRequest
                        if (promptSignature != null) {
                            state.autoEnterPromptSignature = promptSignature
                            state.autoEnterEligible = promptSignature != state.handledPromptSignature
                            if (state.autoEnterEligible && autoEnterEnabled && previousPromptSignature != promptSignature) {
                                state.scheduleAutoEnter(autoEnterDelaySeconds, promptSignature) {
                                    autoEnterPromptSignature(state.buffer.visibleTextTail()) == promptSignature
                                }
                            } else if (!state.autoEnterEligible || !autoEnterEnabled) {
                                state.cancelAutoEnter()
                            }
                        } else {
                            state.handledPromptSignature = null
                            state.pauseAutoEnter()
                        }
                        if (route != AppRoute.TERMINAL || sessions.getOrNull(activePage) !== state) {
                            state.unread = true
                        }
                    }
                },
                onHostKey = { fingerprint, resolver -> mainHandler.post { state.hostFingerprint = fingerprint; state.resolveHostKey = resolver } },
            )
            TerminalConnectionRegistry.register(state.controller)
            sessions.add(state)
            activePage = sessions.lastIndex
            route = AppRoute.TERMINAL
            state.controller.start()
        }

        BackHandler(enabled = route != AppRoute.HOME) {
            if (route == AppRoute.TERMINAL) route = AppRoute.HOME else route = AppRoute.HOME
        }

        Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().imePadding().navigationBarsPadding()) {
            when (route) {
                AppRoute.HOME -> HomeScreen(
                    hosts = hosts, agents = agents, selectedHost = selectedHost, selectedAgent = selectedAgent,
                    sessions = sessions, onSelectHost = { selectedHostId = it.id }, onSelectAgent = { selectedAgentId = it.id },
                    onManage = { route = AppRoute.MANAGE }, onAddHost = { addingHost = true },
                    onAddDirectory = { selectedHost?.let { editingDirectory = it.id to null } },
                    onOpenAgents = { route = AppRoute.AGENTS },
                    onOpenSettings = { showSettings = true },
                    onStartTask = { host, dir, agent -> openTask(host, dir, agent) },
                    onResumeSession = { index -> activePage = index; sessions[index].unread = false; route = AppRoute.TERMINAL },
                )
                AppRoute.MANAGE -> ManageScreen(
                    hosts = hosts, selectedHost = selectedHost,
                    onBack = { route = AppRoute.HOME }, onSelectHost = { selectedHostId = it.id }, onAddHost = { addingHost = true },
                    onEditHost = { editingHost = it }, onDeleteHost = { deletingHost = it },
                    onAddDirectory = { selectedHost?.let { editingDirectory = it.id to null } },
                    onEditDirectory = { host, dir -> editingDirectory = host.id to dir },
                    onDeleteDirectory = { host, dir -> deletingDirectory = host.id to dir },
                )
                AppRoute.AGENTS -> AgentsScreen(
                    agents = agents, onBack = { route = AppRoute.HOME }, onAdd = { addingAgent = true },
                    onEdit = { editingAgent = it }, onDelete = { deletingAgent = it },
                )
                AppRoute.TERMINAL -> TerminalWorkspace(
                    sessions = sessions, initialIndex = activePage,
                    onPageSelected = { index -> activePage = index; sessions.getOrNull(index)?.let { it.unread = false } },
                    onBack = { route = AppRoute.HOME }, onNewTask = { route = AppRoute.HOME },
                    onCloseTask = { index -> sessions.getOrNull(index)?.let { closingSession = it } },
                )
            }
            SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        if (addingHost || editingHost != null) HostEditorDialog(
            initial = editingHost,
            onDismiss = { addingHost = false; editingHost = null },
            onSave = { host ->
                val wasNew = hosts.none { it.id == host.id }
                saveHosts(if (wasNew) hosts.toList() + host else hosts.map { if (it.id == host.id) host else it })
                selectedHostId = host.id
                addingHost = false; editingHost = null
            },
        )
        editingDirectory?.let { (hostId, initial) ->
            val owner = hosts.firstOrNull { it.id == hostId }
            if (owner != null) DirectoryEditorDialog(owner, store, initial, onDismiss = { editingDirectory = null }) { dir ->
                saveHosts(hosts.map { host -> if (host.id == hostId) host.copy(directories = if (initial == null) host.directories + dir else host.directories.map { if (it.id == dir.id) dir else it }) else host })
                editingDirectory = null
            }
        }
        if (addingAgent || editingAgent != null) AgentEditorDialog(
            initial = editingAgent, onDismiss = { addingAgent = false; editingAgent = null },
            onSave = { agent ->
                if (agents.any { it.id == agent.id }) agents.indices.forEach { i -> if (agents[i].id == agent.id) agents[i] = agent }
                else agents.add(agent)
                store.saveAgents(agents.toList())
                if (selectedAgentId.isBlank()) selectedAgentId = agent.id
                addingAgent = false; editingAgent = null
            },
        )
        deletingHost?.let { host -> ConfirmDeleteDialog(
            title = "删除主机？", detail = "${host.name} 及其目录配置将从本机移除。",
            onConfirm = { saveHosts(hosts.filterNot { it.id == host.id }); deletingHost = null },
            onDismiss = { deletingHost = null },
        ) }
        deletingDirectory?.let { (hostId, dir) -> ConfirmDeleteDialog(
            title = "移除开发目录？", detail = dir.path,
            onConfirm = {
                saveHosts(hosts.map { if (it.id == hostId) it.copy(directories = it.directories.filterNot { d -> d.id == dir.id }) else it })
                deletingDirectory = null
            },
            onDismiss = { deletingDirectory = null },
        ) }
        deletingAgent?.let { agent -> ConfirmDeleteDialog(
            title = "删除 Agent？", detail = "${agent.name} 的自定义配置将被移除。",
            onConfirm = {
                agents.removeAll { it.id == agent.id }; store.saveAgents(agents.toList())
                selectedAgentId = agents.firstOrNull()?.id.orEmpty(); deletingAgent = null
            },
            onDismiss = { deletingAgent = null },
        ) }
        closingSession?.let { session -> ConfirmDeleteDialog(
            title = "关闭会话？", detail = "${session.task.displayName} 的 SSH 连接将断开。",
            confirmLabel = "关闭",
            onConfirm = { closeSession(session); closingSession = null },
            onDismiss = { closingSession = null },
        ) }

        if (showSettings) AutoEnterSettingsDialog(
            enabled = autoEnterEnabled,
            delaySeconds = autoEnterDelaySeconds,
            onEnabledChange = { enabled ->
                autoEnterEnabled = enabled
                store.saveAutoEnterEnabled(enabled)
                sessions.forEach { session ->
                    if (enabled && session.needsAction && session.autoEnterEligible) {
                        session.autoEnterPromptSignature?.let { signature ->
                            session.scheduleAutoEnter(autoEnterDelaySeconds, signature) {
                                autoEnterPromptSignature(session.buffer.visibleTextTail()) == signature
                            }
                        }
                    }
                    else if (!enabled) session.cancelAutoEnter()
                }
            },
            onDelaySecondsChange = { seconds ->
                autoEnterDelaySeconds = seconds
                store.saveAutoEnterDelaySeconds(seconds)
                if (autoEnterEnabled) sessions.filter { it.needsAction && it.autoEnterEligible }.forEach { session ->
                    session.autoEnterPromptSignature?.let { signature ->
                        session.scheduleAutoEnter(seconds, signature) {
                            autoEnterPromptSignature(session.buffer.visibleTextTail()) == signature
                        }
                    }
                }
            },
            onDismiss = { showSettings = false },
        )

        sessions.forEach { session ->
            session.hostFingerprint?.let { fingerprint ->
                AlertDialog(
                    onDismissRequest = { session.resolveHostKey?.invoke(false); session.hostFingerprint = null },
                    containerColor = Panel,
                    title = { Text("首次连接这台主机？", color = White, fontWeight = FontWeight.SemiBold) },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请核对 SSH 主机指纹。接受后会保存在本机，后续连接会自动校验。", color = Muted)
                        Text("${session.task.host.address}:${session.task.host.port}", color = White, fontWeight = FontWeight.Medium)
                        Text(fingerprint, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    } },
                    confirmButton = { TextButton(onClick = { session.resolveHostKey?.invoke(true); session.hostFingerprint = null }) { Text("信任并连接", color = Mint) } },
                    dismissButton = { TextButton(onClick = { session.resolveHostKey?.invoke(false); session.hostFingerprint = null }) { Text("取消", color = Muted) } },
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    hosts: List<HostProfile>, agents: List<AgentPreset>, selectedHost: HostProfile?, selectedAgent: AgentPreset?,
    sessions: List<TerminalSessionState>, onSelectHost: (HostProfile) -> Unit, onSelectAgent: (AgentPreset) -> Unit,
    onManage: () -> Unit, onAddHost: () -> Unit, onAddDirectory: () -> Unit, onOpenAgents: () -> Unit, onOpenSettings: () -> Unit,
    onStartTask: (HostProfile, ProjectDirectory, AgentPreset) -> Unit, onResumeSession: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Terminal, null, tint = Mint, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("REMOTE DEV", color = White, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 17.sp)
                Text("随时接回你的开发现场", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = onManage) {
                Icon(Icons.Default.Dns, contentDescription = "管理主机和目录", tint = Muted)
            }
            IconButton(onClick = onOpenAgents) {
                Icon(Icons.Default.SmartToy, contentDescription = "Agent 配置", tint = Muted)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Muted)
            }
        }

        if (sessions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text("进行中的任务", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp)); Text("${sessions.size}", color = Muted, fontSize = 12.sp)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(bottom = 22.dp)) {
                items(sessions, key = { it.task.id }) { session ->
                    Surface(onClick = { onResumeSession(sessions.indexOf(session)) }, color = Panel, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (session.needsAction) Coral.copy(alpha = .6f) else Edge)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(session.task.agent.icon, fontSize = 15.sp); Spacer(Modifier.width(8.dp))
                            Column {
                                Text(session.task.displayName, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(if (session.needsAction) "需要确认" else "${session.task.host.name} · ${session.task.directory.name}", color = if (session.needsAction) Coral else Muted, fontSize = 10.sp)
                            }
                            if (session.needsAction) { Spacer(Modifier.width(8.dp)); Text("!", color = Coral, fontWeight = FontWeight.Bold) }
                            else if (session.unread) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(7.dp).background(Blue, CircleShape)) }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("连接主机", "${hosts.size} 台")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddHost) { Icon(Icons.Default.Add, null, tint = Mint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加", color = Mint) }
        }
        if (hosts.isEmpty()) {
            Surface(color = Panel, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("把开发环境放进口袋", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("点击右上角“添加”，配置 SSH 主机并保存常用目录。", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 17.dp)) {
                items(hosts, key = { it.id }) { host ->
                    val selected = selectedHost?.id == host.id
                    Surface(onClick = { onSelectHost(host) }, color = if (selected) Color(0xFF20342F) else Panel,
                        shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Mint.copy(alpha = .7f) else Edge)) {
                        Row(Modifier.width(160.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(host.icon, fontSize = 21.sp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(host.name, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                Text("${host.username}@${host.address}", color = Muted, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            selectedHost?.let { host ->
                Divider(color = Edge.copy(alpha = .8f))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("开发目录", "${host.directories.size} 个")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAddDirectory) { Icon(Icons.Default.Add, null, tint = Mint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text("添加目录", color = Mint) }
                }
                if (agents.isEmpty()) {
                    EmptyRow("还没有 Agent 配置", "添加一个命令，例如 codex 或 opencode。", onOpenAgents, "去配置")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 13.dp)) {
                        items(agents, key = { it.id }) { agent ->
                            val selected = selectedAgent?.id == agent.id
                            AssistChip(onClick = { onSelectAgent(agent) }, label = { Text("${agent.icon}  ${agent.name}", fontSize = 12.sp) },
                                leadingIcon = if (selected) ({ Text("✓", color = Mint, fontSize = 12.sp) }) else null)
                        }
                    }
                }
                if (host.directories.isEmpty()) {
                    EmptyRow("保存你的第一个项目目录", "例如 /workspace/my-app", onAddDirectory, "添加目录")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        host.directories.forEach { dir ->
                            ProjectRow(
                                directory = dir, selectedAgent = selectedAgent,
                                enabled = selectedAgent != null,
                                onClick = { if (selectedAgent != null) onStartTask(host, dir, selectedAgent) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ManageScreen(
    hosts: List<HostProfile>, selectedHost: HostProfile?, onBack: () -> Unit,
    onSelectHost: (HostProfile) -> Unit, onAddHost: () -> Unit,
    onEditHost: (HostProfile) -> Unit, onDeleteHost: (HostProfile) -> Unit,
    onAddDirectory: () -> Unit, onEditDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onDeleteDirectory: (HostProfile, ProjectDirectory) -> Unit,
) {
    var visibleHostId by remember { mutableStateOf(selectedHost?.id.orEmpty()) }
    LaunchedEffect(selectedHost?.id) {
        if (selectedHost != null) visibleHostId = selectedHost.id
        else if (visibleHostId !in hosts.map { it.id }) visibleHostId = hosts.firstOrNull()?.id.orEmpty()
    }
    val visibleHost = hosts.firstOrNull { it.id == visibleHostId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = White) }
            Column(Modifier.weight(1f)) {
                Text("主机与目录", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("编辑连接信息和常用开发路径", color = Muted, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("主机列表", "${hosts.size} 台")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddHost, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, "添加主机", tint = Mint) }
        }
        if (hosts.isEmpty()) {
            Surface(color = Panel.copy(alpha = .7f), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("还没有主机", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("点击主机列表右上角的 + 添加 SSH 主机。", color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hosts.forEach { host ->
                    val selected = visibleHostId == host.id
                    Surface(onClick = { visibleHostId = host.id; onSelectHost(host) }, color = if (selected) Color(0xFF20342F) else Panel,
                        shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Mint.copy(alpha = .65f) else Edge)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(host.icon, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 5.dp))
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(host.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("${host.username}@${host.address}:${host.port}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
                            }
                            IconButton(onClick = { onEditHost(host) }) { Icon(Icons.Default.Edit, "编辑主机", tint = Muted, modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { onDeleteHost(host) }) { Icon(Icons.Default.DeleteOutline, "删除主机", tint = Muted, modifier = Modifier.size(19.dp)) }
                        }
                    }
                }
            }
        }
        visibleHost?.let { host ->
            Divider(color = Edge, modifier = Modifier.padding(top = 20.dp, bottom = 15.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("${host.name} · 开发目录", "${host.directories.size} 个")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAddDirectory) {
                    Icon(Icons.Default.Add, null, tint = Mint, modifier = Modifier.size(17.dp))
                    Text("添加", color = Mint, fontSize = 12.sp)
                }
            }
            if (host.directories.isEmpty()) {
                EmptyRow("这个主机还没有目录", "保存项目路径后，就能从首页直接启动任务。", onAddDirectory, "添加目录")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    host.directories.forEach { dir ->
                        Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                            Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dir.icon, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                    Text(dir.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(dir.path, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
                                }
                                IconButton(onClick = { onEditDirectory(host, dir) }) { Icon(Icons.Default.Edit, "编辑目录", tint = Muted, modifier = Modifier.size(17.dp)) }
                                IconButton(onClick = { onDeleteDirectory(host, dir) }) { Icon(Icons.Default.DeleteOutline, "删除目录", tint = Muted, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Spacer(Modifier.width(8.dp)); Text(trailing, color = Muted, fontSize = 11.sp)
}

@Composable
private fun ProjectRow(directory: ProjectDirectory, selectedAgent: AgentPreset?, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Panel, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
        Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised), contentAlignment = Alignment.Center) { Text(directory.icon, fontSize = 20.sp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(directory.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                Text(directory.path, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
                if (selectedAgent != null) Text("${selectedAgent.icon}  ${selectedAgent.name} · 新建任务", color = Mint.copy(alpha = .88f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Default.ArrowForward, null, tint = if (enabled) Mint else Muted, modifier = Modifier.padding(start = 8.dp).size(18.dp))
        }
    }
}

@Composable
private fun EmptyRow(title: String, detail: String, action: () -> Unit, label: String) {
    Surface(color = Panel.copy(alpha = .7f), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text(detail, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
            TextButton(onClick = action) { Text(label, color = Mint, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AgentsScreen(agents: List<AgentPreset>, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (AgentPreset) -> Unit, onDelete: (AgentPreset) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = White) }
            Column(Modifier.weight(1f)) { Text("Agent 配置", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("名称、图标和启动命令都可自定义", color = Muted, fontSize = 12.sp) }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, null, tint = Mint) }
        }
        Text("已配置 ${agents.size} 个", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            agents.forEach { agent ->
                Surface(color = Panel, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised), contentAlignment = Alignment.Center) { Text(agent.icon, fontSize = 19.sp) }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) { Text(agent.name, color = White, fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(agent.command, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                        IconButton(onClick = { onEdit(agent) }) { Icon(Icons.Default.Edit, null, tint = Muted, modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = { onDelete(agent) }) { Icon(Icons.Default.DeleteOutline, null, tint = Muted, modifier = Modifier.size(19.dp)) }
                    }
                }
            }
            if (agents.isEmpty()) EmptyRow("添加一个 Agent", "命令会在所选开发目录中启动。", onAdd, "添加")
        }
    }
}

@Composable
private fun TerminalWorkspace(
    sessions: List<TerminalSessionState>, initialIndex: Int, onPageSelected: (Int) -> Unit,
    onBack: () -> Unit, onNewTask: () -> Unit, onCloseTask: (Int) -> Unit,
) {
    if (sessions.isEmpty()) return
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, sessions.lastIndex), pageCount = { sessions.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage, sessions.size) { if (pager.currentPage in sessions.indices) onPageSelected(pager.currentPage) }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(start = 5.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.ArrowBack, null, tint = White, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text("${sessions[pager.currentPage].task.host.icon}  ${sessions[pager.currentPage].task.host.name}", color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                Text(sessions[pager.currentPage].status, color = statusTint(sessions[pager.currentPage].status), fontSize = 10.sp, maxLines = 1)
            }
            IconButton(onClick = onNewTask, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "新建任务", tint = Mint) }
        }
        Divider(color = Edge)
        Row(Modifier.fillMaxWidth().height(45.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            sessions.forEachIndexed { index, session ->
                val selected = index == pager.currentPage
                SessionTab(session, selected, onClick = { scope.launch { pager.animateScrollToPage(index) } }, onClose = { onCloseTask(index) })
                Spacer(Modifier.width(6.dp))
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), key = { sessions[it].task.id }) { page ->
            TerminalPage(sessions[page], page == pager.currentPage)
        }
    }
}

@Composable
private fun SessionTab(session: TerminalSessionState, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "activity")
    val pulse by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val color = when { session.needsAction -> Coral; selected -> Mint; session.unread -> Blue; else -> statusTint(session.status) }
    Surface(onClick = onClick, color = if (selected) PanelRaised else Panel, shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color.copy(alpha = .7f) else Edge)) {
        Row(Modifier.padding(start = 10.dp, end = 5.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).alpha(if (session.isStreaming) pulse else 1f).background(color, CircleShape))
            Text(session.task.agent.icon, fontSize = 12.sp)
            Text(session.task.displayName, color = if (selected) White else Muted, fontSize = 11.sp, maxLines = 1)
            if (session.needsAction) Text("!", color = Coral, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Close, null, tint = Muted, modifier = Modifier.size(13.dp)) }
        }
    }
}

@Composable
private fun TerminalPage(session: TerminalSessionState, isCurrent: Boolean) {
    val screen by session.buffer.screen.collectAsState()
    val scrollback by session.buffer.scrollback.collectAsState()
    val scrollbackBase by session.buffer.scrollbackBase.collectAsState()
    val terminalCursor by session.buffer.cursor.collectAsState()
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val context = LocalContext.current
    val inputMethodManager = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    var terminalViewport by remember(session.task.id) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var inputView by remember(session.task.id) { mutableStateOf<EditText?>(null) }
    var ctrlArmed by remember(session.task.id) { mutableStateOf(false) }
    var altArmed by remember(session.task.id) { mutableStateOf(false) }
    var scrollbackTopRow by remember(session.task.id) { mutableStateOf<Float?>(null) }
    var observedScrollbackBase by remember(session.task.id) { mutableStateOf(0L) }
    val lineHeightPx = 16.3f * density.density
    val gestureVisibleRows = (terminalViewport.height / lineHeightPx).toInt().coerceIn(8, 100)
    val allRows = remember(scrollback, screen) { scrollback + screen }
    val liveFirstVisibleRow = (allRows.size - gestureVisibleRows).coerceAtLeast(0)
    val latestLiveFirstVisibleRow by rememberUpdatedState(liveFirstVisibleRow)
    val latestScrollbackTopRow by rememberUpdatedState(scrollbackTopRow)
    LaunchedEffect(scrollbackBase) {
        val discardedRows = (scrollbackBase - observedScrollbackBase).coerceAtLeast(0).toInt()
        if (discardedRows > 0) {
            scrollbackTopRow = scrollbackTopRow?.minus(discardedRows)?.coerceAtLeast(0f)
        }
        observedScrollbackBase = scrollbackBase
    }
    LaunchedEffect(session.task.id, isCurrent) {
        if (isCurrent) inputView?.requestFocus()
    }
    val showKeyboard: () -> Unit = {
        inputView?.let { view ->
            view.requestFocus()
            view.post { inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    val hideKeyboard: () -> Unit = {
        inputView?.let { view -> inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0) }
    }
    val historyScrollState = rememberScrollableState { delta ->
        if (!isCurrent) return@rememberScrollableState 0f
        val liveTop = latestLiveFirstVisibleRow.toFloat()
        val currentTop = latestScrollbackTopRow ?: liveTop
        val nextTop = (currentTop - delta / lineHeightPx).coerceIn(0f, liveTop)
        scrollbackTopRow = if (nextTop >= liveTop) null else nextTop
        (currentTop - nextTop) * lineHeightPx
    }

    // IME transitions resize the canvas over many animation frames. Resize the PTY and
    // backing grid only after the viewport settles, or the agent receives a burst of
    // window-change requests and the local screen repeatedly truncates/recreates rows.
    LaunchedEffect(session.task.id, terminalViewport, keyboardVisible) {
        if (terminalViewport.width > 0 && terminalViewport.height > 0) {
            kotlinx.coroutines.delay(220)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12f * density.density
                typeface = Typeface.create("monospace", Typeface.NORMAL)
            }
            val charWidth = paint.measureText("M").coerceAtLeast(1f)
            val lineHeight = 16.3f * density.density
            val cols = (terminalViewport.width / charWidth).toInt().coerceIn(24, 200)
            val rows = (terminalViewport.height / lineHeight).toInt().coerceIn(8, 100)
            session.buffer.resize(cols, rows)
            session.controller.resize(cols, rows)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(session.task.directory.icon, fontSize = 12.sp); Spacer(Modifier.width(5.dp))
            Text(session.task.directory.path, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(session.task.agent.name, color = Mint, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Surface(onClick = {
                if (keyboardVisible) hideKeyboard() else showKeyboard()
            }, color = PanelRaised, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                Box(Modifier.size(width = 34.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Keyboard, "打开或收起键盘", tint = if (keyboardVisible) Mint else Blue, modifier = Modifier.size(17.dp))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color(0xFF070B10))) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { terminalViewport = it }
                    .scrollable(state = historyScrollState, orientation = Orientation.Vertical)
                    .pointerInput(isCurrent, inputView) {
                        detectTapGestures(onTap = { if (isCurrent) showKeyboard() })
                    },
            ) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 12f * density.density
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                }
                val charWidth = paint.measureText("M").coerceAtLeast(1f)
                val lineHeight = lineHeightPx
                val visibleCols = (size.width / charWidth).toInt().coerceIn(24, 200)
                val visibleRows = (size.height / lineHeight).toInt().coerceIn(8, 100)
                val liveFirstRow = (allRows.size - visibleRows).coerceAtLeast(0)
                val firstVisibleRow = (scrollbackTopRow ?: liveFirstRow.toFloat()).coerceIn(0f, liveFirstRow.toFloat())
                val firstVisibleIndex = firstVisibleRow.toInt()
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val lastVisibleRow = minOf(allRows.size, firstVisibleIndex + visibleRows + 1)
                    for (screenRow in firstVisibleIndex until lastVisibleRow) {
                        val drawRow = screenRow - firstVisibleRow
                        val line = allRows[screenRow]
                        for (c in 0 until minOf(line.size, visibleCols)) {
                            val cell = line[c]
                            if (cell.background != 0) {
                                paint.color = android.graphics.Color.parseColor("#" + terminalColor(cell.background).toString(16).takeLast(6))
                                native.drawRect(c * charWidth, drawRow * lineHeight, (c + 1) * charWidth, (drawRow + 1) * lineHeight, paint)
                            }
                            if (cell.text.isNotEmpty() && cell.text != " ") {
                                paint.color = terminalColor(cell.foreground).toInt()
                                native.drawText(cell.text, c * charWidth, (drawRow + 1) * lineHeight - 2f, paint)
                            }
                        }
                    }
                    val cursorDisplayRow = scrollback.size + terminalCursor.row - firstVisibleRow
                    if (terminalCursor.visible && terminalCursor.column < visibleCols && cursorDisplayRow >= 0f && cursorDisplayRow < visibleRows) {
                        val left = terminalCursor.column * charWidth
                        val top = cursorDisplayRow * lineHeight
                        paint.color = Mint.copy(alpha = .72f).toArgb()
                        native.drawRect(left, top, left + charWidth, top + lineHeight, paint)
                        val cursorCell = screen.getOrNull(terminalCursor.row)?.getOrNull(terminalCursor.column)
                        if (cursorCell != null && cursorCell.text.isNotBlank()) {
                            paint.color = Ink.toArgb()
                            native.drawText(cursorCell.text, left, top + lineHeight - 2f, paint)
                        }
                    }
                }
            }
        }
        AndroidView(
            modifier = Modifier.size(1.dp).alpha(0.01f),
            factory = { viewContext ->
                var previous = ""
                var ignoreChange = false
                lateinit var editor: EditText
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(editable: Editable?) {
                        val next = editable?.toString().orEmpty()
                        if (ignoreChange) { previous = next; ignoreChange = false; return }
                        val prefix = previous.commonPrefixWith(next).length
                        val suffix = previous.substring(prefix).commonSuffixWith(next.substring(prefix)).length
                        val removedText = previous.substring(prefix, previous.length - suffix)
                        val removed = removedText.codePointCount(0, removedText.length)
                        val added = next.substring(prefix, next.length - suffix.coerceAtMost(next.length - prefix))
                        if (removed > 0) {
                            val backspace = if (ctrlArmed) "\b" else "\u007f"
                            session.controller.send((if (altArmed) "\u001b" else "") + backspace.repeat(removed))
                            ctrlArmed = false; altArmed = false
                        }
                        if (added.isNotEmpty()) {
                            var outgoing = added
                            if (ctrlArmed && added.length == 1 && added[0].code in 32..126) outgoing = (added[0].lowercaseChar().code and 0x1f).toChar().toString()
                            if (altArmed) outgoing = "\u001b$outgoing"
                            session.controller.send(outgoing)
                            ctrlArmed = false; altArmed = false
                        }
                        if (added.isNotEmpty() || removed > 0) session.clearAttention()
                        previous = next
                    }
                }
                editor = EditText(viewContext).apply {
                    setTextColor(android.graphics.Color.TRANSPARENT)
                    setHintTextColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    textSize = 1f
                    setPadding(0, 0, 0, 0)
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    isSingleLine = true
                    isCursorVisible = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    addTextChangedListener(watcher)
                    setOnEditorActionListener { _, actionId, event ->
                        val enter = actionId == EditorInfo.IME_ACTION_SEND || event?.keyCode == KeyEvent.KEYCODE_ENTER
                        if (enter) {
                            session.controller.send("\r")
                            session.clearAttention()
                            ignoreChange = true
                            setText("")
                            true
                        } else false
                    }
                }
                inputView = editor
                editor
            },
            update = { editor -> if (isCurrent && !editor.hasFocus()) editor.requestFocus() },
        )

        Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val keys = listOf(
                listOf("ESC" to "\u001b", "/" to "/", "−" to "-", "SESS" to "/sessions", "PG↑" to "\u001b[5~", "HOME" to "", "↑" to "", "END" to ""),
                listOf("TAB" to "\t", "CTRL" to "", "ALT" to "", "RESU" to "/resume", "PG↓" to "\u001b[6~", "←" to "", "↓" to "", "→" to ""),
            )
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (label, value) ->
                        val armed = (label == "CTRL" && ctrlArmed) || (label == "ALT" && altArmed)
                        Surface(onClick = {
                            when (label) {
                                "CTRL" -> { ctrlArmed = !ctrlArmed; altArmed = false }
                                "ALT" -> { altArmed = !altArmed; ctrlArmed = false }
                                else -> {
                                    val direction = when (label) {
                                        "↑" -> 'A'; "↓" -> 'B'; "→" -> 'C'; "←" -> 'D'; "HOME" -> 'H'; "END" -> 'F'; else -> null
                                    }
                                    var send = direction?.let { session.buffer.cursorSequence(it, ctrlArmed, altArmed) } ?: value
                                    if (direction == null && ctrlArmed && send.length == 1 && send[0].code in 32..126) send = (send[0].lowercaseChar().code and 0x1f).toChar().toString()
                                    if (direction == null && altArmed) send = "\u001b$send"
                                    session.controller.send(send); ctrlArmed = false; altArmed = false
                                    session.clearAttention()
                                }
                            }
                        }, modifier = Modifier.weight(1f).height(35.dp), color = if (armed) Color(0xFF27453F) else Panel,
                            shape = RoundedCornerShape(7.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (armed) Mint else Edge)) {
                            Box(contentAlignment = Alignment.Center) { Text(label, color = if (armed) Mint else White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

private fun statusTint(status: String): Color = when {
    status.startsWith("已连接") -> Mint
    status.contains("重连") || status.contains("连接中") -> Blue
    status.contains("断开") -> Muted
    else -> Coral
}

@Composable
private fun AutoEnterSettingsDialog(
    enabled: Boolean,
    delaySeconds: Int,
    onEnabledChange: (Boolean) -> Unit,
    onDelaySecondsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftDelay by remember(delaySeconds) { mutableStateOf(delaySeconds.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("自动回车设置", color = White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("仅对默认“是”的确认提示自动回车", color = White, fontSize = 13.sp)
                        Text("断线会取消倒计时；[y/N] 等默认否的提示需要手动确认", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Text("延迟 ${draftDelay.roundToInt()} 秒", color = White, fontSize = 13.sp)
                Slider(
                    value = draftDelay,
                    onValueChange = { draftDelay = it },
                    onValueChangeFinished = { onDelaySecondsChange(draftDelay.roundToInt().coerceIn(1, 30)) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Text("倒计时期间手动输入或操作会取消自动回车。", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成", color = Mint) } },
    )
}

@Composable
private fun HostEditorDialog(initial: HostProfile?, onDismiss: () -> Unit, onSave: (HostProfile) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember(initial?.id) { mutableStateOf(initial?.address.orEmpty()) }
    var port by remember(initial?.id) { mutableStateOf(initial?.port?.toString() ?: "22") }
    var username by remember(initial?.id) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial?.id) { mutableStateOf(initial?.password.orEmpty()) }
    var icon by remember(initial?.id) { mutableStateOf(initial?.icon ?: "⌘") }
    EditorDialogFrame(if (initial == null) "添加主机" else "编辑主机", onDismiss, canSave = name.isNotBlank() && address.isNotBlank() && username.isNotBlank(), onConfirm = {
        onSave(HostProfile(initial?.id ?: UUID.randomUUID().toString(), name.trim(), address.trim(), port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22, username.trim(), password, icon.ifBlank { "⌘" }, initial?.directories.orEmpty()))
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("主机名称") }, placeholder = { Text("例如：Mac Studio") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        IconPicker(icon, { icon = it }, listOf("⌘", "🖥️", "☁️", "🐧", "⚡", "🧪", "🏠", "💻", "🔧", "🌐"))
        OutlinedTextField(address, { address = it }, label = { Text("主机地址") }, placeholder = { Text("dev.example.com 或 IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("SSH 端口") }, singleLine = true, modifier = Modifier.weight(0.65f))
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(password, { password = it }, label = { Text("SSH 密码") }, placeholder = { Text("密码保存在本机加密区") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DirectoryEditorDialog(host: HostProfile, store: LocalStore, initial: ProjectDirectory?, onDismiss: () -> Unit, onSave: (ProjectDirectory) -> Unit) {
    var name by remember(host.id, initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var path by remember(host.id, initial?.id) { mutableStateOf(initial?.path.orEmpty()) }
    var icon by remember(host.id, initial?.id) { mutableStateOf(initial?.icon ?: "📁") }
    var showDirectoryPicker by remember(host.id, initial?.id) { mutableStateOf(false) }
    EditorDialogFrame(if (initial == null) "添加开发目录" else "编辑开发目录", onDismiss, canSave = name.isNotBlank() && path.isNotBlank(), onConfirm = {
        onSave(ProjectDirectory(initial?.id ?: UUID.randomUUID().toString(), name.trim(), path.trim(), icon.ifBlank { "📁" }))
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("目录名称") }, placeholder = { Text("例如：API 服务") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        IconPicker(icon, { icon = it }, listOf("📁", "📂", "📦", "🧩", "🌿", "🛠️", "🚀", "🧪", "📱", "🖥️"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(path, { path = it }, label = { Text("服务器目录路径") }, placeholder = { Text("选择远端目录") }, singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
            TextButton(onClick = { showDirectoryPicker = true }) {
                Icon(Icons.Default.FolderOpen, null, tint = Mint, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(3.dp))
                Text("浏览", color = Mint)
            }
        }
        Text("启动 Agent 时会先切换到这个目录。", color = Muted, fontSize = 11.sp)
    }
    if (showDirectoryPicker) RemoteDirectoryPickerDialog(
        host = host,
        store = store,
        initialPath = path,
        onDismiss = { showDirectoryPicker = false },
        onSelect = { selectedPath ->
            path = selectedPath
            if (name.isBlank()) name = selectedPath.trimEnd('/').substringAfterLast('/').ifBlank { "root" }
            showDirectoryPicker = false
        },
    )
}

@Composable
private fun RemoteDirectoryPickerDialog(
    host: HostProfile,
    store: LocalStore,
    initialPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var currentPath by remember(host.id) { mutableStateOf(initialPath) }
    var directories by remember(host.id) { mutableStateOf<List<RemoteDirectoryEntry>>(emptyList()) }
    var loading by remember(host.id) { mutableStateOf(false) }
    var errorMessage by remember(host.id) { mutableStateOf<String?>(null) }
    var pendingFingerprint by remember(host.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadDirectory(path: String) {
        currentPath = path
        directories = emptyList()
        scope.launch {
            loading = true
            errorMessage = null
            try {
                val listing = withContext(Dispatchers.IO) {
                    RemoteDirectoryBrowser.listDirectories(host, store, path)
                }
                currentPath = listing.path
                directories = listing.directories
            } catch (approval: HostKeyApprovalRequiredException) {
                pendingFingerprint = approval.fingerprint
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message?.takeIf { it.isNotBlank() } ?: "读取目录失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(host.id) { loadDirectory(initialPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("选择远端目录", color = White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(currentPath.ifBlank { "正在读取路径…" }, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { loadDirectory(remoteParentPath(currentPath)) }, enabled = !loading && currentPath != "/") {
                        Text("上一级", color = if (loading || currentPath == "/") Muted else Mint)
                    }
                    TextButton(onClick = { loadDirectory("/") }, enabled = !loading && currentPath != "/") {
                        Text("根目录", color = if (loading || currentPath == "/") Muted else Mint)
                    }
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Mint)
                        Text("正在读取远端目录…", color = Muted, fontSize = 12.sp)
                    }
                }
                errorMessage?.let { Text(it, color = Coral, fontSize = 11.sp) }
                if (!loading && errorMessage == null && directories.isEmpty()) {
                    Text("此目录下没有子目录。可以直接选择当前目录。", color = Muted, fontSize = 11.sp)
                }
                LazyColumn(Modifier.heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(directories, key = { it.path }) { directory ->
                        Surface(
                            onClick = { loadDirectory(directory.path) },
                            enabled = !loading,
                            color = PanelRaised,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderOpen, null, tint = Mint, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(directory.name, color = White, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(currentPath) }, enabled = !loading && currentPath.isNotBlank()) {
                Text("选择此目录", color = if (loading || currentPath.isBlank()) Muted else Mint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Muted) } },
    )

    pendingFingerprint?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = {
                pendingFingerprint = null
                errorMessage = "未确认主机指纹，无法浏览目录。"
            },
            containerColor = Panel,
            title = { Text("首次连接这台主机？", color = White, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请核对 SSH 主机指纹。接受后会保存在本机，后续连接会自动校验。", color = Muted)
                    Text("${host.address}:${host.port}", color = White, fontWeight = FontWeight.Medium)
                    Text(fingerprint, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.trustHostKey(host.address, host.port, fingerprint)
                    pendingFingerprint = null
                    loadDirectory(currentPath)
                }) { Text("信任并继续", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingFingerprint = null
                    errorMessage = "未确认主机指纹，无法浏览目录。"
                }) { Text("取消", color = Muted) }
            },
        )
    }
}

private fun remoteParentPath(path: String): String {
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    if (normalized == "/") return "/"
    val parent = normalized.substringBeforeLast('/', "")
    return parent.ifEmpty { "/" }
}

@Composable
private fun AgentEditorDialog(initial: AgentPreset?, onDismiss: () -> Unit, onSave: (AgentPreset) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var command by remember(initial?.id) { mutableStateOf(initial?.command.orEmpty()) }
    var icon by remember(initial?.id) { mutableStateOf(initial?.icon ?: "✳") }
    EditorDialogFrame(if (initial == null) "添加 Agent" else "编辑 Agent", onDismiss, canSave = name.isNotBlank() && command.isNotBlank(), onConfirm = {
        onSave(AgentPreset(initial?.id ?: UUID.randomUUID().toString(), name.trim(), command.trim(), icon.ifBlank { "✳" }))
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, placeholder = { Text("例如：Codex") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        IconPicker(icon, { icon = it }, listOf("✳", "◈", "⌘", "\$_", "⚙️", "🧠", "🤖", "✨", "🛠️"))
        OutlinedTextField(command, { command = it }, label = { Text("启动命令") }, placeholder = { Text("codex --full-auto") }, singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
        Text("支持带参数的命令（codex resume）和登录 Shell 别名（auto codex）；别名需在 SSH 用户的 Shell 配置中定义。", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun EditorDialogFrame(title: String, onDismiss: () -> Unit, canSave: Boolean, onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title, color = White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 510.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = canSave) { Text("保存", color = if (canSave) Mint else Muted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Muted) } },
    )
}

@Composable
private fun IconPicker(value: String, onChange: (String) -> Unit, options: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, onChange, label = { Text("自定义图标 / Emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { option ->
                Surface(onClick = { onChange(option) }, color = if (value == option) Color(0xFF20342F) else PanelRaised, shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (value == option) Mint.copy(alpha = .6f) else Edge)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text(option, fontSize = 17.sp) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, detail: String, onConfirm: () -> Unit, onDismiss: () -> Unit, confirmLabel: String = "删除") {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Panel,
        title = { Text(title, color = White, fontWeight = FontWeight.SemiBold) },
        text = { Text(detail, color = Muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Coral) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Muted) } },
    )
}
