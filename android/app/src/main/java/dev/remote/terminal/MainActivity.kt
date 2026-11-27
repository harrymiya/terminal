package dev.remote.terminal

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.viewinterop.AndroidView
import dev.remote.terminal.data.AgentPreset
import dev.remote.terminal.data.HostProfile
import dev.remote.terminal.data.hostAgents
import dev.remote.terminal.data.hostSelectedAgent
import dev.remote.terminal.data.hasValidConfiguredPorts
import dev.remote.terminal.data.SessionStore
import dev.remote.terminal.data.SyncClient
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.OperationLogEntry
import dev.remote.terminal.data.ProjectDirectory
import dev.remote.terminal.data.QuotaWindow
import dev.remote.terminal.data.quotaExecutable
import dev.remote.terminal.data.TerminalTask
import dev.remote.terminal.data.antigravityExecutable
import dev.remote.terminal.data.codexExecutable
import dev.remote.terminal.data.HostRoute
import dev.remote.terminal.data.HostRouteResolver
import dev.remote.terminal.data.HostEndpoint
import dev.remote.terminal.data.endpoints
import dev.remote.terminal.ssh.SshTerminalController
import dev.remote.terminal.ssh.HostKeyApprovalRequiredException
import dev.remote.terminal.ssh.RemoteDirectoryBrowser
import dev.remote.terminal.ssh.RemoteDirectoryEntry
import dev.remote.terminal.ssh.TerminalCell
import dev.remote.terminal.ssh.TerminalBuffer
import dev.remote.terminal.ssh.TerminalConnectionRegistry
import dev.remote.terminal.ssh.TerminalKeepAliveService
import dev.remote.terminal.ssh.AutoConfirmEngine
import dev.remote.terminal.ssh.AgentQuotaReader
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
private fun quotaPeriod(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}小时" else "${minutes}分"

private data class AgentQuotaKey(
    val hostId: String,
    val username: String,
    val endpoints: List<HostEndpoint>,
    val executable: String,
)

private fun quotaKey(host: HostProfile, command: String): AgentQuotaKey? =
    quotaExecutable(command)?.let { AgentQuotaKey(host.id, host.username, host.endpoints(), it) }

private data class AgentQuotaTarget(
    val host: HostProfile,
    val command: String,
    val session: TerminalSessionState? = null,
)

private fun List<QuotaWindow>?.hourlyQuota(): QuotaWindow? =
    this?.filter { it.minutes < 1440 }?.minByOrNull { it.minutes }

private fun alertConfirmation(context: Context) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    if (audio.ringerMode != android.media.AudioManager.RINGER_MODE_SILENT) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(350, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
    if (audio.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 300)
        }
    }
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

private class TerminalSessionState(
    task: TerminalTask,
    private val mainHandler: Handler,
    private val appContext: Context,
    private val quotaFor: (TerminalTask) -> List<QuotaWindow>?,
) : AutoConfirmEngine.SessionCallback {
    var task by mutableStateOf(task)
    var isMinimized by mutableStateOf(false)
    val buffer = TerminalBuffer()
    var status by mutableStateOf("等待连接")
    var unread by mutableStateOf(false)
    var needsAction by mutableStateOf(false)
    var confirmationAlerted = false
    var autoEnterEligible by mutableStateOf(false)
    var autoEnterPromptSignature by mutableStateOf<String?>(null)
    var handledPromptSignature by mutableStateOf<String?>(null)
    var autoEnterAwaitingResponse = false
    var lastOutputAt by mutableStateOf(0L)
    val startedAt = android.os.SystemClock.elapsedRealtime()
    var isStreaming by mutableStateOf(false)
    val quota get() = quotaFor(task)
    var hostFingerprint by mutableStateOf<String?>(null)
    var hostKeyAddress by mutableStateOf("")
    var hostKeyPort by mutableStateOf(0)
    var resolveHostKey: ((Boolean) -> Unit)? = null
    var autoEnterDeadline by mutableStateOf<Long?>(null)
        private set
    @Volatile var isSessionConnected = false
    @Volatile private var streamActiveStartedAt = 0L
    @Volatile private var receivedChunkBytes = 0L
    @Volatile private var lastReportedTailSignature: String? = null
    private var idleCompletionCheckRunnable: Runnable? = null
    lateinit var controller: SshTerminalController

    override fun isConnected(): Boolean = isSessionConnected && controller.isConnected()
    override fun isClosed(): Boolean = controller.isClosed()
    override fun getVisibleTail(): String = buffer.visibleTextTail(100)
    override fun sendInput(text: String): Boolean = controller.sendIfConnected(text)

    override fun onAutoEnterScheduled(deadlineUptimeMillis: Long, promptSignature: String) {
        autoEnterDeadline = deadlineUptimeMillis
        autoEnterPromptSignature = promptSignature
        autoEnterEligible = true
    }

    override fun onAutoEnterCancelled() {
        autoEnterDeadline = null
        autoEnterEligible = false
        autoEnterAwaitingResponse = false
    }

    override fun onAutoEnterTriggered(promptSignature: String) {
        autoEnterDeadline = null
        autoEnterAwaitingResponse = true
        handledPromptSignature = promptSignature
    }

    override fun onNeedsActionChanged(needsAction: Boolean) {
        if (needsAction && !this.needsAction && !confirmationAlerted) {
            confirmationAlerted = true
            alertConfirmation(appContext)
        } else if (!needsAction) {
            confirmationAlerted = false
        }
        this.needsAction = needsAction
    }

    fun cancelAutoEnter() {
        AutoConfirmEngine.cancel(task.id)
        autoEnterDeadline = null
        autoEnterEligible = false
        autoEnterAwaitingResponse = false
    }

    fun pauseAutoEnter() {
        AutoConfirmEngine.pause(task.id)
        autoEnterDeadline = null
        autoEnterEligible = false
        autoEnterPromptSignature = null
        autoEnterAwaitingResponse = false
    }

    fun onConnectionUnavailable() {
        idleCompletionCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        idleCompletionCheckRunnable = null
        streamActiveStartedAt = 0L
        receivedChunkBytes = 0L
        pauseAutoEnter()
        handledPromptSignature = null
    }

    fun noteOutputChunk(byteCount: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (streamActiveStartedAt == 0L) {
            streamActiveStartedAt = now
        }
        receivedChunkBytes += byteCount
        idleCompletionCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val check = Runnable { evaluateTaskCompletionReport() }
        idleCompletionCheckRunnable = check
        mainHandler.postDelayed(check, 2_800L)
    }

    private fun evaluateTaskCompletionReport() {
        val now = android.os.SystemClock.elapsedRealtime()
        val activeDuration = if (streamActiveStartedAt > 0L) now - streamActiveStartedAt else 0L
        val totalBytes = receivedChunkBytes
        val awaiting = autoEnterAwaitingResponse
        streamActiveStartedAt = 0L
        receivedChunkBytes = 0L

        if (!isConnected() || isClosed() || needsAction) return
        if (now - startedAt < 5_000L) return
        if (!awaiting && (activeDuration < 2_500L || totalBytes < 256L)) return

        val tail = buffer.visibleTextTail(30)
        val nonBlankLines = tail.lineSequence()
            .map { it.trim().trim('│', '┃', '║', '|', ' ') }
            .filter { line ->
                line.isNotBlank() &&
                    !line.all { ch -> ch in "─━═╭╮╰╯┌┐└┘+·• " }
            }
            .toList()
        if (nonBlankLines.isEmpty()) return

        val readyPromptRegex = Regex("""(?:^|[\s\]\)~])(?:\$|#|%|❯|›|>)\s*$""")
        val hasPromptReady = awaiting || nonBlankLines.takeLast(3).any { readyPromptRegex.containsMatchIn(it) }
        if (!hasPromptReady) return

        val normalizedSig = AutoConfirmEngine.normalizeSignature(nonBlankLines.takeLast(8).joinToString("\n"))
        if (normalizedSig.isBlank() || normalizedSig == lastReportedTailSignature) return
        lastReportedTailSignature = normalizedSig
        autoEnterAwaitingResponse = false

        val summaryLines = nonBlankLines
            .filterNot { readyPromptRegex.matches(it) }
            .filterNot { it.startsWith("$ ") || it.startsWith("> ") || it.startsWith("❯ ") }
        val summary = (if (summaryLines.isNotEmpty()) summaryLines.takeLast(2) else nonBlankLines.takeLast(1))
            .joinToString("，")
            .replace(Regex("""[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]+"""), "")
            .trim()
            .take(70)
            .ifBlank { "已执行完成并回到待命状态" }

        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.onTaskCompleted(
            workspace = "${task.directory.name}（${task.agent.name}）",
            status = summary
        )
    }

    fun clearAttention() {
        AutoConfirmEngine.markHandled(task.id, autoEnterPromptSignature)
        autoEnterDeadline = null
        needsAction = false
        autoEnterEligible = false
        autoEnterPromptSignature = null
        autoEnterAwaitingResponse = false
        unread = false
    }

    fun hasScheduledAutoEnter(): Boolean = autoEnterDeadline != null

    fun resumeAutoEnter(delaySeconds: Int, enabled: Boolean) {
        AutoConfirmEngine.globalEnabled = enabled
        AutoConfirmEngine.globalDelaySeconds = delaySeconds
        if (enabled && isConnected()) {
            AutoConfirmEngine.onOutputReceived(task.id)
        }
    }
}

private enum class AppRoute { HOME, MANAGE, TERMINAL, AGENTS, LOGS, SETTINGS, LOGIN }

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
        val operationLogs = remember { mutableStateListOf<OperationLogEntry>().apply { addAll(store.loadOperationLogs()) } }
        var selectedHostId by rememberSaveable { mutableStateOf(hosts.firstOrNull()?.id.orEmpty()) }
        var selectedAgentId by rememberSaveable { mutableStateOf(agents.firstOrNull()?.id.orEmpty()) }
        var route by rememberSaveable { mutableStateOf(if (!store.loadOfflineMode() && store.loadSyncUrl().isNotBlank() && store.loadSyncToken().isBlank()) AppRoute.LOGIN else AppRoute.HOME) }
        var activePage by rememberSaveable { mutableIntStateOf(0) }
        val sessionStore = remember { SessionStore(appContext) }
        var sessionsRestored by remember { mutableStateOf(false) }
        // Sessions live in memory and cannot be restored from saved instance state.
        // A restored terminal route without a session would otherwise render a blank screen.
        LaunchedEffect(route, sessions.size, sessionsRestored) {
            if (sessionsRestored && route == AppRoute.TERMINAL && sessions.isEmpty()) {
                activePage = 0
                route = AppRoute.HOME
            }
        }
        var autoEnterEnabled by remember { mutableStateOf(store.loadAutoEnterEnabled()) }
        var autoEnterDelaySeconds by remember { mutableIntStateOf(store.loadAutoEnterDelaySeconds()) }
        var quotaIntervalSeconds by remember { mutableIntStateOf(store.loadQuotaIntervalSeconds()) }
        var deviceLayoutMode by remember { mutableStateOf(store.loadDeviceLayoutMode()) }
        var tiledMode by remember { mutableStateOf(store.loadTiledMode()) }
        val configuration = LocalConfiguration.current
        val isTablet = configuration.smallestScreenWidthDp >= 600 || configuration.screenWidthDp >= 840
        val isPad = when (deviceLayoutMode) {
            "pad" -> true
            "phone" -> false
            else -> isTablet
        }
        AutoConfirmEngine.init(appContext)
        AutoConfirmEngine.globalEnabled = autoEnterEnabled
        AutoConfirmEngine.globalDelaySeconds = autoEnterDelaySeconds

        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
            if (audioGranted && store.loadJarvisEnabled()) {
                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.start()
            }
        }
        LaunchedEffect(Unit) {
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.storeProvider = { store }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.hostsProvider = { hosts.toList() }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.agentsProvider = { agents.toList() }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.selectedHostIdProvider = { selectedHostId }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.selectedDirectoryProvider = {
                hosts.firstOrNull { it.id == selectedHostId }?.directories?.firstOrNull()?.path
            }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.init(appContext)
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.enabled = store.loadJarvisEnabled()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.wakeWord = store.loadJarvisWakeWord()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.autoWhisperConfirm = store.loadJarvisAutoConfirm()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.notifyOnTaskFinished = store.loadJarvisNotifyFinished()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.bluetoothScoEnabled = store.loadJarvisBluetoothSco()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.asrEngineMode = store.loadJarvisAsrMode()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetHostId = store.loadJarvisTargetHostId()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetAgentCommand = store.loadJarvisTargetAgentCommand()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetModel = store.loadJarvisModel()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetEffort = store.loadJarvisEffort()
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.hapticFeedbackProvider = {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    appContext.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appContext.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                }
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
            if (store.loadJarvisEnabled()) {
                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.start()
                val permissionsToRequest = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_CONNECT)
                }
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }

        LaunchedEffect(selectedHostId, sessions.size, activePage) {
            val selectedHost = hosts.firstOrNull { it.id == selectedHostId }
            val activeSession = sessions.getOrNull(activePage)
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.activeTerminalController = activeSession?.task?.let { dev.remote.terminal.ssh.TerminalConnectionRegistry.get(it.id) }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.currentWorkspaceName = activeSession?.task?.directory?.name ?: selectedHost?.directories?.firstOrNull()?.name
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.currentWorkspacePath = activeSession?.task?.directory?.path ?: selectedHost?.directories?.firstOrNull()?.path
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.workspaceListProvider = {
                selectedHost?.directories?.map { it.name } ?: emptyList()
            }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.agentListProvider = {
                agents.map { it.name }
            }
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.lastTerminalOutputProvider = {
                activeSession?.buffer?.toString()
            }
        }

        LaunchedEffect(Unit) {
            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.onWorkspaceSwitchRequested = { targetWs ->
                val selectedHost = hosts.firstOrNull { it.id == selectedHostId }
                val targetDir = selectedHost?.directories?.firstOrNull {
                    it.name.equals(targetWs, ignoreCase = true) || it.name.contains(targetWs, ignoreCase = true)
                }
                if (targetDir != null) {
                    val existingIndex = sessions.indexOfFirst { it.task.directory.path == targetDir.path }
                    if (existingIndex >= 0) {
                        activePage = existingIndex
                        route = AppRoute.TERMINAL
                    }
                }
            }
        }
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && sessions.isNotEmpty()) {
                    runCatching { TerminalKeepAliveService.start(appContext) }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        var syncUrl by remember { mutableStateOf(store.loadSyncUrl()) }
        var syncToken by remember { mutableStateOf(store.loadSyncToken()) }
        var offlineMode by remember { mutableStateOf(store.loadOfflineMode()) }
        var autoSyncEnabled by remember { mutableStateOf(store.loadAutoSyncEnabled()) }
        var syncIntervalSeconds by remember { mutableIntStateOf(store.loadSyncIntervalSeconds()) }
        var editingHost by remember { mutableStateOf<HostProfile?>(null) }
        var addingHost by remember { mutableStateOf(false) }
        var editingDirectory by remember { mutableStateOf<Pair<String, ProjectDirectory?>?>(null) }
        var editingAgent by remember { mutableStateOf<AgentPreset?>(null) }
        var addingAgent by remember { mutableStateOf(false) }
        var deletingHost by remember { mutableStateOf<HostProfile?>(null) }
        var deletingDirectory by remember { mutableStateOf<Pair<String, ProjectDirectory>?>(null) }
        var deletingAgent by remember { mutableStateOf<AgentPreset?>(null) }
        var closingSession by remember { mutableStateOf<TerminalSessionState?>(null) }
        var renamingSession by remember { mutableStateOf<TerminalSessionState?>(null) }
        var renamingName by remember { mutableStateOf("") }
        var sharedSessions by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
        var sharedHost by remember { mutableStateOf<HostProfile?>(null) }
        var startupShared by remember { mutableStateOf<List<Pair<HostProfile, org.json.JSONObject>>>(emptyList()) }
        var syncLoading by remember { mutableStateOf(false) }
        val sessionSyncErrors = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val dirtyWorkspaces = remember { store.loadDirtyWorkspaces().toMutableSet() }
        val dirtyHostProfiles = remember { store.loadDirtyHostProfiles().toMutableSet() }
        val workspaceVersions = remember { mutableMapOf<String, Int>() }
        val workspaceSyncInFlight = remember { mutableSetOf<String>() }
        val agentVersions = remember { mutableMapOf<String, Int>() }
        val agentSaves = remember { mutableSetOf<String>() }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val selectedHost = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()
        val currentAgents = remember(selectedHost?.id, selectedHost?.agents, agents.toList()) {
            hostAgents(selectedHost, agents)
        }
        val selectedAgent = remember(selectedHost?.id, selectedAgentId, currentAgents) {
            currentAgents.firstOrNull { it.id == selectedAgentId } ?: hostSelectedAgent(selectedHost, currentAgents, store) ?: currentAgents.firstOrNull()
        }
        val quotas = remember { androidx.compose.runtime.mutableStateMapOf<AgentQuotaKey, List<QuotaWindow>>() }
        val quotaAttempts = remember { mutableMapOf<AgentQuotaKey, Long>() }
        val quotaSuccesses = remember { mutableMapOf<AgentQuotaKey, Long>() }
        val quotaJobs = remember { mutableSetOf<AgentQuotaKey>() }
        val quotaTargets by rememberUpdatedState(buildMap {
            for (session in sessions) {
                val task = session.task
                if (session.status.startsWith("已连接")) {
                    quotaKey(task.host, task.agent.command)?.let { key ->
                        if (key !in this) put(key, AgentQuotaTarget(task.host, task.agent.command, session))
                    }
                }
            }
            if (route == AppRoute.HOME && selectedHost != null) {
                val developers = listOfNotNull(selectedAgent) + currentAgents.filter { it.id != selectedAgent?.id }
                for (agent in developers) {
                    quotaKey(selectedHost, agent.command)?.let { key ->
                        if (key !in this) put(key, AgentQuotaTarget(selectedHost, agent.command))
                    }
                }
            }
        })
        LaunchedEffect(Unit) {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val interval = quotaIntervalSeconds * 1_000L
                val cacheLifetime = maxOf(300_000L, interval * 2)
                quotaSuccesses.entries.removeAll { (key, lastSuccess) ->
                    (now - lastSuccess >= cacheLifetime).also { expired -> if (expired) quotas.remove(key) }
                }
                val dueOrder = quotaTargets.entries.sortedBy { quotaAttempts[it.key] ?: Long.MIN_VALUE }
                for ((key, target) in dueOrder) {
                    if (quotaJobs.size >= 4) break
                    val lastAttempt = quotaAttempts[key]
                    if ((lastAttempt != null && now - lastAttempt < interval) || !quotaJobs.add(key)) continue
                    quotaAttempts[key] = now
                    launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val session = target.session
                                    if (session != null) session.controller.readQuota(target.command, session.task.directory.path)
                                    else RemoteDirectoryBrowser.withSshSession(target.host, store) {
                                        AgentQuotaReader.read(it, target.command, null)
                                    }
                                }.getOrNull()
                            }
                            if (!result.isNullOrEmpty()) {
                                quotas[key] = result
                                quotaSuccesses[key] = android.os.SystemClock.elapsedRealtime()
                            }
                        } finally {
                            quotaJobs.remove(key)
                        }
                    }
                }
                quotaAttempts.entries.removeAll { (key, lastAttempt) ->
                    key !in quotaJobs && now - lastAttempt >= cacheLifetime
                }
                kotlinx.coroutines.delay(1_000L)
            }
        }
        SideEffect { SyncClient.configure(syncUrl, syncToken) }

        fun selectHost(host: HostProfile) {
            selectedHostId = host.id
            val hostPresetList = hostAgents(host, agents)
            val agent = hostSelectedAgent(host, hostPresetList, store)
            if (agent != null) {
                selectedAgentId = agent.id
                store.saveHostSelectedAgent(host.id, agent.id)
            }
        }

        fun selectAgent(agent: AgentPreset) {
            selectedAgentId = agent.id
            selectedHost?.let { host ->
                store.saveHostSelectedAgent(host.id, agent.id)
                val updatedHost = host.copy(selectedAgent = agent.id, selectedAgentCommand = agent.command)
                val idx = hosts.indexOfFirst { it.id == host.id }
                if (idx >= 0) {
                    hosts[idx] = updatedHost
                    store.saveHosts(hosts.toList())
                }
                dirtyHostProfiles.add(host.id)
                store.saveDirtyHostProfiles(dirtyHostProfiles)
                if (!offlineMode && autoSyncEnabled && syncUrl.isNotBlank() && syncToken.isNotBlank()) {
                    scope.launch {
                        val localHost = updatedHost
                        runCatching { withContext(SyncClient.dispatcher) { SyncClient.updateCurrentAgent(localHost, agent.command) } }
                    }
                }
            }
        }

        LaunchedEffect(selectedHostId) {
            val currentHost = hosts.firstOrNull { it.id == selectedHostId }
            val hostPresetList = hostAgents(currentHost, agents)
            val agent = hostSelectedAgent(currentHost, hostPresetList, store)
            if (agent != null) {
                selectedAgentId = agent.id
            }
        }

        fun saveHosts(updated: List<HostProfile>) {
            val previous = hosts.toList()
            val next = updated.toList()
            val updatedIds = next.mapTo(mutableSetOf()) { it.id }
            previous.filterNot { it.id in updatedIds }.forEach { HostRouteResolver.invalidate(it.id) }
            next.forEach { host ->
                val oldHost = previous.firstOrNull { it.id == host.id }
                if (oldHost == null || oldHost.endpoints() != host.endpoints()) {
                    HostRouteResolver.invalidate(host.id)
                    scope.launch(Dispatchers.IO) { HostRouteResolver.resolveHostRoute(host, force = true) }
                }
            }
            if (hosts.toList() != next) {
                for (i in next.indices) {
                    if (i < hosts.size) {
                        if (hosts[i] != next[i]) hosts[i] = next[i]
                    } else {
                        hosts.add(next[i])
                    }
                }
                while (hosts.size > next.size) {
                    hosts.removeAt(hosts.lastIndex)
                }
            }
            store.saveHosts(next)
            if (selectedHostId !in next.map { it.id }) selectedHostId = next.firstOrNull()?.id.orEmpty()
        }

        fun syncHostProfile(host: HostProfile) {
            if (offlineMode) return
            dirtyHostProfiles.add(host.id)
            store.saveDirtyHostProfiles(dirtyHostProfiles)
            if (syncUrl.isBlank() || syncToken.isBlank()) return
            val agentCommand = agents.firstOrNull { it.id == selectedAgentId }?.command
                ?: agents.firstOrNull()?.command.orEmpty()
            scope.launch {
                runCatching {
                    withContext(SyncClient.dispatcher) {
                        val response = SyncClient.saveHostProfile(host, agentCommand)
                        val canonicalId = response.optJSONObject("host")?.optString("id", host.id) ?: host.id
                        val canonicalHost = if (canonicalId == host.id) host else host.copy(id = canonicalId)
                        SyncClient.updateHostProfile(canonicalHost, agentCommand)
                        canonicalHost
                    }
                }.onSuccess { canonicalHost ->
                    dirtyHostProfiles.remove(host.id)
                    store.saveDirtyHostProfiles(dirtyHostProfiles)
                    if (canonicalHost.id != host.id) {
                        saveHosts(hosts.map { if (it.id == host.id) canonicalHost else it })
                        sessions.filter { it.task.host.id == host.id }.forEach { session ->
                            session.task = session.task.copy(host = canonicalHost)
                            sessionStore.save(session.task)
                        }
                    }
                }.onFailure { error ->
                    snackbars.showSnackbar("主机配置同步失败，将自动重试：${error.message}")
                }
            }
        }

        fun syncWorkspaceList(host: HostProfile, directories: List<ProjectDirectory>) {
            if (offlineMode || syncUrl.isBlank()) return
            val version = (workspaceVersions[host.id] ?: 0) + 1
            workspaceVersions[host.id] = version
            dirtyWorkspaces.add(host.id)
            store.saveDirtyWorkspaces(dirtyWorkspaces)
            if (!workspaceSyncInFlight.add(host.id)) return
            val identity = "${store.loadSyncUsername().lowercase()}:${SyncClient.hostIdentity(host)}"
            scope.launch {
                runCatching {
                    withContext(SyncClient.dispatcher) {
                        val baseRevision = store.loadWorkspaceRevision(identity).coerceAtLeast(0)
                        val response = SyncClient.saveWorkspaces(host, directories, baseRevision)
                        response to baseRevision
                    }
                }
                    .onSuccess { (response, baseRevision) ->
                        workspaceSyncInFlight.remove(host.id)
                        store.saveWorkspaceRevision(identity, response.optInt("revision", baseRevision))
                        val latestHost = hosts.firstOrNull { it.id == host.id }
                        if (workspaceVersions[host.id] != version) {
                            if (latestHost != null) syncWorkspaceList(latestHost, latestHost.directories)
                        } else {
                            dirtyWorkspaces.remove(host.id)
                            store.saveDirtyWorkspaces(dirtyWorkspaces)
                            val array = response.optJSONArray("workspaces") ?: JSONArray()
                            val merged = (0 until array.length()).map { index ->
                                array.getJSONObject(index).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.optString("icon", "📁")) }
                            }
                            latestHost?.takeIf { it.directories != merged }?.let { current ->
                                saveHosts(hosts.map { if (it.id == current.id) it.copy(directories = merged) else it })
                            }
                        }
                    }
                    .onFailure {
                        workspaceSyncInFlight.remove(host.id)
                        val latestHost = hosts.firstOrNull { it.id == host.id }
                        if (workspaceVersions[host.id] != version && latestHost != null) {
                            syncWorkspaceList(latestHost, latestHost.directories)
                        } else {
                            snackbars.showSnackbar("工作区同步失败，将自动重试：${it.message}")
                        }
                    }
            }
        }

        fun moveDirectory(host: HostProfile, from: Int, offset: Int) {
            val dirs = host.directories.toMutableList()
            if (from !in dirs.indices) return
            val to = (from + offset).coerceIn(0, dirs.lastIndex)
            if (from == to) return
            val item = dirs.removeAt(from)
            dirs.add(to, item)
            val updatedHost = host.copy(directories = dirs)
            saveHosts(hosts.map { if (it.id == host.id) updatedHost else it })
            syncWorkspaceList(updatedHost, dirs)
        }

        fun replaceAgents(ordered: List<AgentPreset>) {
            if (agents.toList() == ordered) return
            val previous = agents.associateBy { it.id }
            val nextIds = ordered.mapTo(mutableSetOf()) { it.id }
            val nextByCommand = ordered.associateBy { it.command }
            fun updatedSelection(id: String): String =
                if (id in nextIds) id else nextByCommand[previous[id]?.command]?.id ?: ordered.firstOrNull()?.id.orEmpty()

            hosts.forEach { host ->
                val id = store.loadHostSelectedAgent(host.id)
                if (id.isNotBlank() && id !in nextIds) store.saveHostSelectedAgent(host.id, updatedSelection(id))
            }
            selectedAgentId = updatedSelection(selectedAgentId)
            agents.clear()
            agents.addAll(ordered)
            store.saveAgents(ordered)
        }

        fun syncAgentList(host: HostProfile, items: List<AgentPreset>) {
            if (offlineMode || syncUrl.isBlank()) return
            val identity = SyncClient.agentRevisionIdentity(store.loadSyncUsername())
            val version = (agentVersions[identity] ?: 0) + 1
            agentVersions[identity] = version
            agentSaves.add(identity)
            scope.launch {
                val result = runCatching {
                    withContext(SyncClient.dispatcher) {
                        val baseRevision = store.loadAgentRevision(identity).coerceAtLeast(0)
                        val response = SyncClient.saveAgents(host, items, baseRevision)
                        store.saveAgentRevision(identity, response.optInt("revision", baseRevision))
                        response
                    }
                }
                if (agentVersions[identity] == version) agentSaves.remove(identity)
                result
                    .onSuccess { response ->
                        if (agentVersions[identity] == version) {
                            replaceAgents(SyncClient.agentsFromSnapshot(response))
                        }
                    }
                    .onFailure { snackbars.showSnackbar("Agent 同步失败：${it.message}") }
            }
        }

        suspend fun syncHostProfiles(): List<HostProfile> {
            val localHosts = withContext(Dispatchers.Main) { hosts.toList() }
            val (localAgents, localSelectedAgentId) = withContext(Dispatchers.Main) { agents.toList() to selectedAgentId }
            val dirtySnapshot = withContext(Dispatchers.Main) { dirtyHostProfiles.toSet() }
            val allRemote = SyncClient.listHosts()
            val active = allRemote.filterNot { it.optBoolean("deleted") }
            val deleted = allRemote.filter { it.optBoolean("deleted") }
            val matched = mutableSetOf<String>()
            val idRemaps = mutableMapOf<String, String>()
            val successfullyUploadedIds = mutableSetOf<String>()
            val agentCommand = localAgents.firstOrNull { it.id == localSelectedAgentId }?.command
                ?: localAgents.firstOrNull()?.command.orEmpty()
            val result = localHosts.mapNotNull { local ->
                val deletedProfile = deleted.firstOrNull { it.optString("id") == local.id }
                if (deletedProfile != null) {
                    val canonicalId = deletedProfile.optString("canonical_id")
                    if (canonicalId.isBlank() || active.none { it.optString("id") == canonicalId }) return@mapNotNull null
                    matched.add(canonicalId)
                    idRemaps[local.id] = canonicalId
                    return@mapNotNull local.copy(id = canonicalId)
                }
                val aliasMatches = allRemote.filter { SyncClient.matchesLegacyHost(local, it) }
                val deletedByUniqueAlias = aliasMatches.size == 1 && aliasMatches.single().optBoolean("deleted")
                if (deletedByUniqueAlias) return@mapNotNull null
                val profile = active.firstOrNull { it.optString("id") == local.id }
                    ?: aliasMatches.filterNot { it.optBoolean("deleted") }.singleOrNull()
                if (local.id in dirtySnapshot) {
                    var canonicalLocal = if (profile == null) local else local.copy(id = profile.optString("id", local.id))
                    if (canonicalLocal.hasValidConfiguredPorts()) {
                        val upload = runCatching {
                            withContext(SyncClient.dispatcher) {
                                val response = SyncClient.saveHostProfile(canonicalLocal, agentCommand)
                                val canonicalId = response.optJSONObject("host")?.optString("id", canonicalLocal.id) ?: canonicalLocal.id
                                canonicalLocal = canonicalLocal.copy(id = canonicalId)
                                runCatching { SyncClient.updateHostProfile(canonicalLocal, agentCommand) }
                            }
                        }
                        if (upload.isSuccess) {
                            matched.add(canonicalLocal.id)
                            idRemaps[local.id] = canonicalLocal.id
                            successfullyUploadedIds.add(local.id)
                            successfullyUploadedIds.add(canonicalLocal.id)
                        } else if (profile != null) {
                            matched.add(profile.optString("id"))
                            idRemaps[local.id] = profile.optString("id", local.id)
                        }
                    } else if (profile != null) {
                        matched.add(profile.optString("id"))
                        idRemaps[local.id] = profile.optString("id", local.id)
                    }
                    canonicalLocal
                } else if (profile == null) {
                    if (!local.hasValidConfiguredPorts()) return@mapNotNull local
                    val response = SyncClient.saveHostProfile(local, agentCommand)
                    val canonicalId = response.optJSONObject("host")?.optString("id", local.id) ?: local.id
                    matched.add(canonicalId)
                    idRemaps[local.id] = canonicalId
                    if (canonicalId == local.id) local else local.copy(id = canonicalId)
                } else {
                    matched.add(profile.optString("id"))
                    idRemaps[local.id] = profile.optString("id", local.id)
                    val lan = profile.optString("lan_address")
                    val external = profile.optString("external_address")
                    val lanPort = if (profile.has("lan_port") && !profile.isNull("lan_port")) profile.optInt("lan_port", local.lanPort) else local.lanPort
                    val externalPort = if (profile.has("external_port") && !profile.isNull("external_port")) profile.optInt("external_port", local.externalPort) else local.externalPort
                    local.copy(id = profile.optString("id", local.id), name = profile.optString("name", local.name),
                        address = external.ifBlank { lan }, lanAddress = lan, externalAddress = external,
                        port = profile.optInt("port", local.port), lanPort = lanPort, externalPort = externalPort,
                        username = profile.optString("username").ifBlank { local.username },
                        password = local.password.ifBlank { store.loadHostPassword(local.id) },
                        icon = profile.optString("icon", local.icon),
                        selectedAgentCommand = profile.optString("selected_agent_command").takeIf { it.isNotBlank() } ?: local.selectedAgentCommand)
                }
            }.toMutableList()
            active.filterNot { it.optString("id") in matched }.forEach { profile ->
                val lan = profile.optString("lan_address")
                val external = profile.optString("external_address")
                val port = profile.getInt("port")
                val lanPort = if (profile.has("lan_port") && !profile.isNull("lan_port")) profile.getInt("lan_port") else port
                val externalPort = if (profile.has("external_port") && !profile.isNull("external_port")) profile.getInt("external_port") else port
                val id = profile.optString("id")
                val localPassword = store.loadHostPassword(id)
                result.add(HostProfile(id, profile.optString("name", "主机"), external.ifBlank { lan },
                    port, profile.optString("username"), password = localPassword, icon = profile.optString("icon", "⌘"),
                    lanAddress = lan, externalAddress = external, legacyAddress = "",
                    lanPort = lanPort, externalPort = externalPort,
                    selectedAgentCommand = profile.optString("selected_agent_command").takeIf { it.isNotBlank() }))
            }
            val uniqueResult = result.distinctBy { it.id }
            val snapshotIds = localHosts.mapTo(mutableSetOf()) { it.id }
            withContext(Dispatchers.Main) {
                val latestHosts = hosts.toList()
                val latestById = latestHosts.associateBy { it.id }
                // A host profile poll may finish after the user edits directories; keep the live local list.
                val reconciled = uniqueResult.map { synced ->
                    val sourceId = idRemaps.entries.firstOrNull { it.value == synced.id }?.key ?: synced.id
                    val current = latestById[synced.id] ?: latestById[sourceId]
                    if (current != null) synced.copy(
                        password = if (synced.password.isNotBlank()) synced.password else current.password.ifBlank { store.loadHostPassword(current.id) },
                        username = if (synced.username.isNotBlank()) synced.username else current.username,
                        directories = current.directories,
                        agents = current.agents,
                        selectedAgent = current.selectedAgent,
                        selectedAgentCommand = synced.selectedAgentCommand ?: current.selectedAgentCommand
                    ) else synced.copy(
                        password = synced.password.ifBlank { store.loadHostPassword(synced.id) }
                    )
                }.toMutableList()
                val reconciledIds = reconciled.mapTo(mutableSetOf()) { it.id }
                latestHosts.filter { it.id !in snapshotIds && it.id !in reconciledIds }.forEach { reconciled.add(it) }
                if (reconciled != latestHosts) {
                    val oldSelected = selectedHostId
                    saveHosts(reconciled)
                    idRemaps[oldSelected]?.let { selectedHostId = it }
                }
                var dirtyProfilesChanged = false
                idRemaps.forEach { (oldId, canonicalId) ->
                    if (oldId != canonicalId) {
                        HostRouteResolver.invalidate(oldId)
                        HostRouteResolver.invalidate(canonicalId)
                        if (dirtyHostProfiles.remove(oldId)) {
                            dirtyHostProfiles.add(canonicalId)
                            dirtyProfilesChanged = true
                        }
                    }
                }
                successfullyUploadedIds.forEach { if (dirtyHostProfiles.remove(it)) dirtyProfilesChanged = true }
                if (dirtyProfilesChanged) store.saveDirtyHostProfiles(dirtyHostProfiles)
                if (idRemaps.isNotEmpty()) store.remapPendingSessionSyncHostIds(idRemaps)
                val canonicalById = reconciled.associateBy { it.id }
                sessions.toList().forEach { session ->
                    val oldId = session.task.host.id
                    val canonicalId = idRemaps[oldId] ?: oldId
                    val canonicalHost = canonicalById[canonicalId] ?: return@forEach
                    if (session.task.host != canonicalHost) {
                        session.task = session.task.copy(host = canonicalHost)
                        sessionStore.save(session.task)
                    }
                }
            }
            return withContext(Dispatchers.Main) { hosts.toList() }
        }

        var refreshShared: (HostProfile) -> Unit = {}

        fun closeSession(session: TerminalSessionState, syncDeletion: Boolean = true) {
            val index = sessions.indexOf(session)
            if (index < 0) return
            sessionStore.remove(session.task.id)
            if (syncDeletion && !offlineMode) {
                store.enqueueSessionSync(session.task, "DELETE")
                refreshShared(session.task.host)
            }
            AutoConfirmEngine.unregisterSession(session.task.id)
            session.onConnectionUnavailable()
            TerminalConnectionRegistry.unregister(session.task.id, session.controller)
            session.controller.close(terminateRemote = true)
            sessions.removeAt(index)
            if (sessions.isEmpty()) {
                route = AppRoute.HOME
                TerminalKeepAliveService.stop(appContext)
            } else {
                activePage = when {
                    index < activePage -> activePage - 1
                    index == activePage -> (index - 1).coerceAtLeast(0)
                    else -> activePage
                }.coerceIn(0, sessions.lastIndex)
            }
        }

        fun recordOperationLog(task: TerminalTask, action: String = "open") {
            val account = store.loadSyncUsername().trim().lowercase()
            val entry = OperationLogEntry(
                id = UUID.randomUUID().toString().replace("-", ""),
                createdAt = System.currentTimeMillis(),
                hostId = task.host.id,
                hostName = task.host.name,
                sshUsername = task.host.username,
                directoryId = task.directory.id,
                directoryName = task.directory.name,
                directoryPath = task.directory.path,
                agentId = task.agent.id,
                agentName = task.agent.name,
                agentCommand = task.agent.command,
                sessionId = task.id,
                sessionName = task.displayName,
                client = "android",
                action = action,
                account = account,
                agentIcon = task.agent.icon,
                titleSource = task.titleSource,
            )
            val merged = store.appendOperationLog(entry)
            operationLogs.clear()
            operationLogs.addAll(merged)
            if (!offlineMode && syncUrl.isNotBlank() && syncToken.isNotBlank()) {
                scope.launch {
                    runCatching {
                        val remote = withContext(SyncClient.dispatcher) { SyncClient.pushOperationLogs(listOf(entry)) }
                        val synced = store.mergeOperationLogs(remote, account)
                        if (store.loadSyncUsername().trim().lowercase() == account) {
                            operationLogs.clear()
                            operationLogs.addAll(synced)
                        }
                    }
                }
            }
        }

        suspend fun syncOperationLogs() {
            if (offlineMode || syncUrl.isBlank() || syncToken.isBlank()) return
            val account = store.loadSyncUsername().trim().lowercase()
            val local = store.loadOperationLogs(account)
            val remote = withContext(SyncClient.dispatcher) {
                if (local.isNotEmpty()) SyncClient.pushOperationLogs(local.take(50))
                else SyncClient.fetchOperationLogs(200)
            }
            val merged = store.mergeOperationLogs(remote, account)
            if (store.loadSyncUsername().trim().lowercase() == account) {
                operationLogs.clear()
                operationLogs.addAll(merged)
            }
        }

        fun applySessionTitle(session: TerminalSessionState, title: String, source: String) {
            val cleanTitle = title.trim().replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").take(512)
            if (cleanTitle.isBlank()
                || (session.task.titleSource == "manual" && source != "manual")
                || (session.task.titleSource == "agent" && source == "default")) return
            if (cleanTitle == session.task.displayName && source == session.task.titleSource) return
            val updated = session.task.copy(displayName = cleanTitle, titleSource = source)
            session.task = updated
            sessionStore.save(updated)
            if (!offlineMode) store.enqueueSessionSync(updated, "PUT", SyncClient.sessionItem(updated))
            if (!offlineMode && autoSyncEnabled && syncUrl.isNotBlank() && syncToken.isNotBlank()) {
                scope.launch {
                    runCatching {
                        val account = store.loadSyncUsername()
                        val pending = store.loadPendingSessionSync(account, updated.host)
                        withContext(SyncClient.dispatcher) { SyncClient.syncSessions(updated.host, pending) }
                    }
                }
            }
            val updatedLogs = store.updateOperationLogSessionTitle(updated.id, cleanTitle, source)
            operationLogs.clear()
            operationLogs.addAll(store.loadOperationLogs())
            if (!offlineMode && syncUrl.isNotBlank() && syncToken.isNotBlank() && updatedLogs.isNotEmpty()) {
                val account = store.loadSyncUsername().trim().lowercase()
                scope.launch {
                    runCatching {
                        val remote = withContext(SyncClient.dispatcher) { SyncClient.pushOperationLogs(updatedLogs.filter { it.sessionId.replace("-", "").equals(updated.id.replace("-", ""), true) }) }
                        val synced = store.mergeOperationLogs(remote)
                        if (store.loadSyncUsername().trim().lowercase() == account) {
                            operationLogs.clear()
                            operationLogs.addAll(synced)
                        }
                    }
                }
            }
        }

        fun openTask(
            host: HostProfile,
            directory: ProjectDirectory,
            agent: AgentPreset,
            restoredTask: TerminalTask? = null,
            attachOrCreate: Boolean = false,
            navigateToTerminal: Boolean = true,
        ) {
            runCatching { TerminalKeepAliveService.start(appContext) }
                .onFailure { error -> scope.launch { snackbars.showSnackbar("后台 SSH 保活未能启动：${error.message ?: "系统拒绝启动服务"}") } }
            val same = sessions.count { it.task.host.id == host.id && it.task.directory.id == directory.id && it.task.agent.id == agent.id }
            val agentLabel = if (same == 0) agent.name else "${agent.name} ${same + 1}"
            val displayName = if (directory.name.isNotBlank()) "${directory.name} · $agentLabel" else agentLabel
            val taskHost = if (host.password.isNotBlank()) host else host.copy(password = store.loadHostPassword(host.id))
            val task = (restoredTask ?: TerminalTask(UUID.randomUUID().toString().replace("-", ""), taskHost, directory, agent, displayName))
                .let { if (it.host.password.isBlank() && taskHost.password.isNotBlank()) it.copy(host = taskHost) else it }
            if (restoredTask == null || attachOrCreate) {
                sessionStore.save(task)
                if (!offlineMode) {
                    store.enqueueSessionSync(task, "PUT", SyncClient.sessionItem(task))
                    refreshShared(taskHost)
                }
                if (restoredTask == null) recordOperationLog(task, "open")
            }
            val state = TerminalSessionState(task, mainHandler, appContext) { sessionTask ->
                quotaKey(sessionTask.host, sessionTask.agent.command)?.let(quotas::get)
            }
            state.buffer.onAgentTitle = { title -> mainHandler.post { applySessionTitle(state, title, "agent") } }
            state.buffer.onJarvisCommand = { cmd, payload ->
                mainHandler.post {
                    when (cmd.lowercase()) {
                        "speak" -> dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.speakWithState(payload)
                        "vibrate" -> alertConfirmation(appContext)
                        "tile" -> {
                            val tiled = payload.equals("true", true) || payload == "1"
                            tiledMode = tiled
                            store.saveTiledMode(tiled)
                        }
                        "focus" -> {
                            val idx = payload.toIntOrNull()
                            if (idx != null && idx in sessions.indices) {
                                activePage = idx
                            }
                        }
                    }
                }
            }
            state.controller = SshTerminalController(
                task = task, store = store, restoreOnly = restoredTask != null && !attachOrCreate,
                onStatus = { value ->
                    state.isSessionConnected = value.startsWith("已连接")
                    mainHandler.post {
                        state.status = value
                        if (value.contains("重连") || value.contains("恢复 SSH") || value == "已断开" || value.startsWith("远端终端已结束")) {
                            state.onConnectionUnavailable()
                        }
                        if (value.startsWith("远端终端已结束") &&
                            android.os.SystemClock.elapsedRealtime() - state.startedAt >= 5_000L
                        ) {
                            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.onTaskCompleted(
                                workspace = "${state.task.directory.name}（${state.task.agent.name}）",
                                status = value
                            )
                        }
                        if (value.startsWith("已连接")) state.resumeAutoEnter(autoEnterDelaySeconds, autoEnterEnabled)
                    }
                },
                onBytes = { bytes ->
                    state.buffer.feed(bytes)
                    AutoConfirmEngine.onOutputReceived(task.id)
                    mainHandler.post {
                        state.lastOutputAt = android.os.SystemClock.elapsedRealtime()
                        state.isStreaming = true
                        state.noteOutputChunk(bytes.length)
                        mainHandler.postDelayed({
                            if (android.os.SystemClock.elapsedRealtime() - state.lastOutputAt >= 1_100L) state.isStreaming = false
                        }, 1_150L)
                        if (route != AppRoute.TERMINAL || sessions.getOrNull(activePage) !== state) {
                            state.unread = true
                        }
                    }
                },
                onHostKey = { address, port, fingerprint, resolver -> mainHandler.post {
                    state.hostKeyAddress = address; state.hostKeyPort = port; state.hostFingerprint = fingerprint; state.resolveHostKey = resolver
                    alertConfirmation(appContext)
                } },
            )
            TerminalConnectionRegistry.register(task.id, state.controller)
            AutoConfirmEngine.registerSession(task.id, state)
            sessions.add(state)
            if (navigateToTerminal && (restoredTask == null || attachOrCreate)) {
                activePage = sessions.lastIndex
                route = AppRoute.TERMINAL
            }
            state.controller.start()
        }

        fun restoreFromLog(entry: OperationLogEntry) {
            val existingIndex = sessions.indexOfFirst {
                it.task.id == entry.sessionId || it.task.id.replace("-", "") == entry.sessionId.replace("-", "")
            }
            if (existingIndex >= 0) {
                activePage = existingIndex
                sessions[existingIndex].unread = false
                route = AppRoute.TERMINAL
                return
            }
            val host = hosts.firstOrNull { it.id == entry.hostId }
                ?: hosts.firstOrNull { it.name == entry.hostName }
            if (host == null) {
                scope.launch { snackbars.showSnackbar("未找到主机 ${entry.hostName}，请先添加主机") }
                return
            }
            val dir = host.directories.firstOrNull { it.path == entry.directoryPath || it.id == entry.directoryId }
                ?: ProjectDirectory(
                    entry.directoryId.ifBlank { UUID.randomUUID().toString() },
                    entry.directoryName.ifBlank { entry.directoryPath },
                    entry.directoryPath,
                )
            val agent = agents.firstOrNull { it.command == entry.agentCommand || it.id == entry.agentId }
                ?: AgentPreset(
                    entry.agentId.ifBlank { UUID.randomUUID().toString() },
                    entry.agentName.ifBlank { entry.agentCommand },
                    entry.agentCommand,
                    "✳",
                )
            val targetId = entry.sessionId.ifBlank { UUID.randomUUID().toString() }
            val displayName = entry.sessionName.ifBlank { "${dir.name} · ${agent.name}" }
            val task = TerminalTask(targetId, host, dir, agent, displayName, entry.titleSource)
            recordOperationLog(task, "resume")
            openTask(host, dir, agent, restoredTask = task, attachOrCreate = true)
        }

        fun openSharedSession(host: HostProfile, item: org.json.JSONObject, activate: Boolean = true) {
            val id = item.getString("id").replace("-", "")
            val path = item.getString("directory_path")
            val command = item.getString("agent_command")
            val existing = sessions.indexOfFirst {
                it.task.id.replace("-", "") == id
            }
            if (existing >= 0) {
                val remoteName = item.optString("display_name")
                val remoteSource = item.optString("title_source", "default").takeIf { it in setOf("default", "agent", "manual") } ?: "default"
                if (remoteName.isNotBlank()) applySessionTitle(sessions[existing], remoteName, remoteSource)
                if (activate) { activePage = existing; route = AppRoute.TERMINAL }
                return
            }
            val currentHost = hosts.firstOrNull { it.id == host.id } ?: run {
                scope.launch { snackbars.showSnackbar("找不到远端实例对应的主机，请先同步或重新添加主机") }
                return
            }
            val effectiveHost = if (currentHost.password.isNotBlank()) currentHost else currentHost.copy(password = store.loadHostPassword(currentHost.id))
            val directory = effectiveHost.directories.firstOrNull { it.path == path }
                ?: ProjectDirectory(UUID.randomUUID().toString(), item.getString("directory_name"), path)
            val updatedHost = if (directory in effectiveHost.directories) effectiveHost else effectiveHost.copy(directories = effectiveHost.directories + directory)
            if (updatedHost != currentHost) saveHosts(hosts.map { if (it.id == currentHost.id) updatedHost else it })
            val agent = agents.firstOrNull { it.command == command }
                ?: AgentPreset(UUID.randomUUID().toString(), item.getString("agent_name"), command, "✳").also { agents.add(it); store.saveAgents(agents) }
            val displayName = item.optString("display_name").ifBlank {
                if (directory.name.isNotBlank()) "${directory.name} · ${agent.name}" else agent.name
            }
            val titleSource = item.optString("title_source", "default").takeIf { it in setOf("default", "agent", "manual") } ?: "default"
            val task = TerminalTask(id, updatedHost, directory, agent, displayName, titleSource)
            sessionStore.save(task)
            openTask(updatedHost, directory, agent, task)
            if (activate) { activePage = sessions.lastIndex; route = AppRoute.TERMINAL }
        }

        suspend fun syncSharedSessions(host: HostProfile) {
            val account = store.loadSyncUsername()
            val pendingBeforePull = store.loadPendingSessionSync(account, host)
            val batch = withContext(SyncClient.dispatcher) { SyncClient.syncSessions(host, pendingBeforePull) }
            val pendingAfterPush = store.loadPendingSessionSync(account, host)
            val mergedPending = pendingBeforePull + pendingAfterPush
            val items = SyncClient.mergeSessionItems(batch.remoteItems, mergedPending)
            startupShared = startupShared.filterNot { it.first.id == host.id } + items.map { host to it }
            val remoteIds = items.map { it.optString("id").replace("-", "") }.toSet()
            sessions.toList().filter { session ->
                session.task.host.id == host.id && session.task.id.replace("-", "") !in remoteIds &&
                    android.os.SystemClock.elapsedRealtime() - session.startedAt > 15_000L
            }.forEach { closeSession(it, syncDeletion = false) }
            // Keep the remote list visible without attaching every remote tmux session automatically.
            // The user can choose an instance from HomeScreen when they want to take it over.
            if (sharedHost?.id == host.id && sharedSessions != null) sharedSessions = items
            store.clearPendingSessionSync(batch.completedMutationIds)
        }

        refreshShared = { host ->
            if (!offlineMode && autoSyncEnabled) {
                scope.launch {
                    runCatching { syncSharedSessions(host) }
                        .onFailure { snackbars.showSnackbar("${host.name} 同步失败：${it.message}") }
                }
            }
        }

        fun renameTask(session: TerminalSessionState, newName: String) {
            val trimmed = newName.trim()
            if (trimmed.isNotBlank() && trimmed != session.task.displayName) {
                applySessionTitle(session, trimmed, "manual")
                refreshShared(session.task.host)
            }
        }

        LaunchedEffect(Unit) {
            sessionStore.load(hosts, agents).forEach { task ->
                if (offlineMode || !autoSyncEnabled || syncUrl.isBlank() || syncToken.isBlank()) openTask(task.host, task.directory, task.agent, task)
            }
            activePage = activePage.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))
            sessionsRestored = true
        }

        LaunchedEffect(Unit) {
            fun resolveSessionMatch(selector: dev.remote.terminal.jarvis.mcp.SessionSelector): TerminalSessionState? {
                if (sessions.isEmpty()) return null
                if (!selector.hasAnyFilter()) {
                    return sessions.getOrNull(activePage) ?: sessions.firstOrNull()
                }
                selector.sessionId?.let { sid ->
                    val normalized = sid.replace("-", "").lowercase()
                    sessions.firstOrNull {
                        it.task.id.equals(sid, true) ||
                            it.task.id.replace("-", "").equals(normalized, true) ||
                            it.task.id.replace("-", "").startsWith(normalized, true)
                    }?.let { return it }
                }
                selector.sessionIndex?.let { idx ->
                    sessions.getOrNull(idx)?.let { return it }
                }

                val wsKey = (selector.workspaceName ?: selector.query ?: selector.sessionId)?.trim().orEmpty()
                val pathKey = selector.workspacePath?.trim().orEmpty()
                val hostKey = selector.hostName?.trim().orEmpty()
                val agentKey = selector.agentName?.trim().orEmpty()

                val scored = sessions.mapIndexed { idx, s ->
                    var score = 0
                    val dirName = s.task.directory.name
                    val dirPath = s.task.directory.path
                    val disp = s.task.displayName
                    val labelTitle = sessionDisplayLabel(s.task)
                    val hName = s.task.host.name
                    val hId = s.task.host.id
                    val aName = s.task.agent.name
                    val aCmd = s.task.agent.command

                    if (pathKey.isNotEmpty()) {
                        if (dirPath.equals(pathKey, true)) score += 100
                        else if (dirPath.contains(pathKey, true)) score += 50
                        else score -= 40
                    }
                    if (wsKey.isNotEmpty()) {
                        when {
                            dirName.equals(wsKey, true) || disp.equals(wsKey, true) || labelTitle.equals(wsKey, true) -> score += 90
                            dirName.contains(wsKey, true) || disp.contains(wsKey, true) || labelTitle.contains(wsKey, true) || dirPath.contains(wsKey, true) -> score += 45
                            else -> score -= 25
                        }
                    }
                    if (hostKey.isNotEmpty()) {
                        when {
                            hId.equals(hostKey, true) || hName.equals(hostKey, true) -> score += 60
                            hName.contains(hostKey, true) -> score += 30
                            else -> score -= 35
                        }
                    }
                    if (agentKey.isNotEmpty()) {
                        when {
                            aName.equals(agentKey, true) || aCmd.equals(agentKey, true) -> score += 55
                            aName.contains(agentKey, true) || aCmd.contains(agentKey, true) -> score += 28
                            else -> score -= 30
                        }
                    }
                    if (s.isSessionConnected) score += 8
                    if (idx == activePage) score += 5
                    Triple(score, idx, s)
                }
                val best = scored.maxByOrNull { it.first }
                return if (best != null && best.first >= 25) best.third else null
            }

            fun openSessionsHint(): String {
                if (sessions.isEmpty()) return "当前没有已打开的工作区终端实例"
                return sessions.mapIndexed { i, s -> "${i}:${s.task.directory.name}(${s.task.agent.name})" }.joinToString(", ")
            }

            fun formatExecResult(
                res: dev.remote.terminal.ssh.SshTerminalController.CommandExecResult,
                workspaceLabel: String
            ): dev.remote.terminal.jarvis.mcp.ToolOpResult {
                val json = org.json.JSONObject().apply {
                    put("ok", res.ok)
                    put("exit_code", res.exitCode)
                    put("timed_out", res.timedOut)
                    put("duration_ms", res.durationMs)
                    put("mode", res.mode)
                    put("workspace", workspaceLabel)
                    put("working_dir", res.workingDir)
                    put("stdout", res.stdout)
                    put("stderr", res.stderr)
                    put("truncated", res.truncated)
                    if (!res.error.isNullOrBlank()) put("error", res.error)
                }
                val preview = res.stdout.lines().lastOrNull { it.isNotBlank() }
                    ?: res.stderr.lines().lastOrNull { it.isNotBlank() }
                    ?: ""
                val voice = when {
                    res.timedOut -> "$workspaceLabel 命令执行超时（耗时 ${res.durationMs / 1000.0} 秒）。"
                    res.ok && preview.isNotBlank() -> "$workspaceLabel 执行完成，退出码 0，输出：${preview.take(45)}"
                    res.ok -> "$workspaceLabel 命令执行完成，退出码 0。"
                    else -> "$workspaceLabel 命令执行失败，退出码 ${res.exitCode}${if (preview.isNotBlank()) "，${preview.take(40)}" else ""}。"
                }
                return dev.remote.terminal.jarvis.mcp.ToolOpResult(
                    ok = res.ok,
                    detail = json.toString(),
                    voiceSummary = voice,
                    expectsFollowUp = true
                )
            }

            suspend fun dispatchTextToSession(
                target: TerminalSessionState,
                text: String,
                pressEnter: Boolean,
                sync: Boolean = false,
                timeoutMs: Long = dev.remote.terminal.ssh.SshTerminalController.DEFAULT_SYNC_TIMEOUT_MS,
                mode: String? = null
            ): dev.remote.terminal.jarvis.mcp.ToolOpResult {
                val label = "${target.task.directory.name}（${target.task.agent.name}）"
                val ctrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val c = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(target.task.id) ?: target.controller
                    if (target.status.startsWith("已断开") || target.status.startsWith("远端终端已结束")) {
                        c.start()
                    }
                    c
                }
                if (sync && pressEnter) {
                    val execRes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val effectiveMode = mode ?: if (ctrl.isShellSession()) "pty" else "pty_wait"
                        when (effectiveMode) {
                            "ssh_exec" -> ctrl.execCommandSync(text.trimEnd('\r', '\n'), timeoutMs)
                            "pty" -> if (ctrl.isShellSession()) {
                                ctrl.execInPtySync(text.trimEnd('\r', '\n'), timeoutMs)
                            } else {
                                ctrl.sendTextAndWaitSync(text, pressEnter = true, timeoutMs = timeoutMs, terminalBuffer = target.buffer)
                            }
                            else -> ctrl.sendTextAndWaitSync(text, pressEnter = true, timeoutMs = timeoutMs, terminalBuffer = target.buffer)
                        }
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { target.clearAttention() }
                    return formatExecResult(execRes, label)
                }

                val cleanText = text.trimEnd('\r', '\n')
                val outgoing = if (cleanText.contains('\n')) target.buffer.pasteSequence(cleanText) else cleanText
                if (outgoing.isNotEmpty()) {
                    ctrl.send(outgoing)
                }
                if (pressEnter) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        ctrl.send("\r")
                        target.clearAttention()
                    }, 120L)
                }
                return dev.remote.terminal.jarvis.mcp.ToolOpResult(
                    ok = true,
                    detail = "Sent command to session ${target.task.id} [$label]: ${cleanText.take(120)}",
                    voiceSummary = "已向工作区 $label 发送指令。"
                )
            }

            fun resolveHostBy(hostId: String?, hostName: String?): HostProfile? {
                if (!hostId.isNullOrBlank()) {
                    hosts.firstOrNull { it.id == hostId || it.name.equals(hostId, true) || it.name.contains(hostId, true) }?.let { return it }
                }
                if (!hostName.isNullOrBlank()) {
                    hosts.firstOrNull { it.name.equals(hostName, true) || it.name.contains(hostName, true) }?.let { return it }
                }
                return hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.appStateProvider = {
                org.json.JSONObject().apply {
                    put("current_screen", route.name.lowercase())
                    put("selected_host_id", selectedHostId)
                    put("selected_host_name", hosts.firstOrNull { it.id == selectedHostId }?.name.orEmpty())
                    put("selected_agent_id", selectedAgentId)
                    put("tiled_mode", tiledMode)
                    put("active_page", activePage)
                    put("auto_confirm_enabled", autoEnterEnabled)
                    put("sessions", org.json.JSONArray().apply {
                        sessions.forEachIndexed { idx, s ->
                            val tailLine = s.buffer.visibleTextTail(6)
                                .lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .lastOrNull()
                                .orEmpty()
                            put(org.json.JSONObject().apply {
                                put("index", idx)
                                put("id", s.task.id)
                                put("workspace", s.task.directory.name)
                                put("path", s.task.directory.path)
                                put("host", s.task.host.name)
                                put("host_id", s.task.host.id)
                                put("agent", s.task.agent.name)
                                put("agent_command", s.task.agent.command)
                                put("title", sessionDisplayLabel(s.task))
                                put("display_name", s.task.displayName)
                                put("status", s.status)
                                put("connected", s.isSessionConnected)
                                put("focused", idx == activePage && route == AppRoute.TERMINAL)
                                put("minimized", s.isMinimized)
                                put("is_streaming", s.isStreaming)
                                put("auto_enter_pending", s.autoEnterDeadline != null)
                                put("pending_host_key", s.hostFingerprint != null)
                                put("needs_action", s.needsAction)
                                quotaKey(s.task.host, s.task.agent.command)?.let { qKey ->
                                    quotas[qKey]?.let { qList ->
                                        put("quotas", org.json.JSONArray().apply {
                                            qList.forEach { q ->
                                                put(org.json.JSONObject().apply {
                                                    put("minutes", q.minutes)
                                                    put("remaining_percent", q.remainingPercent)
                                                })
                                            }
                                        })
                                    }
                                }
                                if (tailLine.isNotBlank()) put("last_line", tailLine.take(60))
                            })
                        }
                    })
                    val attachedIds = sessions.map { it.task.id.replace("-", "") }.toSet()
                    put("shared_tasks", org.json.JSONArray().apply {
                        startupShared.forEach { (h, item) ->
                            val sid = item.optString("id")
                            if (sid.replace("-", "") !in attachedIds) {
                                put(org.json.JSONObject().apply {
                                    put("id", sid)
                                    put("host", h.name)
                                    put("host_id", h.id)
                                    put("workspace", item.optString("directory_name"))
                                    put("path", item.optString("directory_path"))
                                    put("agent", item.optString("agent_name"))
                                    put("agent_command", item.optString("agent_command"))
                                    put("display_name", item.optString("display_name"))
                                    put("status", item.optString("status"))
                                })
                            }
                        }
                    })
                    put("settings", org.json.JSONObject().apply {
                        put("device_layout_mode", deviceLayoutMode)
                        put("tiled_mode", tiledMode)
                        put("auto_confirm_enabled", autoEnterEnabled)
                        put("auto_confirm_delay", autoEnterDelaySeconds)
                        put("offline_mode", offlineMode)
                        put("auto_sync_enabled", autoSyncEnabled)
                        put("sync_interval", syncIntervalSeconds)
                        put("quota_interval", quotaIntervalSeconds)
                        put("jarvis_model", store.loadJarvisModel())
                        put("jarvis_effort", store.loadJarvisEffort())
                        put("jarvis_wake_word", store.loadJarvisWakeWord())
                        put("jarvis_notify_finished", store.loadJarvisNotifyFinished())
                        put("jarvis_bluetooth_sco", store.loadJarvisBluetoothSco())
                    })
                    put("hosts", org.json.JSONArray().apply {
                        hosts.forEach { h ->
                            put(org.json.JSONObject().apply {
                                put("id", h.id)
                                put("name", h.name)
                                put("lan_address", h.lanAddress)
                                put("external_address", h.externalAddress)
                                put("workspaces", org.json.JSONArray().apply {
                                    h.directories.forEach { d ->
                                        put("${d.name}(${d.path})")
                                    }
                                })
                            })
                        }
                    })
                    put("agents", org.json.JSONArray().apply {
                        agents.forEach { a ->
                            put("${a.name}(${a.command})")
                        }
                    })
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.selectHostHandler = { id, index, name ->
                val target = when {
                    id != null -> hosts.firstOrNull { it.id == id }
                    index != null -> hosts.getOrNull(index)
                    name != null -> hosts.firstOrNull { it.name.equals(name, true) || it.name.contains(name, true) }
                    else -> null
                }
                if (target != null) {
                    selectHost(target)
                    true
                } else false
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.selectAgentHandler = { id, name ->
                val activeAgents = hostAgents(hosts.firstOrNull { it.id == selectedHostId }, agents)
                val target = when {
                    id != null -> activeAgents.firstOrNull { it.id == id } ?: agents.firstOrNull { it.id == id }
                    name != null -> activeAgents.firstOrNull { it.name.equals(name, true) || it.name.contains(name, true) || it.command.contains(name, true) }
                        ?: agents.firstOrNull { it.name.equals(name, true) || it.name.contains(name, true) || it.command.contains(name, true) }
                    else -> null
                }
                if (target != null) {
                    selectAgent(target)
                    true
                } else false
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.openTerminalHandler = { hostId, hostName, wsPath, wsName, cmd, agentName, reuseExisting, initialCommand, focus ->
                val targetHost = resolveHostBy(hostId, hostName)
                if (targetHost == null) {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "No host found", "未找到可用主机。")
                } else {
                    val targetDir = targetHost.directories.firstOrNull { d ->
                        (wsPath != null && (d.path.equals(wsPath, true) || d.path.contains(wsPath, true))) ||
                            (wsName != null && (d.name.equals(wsName, true) || d.name.contains(wsName, true)))
                    } ?: if (wsName == null && wsPath == null) targetHost.directories.firstOrNull() else null

                    val targetAgent = when {
                        cmd != null -> agents.firstOrNull { it.command.equals(cmd, true) || it.name.equals(cmd, true) || it.name.contains(cmd, true) }
                        agentName != null -> agents.firstOrNull { it.name.equals(agentName, true) || it.name.contains(agentName, true) || it.command.contains(agentName, true) }
                        else -> null
                    } ?: agents.firstOrNull { it.id == selectedAgentId } ?: agents.firstOrNull()

                    if (targetDir == null) {
                        dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Workspace not found on ${targetHost.name}", "在主机 ${targetHost.name} 上未找到工作区 ${wsName ?: wsPath}。")
                    } else if (targetAgent == null) {
                        dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "No agent configured", "未配置可用开发者预设。")
                    } else {
                        val existingIdx = if (reuseExisting) {
                            sessions.indexOfFirst { s ->
                                s.task.host.id == targetHost.id &&
                                    s.task.directory.path == targetDir.path &&
                                    (cmd == null && agentName == null || s.task.agent.id == targetAgent.id)
                            }
                        } else -1

                        if (existingIdx >= 0) {
                            val existing = sessions[existingIdx]
                            if (focus) {
                                activePage = existingIdx
                                route = AppRoute.TERMINAL
                            }
                            if (!initialCommand.isNullOrBlank()) {
                                val ctrl = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(existing.task.id) ?: existing.controller
                                val cleanCmd = initialCommand.trimEnd('\r', '\n')
                                ctrl.send(if (cleanCmd.contains('\n')) existing.buffer.pasteSequence(cleanCmd) else cleanCmd)
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    ctrl.send("\r")
                                    existing.clearAttention()
                                }, 120L)
                                dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                    true,
                                    "Sent initial command to existing session ${existing.task.id}",
                                    "已向进行中的 ${targetDir.name} 任务发送指令。"
                                )
                            } else {
                                dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                    true,
                                    "Focused existing session ${existing.task.id}",
                                    if (focus) "已为您切换到已打开的 ${targetDir.name} 终端。" else "工作区 ${targetDir.name} 正在运行中。"
                                )
                            }
                        } else {
                            openTask(targetHost, targetDir, targetAgent, navigateToTerminal = focus)
                            val opened = sessions.lastOrNull()
                            if (opened != null && !initialCommand.isNullOrBlank()) {
                                val ctrl = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(opened.task.id) ?: opened.controller
                                val cleanCmd = initialCommand.trimEnd('\r', '\n')
                                ctrl.send(if (cleanCmd.contains('\n')) opened.buffer.pasteSequence(cleanCmd) else cleanCmd)
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    ctrl.send("\r")
                                    opened.clearAttention()
                                }, 120L)
                                dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                    true,
                                    "Opened session ${opened.task.id} and queued command",
                                    "已为您在后台启动 ${targetDir.name}（${targetAgent.name}）并发送指令。"
                                )
                            } else {
                                dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                    true,
                                    "Opened ${targetDir.name} on ${targetHost.name}",
                                    "已为您打开 ${targetDir.name}（${targetAgent.name}）终端。"
                                )
                            }
                        }
                    }
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.runCommandHandler = { selector, command, timeoutMs, workingDir, mode ->
                val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val existing = resolveSessionMatch(selector)
                    if (existing != null) {
                        Triple(existing, existing.task.host, workingDir ?: existing.task.directory.path)
                    } else {
                        val host = resolveHostBy(selector.hostName, selector.hostName)
                        val wsQuery = selector.workspacePath ?: selector.workspaceName ?: selector.query ?: selector.sessionId
                        val dir = host?.directories?.firstOrNull { d ->
                            wsQuery != null && (d.path.equals(wsQuery, true) || d.path.contains(wsQuery, true) || d.name.equals(wsQuery, true) || d.name.contains(wsQuery, true))
                        } ?: if (wsQuery == null) host?.directories?.firstOrNull() else null
                        Triple(null, host, workingDir ?: dir?.path)
                    }
                }
                val session = resolved.first
                val host = resolved.second
                val targetDir = resolved.third
                if (session == null && (host == null || targetDir.isNullOrBlank())) {
                    val hint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { openSessionsHint() }
                    val errJson = org.json.JSONObject().apply {
                        put("ok", false)
                        put("exit_code", -1)
                        put("timed_out", false)
                        put("error", "Workspace/session not found. Open sessions: [$hint]")
                    }.toString()
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, errJson, "未找到对应工作区，当前已打开：$hint。", expectsFollowUp = true)
                } else {
                    val wsLabel = session?.let { "${it.task.directory.name}（${it.task.agent.name}）" }
                        ?: "${targetDir?.substringAfterLast('/').orEmpty()}（${host?.name.orEmpty()}）"
                    val execRes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        when {
                            mode == "pty" && session != null -> {
                                val ctrl = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(session.task.id) ?: session.controller
                                ctrl.execInPtySync(command, timeoutMs, workingDir)
                            }
                            session != null -> {
                                val ctrl = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(session.task.id) ?: session.controller
                                ctrl.execCommandSync(command, timeoutMs, workingDir)
                            }
                            else -> {
                                dev.remote.terminal.ssh.SshTerminalController.execOnHostSync(host!!, store, command, targetDir!!, timeoutMs)
                            }
                        }
                    }
                    formatExecResult(execRes, wsLabel)
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.runInWorkspaceHandler = { selector, text, pressEnter, agentName, sync, timeoutMs, mode, focus ->
                val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val existing = resolveSessionMatch(selector)
                    if (existing != null) {
                        val idx = sessions.indexOf(existing)
                        if (focus && idx >= 0) {
                            activePage = idx
                            route = AppRoute.TERMINAL
                        }
                        existing to null
                    } else {
                        val targetHost = resolveHostBy(selector.hostName, selector.hostName)
                        val wsQuery = selector.workspacePath ?: selector.workspaceName ?: selector.query ?: selector.sessionId
                        val targetDir = targetHost?.directories?.firstOrNull { d ->
                            wsQuery != null && (d.path.equals(wsQuery, true) || d.path.contains(wsQuery, true) || d.name.equals(wsQuery, true) || d.name.contains(wsQuery, true))
                        } ?: if (wsQuery == null) targetHost?.directories?.firstOrNull() else null
                        val targetAgent = (if (agentName != null) agents.firstOrNull { it.name.contains(agentName, true) || it.command.contains(agentName, true) } else null)
                            ?: agents.firstOrNull { it.id == selectedAgentId } ?: agents.firstOrNull()
                        if (targetHost != null && targetDir != null && targetAgent != null) {
                            openTask(targetHost, targetDir, targetAgent, navigateToTerminal = focus)
                            sessions.lastOrNull() to null
                        } else {
                            null to openSessionsHint()
                        }
                    }
                }
                val target = resolved.first
                if (target != null) {
                    dispatchTextToSession(target, text, pressEnter, sync = sync, timeoutMs = timeoutMs, mode = mode)
                } else {
                    val hint = resolved.second ?: ""
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        false,
                        "Session/workspace not found. Open sessions: [$hint]",
                        "未找到匹配的工作区实例。当前已打开：$hint。"
                    )
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.focusTerminalHandler = { selector ->
                val target = resolveSessionMatch(selector)
                val index = if (target != null) sessions.indexOf(target) else -1
                if (target != null && index in sessions.indices) {
                    activePage = index
                    target.unread = false
                    route = AppRoute.TERMINAL
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        true,
                        "Focused session ${target.task.id} (${target.task.directory.name})",
                        "已为您切换到工作区 ${target.task.directory.name}（${target.task.agent.name}）。"
                    )
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        false,
                        "Session not found. Open sessions: [${openSessionsHint()}]",
                        "未找到对应工作区会话。当前已打开：${openSessionsHint()}。"
                    )
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.closeTerminalHandler = { selector ->
                val target = resolveSessionMatch(selector)
                if (target != null) {
                    val label = "${target.task.directory.name}（${target.task.agent.name}）"
                    closeSession(target)
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Closed session ${target.task.id}", "已为您关闭工作区 $label。")
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Session not found", "未找到需要关闭的终端会话。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.reconnectTerminalHandler = { selector ->
                val target = resolveSessionMatch(selector)
                if (target != null) {
                    target.controller.start()
                    val label = "${target.task.directory.name}（${target.task.agent.name}）"
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Reconnecting ${target.task.id}", "已为工作区 $label 发起重连。")
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Session not found", "未找到需要重连的终端会话。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.renameTerminalHandler = { selector, newName ->
                val target = resolveSessionMatch(selector)
                if (target != null) {
                    renameTask(target, newName)
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Renamed session to $newName", "已将终端标签重命名为 $newName。")
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Session not found", "未找到需要重命名的终端会话。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.scrollTerminalHandler = { selector, direction ->
                val target = resolveSessionMatch(selector)
                if (target != null) {
                    val ctrl = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(target.task.id) ?: target.controller
                    when (direction) {
                        "up", "pageup" -> ctrl.scrollTmuxPage(up = true)
                        "down", "pagedown", "bottom" -> ctrl.scrollTmuxPage(up = false)
                        else -> ctrl.scrollTmuxPage(up = true)
                    }
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        true,
                        "Scrolled ${target.task.directory.name} $direction",
                        "已将 ${target.task.directory.name} 终端${if (direction == "up" || direction == "pageup") "向上" else "向下"}翻页。"
                    )
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Session not found", "未找到目标终端会话。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.confirmTerminalHandler = { selector, approve ->
                val target = resolveSessionMatch(selector)
                if (target != null) {
                    val ctrl = dev.remote.terminal.ssh.TerminalConnectionRegistry.get(target.task.id) ?: target.controller
                    ctrl.send(if (approve) "\r" else "\u0003")
                    target.clearAttention()
                    val label = "${target.task.directory.name}（${target.task.agent.name}）"
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        true,
                        "Confirmed=$approve for ${target.task.id}",
                        "已在 $label ${if (approve) "确认继续执行" else "发送中止"}。"
                    )
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Session not found", "未找到目标终端会话。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.sendTextHandler = { text, selector, pressEnter, sync, timeoutMs, mode ->
                val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val existing = resolveSessionMatch(selector)
                    if (existing != null) {
                        existing to null
                    } else if (selector.hasAnyFilter()) {
                        // 在首页尚未进入某个任务时，尝试匹配首页共享/已同步进行中任务或工作区并在后台直接附加发送（不跳转页面）
                        val wsQuery = selector.workspacePath ?: selector.workspaceName ?: selector.query ?: selector.sessionId
                        val sharedMatch = startupShared.firstOrNull { (_, item) ->
                            val sid = item.optString("id")
                            val dName = item.optString("directory_name")
                            val dPath = item.optString("directory_path")
                            val disp = item.optString("display_name")
                            wsQuery != null && (
                                sid.equals(wsQuery, true) ||
                                    sid.replace("-", "").startsWith(wsQuery.replace("-", ""), true) ||
                                    dName.contains(wsQuery, true) ||
                                    dPath.contains(wsQuery, true) ||
                                    disp.contains(wsQuery, true)
                                )
                        }
                        if (sharedMatch != null) {
                            val (sHost, sItem) = sharedMatch
                            openSharedSession(sHost, sItem, activate = false)
                            resolveSessionMatch(selector) to null
                        } else {
                            null to openSessionsHint()
                        }
                    } else {
                        null to openSessionsHint()
                    }
                }
                val target = resolved.first
                if (target != null) {
                    dispatchTextToSession(target, text, pressEnter, sync = sync, timeoutMs = timeoutMs, mode = mode)
                } else {
                    val hint = resolved.second ?: ""
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        ok = false,
                        detail = "Target workspace/session not found. Open sessions: [$hint]. Use open_terminal or run_in_workspace if it needs to be opened.",
                        voiceSummary = if (sessions.isEmpty()) "当前没有打开的工作区终端，请先打开终端。" else "未找到对应工作区会话，当前已打开：$hint。"
                    )
                }
            }

            suspend fun resolveOrAttachShared(selector: dev.remote.terminal.jarvis.mcp.SessionSelector, waitMs: Long = 450L): TerminalSessionState? {
                resolveSessionMatch(selector)?.let { return it }
                if (!selector.hasAnyFilter()) return null
                val wsQuery = selector.workspacePath ?: selector.workspaceName ?: selector.query ?: selector.sessionId ?: selector.agentName
                val sharedMatch = startupShared.firstOrNull { (_, item) ->
                    val sid = item.optString("id")
                    val dName = item.optString("directory_name")
                    val dPath = item.optString("directory_path")
                    val disp = item.optString("display_name")
                    val agName = item.optString("agent_name")
                    wsQuery != null && (
                        sid.equals(wsQuery, true) ||
                            sid.replace("-", "").startsWith(wsQuery.replace("-", ""), true) ||
                            dName.contains(wsQuery, true) ||
                            dPath.contains(wsQuery, true) ||
                            disp.contains(wsQuery, true) ||
                            agName.contains(wsQuery, true)
                        )
                } ?: return null
                openSharedSession(sharedMatch.first, sharedMatch.second, activate = false)
                if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                return resolveSessionMatch(selector)
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.sendKeysHandler = { keys, selector, sync, waitMs ->
                val target = resolveOrAttachShared(selector)
                val ctrl = target?.let { dev.remote.terminal.ssh.TerminalConnectionRegistry.get(it.task.id) ?: it.controller }
                if (target != null && ctrl != null) {
                    val sentSeqs = mutableListOf<String>()
                    withContext(Dispatchers.IO) {
                        keys.forEachIndexed { idx, keyName ->
                            val seq = dev.remote.terminal.jarvis.mcp.McpKeyMap.resolveKey(keyName) ?: keyName
                            ctrl.send(seq)
                            sentSeqs.add(keyName)
                            if (idx + 1 < keys.size) kotlinx.coroutines.delay(30L)
                        }
                    }
                    target.clearAttention()
                    val payload = org.json.JSONObject().apply {
                        put("session_id", target.task.id)
                        put("workspace", target.task.directory.name)
                        put("keys", org.json.JSONArray(sentSeqs))
                        if (sync) {
                            withContext(Dispatchers.IO) {
                                kotlinx.coroutines.delay(waitMs.coerceIn(100L, 10_000L))
                            }
                            val tail = target.buffer.visibleTextTail(30)
                                .lines()
                                .filter { it.isNotBlank() }
                                .takeLast(30)
                                .joinToString("\n")
                            put("screen_tail", tail)
                        }
                    }
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        ok = true,
                        detail = payload.toString(),
                        voiceSummary = "已向 ${target.task.directory.name} 发送按键 $keys。",
                        expectsFollowUp = sync
                    )
                } else {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        ok = false,
                        detail = "Target session not found. Open sessions: [${openSessionsHint()}]",
                        voiceSummary = "未找到目标终端会话，按键发送失败。"
                    )
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.readScreenHandler = { selector, lines ->
                val target = resolveOrAttachShared(selector, waitMs = 550L)
                if (target == null) {
                    false to "未找到对应工作区会话。当前已打开：[${openSessionsHint()}]"
                } else {
                    val full = target.buffer.visibleTextTail(lines.coerceIn(1, 100))
                    val lineList = full.lines().filter { it.isNotBlank() }
                    true to lineList.takeLast(lines.coerceIn(1, 100)).joinToString("\n").ifBlank { "终端屏幕当前无输出" }
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.setViewModeHandler = { tiled ->
                tiledMode = tiled
                store.saveTiledMode(tiled)
                true
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.autoConfirmHandler = { enabled, delaySeconds ->
                val nextEnabled = enabled ?: autoEnterEnabled
                val nextDelay = (delaySeconds ?: autoEnterDelaySeconds).coerceIn(1, 30)
                autoEnterEnabled = nextEnabled
                autoEnterDelaySeconds = nextDelay
                store.saveAutoEnterEnabled(nextEnabled)
                store.saveAutoEnterDelaySeconds(nextDelay)
                AutoConfirmEngine.globalEnabled = nextEnabled
                AutoConfirmEngine.globalDelaySeconds = nextDelay
                dev.remote.terminal.jarvis.mcp.ToolOpResult(
                    true,
                    "Auto-confirm enabled=$nextEnabled delay=${nextDelay}s",
                    "已${if (nextEnabled) "开启" else "关闭"}自动确认，延迟 ${nextDelay} 秒。"
                )
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.triggerSyncHandler = {
                val host = hosts.firstOrNull { it.id == selectedHostId }
                if (host != null) {
                    refreshShared(host)
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.publicKeyProvider = {
                store.sshPublicKey()
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.addWorkspaceHandler = { path, name, hostId, hostName ->
                val host = resolveHostBy(hostId, hostName)
                if (host != null) {
                    val newDir = ProjectDirectory(java.util.UUID.randomUUID().toString(), name, path)
                    val updatedHost = host.copy(directories = host.directories + newDir)
                    saveHosts(hosts.map { if (it.id == host.id) updatedHost else it })
                    true
                } else false
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.deleteWorkspaceHandler = { path, hostId, hostName ->
                val host = resolveHostBy(hostId, hostName)
                if (host != null) {
                    val updatedHost = host.copy(directories = host.directories.filterNot { it.path == path || it.name.equals(path, true) })
                    saveHosts(hosts.map { if (it.id == host.id) updatedHost else it })
                    true
                } else false
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.vibrateHandler = { _ ->
                alertConfirmation(appContext)
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.deviceStateProvider = {
                val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val battery = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val isCharging = batteryManager?.isCharging == true
                org.json.JSONObject().apply {
                    put("battery_level", battery)
                    put("is_charging", isCharging)
                    put("sessions_count", sessions.size)
                    put("tiled_mode", tiledMode)
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.navigateScreenHandler = { screen ->
                val nextRoute = when (screen.trim().lowercase()) {
                    "home", "主页" -> AppRoute.HOME
                    "terminal", "终端" -> if (sessions.isNotEmpty()) AppRoute.TERMINAL else null
                    "settings", "设置" -> AppRoute.SETTINGS
                    "manage", "管理", "工作区", "主机" -> AppRoute.MANAGE
                    "agents", "开发者" -> AppRoute.AGENTS
                    "logs", "history", "历史", "日志" -> AppRoute.LOGS
                    else -> null
                }
                if (nextRoute != null) {
                    route = nextRoute
                    true
                } else false
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.taskStatusHandler = { selector, lines ->
                fun buildSessionJson(idx: Int, s: TerminalSessionState): org.json.JSONObject {
                    val tail = s.buffer.visibleTextTail(lines)
                        .lines()
                        .filter { it.isNotBlank() }
                        .takeLast(lines)
                        .joinToString("\n")
                    return org.json.JSONObject().apply {
                        put("index", idx)
                        put("id", s.task.id)
                        put("title", sessionDisplayLabel(s.task))
                        put("workspace", s.task.directory.name)
                        put("path", s.task.directory.path)
                        put("host", s.task.host.name)
                        put("agent", s.task.agent.name)
                        put("agent_command", s.task.agent.command)
                        put("status", s.status)
                        put("connected", s.isSessionConnected)
                        put("is_streaming", s.isStreaming)
                        put("needs_action", s.needsAction)
                        put("auto_enter_pending", s.autoEnterDeadline != null)
                        put("minimized", s.isMinimized)
                        put("pending_host_key", s.hostFingerprint != null)
                        quotaKey(s.task.host, s.task.agent.command)?.let { key ->
                            quotas[key]?.let { qList ->
                                put("quotas", org.json.JSONArray().apply {
                                    qList.forEach { q ->
                                        put(org.json.JSONObject().put("minutes", q.minutes).put("remaining_percent", q.remainingPercent))
                                    }
                                })
                            }
                        }
                        if (tail.isNotBlank()) put("screen_tail", tail)
                    }
                }

                if (!selector.hasAnyFilter()) {
                    val attachedIds = sessions.map { it.task.id.replace("-", "") }.toSet()
                    val tasksArr = org.json.JSONArray().apply {
                        sessions.forEachIndexed { idx, s -> put(buildSessionJson(idx, s)) }
                    }
                    val sharedArr = org.json.JSONArray().apply {
                        startupShared.forEach { (h, item) ->
                            val sid = item.optString("id")
                            if (sid.replace("-", "") !in attachedIds) {
                                put(org.json.JSONObject().apply {
                                    put("id", sid)
                                    put("host", h.name)
                                    put("workspace", item.optString("directory_name"))
                                    put("path", item.optString("directory_path"))
                                    put("agent", item.optString("agent_name"))
                                    put("status", item.optString("status"))
                                })
                            }
                        }
                    }
                    val root = org.json.JSONObject().apply {
                        put("current_screen", route.name.lowercase())
                        put("sessions", tasksArr)
                        put("shared_tasks", sharedArr)
                    }
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(
                        ok = true,
                        detail = root.toString(),
                        voiceSummary = "当前有 ${sessions.size} 个本地任务、${sharedArr.length()} 个待附着共享任务。",
                        expectsFollowUp = true
                    )
                } else {
                    val target = resolveOrAttachShared(selector, waitMs = 500L)
                    if (target == null) {
                        dev.remote.terminal.jarvis.mcp.ToolOpResult(
                            ok = false,
                            detail = "Target task not found. Open sessions: [${openSessionsHint()}]",
                            voiceSummary = "未找到对应进行中任务。"
                        )
                    } else {
                        val idx = sessions.indexOf(target)
                        val info = buildSessionJson(idx, target)
                        dev.remote.terminal.jarvis.mcp.ToolOpResult(
                            ok = true,
                            detail = info.toString(),
                            voiceSummary = "${target.task.directory.name}（${target.task.agent.name}）状态：${target.status}。",
                            expectsFollowUp = true
                        )
                    }
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.manageHostHandler = { action, args ->
                val hostId = args.optString("host_id", "").trim().takeIf { it.isNotBlank() }
                val name = args.optString("name", "").trim()
                fun matchHost(): HostProfile? = when {
                    hostId != null -> hosts.firstOrNull { it.id == hostId || it.name.equals(hostId, true) }
                    name.isNotBlank() -> hosts.firstOrNull { it.name.equals(name, true) || it.name.contains(name, true) }
                    else -> hosts.firstOrNull { it.id == selectedHostId }
                }

                when (action) {
                    "add" -> {
                        val lanAddr = args.optString("lan_address", "").trim()
                        val extAddr = args.optString("external_address", "").trim()
                        val primaryAddr = extAddr.ifBlank { lanAddr }
                        if (primaryAddr.isBlank()) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Missing lan_address or external_address", "添加主机需要提供内网或外网地址。")
                        } else {
                            val lanPort = args.optInt("lan_port", 22).coerceIn(1, 65535)
                            val extPort = args.optInt("external_port", 22).coerceIn(1, 65535)
                            val username = args.optString("username", "").trim().ifBlank { store.loadSyncUsername().ifBlank { "root" } }
                            val password = args.optString("password", "")
                            val icon = args.optString("icon", "⌘").ifBlank { "⌘" }
                            val newId = java.util.UUID.randomUUID().toString()
                            val newHost = HostProfile(
                                id = newId,
                                name = name.ifBlank { primaryAddr },
                                address = primaryAddr,
                                port = if (extAddr.isNotBlank()) extPort else lanPort,
                                username = username,
                                password = password,
                                icon = icon,
                                lanAddress = lanAddr,
                                externalAddress = extAddr,
                                legacyAddress = primaryAddr,
                                lanPort = lanPort,
                                externalPort = extPort,
                            )
                            saveHosts(hosts + newHost)
                            dirtyHostProfiles.add(newHost.id)
                            store.saveDirtyHostProfiles(dirtyHostProfiles)
                            syncHostProfile(newHost)
                            if (selectedHostId.isBlank()) selectHost(newHost)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                true,
                                org.json.JSONObject().put("id", newHost.id).put("name", newHost.name).toString(),
                                "已添加主机 ${newHost.name}。"
                            )
                        }
                    }
                    "update" -> {
                        val target = matchHost()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Host not found", "未找到要修改的主机。")
                        } else {
                            val lanAddr = if (args.has("lan_address")) args.optString("lan_address", "").trim() else target.lanAddress
                            val extAddr = if (args.has("external_address")) args.optString("external_address", "").trim() else target.externalAddress
                            val lanPort = if (args.has("lan_port")) args.optInt("lan_port", target.lanPort).coerceIn(1, 65535) else target.lanPort
                            val extPort = if (args.has("external_port")) args.optInt("external_port", target.externalPort).coerceIn(1, 65535) else target.externalPort
                            val nextName = if (args.has("name") && name.isNotBlank()) name else target.name
                            val nextUser = args.optString("username", "").trim().ifBlank { target.username }
                            val nextPass = args.optString("password", "").ifBlank { target.password }
                            val nextIcon = args.optString("icon", "").ifBlank { target.icon }
                            val primaryAddr = extAddr.ifBlank { lanAddr }.ifBlank { target.address }
                            val updated = target.copy(
                                name = nextName,
                                address = primaryAddr,
                                port = if (extAddr.isNotBlank()) extPort else lanPort,
                                username = nextUser,
                                password = nextPass,
                                icon = nextIcon,
                                lanAddress = lanAddr,
                                externalAddress = extAddr,
                                legacyAddress = primaryAddr,
                                lanPort = lanPort,
                                externalPort = extPort,
                            )
                            HostRouteResolver.invalidate(updated.id)
                            saveHosts(hosts.map { if (it.id == updated.id) updated else it })
                            sessions.forEach { s -> if (s.task.host.id == updated.id) s.task = s.task.copy(host = updated) }
                            dirtyHostProfiles.add(updated.id)
                            store.saveDirtyHostProfiles(dirtyHostProfiles)
                            syncHostProfile(updated)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Updated host ${updated.name}", "已更新主机 ${updated.name}。")
                        }
                    }
                    "delete" -> {
                        val target = matchHost()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Host not found", "未找到要删除的主机。")
                        } else {
                            dirtyHostProfiles.remove(target.id)
                            store.saveDirtyHostProfiles(dirtyHostProfiles)
                            store.removeHostPassword(target.id)
                            saveHosts(hosts.filterNot { it.id == target.id })
                            if (!offlineMode && syncUrl.isNotBlank() && syncToken.isNotBlank()) {
                                scope.launch { runCatching { withContext(SyncClient.dispatcher) { SyncClient.deleteHostProfile(target.id) } } }
                            }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Deleted host ${target.name}", "已删除主机 ${target.name}。")
                        }
                    }
                    "select" -> {
                        val target = matchHost()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Host not found", "未找到主机。")
                        } else {
                            selectHost(target)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Selected host ${target.name}", "已选中主机 ${target.name}。")
                        }
                    }
                    "probe" -> {
                        val target = matchHost()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Host not found", "未找到主机。")
                        } else {
                            val chosen = HostRouteResolver.resolveHostRoute(target, force = true)
                            val (lanOk, extOk) = withContext(Dispatchers.IO) {
                                val l = if (target.lanAddress.isNotBlank()) HostRouteResolver.isPortReachable(target.lanAddress.trim(), target.lanPort, 1500) else false
                                val e = if (target.externalAddress.isNotBlank()) HostRouteResolver.isPortReachable(target.externalAddress.trim(), target.externalPort, 2000) else false
                                l to e
                            }
                            val info = org.json.JSONObject().apply {
                                put("host", target.name)
                                put("selected_address", chosen.address)
                                put("selected_port", chosen.port)
                                put("is_lan", chosen.isLan)
                                put("reachable", chosen.reachable)
                                put("lan_reachable", lanOk)
                                put("external_reachable", extOk)
                            }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                true,
                                info.toString(),
                                if (chosen.reachable) "主机 ${target.name} 可达（${chosen.address}:${chosen.port}）。" else "主机 ${target.name} 当前不可达。",
                                expectsFollowUp = true
                            )
                        }
                    }
                    "move" -> {
                        val target = matchHost()
                        val toIdx = args.optInt("to_index", -1)
                        val fromIdx = if (target != null) hosts.indexOfFirst { it.id == target.id } else -1
                        if (target == null || fromIdx < 0 || toIdx !in hosts.indices) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Invalid host or to_index", "无法移动主机顺序。")
                        } else {
                            val reordered = hosts.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                            saveHosts(reordered)
                            dirtyHostProfiles.add(target.id)
                            store.saveDirtyHostProfiles(dirtyHostProfiles)
                            syncHostProfile(target)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Moved host ${target.name} to index $toIdx", "已将主机 ${target.name} 移至第 ${toIdx + 1} 位。")
                        }
                    }
                    else -> dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Unknown host action: $action", "不支持的主机操作。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.manageAgentHandler = { action, args ->
                val agentId = args.optString("agent_id", "").trim().takeIf { it.isNotBlank() }
                val name = args.optString("name", "").trim()
                val cmd = args.optString("command", "").trim()
                val icon = args.optString("icon", "✳").ifBlank { "✳" }
                val activeList = currentAgents
                fun matchAgent(): AgentPreset? = when {
                    agentId != null -> activeList.firstOrNull { it.id == agentId }
                    name.isNotBlank() -> activeList.firstOrNull { it.name.equals(name, true) || it.name.contains(name, true) || it.command.equals(name, true) }
                    cmd.isNotBlank() -> activeList.firstOrNull { it.command.equals(cmd, true) || it.command.contains(cmd, true) }
                    else -> activeList.firstOrNull { it.id == selectedAgentId }
                }

                when (action) {
                    "select" -> {
                        val target = matchAgent()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Agent not found", "未找到对应 AI 开发者。")
                        } else {
                            selectAgent(target)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Selected agent ${target.name}", "已切换开发者为 ${target.name}。")
                        }
                    }
                    "add" -> {
                        if (name.isBlank() && cmd.isBlank()) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Missing name or command", "添加开发者需要名称或启动命令。")
                        } else {
                            val newAgent = AgentPreset(
                                id = java.util.UUID.randomUUID().toString(),
                                name = name.ifBlank { cmd },
                                command = cmd.ifBlank { name },
                                icon = icon
                            )
                            val updated = agents.toList() + newAgent
                            replaceAgents(updated)
                            selectedHost?.let { syncAgentList(it, hostAgents(it, updated)) }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Added agent ${newAgent.name}", "已添加开发者 ${newAgent.name}。")
                        }
                    }
                    "update" -> {
                        val target = matchAgent()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Agent not found", "未找到要修改的开发者。")
                        } else {
                            val nextAgent = target.copy(
                                name = name.ifBlank { target.name },
                                command = cmd.ifBlank { target.command },
                                icon = if (args.has("icon")) icon else target.icon
                            )
                            val updated = agents.map { if (it.id == target.id) nextAgent else it }
                            replaceAgents(updated)
                            selectedHost?.let { syncAgentList(it, hostAgents(it, updated)) }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Updated agent ${nextAgent.name}", "已更新开发者 ${nextAgent.name}。")
                        }
                    }
                    "delete" -> {
                        val target = matchAgent()
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Agent not found", "未找到要删除的开发者。")
                        } else {
                            val updated = agents.filterNot { it.id == target.id }
                            replaceAgents(updated)
                            selectedHost?.let { syncAgentList(it, hostAgents(it, updated)) }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Deleted agent ${target.name}", "已删除开发者 ${target.name}。")
                        }
                    }
                    "move" -> {
                        val target = matchAgent()
                        val toIdx = args.optInt("to_index", -1)
                        val fromIdx = if (target != null) agents.indexOfFirst { it.id == target.id } else -1
                        if (target == null || fromIdx < 0 || toIdx !in agents.indices) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Invalid agent or to_index", "无法调整开发者排序。")
                        } else {
                            val updated = agents.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                            replaceAgents(updated)
                            selectedHost?.let { syncAgentList(it, hostAgents(it, updated)) }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Moved agent ${target.name} to $toIdx", "已将开发者 ${target.name} 移至第 ${toIdx + 1} 位。")
                        }
                    }
                    else -> dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Unknown agent action: $action", "不支持的开发者操作。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.manageWorkspaceHandler = { action, args ->
                val hostId = args.optString("host_id", "").trim().takeIf { it.isNotBlank() }
                val hostName = args.optString("host_name", "").trim().takeIf { it.isNotBlank() }
                val host = resolveHostBy(hostId, hostName)
                val path = args.optString("path", "").trim()
                val newPath = args.optString("new_path", "").trim()
                val name = args.optString("name", "").trim()
                val icon = args.optString("icon", "").trim()
                if (host == null) {
                    dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "No host found", "未找到对应主机。")
                } else when (action) {
                    "list_remote" -> {
                        runCatching {
                            val listing = withContext(Dispatchers.IO) {
                                dev.remote.terminal.ssh.RemoteDirectoryBrowser.listDirectories(host, store, path.ifBlank { "~" })
                            }
                            val payload = org.json.JSONObject().apply {
                                put("host", host.name)
                                put("path", listing.path)
                                put("directories", org.json.JSONArray().apply {
                                    listing.directories.forEach { d ->
                                        put(org.json.JSONObject().put("name", d.name).put("path", d.path))
                                    }
                                })
                            }
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                true,
                                payload.toString(),
                                "在 ${listing.path} 找到 ${listing.directories.size} 个子目录。",
                                expectsFollowUp = true
                            )
                        }.getOrElse { err ->
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                false,
                                "Remote directory listing failed: ${err.message}",
                                "浏览远端目录失败：${err.message ?: "连接异常"}。"
                            )
                        }
                    }
                    "add" -> {
                        if (path.isBlank()) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Missing path", "添加工作区需要指定目录路径。")
                        } else {
                            val dirName = name.ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
                            val newDir = ProjectDirectory(java.util.UUID.randomUUID().toString(), dirName, path, icon.ifBlank { "📁" })
                            val updatedDirs = host.directories + newDir
                            val updatedHost = host.copy(directories = updatedDirs)
                            saveHosts(hosts.map { if (it.id == host.id) updatedHost else it })
                            syncWorkspaceList(updatedHost, updatedDirs)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Added workspace ${newDir.name} (${newDir.path})", "已为 ${host.name} 添加工作区 ${newDir.name}。")
                        }
                    }
                    "update" -> {
                        val targetIdx = host.directories.indexOfFirst {
                            (path.isNotBlank() && (it.path == path || it.name.equals(path, true))) ||
                                (name.isNotBlank() && it.name.equals(name, true))
                        }
                        if (targetIdx < 0) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Workspace not found on ${host.name}", "未找到要修改的工作区目录。")
                        } else {
                            val cur = host.directories[targetIdx]
                            val nextDir = cur.copy(
                                name = name.ifBlank { cur.name },
                                path = newPath.ifBlank { cur.path },
                                icon = icon.ifBlank { cur.icon }
                            )
                            val updatedDirs = host.directories.toMutableList().apply { this[targetIdx] = nextDir }
                            val updatedHost = host.copy(directories = updatedDirs)
                            saveHosts(hosts.map { if (it.id == host.id) updatedHost else it })
                            syncWorkspaceList(updatedHost, updatedDirs)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Updated workspace ${nextDir.name}", "已更新工作区 ${nextDir.name}。")
                        }
                    }
                    "delete" -> {
                        val updatedDirs = host.directories.filterNot {
                            (path.isNotBlank() && (it.path == path || it.name.equals(path, true))) ||
                                (name.isNotBlank() && it.name.equals(name, true))
                        }
                        if (updatedDirs.size == host.directories.size) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Workspace not found on ${host.name}", "未找到要删除的工作区目录。")
                        } else {
                            val updatedHost = host.copy(directories = updatedDirs)
                            saveHosts(hosts.map { if (it.id == host.id) updatedHost else it })
                            syncWorkspaceList(updatedHost, updatedDirs)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Deleted workspace", "已删除工作区目录。")
                        }
                    }
                    "move" -> {
                        val fromIdx = host.directories.indexOfFirst {
                            (path.isNotBlank() && (it.path == path || it.name.equals(path, true))) ||
                                (name.isNotBlank() && it.name.equals(name, true))
                        }
                        val toIdx = args.optInt("to_index", -1)
                        if (fromIdx < 0 || toIdx !in host.directories.indices) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Invalid workspace or to_index", "无法调整工作区排序。")
                        } else {
                            moveDirectory(host, fromIdx, toIdx - fromIdx)
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Moved workspace to index $toIdx", "已调整工作区排序。")
                        }
                    }
                    else -> dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Unknown workspace action: $action", "不支持的工作区操作。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.manageLogsHandler = { action, args ->
                if (!offlineMode && syncUrl.isNotBlank() && syncToken.isNotBlank()) {
                    runCatching { syncOperationLogs() }
                }
                val sessionId = args.optString("session_id", "").trim()
                val query = args.optString("query", "").trim()
                val limit = args.optInt("limit", 15).coerceIn(1, 50)
                when (action) {
                    "list" -> {
                        val filtered = operationLogs.filter { entry ->
                            if (query.isBlank() && sessionId.isBlank()) true
                            else (sessionId.isNotBlank() && (entry.sessionId.equals(sessionId, true) || entry.sessionId.replace("-", "").startsWith(sessionId.replace("-", ""), true))) ||
                                (query.isNotBlank() && (
                                    entry.sessionName.contains(query, true) ||
                                        entry.directoryName.contains(query, true) ||
                                        entry.directoryPath.contains(query, true) ||
                                        entry.hostName.contains(query, true) ||
                                        entry.agentName.contains(query, true)
                                    ))
                        }.take(limit)
                        val arr = org.json.JSONArray().apply {
                            filtered.forEach { e ->
                                put(org.json.JSONObject().apply {
                                    put("id", e.id)
                                    put("session_id", e.sessionId)
                                    put("session_name", e.sessionName)
                                    put("host", e.hostName)
                                    put("workspace", e.directoryName)
                                    put("path", e.directoryPath)
                                    put("agent", e.agentName)
                                    put("agent_command", e.agentCommand)
                                    put("updated_at", e.updatedAt)
                                })
                            }
                        }
                        dev.remote.terminal.jarvis.mcp.ToolOpResult(
                            true,
                            arr.toString(),
                            "共检索到 ${filtered.size} 条历史操作日志。",
                            expectsFollowUp = true
                        )
                    }
                    "restore" -> {
                        val target = operationLogs.firstOrNull { e ->
                            (sessionId.isNotBlank() && (e.sessionId.equals(sessionId, true) || e.id == sessionId || e.sessionId.replace("-", "").startsWith(sessionId.replace("-", ""), true))) ||
                                (query.isNotBlank() && (
                                    e.sessionName.contains(query, true) ||
                                        e.directoryName.contains(query, true) ||
                                        e.directoryPath.contains(query, true) ||
                                        e.agentName.contains(query, true)
                                    ))
                        }
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "No matching history log found", "未找到匹配的历史记录。")
                        } else {
                            val focus = args.optBoolean("focus", false)
                            val prevRoute = route
                            restoreFromLog(target)
                            if (!focus) route = prevRoute
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                true,
                                "Restored session ${target.sessionName.ifBlank { target.directoryName }} (${target.sessionId}), focus=$focus",
                                "已从历史记录恢复 ${target.directoryName}（${target.agentName}）。"
                            )
                        }
                    }
                    else -> dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Unknown logs action: $action", "不支持的日志操作。")
                }
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.updateSettingsHandler = { args ->
                val changed = mutableListOf<String>()
                var generatedPubKey: String? = null
                if (args.has("device_layout_mode")) {
                    val m = args.optString("device_layout_mode", "auto").trim().lowercase()
                    if (m in setOf("auto", "phone", "pad")) {
                        deviceLayoutMode = m
                        store.saveDeviceLayoutMode(m)
                        changed.add("device_layout_mode=$m")
                    }
                }
                if (args.has("tiled_mode")) {
                    val v = args.optBoolean("tiled_mode", tiledMode)
                    tiledMode = v
                    store.saveTiledMode(v)
                    changed.add("tiled_mode=$v")
                }
                if (args.has("auto_confirm_enabled")) {
                    val v = args.optBoolean("auto_confirm_enabled", autoEnterEnabled)
                    autoEnterEnabled = v
                    store.saveAutoEnterEnabled(v)
                    changed.add("auto_confirm_enabled=$v")
                }
                if (args.has("auto_confirm_delay")) {
                    val v = args.optInt("auto_confirm_delay", autoEnterDelaySeconds).coerceIn(1, 30)
                    autoEnterDelaySeconds = v
                    store.saveAutoEnterDelaySeconds(v)
                    changed.add("auto_confirm_delay=$v")
                }
                if (args.has("offline_mode")) {
                    val v = args.optBoolean("offline_mode", offlineMode)
                    offlineMode = v
                    store.saveOfflineMode(v)
                    changed.add("offline_mode=$v")
                }
                if (args.has("auto_sync_enabled")) {
                    val v = args.optBoolean("auto_sync_enabled", autoSyncEnabled)
                    autoSyncEnabled = v
                    store.saveAutoSyncEnabled(v)
                    changed.add("auto_sync_enabled=$v")
                }
                if (args.has("sync_url")) {
                    val v = args.optString("sync_url", "").trim()
                    syncUrl = v
                    store.saveSyncSettings(v, syncToken)
                    changed.add("sync_url=$v")
                }
                if (args.has("sync_interval")) {
                    val v = args.optInt("sync_interval", syncIntervalSeconds).coerceIn(5, 600)
                    syncIntervalSeconds = v
                    store.saveSyncIntervalSeconds(v)
                    changed.add("sync_interval=$v")
                }
                if (args.has("quota_interval")) {
                    val v = args.optInt("quota_interval", quotaIntervalSeconds).coerceIn(15, 300)
                    quotaIntervalSeconds = v
                    store.saveQuotaIntervalSeconds(v)
                    changed.add("quota_interval=$v")
                }
                if (args.has("jarvis_model")) {
                    val v = args.optString("jarvis_model", "").trim()
                    if (v.isNotBlank()) {
                        store.saveJarvisModel(v)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetModel = v
                        changed.add("jarvis_model=$v")
                    }
                }
                if (args.has("jarvis_effort")) {
                    val v = args.optString("jarvis_effort", "").trim().lowercase()
                    if (v.isNotBlank()) {
                        store.saveJarvisEffort(v)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetEffort = v
                        changed.add("jarvis_effort=$v")
                    }
                }
                if (args.has("jarvis_wake_word")) {
                    val v = args.optString("jarvis_wake_word", "").trim()
                    if (v.isNotBlank()) {
                        store.saveJarvisWakeWord(v)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.wakeWord = v
                        changed.add("jarvis_wake_word=$v")
                    }
                }
                if (args.has("jarvis_notify_finished")) {
                    val v = args.optBoolean("jarvis_notify_finished", true)
                    store.saveJarvisNotifyFinished(v)
                    dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.notifyOnTaskFinished = v
                    changed.add("jarvis_notify_finished=$v")
                }
                if (args.has("jarvis_bluetooth_sco")) {
                    val v = args.optBoolean("jarvis_bluetooth_sco", true)
                    store.saveJarvisBluetoothSco(v)
                    dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.bluetoothScoEnabled = v
                    changed.add("jarvis_bluetooth_sco=$v")
                }
                if (args.optBoolean("generate_ssh_key", false)) {
                    generatedPubKey = withContext(Dispatchers.IO) { store.generateSshKey() }
                    changed.add("generate_ssh_key=true")
                }
                val resp = org.json.JSONObject().apply {
                    put("updated", org.json.JSONArray(changed))
                    generatedPubKey?.let { put("ssh_public_key", it) }
                }
                dev.remote.terminal.jarvis.mcp.ToolOpResult(
                    ok = changed.isNotEmpty(),
                    detail = resp.toString(),
                    voiceSummary = if (changed.isNotEmpty()) "已更新设置（${changed.size} 项）。" else "未检测到可更新的设置项。",
                    expectsFollowUp = generatedPubKey != null
                )
            }

            dev.remote.terminal.jarvis.mcp.JarvisToolRegistry.sessionWindowHandler = { selector, action, args ->
                when (action) {
                    "minimize", "restore" -> {
                        val target = resolveSessionMatch(selector)
                        if (target == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Target session not found", "未找到目标终端会话。")
                        } else {
                            val minimize = action == "minimize"
                            target.isMinimized = minimize
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(
                                true,
                                "Session ${target.task.directory.name} minimized=$minimize",
                                if (minimize) "已最小化 ${target.task.directory.name} 窗口。" else "已恢复 ${target.task.directory.name} 窗口。"
                            )
                        }
                    }
                    "approve_host_key" -> {
                        val target = resolveSessionMatch(selector) ?: sessions.firstOrNull { it.hostFingerprint != null }
                        val fingerprint = target?.hostFingerprint
                        if (target == null || fingerprint == null) {
                            dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "No pending SSH host key approval", "当前没有待确认的 SSH 主机指纹弹窗。")
                        } else {
                            val approve = args.optBoolean("approve", true)
                            val addr = target.hostKeyAddress
                            val port = target.hostKeyPort
                            if (approve) {
                                store.trustHostKey(addr, port, fingerprint)
                                val trustedHost = target.task.host.copy(syncFingerprint = fingerprint)
                                saveHosts(hosts.map { if (it.id == trustedHost.id) trustedHost else it })
                                sessions.filter { it.hostKeyAddress == addr && it.hostKeyPort == port }.forEach {
                                    it.task = it.task.copy(host = trustedHost)
                                    it.resolveHostKey?.invoke(true)
                                    it.hostFingerprint = null
                                }
                                dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Trusted host key $addr:$port ($fingerprint)", "已信任主机指纹并继续连接。")
                            } else {
                                sessions.filter { it.hostKeyAddress == addr && it.hostKeyPort == port }.forEach {
                                    it.resolveHostKey?.invoke(false)
                                    it.hostFingerprint = null
                                }
                                dev.remote.terminal.jarvis.mcp.ToolOpResult(true, "Rejected host key $addr:$port", "已拒绝该 SSH 主机指纹。")
                            }
                        }
                    }
                    else -> dev.remote.terminal.jarvis.mcp.ToolOpResult(false, "Unknown session window action: $action", "不支持的窗口控制操作。")
                }
            }
        }

        LaunchedEffect(agents.toList(), sessions.size) {
            for (session in sessions) {
                val agent = session.task.agent
                val preset = agents.firstOrNull { it.id == agent.id }
                    ?: agents.firstOrNull { it.command == agent.command && it.name == agent.name }
                if (preset != null && preset.icon != agent.icon) {
                    session.task = session.task.copy(agent = agent.copy(icon = preset.icon))
                }
            }
        }

        LaunchedEffect(syncUrl, syncToken, offlineMode, autoSyncEnabled, syncIntervalSeconds) {
            operationLogs.clear()
            operationLogs.addAll(store.loadOperationLogs(store.loadSyncUsername()))
            val syncEnabled = !offlineMode && autoSyncEnabled && syncUrl.isNotBlank() && syncToken.isNotBlank()
            syncLoading = syncEnabled && hosts.isNotEmpty()
            if (!syncEnabled) {
                startupShared = emptyList()
                sessionSyncErrors.clear()
            }
            while (true) {
                if (!syncEnabled) {
                    syncLoading = false
                    kotlinx.coroutines.delay(syncIntervalSeconds * 1_000L)
                    continue
                }
                val resolvedHosts = if (syncEnabled) runCatching {
                    withContext(SyncClient.dispatcher) { syncHostProfiles() }
                }.onSuccess {
                    sessionSyncErrors.remove("host_profiles")
                }.getOrElse { error ->
                    val message = error.message.orEmpty()
                    sessionSyncErrors["host_profiles"] = "远端主机列表同步失败：${message.ifBlank { "网络请求失败" }}"
                    if (message.contains("401") || message.contains("unauthorized", ignoreCase = true)) {
                        syncToken = ""; store.saveSyncSettings(syncUrl, "")
                    }
                    emptyList()
                } else emptyList()
                startupShared = startupShared.filter { entry -> resolvedHosts.any { it.id == entry.first.id } }
                resolvedHosts.forEach { host ->
                    val workspaceVersion = workspaceVersions[host.id] ?: 0
                    if (host.id in dirtyWorkspaces) {
                        val current = hosts.firstOrNull { it.id == host.id }
                        if (current != null) {
                            val identity = SyncClient.hostIdentity(current)
                            val revisionIdentity = "${store.loadSyncUsername().lowercase()}:$identity"
                            runCatching {
                                withContext(SyncClient.dispatcher) {
                                    val baseRevision = store.loadWorkspaceRevision(revisionIdentity).coerceAtLeast(0)
                                    val response = SyncClient.saveWorkspaces(current, current.directories, baseRevision)
                                    response to baseRevision
                                }
                            }.onSuccess { (response, baseRevision) ->
                                    if ((workspaceVersions[host.id] ?: 0) == workspaceVersion) {
                                        store.saveWorkspaceRevision(revisionIdentity, response.optInt("revision", baseRevision))
                                        dirtyWorkspaces.remove(host.id)
                                        store.saveDirtyWorkspaces(dirtyWorkspaces)
                                    }
                                }
                        }
                    }
                    runCatching {
                        withContext(SyncClient.dispatcher) {
                        val workspaceSnapshot = SyncClient.getWorkspacesSnapshot(host)
                        val configuredWorkspaces = workspaceSnapshot.optBoolean("configured", false)
                        val agentsSnapshot = SyncClient.getAgentsSnapshot(host)
                        val configuredAgents = agentsSnapshot.optBoolean("configured", false)
                            val workspaceArray = workspaceSnapshot.getJSONArray("workspaces")
                            val remoteWorkspaces = (0 until workspaceArray.length()).map { index ->
                                workspaceArray.getJSONObject(index).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.optString("icon", "📁")) }
                            }
                            val remoteAgents = SyncClient.agentsFromSnapshot(agentsSnapshot)
                        val hostState = SyncClient.getHostState(host)
                        Triple(Triple(configuredWorkspaces, remoteWorkspaces, workspaceSnapshot), Triple(configuredAgents, remoteAgents, agentsSnapshot), hostState)
                        }
                    }.onSuccess { (workspaceState, agentState, hostState) ->
                        val (configuredWorkspaces, remoteWorkspaces, workspaceSnapshot) = workspaceState
                        val (configuredAgents, remoteAgents, agentsSnapshot) = agentState
                        val localHost = hosts.firstOrNull { it.id == host.id } ?: host
                        if (host.id !in dirtyWorkspaces && (workspaceVersions[host.id] ?: 0) == workspaceVersion) {
                            val identity = SyncClient.hostIdentity(localHost)
                            val revisionIdentity = "${store.loadSyncUsername().lowercase()}:$identity"
                            val localRevision = store.loadWorkspaceRevision(revisionIdentity)
                            val remoteRevision = workspaceSnapshot.optInt("revision", 0)
                            if (!configuredWorkspaces && localHost.directories.isNotEmpty()) {
                                syncWorkspaceList(localHost, localHost.directories)
                            } else if (configuredWorkspaces && localRevision < 0 && localHost.directories.isNotEmpty() && remoteRevision > 0) {
                                syncWorkspaceList(localHost, localHost.directories)
                            } else if (configuredWorkspaces) {
                                if (remoteRevision > localRevision || localHost.directories != remoteWorkspaces) {
                                    if (localHost.directories != remoteWorkspaces) {
                                        saveHosts(hosts.map { if (it.id == host.id) it.copy(directories = remoteWorkspaces) else it })
                                    }
                                    store.saveWorkspaceRevision(revisionIdentity, remoteRevision)
                                }
                            }
                        }
                        val agentRevisionIdentity = SyncClient.agentRevisionIdentity(store.loadSyncUsername())
                        val localAgentRevision = store.loadAgentRevision(agentRevisionIdentity)
                        val remoteAgentRevision = agentsSnapshot.optInt("revision", 0)
                        if (agentRevisionIdentity !in agentSaves) {
                            if (!configuredAgents && agents.isNotEmpty()) {
                                syncAgentList(localHost, agents.toList())
                            } else if (configuredAgents && remoteAgentRevision > localAgentRevision) {
                                replaceAgents(remoteAgents)
                                store.saveAgentRevision(agentRevisionIdentity, remoteAgentRevision)
                            }
                        }
                        if (hostState.optBoolean("configured")) {
                            val profile = hostState.optJSONObject("state") ?: JSONObject()
                            val command = profile.optString("selected_agent_command")
                            if (command.isNotBlank()) {
                                agents.firstOrNull { it.command == command }?.let { matched ->
                                    store.saveHostSelectedAgent(host.id, matched.id)
                                    if (selectedHostId == host.id) {
                                        selectedAgentId = matched.id
                                    }
                                }
                            }
                            if (localHost.name != profile.optString("name", localHost.name)
                                || localHost.icon != profile.optString("icon", localHost.icon)) {
                                val updated = localHost.copy(name = profile.optString("name", localHost.name), icon = profile.optString("icon", localHost.icon))
                                saveHosts(hosts.map { if (it.id == host.id) updated else it })
                            }
                        } else if (selectedHostId == host.id) {
                            agents.firstOrNull { it.id == selectedAgentId }?.let { agent ->
                                runCatching { withContext(SyncClient.dispatcher) { SyncClient.updateHostProfile(localHost, agent.command) } }
                            }
                        }
                    }.onFailure { error ->
                        val message = error.message.orEmpty()
                        if (message.contains("401") || message.contains("unauthorized", ignoreCase = true)) {
                            syncToken = ""
                            store.saveSyncSettings(syncUrl, "")
                        } else {
                            snackbars.showSnackbar("${host.name} 同步失败：$message")
                        }
                    }
                    runCatching { syncSharedSessions(host) }
                        .onSuccess { sessionSyncErrors.remove(host.id) }
                        .onFailure { error ->
                            val message = error.message ?: "网络请求失败"
                            sessionSyncErrors[host.id] = "${host.name} 会话同步失败：$message"
                            snackbars.showSnackbar("${host.name} 会话同步失败：$message")
                        }
                }
                runCatching { syncOperationLogs() }
                syncLoading = false
                kotlinx.coroutines.delay(syncIntervalSeconds * 1_000L)
            }
        }

        BackHandler(enabled = if (isPad) (route == AppRoute.SETTINGS || route == AppRoute.LOGIN || route == AppRoute.LOGS) else (route != AppRoute.HOME)) {
            route = AppRoute.HOME
        }

        Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().imePadding().navigationBarsPadding()) {
            if (!isPad) {
                when (route) {
                    AppRoute.HOME -> HomeScreen(
                        hosts = hosts, agents = currentAgents, selectedHost = selectedHost, selectedAgent = selectedAgent,
                        quotaFor = { host, agent -> quotaKey(host, agent.command)?.let(quotas::get) },
                        sessions = sessions, onSelectHost = { selectHost(it) }, onSelectAgent = ::selectAgent,
                        remoteInstances = startupShared,
                        onOpenRemoteInstance = { host, item -> openSharedSession(host, item, activate = true) },
                        syncErrors = sessionSyncErrors.values.toList(),
                        syncLoading = syncLoading,
                        onManage = { route = AppRoute.MANAGE }, onAddHost = { addingHost = true },
                        onAddDirectory = { selectedHost?.let { editingDirectory = it.id to null } },
                        onReorderDirectories = { host, ordered ->
                            saveHosts(hosts.map { if (it.id == host.id) it.copy(directories = ordered) else it })
                            syncWorkspaceList(host, ordered)
                        },
                        onOpenAgents = { route = AppRoute.AGENTS },
                        onOpenLogs = {
                            route = AppRoute.LOGS
                            scope.launch { runCatching { syncOperationLogs() } }
                        },
                        onOpenSettings = { route = AppRoute.SETTINGS },
                        onStartTask = { host, dir, agent -> openTask(host, dir, agent) },
                        onResumeSession = { index -> activePage = index; sessions[index].unread = false; route = AppRoute.TERMINAL },
                        onRenameSession = { session -> renamingSession = session; renamingName = session.task.displayName },
                    )
                    AppRoute.MANAGE -> ManageScreen(
                        hosts = hosts, selectedHost = selectedHost,
                        onBack = { route = AppRoute.HOME }, onSelectHost = { selectHost(it) }, onAddHost = { addingHost = true },
                        onEditHost = { editingHost = it }, onDeleteHost = { deletingHost = it },
                        onAddDirectory = { selectedHost?.let { editingDirectory = it.id to null } },
                        onEditDirectory = { host, dir -> editingDirectory = host.id to dir },
                        onDeleteDirectory = { host, dir -> deletingDirectory = host.id to dir },
                        onMoveDirectory = ::moveDirectory,
                    )
                    AppRoute.AGENTS -> AgentsScreen(
                        agents = agents, onBack = { route = AppRoute.HOME }, onAdd = { addingAgent = true },
                        onEdit = { editingAgent = it }, onDelete = { deletingAgent = it },
                        onMove = { index, offset ->
                            val agent = agents.removeAt(index)
                            agents.add(index + offset, agent)
                            val ordered = agents.toList()
                            store.saveAgents(ordered)
                            selectedHost?.let { syncAgentList(it, ordered) }
                        },
                    )
                    AppRoute.LOGS -> OperationLogsScreen(
                        logs = operationLogs,
                        agents = agents,
                        openSessionIds = sessions.map { it.task.id.replace("-", "") }.toSet(),
                        liveSessionTitles = buildMap {
                            startupShared.forEach { (_, item) ->
                                val sid = item.optString("id").replace("-", "").lowercase()
                                val title = item.optString("display_name").trim()
                                if (sid.isNotBlank() && title.isNotBlank()) put(sid, title)
                            }
                            sessions.forEach { s ->
                                val sid = s.task.id.replace("-", "").lowercase()
                                val title = s.task.displayName.trim()
                                if (sid.isNotBlank() && title.isNotBlank()) put(sid, title)
                            }
                        },
                        onBack = { route = AppRoute.HOME },
                        onRefresh = {
                            scope.launch {
                                runCatching { syncOperationLogs() }
                                    .onFailure { snackbars.showSnackbar("日志同步失败：${it.message}") }
                            }
                        },
                        onRestore = { entry -> restoreFromLog(entry) },
                    )
                    AppRoute.SETTINGS -> SettingsScreen(
                        store = store,
                        hosts = hosts,
                        agents = agents,
                        syncUrl = syncUrl,
                        syncToken = syncToken,
                        offlineMode = offlineMode,
                        autoSyncEnabled = autoSyncEnabled,
                        syncIntervalSeconds = syncIntervalSeconds,
                        onOfflineModeChange = { enabled -> offlineMode = enabled; store.saveOfflineMode(enabled) },
                        onAutoSyncEnabledChange = { enabled -> autoSyncEnabled = enabled; store.saveAutoSyncEnabled(enabled) },
                        onSyncIntervalSecondsChange = { seconds -> syncIntervalSeconds = seconds; store.saveSyncIntervalSeconds(seconds) },
                        syncUsername = store.loadSyncUsername(),
                        onSyncSettingsChange = { url, token ->
                            if (syncUrl != url) syncToken = ""
                            syncUrl = url
                            syncToken = token
                            store.saveSyncSettings(url, token)
                        },
                        onLogin = { route = AppRoute.LOGIN },
                        enabled = autoEnterEnabled,
                        delaySeconds = autoEnterDelaySeconds,
                        quotaIntervalSeconds = quotaIntervalSeconds,
                        onQuotaIntervalChange = { seconds ->
                            quotaIntervalSeconds = seconds
                            store.saveQuotaIntervalSeconds(seconds)
                        },
                        onEnabledChange = { enabled ->
                            autoEnterEnabled = enabled
                            store.saveAutoEnterEnabled(enabled)
                            AutoConfirmEngine.globalEnabled = enabled
                            sessions.forEach { session ->
                                if (enabled) session.resumeAutoEnter(autoEnterDelaySeconds, enabled)
                                else session.cancelAutoEnter()
                            }
                        },
                        onDelaySecondsChange = { seconds ->
                            autoEnterDelaySeconds = seconds
                            store.saveAutoEnterDelaySeconds(seconds)
                            AutoConfirmEngine.globalDelaySeconds = seconds
                            if (autoEnterEnabled) sessions.forEach { it.resumeAutoEnter(seconds, autoEnterEnabled) }
                        },
                        deviceLayoutMode = deviceLayoutMode,
                        onDeviceLayoutModeChange = { mode ->
                            deviceLayoutMode = mode
                            store.saveDeviceLayoutMode(mode)
                        },
                        isTablet = isTablet,
                        onBack = { route = if (!offlineMode && syncUrl.isNotBlank() && syncToken.isBlank()) AppRoute.LOGIN else AppRoute.HOME },
                    )
                    AppRoute.LOGIN -> SyncLoginScreen(
                        syncUrl = syncUrl,
                        initialUsername = store.loadSyncUsername(),
                        initialPassword = store.loadSyncPassword(),
                        onBack = { route = AppRoute.HOME },
                        onOfflineMode = {
                            offlineMode = true
                            store.saveOfflineMode(true)
                            route = AppRoute.HOME
                        },
                        onSuccess = { username, password, token, remember ->
                            offlineMode = false
                            store.saveOfflineMode(false)
                            syncToken = token
                            store.saveSyncSettings(syncUrl, token)
                            store.saveSyncCredentials(username, password, remember)
                            route = AppRoute.HOME
                        },
                    )
                    AppRoute.TERMINAL -> TerminalWorkspace(
                        sessions = sessions, initialIndex = activePage,
                        onPageSelected = { index -> activePage = index; sessions.getOrNull(index)?.let { it.unread = false } },
                        onBack = { route = AppRoute.HOME }, onNewTask = { route = AppRoute.HOME },
                        onCloseTask = { index -> sessions.getOrNull(index)?.let { closingSession = it } },
                        onRenameTask = { session -> renamingSession = session; renamingName = session.task.displayName },
                    )
                }
            } else {
                when (route) {
                    AppRoute.SETTINGS -> SettingsScreen(
                        store = store,
                        hosts = hosts,
                        agents = agents,
                        syncUrl = syncUrl,
                        syncToken = syncToken,
                        offlineMode = offlineMode,
                        autoSyncEnabled = autoSyncEnabled,
                        syncIntervalSeconds = syncIntervalSeconds,
                        onOfflineModeChange = { enabled -> offlineMode = enabled; store.saveOfflineMode(enabled) },
                        onAutoSyncEnabledChange = { enabled -> autoSyncEnabled = enabled; store.saveAutoSyncEnabled(enabled) },
                        onSyncIntervalSecondsChange = { seconds -> syncIntervalSeconds = seconds; store.saveSyncIntervalSeconds(seconds) },
                        syncUsername = store.loadSyncUsername(),
                        onSyncSettingsChange = { url, token ->
                            if (syncUrl != url) syncToken = ""
                            syncUrl = url
                            syncToken = token
                            store.saveSyncSettings(url, token)
                        },
                        onLogin = { route = AppRoute.LOGIN },
                        enabled = autoEnterEnabled,
                        delaySeconds = autoEnterDelaySeconds,
                        quotaIntervalSeconds = quotaIntervalSeconds,
                        onQuotaIntervalChange = { seconds ->
                            quotaIntervalSeconds = seconds
                            store.saveQuotaIntervalSeconds(seconds)
                        },
                        onEnabledChange = { enabled ->
                            autoEnterEnabled = enabled
                            store.saveAutoEnterEnabled(enabled)
                            AutoConfirmEngine.globalEnabled = enabled
                            sessions.forEach { session ->
                                if (enabled) session.resumeAutoEnter(autoEnterDelaySeconds, enabled)
                                else session.cancelAutoEnter()
                            }
                        },
                        onDelaySecondsChange = { seconds ->
                            autoEnterDelaySeconds = seconds
                            store.saveAutoEnterDelaySeconds(seconds)
                            AutoConfirmEngine.globalDelaySeconds = seconds
                            if (autoEnterEnabled) sessions.forEach { it.resumeAutoEnter(seconds, autoEnterEnabled) }
                        },
                        deviceLayoutMode = deviceLayoutMode,
                        onDeviceLayoutModeChange = { mode ->
                            deviceLayoutMode = mode
                            store.saveDeviceLayoutMode(mode)
                        },
                        isTablet = isTablet,
                        onBack = { route = if (!offlineMode && syncUrl.isNotBlank() && syncToken.isBlank()) AppRoute.LOGIN else AppRoute.HOME },
                    )
                    AppRoute.LOGIN -> SyncLoginScreen(
                        syncUrl = syncUrl,
                        initialUsername = store.loadSyncUsername(),
                        initialPassword = store.loadSyncPassword(),
                        onBack = { route = AppRoute.HOME },
                        onOfflineMode = {
                            offlineMode = true
                            store.saveOfflineMode(true)
                            route = AppRoute.HOME
                        },
                        onSuccess = { username, password, token, remember ->
                            offlineMode = false
                            store.saveOfflineMode(false)
                            syncToken = token
                            store.saveSyncSettings(syncUrl, token)
                            store.saveSyncCredentials(username, password, remember)
                            route = AppRoute.HOME
                        },
                    )
                    AppRoute.LOGS -> OperationLogsScreen(
                        logs = operationLogs,
                        agents = agents,
                        openSessionIds = sessions.map { it.task.id.replace("-", "") }.toSet(),
                        liveSessionTitles = buildMap {
                            startupShared.forEach { (_, item) ->
                                val sid = item.optString("id").replace("-", "").lowercase()
                                val title = item.optString("display_name").trim()
                                if (sid.isNotBlank() && title.isNotBlank()) put(sid, title)
                            }
                            sessions.forEach { s ->
                                val sid = s.task.id.replace("-", "").lowercase()
                                val title = s.task.displayName.trim()
                                if (sid.isNotBlank() && title.isNotBlank()) put(sid, title)
                            }
                        },
                        onBack = { route = AppRoute.HOME },
                        onRefresh = {
                            scope.launch {
                                runCatching { syncOperationLogs() }
                                    .onFailure { snackbars.showSnackbar("日志同步失败：${it.message}") }
                            }
                        },
                        onRestore = { entry -> restoreFromLog(entry) },
                    )
                    else -> PadWorkspace(
                        hosts = hosts,
                        agents = currentAgents,
                        sessions = sessions,
                        selectedHost = selectedHost,
                        selectedAgent = selectedAgent,
                        activePage = activePage,
                        tiled = tiledMode,
                        onToggleTiled = { tiled ->
                            tiledMode = tiled
                            store.saveTiledMode(tiled)
                        },
                        onSelectHost = { selectHost(it) },
                        onSelectAgent = ::selectAgent,
                        onSelectSession = { index ->
                            activePage = index
                            sessions.getOrNull(index)?.let {
                                it.isMinimized = false
                                it.unread = false
                            }
                        },
                        onStartTask = { host, dir, agent -> openTask(host, dir, agent) },
                        onCloseSession = { session -> closingSession = session },
                        onRenameSession = { session -> renamingSession = session; renamingName = session.task.displayName },
                        onOpenSettings = { route = AppRoute.SETTINGS },
                        onRefresh = {
                            scope.launch {
                                syncLoading = true
                                selectedHost?.let { refreshShared(it) }
                                syncLoading = false
                            }
                        },
                        syncLoading = syncLoading,
                        offlineMode = offlineMode,
                        autoSyncEnabled = autoSyncEnabled,
                        quotaFor = { host, agent -> quotaKey(host, agent.command)?.let(quotas::get) },
                        onAddHost = { addingHost = true },
                        onEditHost = { editingHost = it },
                        onDeleteHost = { deletingHost = it },
                        onMoveHost = { from, offset ->
                            val to = (from + offset).coerceIn(0, hosts.lastIndex)
                            if (from != to) {
                                val item = hosts.removeAt(from)
                                hosts.add(to, item)
                                saveHosts(hosts.toList())
                            }
                        },
                        onAddDirectory = { host -> editingDirectory = host.id to null },
                        onEditDirectory = { host, dir -> editingDirectory = host.id to dir },
                        onDeleteDirectory = { host, dir -> deletingDirectory = host.id to dir },
                        onMoveDirectory = ::moveDirectory,
                        onAddAgent = { addingAgent = true },
                        onEditAgent = { editingAgent = it },
                        onDeleteAgent = { deletingAgent = it },
                        onMoveAgent = { from, offset ->
                            val to = (from + offset).coerceIn(0, agents.lastIndex)
                            if (from != to) {
                                val item = agents.removeAt(from)
                                agents.add(to, item)
                                val ordered = agents.toList()
                                store.saveAgents(ordered)
                                selectedHost?.let { syncAgentList(it, ordered) }
                            }
                        },
                    )
                }
            }
            dev.remote.terminal.jarvis.ui.JarvisVoiceprintOverlay(modifier = Modifier.align(Alignment.BottomCenter))
            SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        sharedSessions?.let { items ->
            AlertDialog(
                onDismissRequest = { sharedSessions = null },
                title = { Text("共享会话") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (items.isEmpty()) Text("暂无共享会话")
                        items.forEach { item ->
                            TextButton(onClick = {
                                val host = sharedHost ?: return@TextButton
                                openSharedSession(host, item)
                                sharedSessions = null
                            }) { Text("${item.optString("display_name")} · ${item.optString("directory_name")}") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sharedSessions = null }) { Text("关闭") } },
            )
        }

        if (addingHost || editingHost != null) HostEditorDialog(
            initial = editingHost,
            onDismiss = { addingHost = false; editingHost = null },
            onSave = { host ->
                val wasNew = hosts.none { it.id == host.id }
                HostRouteResolver.invalidate(host.id)
                saveHosts(if (wasNew) hosts.toList() + host else hosts.map { if (it.id == host.id) host else it })
                selectedHostId = host.id
                syncHostProfile(host)
                scope.launch(Dispatchers.IO) { HostRouteResolver.resolveHostRoute(host, force = true) }
                if (!offlineMode && autoSyncEnabled && syncUrl.isNotBlank() && syncToken.isNotBlank()) refreshShared(host)
                addingHost = false; editingHost = null
            },
        )
        editingDirectory?.let { (hostId, initial) ->
            val owner = hosts.firstOrNull { it.id == hostId }
            if (owner != null) DirectoryEditorDialog(owner, store, initial, onDismiss = { editingDirectory = null }) { dir ->
                val updatedDirectories = if (initial == null) owner.directories + dir else owner.directories.map { if (it.id == dir.id) dir else it }
                saveHosts(hosts.map { host -> if (host.id == hostId) host.copy(directories = updatedDirectories) else host })
                syncWorkspaceList(owner, updatedDirectories)
                editingDirectory = null
            }
        }
        if (addingAgent || editingAgent != null) AgentEditorDialog(
            initial = editingAgent, onDismiss = { addingAgent = false; editingAgent = null },
            onSave = { agent ->
                if (agents.any { it.id == agent.id }) agents.indices.forEach { i -> if (agents[i].id == agent.id) agents[i] = agent }
                else agents.add(agent)
                store.saveAgents(agents.toList())
                selectedHost?.let { syncAgentList(it, agents.toList()) }
                if (selectedAgentId.isBlank()) selectedAgentId = agent.id
                addingAgent = false; editingAgent = null
            },
        )
        deletingHost?.let { host -> ConfirmDeleteDialog(
            title = "删除主机？", detail = "${host.name} 及其目录配置将从本机移除。",
            onConfirm = {
                dirtyHostProfiles.remove(host.id)
                store.saveDirtyHostProfiles(dirtyHostProfiles)
                if (!offlineMode && syncUrl.isNotBlank() && syncToken.isNotBlank()) scope.launch { runCatching { withContext(SyncClient.dispatcher) { SyncClient.deleteHostProfile(host.id) } } }
                store.removeHostPassword(host.id)
                saveHosts(hosts.filterNot { it.id == host.id }); deletingHost = null
            },
            onDismiss = { deletingHost = null },
        ) }
        deletingDirectory?.let { (hostId, dir) -> ConfirmDeleteDialog(
            title = "移除开发目录？", detail = dir.path,
            onConfirm = {
                val owner = hosts.firstOrNull { it.id == hostId }
                val updatedDirectories = owner?.directories?.filterNot { it.id == dir.id }.orEmpty()
                saveHosts(hosts.map { if (it.id == hostId) it.copy(directories = updatedDirectories) else it })
                owner?.let { syncWorkspaceList(it, updatedDirectories) }
                deletingDirectory = null
            },
            onDismiss = { deletingDirectory = null },
        ) }
        deletingAgent?.let { agent -> ConfirmDeleteDialog(
            title = "删除开发者？", detail = "${agent.name} 的自定义配置将被移除。",
            onConfirm = {
                agents.removeAll { it.id == agent.id }; store.saveAgents(agents.toList())
                selectedHost?.let { syncAgentList(it, agents.toList()) }
                selectedAgentId = agents.firstOrNull()?.id.orEmpty(); deletingAgent = null
            },
            onDismiss = { deletingAgent = null },
        ) }
        closingSession?.let { session -> ConfirmDeleteDialog(
            title = "关闭会话？", detail = "${sessionDisplayLabel(session.task)} 的 SSH 连接将断开。",
            confirmLabel = "关闭",
            onConfirm = { closeSession(session); closingSession = null },
            onDismiss = { closingSession = null },
        ) }
        renamingSession?.let { session ->
            AlertDialog(
                onDismissRequest = { renamingSession = null },
                containerColor = Panel,
                title = { Text("修改实例名称", color = White, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请输入新的实例名称：", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = renamingName,
                            onValueChange = { renamingName = it },
                            label = { Text("实例名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = session
                            val newName = renamingName
                            renamingSession = null
                            renameTask(target, newName)
                        },
                        enabled = renamingName.isNotBlank(),
                    ) {
                        Text("确定", color = Mint)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renamingSession = null }) {
                        Text("取消", color = Muted)
                    }
                },
            )
        }

        val pendingKeySession = sessions.firstOrNull { it.hostFingerprint != null }
        pendingKeySession?.let { session ->
            session.hostFingerprint?.let { fingerprint ->
                AlertDialog(
                    onDismissRequest = {
                        val addr = session.hostKeyAddress
                        val port = session.hostKeyPort
                        sessions.filter { it.hostKeyAddress == addr && it.hostKeyPort == port }.forEach {
                            it.resolveHostKey?.invoke(false)
                            it.hostFingerprint = null
                        }
                    },
                    containerColor = Panel,
                    title = { Text("首次连接这台主机？", color = White, fontWeight = FontWeight.SemiBold) },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请核对 SSH 主机指纹。接受后会保存在本机，后续连接会自动校验。", color = Muted)
                        Text("${session.hostKeyAddress}:${session.hostKeyPort}", color = White, fontWeight = FontWeight.Medium)
                        Text(fingerprint, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    } },
                    confirmButton = { TextButton(onClick = {
                        val addr = session.hostKeyAddress
                        val port = session.hostKeyPort
                        store.trustHostKey(addr, port, fingerprint)
                        val trustedHost = session.task.host.copy(syncFingerprint = fingerprint)
                        saveHosts(hosts.map { if (it.id == trustedHost.id) trustedHost else it })
                        sessions.filter { it.hostKeyAddress == addr && it.hostKeyPort == port }.forEach {
                            it.task = it.task.copy(host = trustedHost)
                            it.resolveHostKey?.invoke(true)
                            it.hostFingerprint = null
                        }
                    }) { Text("信任并连接", color = Mint) } },
                    dismissButton = { TextButton(onClick = {
                        val addr = session.hostKeyAddress
                        val port = session.hostKeyPort
                        sessions.filter { it.hostKeyAddress == addr && it.hostKeyPort == port }.forEach {
                            it.resolveHostKey?.invoke(false)
                            it.hostFingerprint = null
                        }
                    }) { Text("取消", color = Muted) } },
                )
            }
        }
    }
}

private val homeCardGridGap = 8.dp

@Composable
private fun HomeScreen(
    hosts: List<HostProfile>, agents: List<AgentPreset>, selectedHost: HostProfile?, selectedAgent: AgentPreset?,
    quotaFor: (HostProfile, AgentPreset) -> List<QuotaWindow>?,
    sessions: List<TerminalSessionState>, onSelectHost: (HostProfile) -> Unit, onSelectAgent: (AgentPreset) -> Unit,
    remoteInstances: List<Pair<HostProfile, org.json.JSONObject>>,
    onOpenRemoteInstance: (HostProfile, org.json.JSONObject) -> Unit,
    syncErrors: List<String>,
    onManage: () -> Unit, onAddHost: () -> Unit, onAddDirectory: () -> Unit, onOpenAgents: () -> Unit, onOpenLogs: () -> Unit, onOpenSettings: () -> Unit,
    onStartTask: (HostProfile, ProjectDirectory, AgentPreset) -> Unit, onResumeSession: (Int) -> Unit,
    onRenameSession: (TerminalSessionState) -> Unit,
    syncLoading: Boolean,
    onReorderDirectories: (HostProfile, List<ProjectDirectory>) -> Unit,
) {
    var draggingDirectoryId by remember(selectedHost?.id) { mutableStateOf<String?>(null) }
    var dragOffsetY by remember(selectedHost?.id) { mutableStateOf(0f) }

    val hostEndpointsKey = remember(hosts.map { it.id to it.endpoints() }) {
        hosts.joinToString(";") { "${it.id}:${it.endpoints().joinToString(",") { ep -> "${ep.address}:${ep.port}" }}" }
    }
    LaunchedEffect(hostEndpointsKey) {
        hosts.forEach { host ->
            launch(Dispatchers.IO) {
                HostRouteResolver.resolveHostRoute(host)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                NeonGlowBrandLogo(fontSize = 19.sp, letterSpacing = 1.4.sp)
                Text("随时接回你的开发现场 · v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = onManage, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Dns, contentDescription = "管理主机和目录", tint = Muted, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onOpenAgents, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SmartToy, contentDescription = "编辑开发者", tint = Muted, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onOpenLogs, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.History, contentDescription = "历史记录", tint = Muted, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Muted, modifier = Modifier.size(22.dp))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {

        if (syncLoading) Text("正在同步共享会话…", color = Muted, fontSize = 12.sp)
        syncErrors.forEach { message ->
            Text(message, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, bottom = 3.dp))
        }

        val hostSessions = remember(sessions.toList(), selectedHost?.id) {
            if (selectedHost == null) sessions
            else sessions.filter { it.task.host.id == selectedHost.id }
        }
        // Match desktop: list sessions from every synchronized host by default.
        var filterByCurrentHost by remember { mutableStateOf(false) }
        val displayedSessions = if (filterByCurrentHost && selectedHost != null) hostSessions else sessions

        if (sessions.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text("进行中的任务", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${displayedSessions.size}", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (selectedHost != null && sessions.size > hostSessions.size) {
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = { filterByCurrentHost = !filterByCurrentHost },
                        color = if (filterByCurrentHost) PanelRaised else Color(0xFF20342F),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (filterByCurrentHost) Edge else Mint.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            if (filterByCurrentHost) "当前主机 (${hostSessions.size}/${sessions.size})" else "全部主机 (${sessions.size})",
                            color = if (filterByCurrentHost) Muted else Mint,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (displayedSessions.isEmpty()) {
                Surface(
                    onClick = { filterByCurrentHost = false },
                    color = Panel, shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前主机暂无任务", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("查看其他 ${sessions.size} 个任务 →", color = Mint, fontSize = 11.sp)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(homeCardGridGap),
                ) {
                    displayedSessions.chunked(2).forEach { rowSessions ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(homeCardGridGap),
                        ) {
                            rowSessions.forEach { session ->
                                val originalIndex = sessions.indexOf(session).coerceAtLeast(0)
                                Surface(
                                    onClick = { onResumeSession(originalIndex) },
                                    color = Panel,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, if (session.needsAction) Coral.copy(alpha = 0.7f) else Edge),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AgentMark(session.task.agent.icon, 16.dp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                sessionDisplayLabel(session.task),
                                                color = White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = { onRenameSession(session) },
                                                modifier = Modifier.size(18.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "编辑实例名称",
                                                    tint = Muted,
                                                    modifier = Modifier.size(11.dp),
                                                )
                                            }
                                            if (session.needsAction) {
                                                Spacer(Modifier.width(3.dp))
                                                Text("!", color = Coral, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            } else if (session.unread) {
                                                Spacer(Modifier.width(3.dp))
                                                Box(Modifier.size(6.dp).background(Blue, CircleShape))
                                            }
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                if (session.needsAction) "需要确认" else "${session.task.host.name} · ${session.task.directory.name}",
                                                color = if (session.needsAction) Coral else Muted,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (codexExecutable(session.task.agent.command) != null || antigravityExecutable(session.task.agent.command) != null) {
                                                Spacer(Modifier.width(4.dp))
                                                HourlyQuotaLabel(session.quota)
                                            }
                                        }
                                    }
                                }
                            }
                            if (rowSessions.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        val openSessionIds = sessions.mapTo(mutableSetOf()) { it.task.id.replace("-", "").lowercase() }
        val remoteOnlyInstances = remoteInstances.filter { (_, item) ->
            item.optString("id").replace("-", "").lowercase() !in openSessionIds
        }
        if (remoteOnlyInstances.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("远端实例", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${remoteOnlyInstances.size}", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                remoteOnlyInstances.forEach { (host, item) ->
                    val workspace = item.optString("directory_name").ifBlank { item.optString("directory_path") }
                    val agent = item.optString("agent_name")
                    val title = item.optString("display_name").ifBlank {
                        listOf(workspace, agent).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "远端工作实例" }
                    }
                    Surface(
                        onClick = { onOpenRemoteInstance(host, item) },
                        color = Panel,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Blue.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(host.name, workspace, agent).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = Muted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("点按接入", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
            val hostRoutes by HostRouteResolver.routes.collectAsState()
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 17.dp),
                verticalArrangement = Arrangement.spacedBy(homeCardGridGap),
            ) {
                hosts.chunked(2).forEach { rowHosts ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(homeCardGridGap),
                    ) {
                        rowHosts.forEach { host ->
                            val selected = selectedHost?.id == host.id
                            val route = hostRoutes[host.id]
                            val hasLan = host.lanAddress.isNotBlank()
                            val hasExt = host.externalAddress.isNotBlank()
                            val isSingle = !hasLan || !hasExt || host.lanAddress.trim() == host.externalAddress.trim()
                            val routeText = when {
                                route != null && route.reachable -> if (isSingle) "${route.address}:${route.port}" else "${if (route.isLan) "内网" else "外网"} ${route.address}:${route.port}"
                                route != null && !route.reachable -> "离线 ${route.address}:${route.port}"
                                isSingle -> "${host.address.ifBlank { host.lanAddress.ifBlank { host.externalAddress } }}:${host.port}"
                                else -> "优先内网 ${host.lanAddress}:${host.lanPort}"
                            }
                            val routeColor = when {
                                route?.reachable == true -> if (route.isLan) Mint else Blue
                                else -> Muted
                            }
                            Surface(
                                onClick = { onSelectHost(host) },
                                color = if (selected) Color(0xFF20342F) else Panel,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (selected) Mint.copy(alpha = .7f) else Edge),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(host.icon.ifBlank { "⌘" }, fontSize = 19.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            host.name,
                                            color = White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Box(
                                            Modifier.size(7.dp).clip(CircleShape)
                                                .background(if (selected) Mint else Edge),
                                        )
                                    }
                                    Text(
                                        routeText,
                                        color = routeColor,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (rowHosts.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            selectedHost?.let { host ->
                Divider(color = Edge.copy(alpha = .8f))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("开发者", "")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenAgents) { Text("编辑", color = Mint) }
                }
                if (agents.isEmpty()) {
                    EmptyRow("还没有开发者", "添加一个启动命令，例如 codex 或 opencode。", onOpenAgents, "添加")
                } else {
                    AgentSelectorGrid(
                        agents = agents,
                        selectedAgent = selectedAgent,
                        host = host,
                        quotaFor = quotaFor,
                        onSelectAgent = onSelectAgent,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("开发目录", "${host.directories.size} 个")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAddDirectory) { Icon(Icons.Default.Add, null, tint = Mint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text("添加目录", color = Mint) }
                }
                if (host.directories.isEmpty()) {
                    EmptyRow("保存你的第一个项目目录", "例如 /workspace/my-app", onAddDirectory, "添加目录")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        val latestHost by rememberUpdatedState(host)
                        val latestDirectories by rememberUpdatedState(host.directories)
                        host.directories.forEach { dir ->
                            val isDragging = draggingDirectoryId == dir.id
                            val rowHeightPx = with(LocalDensity.current) { 90.dp.toPx() }
                            ProjectRow(
                                directory = dir, selectedAgent = selectedAgent,
                                enabled = selectedAgent != null,
                                onClick = { if (selectedAgent != null) onStartTask(latestHost, dir, selectedAgent) },
                                modifier = Modifier
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffsetY else 0f
                                        scaleX = if (isDragging) 1.025f else 1f
                                        scaleY = if (isDragging) 1.025f else 1f
                                        shadowElevation = if (isDragging) 14.dp.toPx() else 0f
                                    }
                                    .pointerInput(host.id, dir.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { draggingDirectoryId = dir.id; dragOffsetY = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                                val currentIndex = latestDirectories.indexOfFirst { it.id == dir.id }
                                                if (currentIndex >= 0) {
                                                    val delta = (dragOffsetY / rowHeightPx).toInt()
                                                    val target = (currentIndex + delta).coerceIn(0, latestDirectories.lastIndex)
                                                    if (target != currentIndex) {
                                                        val reordered = latestDirectories.toMutableList().apply { add(target, removeAt(currentIndex)) }
                                                        onReorderDirectories(latestHost, reordered)
                                                        dragOffsetY -= (target - currentIndex) * rowHeightPx
                                                    }
                                                }
                                            },
                                            onDragEnd = { draggingDirectoryId = null; dragOffsetY = 0f },
                                            onDragCancel = { draggingDirectoryId = null; dragOffsetY = 0f },
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NeonGlowBrandLogo(
    text: String = "REMOTE DEV",
    fontSize: TextUnit = 19.sp,
    letterSpacing: TextUnit = 1.4.sp,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF00F0FF)
    val highlight = Color(0xFFF4EAFF)
    val brand = Color(0xFFFF2EEC)
    val brush = remember {
        Brush.linearGradient(
            colors = listOf(accent, highlight, brand, accent)
        )
    }
    val shadow = remember {
        Shadow(
            color = accent.copy(alpha = 0.65f),
            offset = Offset.Zero,
            blurRadius = 6f,
        )
    }
    val style = remember(fontSize, letterSpacing) {
        TextStyle(
            brush = brush,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = letterSpacing,
            shadow = shadow,
        )
    }
    Text(
        text = text,
        maxLines = 1,
        modifier = modifier,
        style = style,
    )
}

@Composable
private fun ManageScreen(
    hosts: List<HostProfile>, selectedHost: HostProfile?, onBack: () -> Unit,
    onSelectHost: (HostProfile) -> Unit, onAddHost: () -> Unit,
    onEditHost: (HostProfile) -> Unit, onDeleteHost: (HostProfile) -> Unit,
    onAddDirectory: () -> Unit, onEditDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onDeleteDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onMoveDirectory: (HostProfile, Int, Int) -> Unit,
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
            val hostRoutes by HostRouteResolver.routes.collectAsState()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hosts.forEach { host ->
                    val selected = visibleHostId == host.id
                    Surface(onClick = { visibleHostId = host.id; onSelectHost(host) }, color = if (selected) Color(0xFF20342F) else Panel,
                        shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Mint.copy(alpha = .65f) else Edge)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (selected) "◉" else "○", color = if (selected) Mint else Muted, fontSize = 17.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(host.icon, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 5.dp))
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(host.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                val route = hostRoutes[host.id]
                                val hasLan = host.lanAddress.isNotBlank()
                                val hasExt = host.externalAddress.isNotBlank()
                                val isSingle = !hasLan || !hasExt || host.lanAddress.trim() == host.externalAddress.trim()
                                val routeDesc = when {
                                    route != null && route.reachable -> "${host.username} · ${if (isSingle) "" else if (route.isLan) "内网 " else "外网 "}${route.address}:${route.port}"
                                    route != null && !route.reachable -> "${host.username} · 离线 (${route.address}:${route.port})"
                                    isSingle -> "${host.username}@${host.address.ifBlank { host.lanAddress.ifBlank { host.externalAddress } }}:${host.port}"
                                    else -> "${host.username} · 优先内网 ${host.lanAddress}:${host.lanPort}"
                                }
                                val routeColor = when {
                                    route?.reachable == true -> if (route.isLan) Mint else Blue
                                    else -> Muted
                                }
                                Text(routeDesc, color = routeColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
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
                    host.directories.forEachIndexed { index, dir ->
                        Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                            Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dir.icon, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                    Text(dir.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(dir.path, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
                                }
                                IconButton(enabled = index > 0, onClick = { onMoveDirectory(host, index, -1) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, "上移 ${dir.name}", tint = if (index > 0) White else Muted.copy(alpha = 0.35f))
                                }
                                IconButton(enabled = index < host.directories.lastIndex, onClick = { onMoveDirectory(host, index, 1) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, "下移 ${dir.name}", tint = if (index < host.directories.lastIndex) White else Muted.copy(alpha = 0.35f))
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
private fun ProjectRow(directory: ProjectDirectory, selectedAgent: AgentPreset?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, color = Panel, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
        Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised), contentAlignment = Alignment.Center) { Text(directory.icon, fontSize = 20.sp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(directory.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                Text(directory.path, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
                if (selectedAgent != null) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AgentMark(selectedAgent.icon, 12.dp)
                        Text("${selectedAgent.name} · 新建任务", color = Mint.copy(alpha = .88f), fontSize = 10.sp)
                    }
                }
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
private fun OperationLogsScreen(
    logs: List<OperationLogEntry>,
    agents: List<AgentPreset>,
    openSessionIds: Set<String>,
    liveSessionTitles: Map<String, String> = emptyMap(),
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (OperationLogEntry) -> Unit,
) {
    val formatter = remember { java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = White)
            }
            Text("历史记录", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "同步日志", tint = Mint)
            }
        }
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无操作记录\n打开任意工作区会话后会自动记录并同步", color = Muted, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(logs.size, key = { logs[it].id }) { index ->
                    val entry = logs[index]
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth(0.8f).height(1.dp).background(Edge))
                        }
                    }
                    val isOpen = entry.sessionId.replace("-", "") in openSessionIds
                    val iconValue = entry.agentIcon.ifBlank {
                        agents.firstOrNull { it.id == entry.agentId }?.icon
                            ?: agents.firstOrNull { it.command == entry.agentCommand && it.name == entry.agentName }?.icon
                            ?: agents.firstOrNull { it.command == entry.agentCommand }?.icon
                            ?: "✳"
                    }
                    Surface(
                        color = if (isOpen) Mint.copy(alpha = 0.12f) else Panel,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (isOpen) Mint else Edge),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatter.format(java.util.Date(entry.createdAt)),
                                    color = Mint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                if (entry.client.isNotBlank()) {
                                    Text(
                                        entry.client.uppercase(),
                                        color = Blue,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Blue.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                if (isOpen) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "● 已打开",
                                        color = Mint,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Mint.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (entry.action == "resume") "回到现场" else "打开会话",
                                    color = Muted,
                                    fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AgentMark(iconValue, 20.dp)
                                val normalizedSid = entry.sessionId.replace("-", "").lowercase()
                                val fullTitle = liveSessionTitles[normalizedSid]?.takeIf { it.isNotBlank() && entry.titleSource != "manual" }
                                    ?: entry.sessionName.ifBlank { "${entry.agentName.ifBlank { entry.agentCommand }} · ${entry.directoryName.ifBlank { entry.directoryPath }}" }
                                Text(
                                    fullTitle,
                                    color = White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "主机: ${entry.hostName}  ·  ${entry.agentName.ifBlank { entry.agentCommand }}  ·  目录: ${entry.directoryPath}",
                                color = Muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.End) {
                                Button(
                                    onClick = { onRestore(entry) },
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isOpen) "切到会话" else "回到现场", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentsScreen(
    agents: List<AgentPreset>, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (AgentPreset) -> Unit,
    onDelete: (AgentPreset) -> Unit, onMove: (Int, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = White) }
            Column(Modifier.weight(1f)) { Text("编辑开发者", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text("名称、图标和启动命令都可自定义", color = Muted, fontSize = 12.sp) }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, null, tint = Mint) }
        }
        Text("已配置 ${agents.size} 个", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            agents.forEachIndexed { index, agent ->
                Surface(color = Panel, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                            AgentMark(agent.icon, 24.dp)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) { Text(agent.name, color = White, fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(agent.command, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        IconButton(enabled = index > 0, onClick = { onMove(index, -1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, "上移 ${agent.name}")
                        }
                        IconButton(enabled = index < agents.lastIndex, onClick = { onMove(index, 1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, "下移 ${agent.name}")
                        }
                        IconButton(onClick = { onEdit(agent) }) { Icon(Icons.Default.Edit, null, tint = Muted, modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = { onDelete(agent) }) { Icon(Icons.Default.DeleteOutline, null, tint = Muted, modifier = Modifier.size(19.dp)) }
                    }
                }
            }
            if (agents.isEmpty()) EmptyRow("添加开发者", "命令会在所选开发目录中启动。", onAdd, "添加")
        }
    }
}

@Composable
private fun TerminalWorkspace(
    sessions: List<TerminalSessionState>, initialIndex: Int, onPageSelected: (Int) -> Unit,
    onBack: () -> Unit, onNewTask: () -> Unit, onCloseTask: (Int) -> Unit,
    onRenameTask: (TerminalSessionState) -> Unit,
) {
    if (sessions.isEmpty()) return
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, sessions.lastIndex), pageCount = { sessions.size })
    val sessionIds = sessions.map { it.task.id }
    var pagerReady by remember(sessionIds) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Pager may briefly retain a removed page index while it reconciles the new count.
    val fallbackSession = sessions.getOrNull(initialIndex.coerceIn(0, sessions.lastIndex)) ?: sessions.lastOrNull() ?: return
    val currentSession = sessions.getOrNull(pager.currentPage) ?: fallbackSession
    val context = LocalContext.current
    var showUploadDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }

    fun processUpload(uri: Uri, isImage: Boolean) {
        if (!currentSession.status.startsWith("已连接") && !currentSession.controller.isConnected()) {
            Toast.makeText(context, "无法发送图片或文件：请先等待该终端连接成功", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                isUploading = true
                var displayName = if (isImage) "image.png" else "file"
                var fileSize = -1L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }
                if (fileSize > 20 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "文件超过 20 MB，无法上传", Toast.LENGTH_LONG).show()
                        isUploading = false
                    }
                    return@launch
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("无法读取文件内容")
                if (bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "文件内容为空", Toast.LENGTH_SHORT).show()
                        isUploading = false
                    }
                    return@launch
                }
                if (bytes.size > 20 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "文件超过 20 MB，无法上传", Toast.LENGTH_LONG).show()
                        isUploading = false
                    }
                    return@launch
                }
                val remotePath = currentSession.controller.uploadRemoteFile(bytes, displayName, isImage)
                withContext(Dispatchers.Main) {
                    isUploading = false
                    if (isImage) {
                        currentSession.controller.send("请查看这张图片：$remotePath\r")
                    } else {
                        val escapedName = displayName.replace("'", "'\\''")
                        val escapedPath = remotePath.replace("'", "'\\''")
                        currentSession.controller.send("请查看文件 '$escapedName'，路径：'$escapedPath'\r")
                    }
                    Toast.makeText(context, if (isImage) "图片已上传并发送给 Agent" else "文件已上传并发送给 Agent", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isUploading = false
                    Toast.makeText(context, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) processUpload(uri, isImage = true)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) processUpload(uri, isImage = false)
    }

    LaunchedEffect(sessionIds) {
        pagerReady = false
        pager.scrollToPage(initialIndex.coerceIn(0, sessions.lastIndex))
        pagerReady = true
    }
    LaunchedEffect(pager.currentPage, sessions.size, pagerReady) {
        if (pagerReady && pager.currentPage in sessions.indices) onPageSelected(pager.currentPage)
    }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(start = 5.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.ArrowBack, null, tint = White, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${currentSession.task.host.icon}  ${currentSession.task.host.name}", color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(currentSession.status, color = statusTint(currentSession.status), fontSize = 10.sp, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${currentSession.task.directory.icon}  ", color = Muted, fontSize = 10.sp, maxLines = 1)
                    if (currentSession.task.directory.name.isNotBlank()) {
                        Text("${currentSession.task.directory.name}  ·  ", color = White.copy(alpha = 0.78f), fontSize = 10.sp, maxLines = 1)
                    }
                    Text(currentSession.task.directory.path, color = Muted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ·  ", color = Muted, fontSize = 10.sp)
                    AgentMark(currentSession.task.agent.icon, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(currentSession.task.agent.name, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(
                onClick = { showUploadDialog = true },
                enabled = !isUploading,
                modifier = Modifier.size(40.dp)
            ) {
                if (isUploading) {
                    CircularProgressIndicator(color = Mint, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, "上传图片或文件", tint = Mint)
                }
            }
        }
        Divider(color = Edge)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sessions.forEachIndexed { originalIndex, session ->
                    val selected = originalIndex == pager.currentPage
                    SessionTab(
                        session = session,
                        selected = selected,
                        onClick = { scope.launch { pager.animateScrollToPage(originalIndex) } },
                        onRename = { onRenameTask(session) },
                        onClose = { onCloseTask(originalIndex) },
                        modifier = Modifier.widthIn(min = 145.dp, max = 220.dp),
                    )
                }
            }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
            beyondViewportPageCount = (sessions.size - 1).coerceIn(0, 4),
            key = { sessions.getOrNull(it)?.task?.id ?: "removed-$it" },
        ) { page ->
            TerminalPage(sessions.getOrNull(page) ?: currentSession, page == pager.currentPage)
        }
    }

    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            containerColor = Panel,
            title = { Text("发送图片或文件给 Agent", color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "文件将上传至远端 ~/.remote-dev-terminal/ 目录，并将路径发送给当前终端。最大支持 20 MB。",
                        color = Muted,
                        fontSize = 12.sp
                    )
                    Surface(
                        onClick = {
                            showUploadDialog = false
                            imagePickerLauncher.launch("image/*")
                        },
                        color = PanelRaised,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Edge),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = Mint, modifier = Modifier.size(22.dp))
                            Column {
                                Text("上传本地图片", color = White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("支持 PNG、JPEG、WEBP 等常见格式", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            showUploadDialog = false
                            filePickerLauncher.launch("*/*")
                        },
                        color = PanelRaised,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Edge),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = Blue, modifier = Modifier.size(22.dp))
                            Column {
                                Text("上传本地文件", color = White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("支持代码、文本、压缩包等任意格式", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false }) {
                    Text("取消", color = Muted)
                }
            }
        )
    }
}

@Composable
private fun SessionTab(
    session: TerminalSessionState,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "activity")
    val pulse by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val color = when { session.needsAction -> Coral; selected -> Mint; session.unread -> Blue; else -> statusTint(session.status) }
    val deadline = session.autoEnterDeadline
    var remainingSeconds by remember(session, deadline) { mutableIntStateOf(0) }
    LaunchedEffect(deadline) {
        while (deadline != null) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) { remainingSeconds = 0; break }
            remainingSeconds = ((remaining + 999) / 1000).toInt()
            kotlinx.coroutines.delay((remaining - (remainingSeconds - 1) * 1000L).coerceAtLeast(1))
        }
    }
    Surface(
        onClick = onClick,
        color = if (selected) PanelRaised else Panel,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color.copy(alpha = .7f) else Edge),
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 5.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).alpha(if (session.isStreaming) pulse else 1f).background(color, CircleShape))
            val supportsQuota = codexExecutable(session.task.agent.command) != null || antigravityExecutable(session.task.agent.command) != null
            if (remainingSeconds > 0) {
                Text(remainingSeconds.toString(), color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            if (supportsQuota) HourlyQuotaLabel(session.quota)
            AgentMark(session.task.agent.icon, 14.dp)
            Text(
                sessionDisplayLabel(session.task),
                color = if (selected) White else Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (modifier != Modifier) Modifier.weight(1f) else Modifier,
            )
            IconButton(onClick = onRename, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Edit, "编辑实例名称", tint = Muted, modifier = Modifier.size(12.dp)) }
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Close, null, tint = Muted, modifier = Modifier.size(13.dp)) }
        }
    }
}

@Composable
private fun HourlyQuotaLabel(quota: List<QuotaWindow>?) {
    val hourly = quota.hourlyQuota()
    QuotaBattery(quotaPeriod(hourly?.minutes ?: 300), hourly?.remainingPercent)
}

@Composable
private fun QuotaBattery(period: String, percent: Int?, compact: Boolean = false) {
    val fill = when {
        percent == null -> Color.Transparent
        percent < 5 -> Coral
        percent < 20 -> Color(0xFFFF9E20)
        else -> Mint
    }
    val description = if (compact) Modifier.clearAndSetSemantics {
        contentDescription = "${period}剩余额度 ${percent?.let { "$it%" } ?: "未知"}"
    } else Modifier
    Row(description, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) Text(period, color = Muted, fontSize = 10.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(28.dp, 11.dp),
                shape = RoundedCornerShape(3.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Muted),
                color = Ink,
            ) {
                Box(Modifier.fillMaxSize().padding(2.dp)) {
                    Box(Modifier.fillMaxWidth((percent ?: 0).coerceIn(0, 100) / 100f)
                        .height(7.dp).background(fill, RoundedCornerShape(1.dp)))
                }
            }
            Box(Modifier.size(3.dp, 5.dp).background(Muted, RoundedCornerShape(1.dp)))
        }
        if (compact) {
            QuotaNumber(percent?.toString() ?: "--", columns = 3, color = White)
        } else {
            Text(percent?.let { "$it%" } ?: "--", color = White, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun QuotaNumber(value: String, columns: Int, color: Color, modifier: Modifier = Modifier) {
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, textAlign = TextAlign.End)
    val width = rememberTextMeasurer().measure("0".repeat(columns), style, maxLines = 1).size.width
    Text(value, modifier.width(with(LocalDensity.current) { width.toDp() }), color = color, style = style,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun AgentSelectorGrid(
    agents: List<AgentPreset>,
    selectedAgent: AgentPreset?,
    host: HostProfile,
    quotaFor: (HostProfile, AgentPreset) -> List<QuotaWindow>?,
    onSelectAgent: (AgentPreset) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(homeCardGridGap),
    ) {
        agents.chunked(2).forEach { rowAgents ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(homeCardGridGap),
            ) {
                rowAgents.forEach { agent ->
                    val selected = selectedAgent?.id == agent.id
                    val supportsQuota = quotaExecutable(agent.command) != null
                    Surface(
                        color = if (selected) Color(0xFF20342F) else Panel,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) Mint else Edge,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelectAgent(agent) },
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                AgentMark(agent.icon, 14.dp)
                                Text(
                                    agent.name,
                                    color = if (selected) White else Muted,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (supportsQuota) {
                                AgentQuotaDetails(quotaFor(host, agent))
                            }
                        }
                    }
                }
                if (rowAgents.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AgentQuotaDetails(quota: List<QuotaWindow>?) {
    val hourly = quota.hourlyQuota()
    val weekly = quota?.firstOrNull { it.minutes == 10080 }
    val remaining = weekly?.resetsAt?.minus(java.time.Instant.now().epochSecond)
    val reset = when {
        remaining == null -> "--"
        remaining in 1L until 86_400L -> "<1"
        else -> (remaining / 86_400L).coerceAtLeast(0).toString()
    }
    val resetText = if (remaining == null) "--" else "${reset}天"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QuotaBattery(quotaPeriod(hourly?.minutes ?: 300), hourly?.remainingPercent, compact = true)
        QuotaBattery("周", weekly?.remainingPercent, compact = true)
        Text(
            resetText,
            color = Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "周额度重置 ${if (remaining == null) "未知" else "${reset}天"}"
            },
        )
    }
}

private data class TerminalSelection(
    val rows: List<List<TerminalCell>>,
    val firstRow: Float,
    val anchorRow: Int,
    val anchorColumn: Int,
    val endRow: Int,
    val endColumn: Int,
)

@Composable
private fun TerminalPage(session: TerminalSessionState, isCurrent: Boolean) {
    val scope = rememberCoroutineScope()
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
    var resetInput by remember(session.task.id) { mutableStateOf<(() -> Unit)?>(null) }
    var selection by remember(session.task.id) { mutableStateOf<TerminalSelection?>(null) }
    var selectionMenu by remember(session.task.id) { mutableStateOf(false) }
    var ctrlArmed by remember(session.task.id) { mutableStateOf(false) }
    var shiftArmed by remember(session.task.id) { mutableStateOf(false) }
    var altArmed by remember(session.task.id) { mutableStateOf(false) }
    var scrollbackTopRow by remember(session.task.id) { mutableStateOf<Float?>(null) }
    var observedScrollbackBase by remember(session.task.id) { mutableStateOf(0L) }
    val lineHeightPx = 16.3f * density.density
    val gestureVisibleRows = (terminalViewport.height / lineHeightPx).toInt().coerceIn(8, 100)
    val allRows = remember(scrollback, screen) { scrollback + screen }
    val liveFirstVisibleRow = (allRows.size - gestureVisibleRows).coerceAtLeast(0)
    val latestLiveFirstVisibleRow by rememberUpdatedState(liveFirstVisibleRow)
    val latestAllRows by rememberUpdatedState(allRows)
    val latestTerminalViewport by rememberUpdatedState(terminalViewport)
    val latestScrollbackTopRow by rememberUpdatedState(scrollbackTopRow)
    LaunchedEffect(liveFirstVisibleRow) {
        scrollbackTopRow = scrollbackTopRow?.let { if (it >= liveFirstVisibleRow) null else it }
    }
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
    val charWidthPx = remember(density.density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * density.density
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }.measureText("M").coerceAtLeast(1f)
    }
    fun selectionPosition(position: androidx.compose.ui.geometry.Offset, firstRow: Float, rows: List<List<TerminalCell>>): Pair<Int, Int> {
        val row = (firstRow + position.y / lineHeightPx).toInt().coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val column = (position.x / charWidthPx).toInt().coerceIn(0, ((rows.getOrNull(row)?.size ?: 1) - 1).coerceAtLeast(0))
        return row to column
    }
    val viewportColumns = (terminalViewport.width / charWidthPx).toInt().coerceIn(24, 200)
    val viewportRows = (terminalViewport.height / lineHeightPx).toInt().coerceIn(8, 100)
    // Keep the remote grid stable while the keyboard moves; only resize when
    // the terminal character grid dimensions actually change.
    LaunchedEffect(session.task.id, viewportColumns, viewportRows, terminalViewport.width > 0 && terminalViewport.height > 0) {
        if (terminalViewport.width > 0 && terminalViewport.height > 0) {
            kotlinx.coroutines.delay(220)
            session.buffer.resize(viewportColumns, viewportRows)
            session.controller.resize(viewportColumns, viewportRows)
        }
    }

    var wheelRemainder by remember(session.task.id) { mutableStateOf(0f) }
    var wheelPosition by remember(session.task.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val terminalScroll = rememberScrollableState { delta ->
        if (!isCurrent) return@rememberScrollableState 0f
        val liveTop = latestLiveFirstVisibleRow.toFloat()
        val currentTop = (latestScrollbackTopRow ?: liveTop).coerceIn(0f, liveTop)
        val charWidth = Paint().apply {
            textSize = 12f * density.density
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }.measureText("M").coerceAtLeast(1f)
        val column = (wheelPosition.x / charWidth).toInt() + 1
        val row = (wheelPosition.y / lineHeightPx).toInt() + 1
        val codex = codexExecutable(session.task.agent.command) != null
        val wheel = session.buffer.mouseWheelSequence(delta > 0, column, row)
        if ((codex || wheel != null || session.buffer.isAlternateScreen()) && latestScrollbackTopRow == null) {
            wheelRemainder += delta
            // Arrow keys navigate the prompt's input history in agent TUIs. When
            // mouse reporting is unavailable, use page keys to scroll their transcript.
            val scrollStep = if (!codex && wheel != null) lineHeightPx else lineHeightPx * 2
            while (kotlin.math.abs(wheelRemainder) >= scrollStep) {
                val up = wheelRemainder > 0
                val page = if (up) "\u001b[5~" else "\u001b[6~"
                if (codex) {
                    // Ordinary input also cancels any stale tmux copy mode.
                    session.controller.send(page)
                } else {
                    session.controller.sendScroll(session.buffer.mouseWheelSequence(up, column, row) ?: page)
                }
                wheelRemainder += if (up) -scrollStep else scrollStep
            }
            delta
        } else {
            wheelRemainder = 0f
            val nextTop = (currentTop - delta / lineHeightPx).coerceIn(0f, liveTop)
            scrollbackTopRow = if (nextTop >= liveTop) null else nextTop
            (currentTop - nextTop) * lineHeightPx
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF070B10))) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { terminalViewport = it }
                    .pointerInput(session.task.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { wheelPosition = it.position }
                            }
                        }
                    }
                    .scrollable(terminalScroll, Orientation.Vertical, enabled = isCurrent && selection == null)
                    .pointerInput(isCurrent, inputView, allRows, scrollbackTopRow, terminalViewport, selection) {
                        detectTapGestures(
                            onTap = { position ->
                                if (isCurrent) {
                                    if (selection == null && scrollbackTopRow == null) {
                                        val first = (allRows.size - terminalViewport.height / lineHeightPx).coerceAtLeast(0f)
                                        val row = (first + position.y / lineHeightPx).toInt() - scrollback.size
                                        val column = (position.x / charWidthPx).toInt()
                                        if (row in screen.indices && column in 0 until viewportColumns) {
                                            session.buffer.mouseClickSequence(column + 1, row + 1)?.let {
                                                resetInput?.invoke()
                                                session.controller.send(it)
                                            }
                                        }
                                    }
                                    selection = null; selectionMenu = false; showKeyboard()
                                }
                            },
                        )
                    }
                    .pointerInput(isCurrent, terminalViewport) {
                        if (isCurrent) detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                val rows = latestAllRows
                                if (rows.isNotEmpty()) {
                                    val first = (latestScrollbackTopRow ?: (rows.size - latestTerminalViewport.height / lineHeightPx).coerceAtLeast(0f))
                                    val (row, col) = selectionPosition(position, first, rows)
                                    selection = TerminalSelection(rows, first, row, col, row, col)
                                    selectionMenu = true
                                }
                            },
                            onDrag = { change, _ ->
                                selection?.let { current ->
                                    val (row, col) = selectionPosition(change.position, current.firstRow, current.rows)
                                    selection = current.copy(endRow = row, endColumn = col)
                                    change.consume()
                                }
                            },
                        )
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
                // Anchor the live screen to the moving bottom edge at pixel precision.
                val selected = selection
                val displayRows = selected?.rows ?: allRows
                val liveFirstRow = (displayRows.size - size.height / lineHeight).coerceAtLeast(0f)
                val firstVisibleRow = selected?.firstRow ?: (scrollbackTopRow ?: liveFirstRow).coerceIn(0f, liveFirstRow)
                val firstVisibleIndex = firstVisibleRow.toInt()
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val lastVisibleRow = minOf(displayRows.size, firstVisibleIndex + visibleRows + 2)
                    for (screenRow in firstVisibleIndex until lastVisibleRow) {
                        val drawRow = screenRow - firstVisibleRow
                        val line = displayRows[screenRow]
                        for (c in 0 until minOf(line.size, visibleCols)) {
                            val cell = line[c]
                            if (cell.background != 0) {
                                paint.color = android.graphics.Color.parseColor("#" + terminalColor(cell.background).toString(16).takeLast(6))
                                native.drawRect(c * charWidth, drawRow * lineHeight, (c + 1) * charWidth, (drawRow + 1) * lineHeight, paint)
                            }
                        }
                        if (selected != null) {
                            val a = selected.anchorRow * viewportColumns + selected.anchorColumn
                            val b = selected.endRow * viewportColumns + selected.endColumn
                            val start = minOf(a, b)
                            val end = maxOf(a, b)
                            paint.color = Blue.copy(alpha = .45f).toArgb()
                            for (c in 0 until minOf(line.size, visibleCols)) {
                                if (screenRow * viewportColumns + c in start..end)
                                    native.drawRect(c * charWidth, drawRow * lineHeight, (c + 1) * charWidth, (drawRow + 1) * lineHeight, paint)
                            }
                        }
                        // Paint the whole row's background first: a wide glyph extends
                        // into the following cell, whose background must not cover it.
                        for (c in 0 until minOf(line.size, visibleCols)) {
                            val cell = line[c]
                            if (cell.text.isNotEmpty() && cell.text != " ") {
                                paint.color = terminalColor(cell.foreground).toInt()
                                native.drawText(cell.text, c * charWidth, (drawRow + 1) * lineHeight - 2f, paint)
                            }
                        }
                    }
                    val cursorDisplayRow = scrollback.size + terminalCursor.row - firstVisibleRow
                    if (selected == null && terminalCursor.visible && terminalCursor.column < visibleCols && cursorDisplayRow > -1f && cursorDisplayRow * lineHeight < size.height) {
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
            if (selectionMenu && selection != null) {
                Row(Modifier.align(Alignment.TopCenter).background(PanelRaised, RoundedCornerShape(8.dp)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val current = selection ?: return@TextButton
                        val a = current.anchorRow * viewportColumns + current.anchorColumn
                        val b = current.endRow * viewportColumns + current.endColumn
                        val start = minOf(a, b); val end = maxOf(a, b)
                        val text = (start / viewportColumns..end / viewportColumns).joinToString("\n") { row ->
                            val cells = current.rows.getOrNull(row).orEmpty()
                            val left = if (row == start / viewportColumns) start % viewportColumns else 0
                            val right = if (row == end / viewportColumns) end % viewportColumns + 1 else cells.size
                            cells.subList(left.coerceIn(0, cells.size), right.coerceIn(left.coerceIn(0, cells.size), cells.size)).joinToString("") { it.text }.trimEnd()
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("终端文本", text))
                        selection = null; selectionMenu = false
                    }) { Text("复制") }
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() else ""
                    if (text.isNotEmpty()) { resetInput?.invoke(); session.controller.send(session.buffer.pasteSequence(text)); session.clearAttention() }
                        selection = null; selectionMenu = false; showKeyboard()
                    }) { Text("粘贴") }
                    TextButton(onClick = { selection = null; selectionMenu = false }) { Text("取消") }
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
                        if (added.isNotEmpty() || removed > 0) scrollbackTopRow = null
                        if (removed > 0) {
                            val backspace = if (ctrlArmed) "\b" else "\u007f"
                            session.controller.send((if (altArmed) "\u001b" else "") + backspace.repeat(removed))
                            ctrlArmed = false; altArmed = false; shiftArmed = false
                        }
                        if (added.isNotEmpty()) {
                            var outgoing = if (shiftArmed) added.uppercase() else added
                            if (ctrlArmed && added.length == 1 && added[0].code in 32..126) outgoing = (added[0].lowercaseChar().code and 0x1f).toChar().toString()
                            if (altArmed) outgoing = "\u001b$outgoing"
                            session.controller.send(outgoing)
                            ctrlArmed = false; altArmed = false; shiftArmed = false
                        }
                        if (added.isNotEmpty() || removed > 0) session.clearAttention()
                        previous = next
                    }
                }
                fun sendBackspace() {
                    scrollbackTopRow = null
                    session.controller.send((if (altArmed) "\u001b" else "") + if (ctrlArmed) "\b" else "\u007f")
                    ctrlArmed = false; altArmed = false; shiftArmed = false
                    session.clearAttention()
                }
                fun pasteClipboard(): Boolean {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                    } else ""
                    if (text.isNotEmpty()) {
                        scrollbackTopRow = null
                        resetInput?.invoke()
                        session.controller.send(session.buffer.pasteSequence(text))
                        session.clearAttention()
                    }
                    return true
                }
                editor = object : EditText(viewContext) {
                    override fun onTextContextMenuItem(id: Int): Boolean {
                        if (id == android.R.id.paste) return pasteClipboard()
                        return super.onTextContextMenuItem(id)
                    }

                    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
                        if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed) return pasteClipboard()
                        return super.onKeyShortcut(keyCode, event)
                    }

                    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                        val connection = super.onCreateInputConnection(outAttrs) ?: return null
                        return object : InputConnectionWrapper(connection, true) {
                            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                                val committed = text?.toString().orEmpty()
                                if (committed.contains('\n') || committed.contains('\r')) {
                                    resetInput?.invoke()
                                    if (committed.isNotEmpty()) {
                                        scrollbackTopRow = null
                                        session.controller.send(session.buffer.pasteSequence(committed))
                                        session.clearAttention()
                                    }
                                    return true
                                }
                                return super.commitText(text, newCursorPosition)
                            }

                            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0) {
                                    repeat(beforeLength) { sendBackspace() }
                                    return true
                                }
                                return super.deleteSurroundingText(beforeLength, afterLength)
                            }

                            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0) {
                                    repeat(beforeLength) { sendBackspace() }
                                    return true
                                }
                                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                            }

                            override fun sendKeyEvent(event: KeyEvent): Boolean {
                                if (event.keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0) {
                                    if (event.action == KeyEvent.ACTION_DOWN) sendBackspace()
                                    return true
                                }
                                return super.sendKeyEvent(event)
                            }
                        }
                    }
                }.apply {
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
                            if (event != null && event.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
                            if (event != null && event.repeatCount > 0) return@setOnEditorActionListener true
                            session.controller.send("\r")
                            ctrlArmed = false; altArmed = false; shiftArmed = false
                            session.clearAttention()
                            ignoreChange = true
                            setText("")
                            true
                        } else false
                    }
                }
                resetInput = {
                    ignoreChange = true
                    editor.setText("")
                }
                inputView = editor
                editor
            },
            update = { editor -> if (isCurrent && !editor.hasFocus()) editor.requestFocus() },
        )

        AnimatedVisibility(visible = keyboardVisible) {
        Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val keys = listOf(
                listOf("ESC" to "\u001b", "SHIFT" to "", "/" to "/", "PAST" to "", "PG↑" to "", "HOME" to "", "↑" to "", "END" to ""),
                listOf("TAB" to "\t", "CTRL" to "", "ALT" to "", "RESU" to "/resume", "PG↓" to "", "←" to "", "↓" to "", "→" to ""),
            )
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { (label, value) ->
                        val armed = (label == "CTRL" && ctrlArmed) || (label == "ALT" && altArmed) || (label == "SHIFT" && shiftArmed)
                        Surface(onClick = {
                            when (label) {
                                "SHIFT" -> { shiftArmed = !shiftArmed }
                                "CTRL" -> { ctrlArmed = !ctrlArmed; altArmed = false }
                                "ALT" -> { altArmed = !altArmed; ctrlArmed = false }
                                "PG↑", "PG↓" -> {
                                    scrollbackTopRow = null
                                    session.controller.scrollTmuxPage(label == "PG↑")
                                    ctrlArmed = false; altArmed = false; shiftArmed = false
                                }
                                "PAST" -> {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() else ""
                                    if (text.isNotEmpty()) {
                                        scrollbackTopRow = null
                                        resetInput?.invoke()
                                        session.controller.send(session.buffer.pasteSequence(text))
                                        session.clearAttention()
                                    }
                                    ctrlArmed = false; altArmed = false; shiftArmed = false
                                }
                                "RESU" -> {
                                    resetInput?.invoke()
                                    session.controller.send(value)
                                    ctrlArmed = false; altArmed = false; shiftArmed = false
                                    session.clearAttention()
                                    scope.launch {
                                        kotlinx.coroutines.delay(200)
                                        inputView?.onEditorAction(EditorInfo.IME_ACTION_SEND)
                                    }
                                }
                                else -> {
                                    val direction = when (label) {
                                        "↑" -> 'A'; "↓" -> 'B'; "→" -> 'C'; "←" -> 'D'; "HOME" -> 'H'; "END" -> 'F'; else -> null
                                    }
                                    var send = direction?.let { session.buffer.cursorSequence(it, ctrlArmed, altArmed, shiftArmed) } ?: value
                                    resetInput?.invoke()
                                    if (direction == null && shiftArmed && send == "/") send = "?"
                                    if (direction == null && ctrlArmed && send.length == 1 && send[0].code in 32..126) send = (send[0].lowercaseChar().code and 0x1f).toChar().toString()
                                    if (direction == null && altArmed) send = "\u001b$send"
                                    session.controller.send(send); ctrlArmed = false; altArmed = false; shiftArmed = false
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
}

private fun sessionDisplayLabel(task: TerminalTask): String {
    val workspace = task.directory.name.trim()
    val name = task.displayName.trim()
    if (workspace.isEmpty()) return name
    if (name.isEmpty()) return workspace
    return name
}

private fun statusTint(status: String): Color = when {
    status.startsWith("已连接") -> Mint
    status.contains("重连") || status.contains("连接中") -> Blue
    status.contains("断开") -> Muted
    else -> Coral
}

@Composable
private fun SyncLoginScreen(
    syncUrl: String,
    initialUsername: String,
    initialPassword: String,
    onBack: () -> Unit,
    onOfflineMode: () -> Unit,
    onSuccess: (String, String, String, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("同步账号登录", color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(syncUrl, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
            Text("保持账号密码", color = White, fontSize = 13.sp)
        }
        if (error.isNotBlank()) Text(error, color = Coral, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        Button(enabled = !busy && syncUrl.isNotBlank(), onClick = {
            busy = true; error = ""
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { SyncClient.authenticate(syncUrl, "login", username.trim(), password) } }
                    .onSuccess { response -> onSuccess(response.getString("username"), password, response.getString("access_token"), rememberPassword) }
                    .onFailure { error = it.localizedMessage ?: "登录失败" }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = White) else Text("登录")
        }
        FilledTonalButton(enabled = !busy, onClick = onOfflineMode, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("离线模式")
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("返回", color = Muted) }
    }
}

@Composable
private fun TerminalTileCard(
    session: TerminalSessionState,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Panel)
            .border(if (isCurrent) BorderStroke(1.5.dp, Mint) else BorderStroke(1.dp, Edge), RoundedCornerShape(8.dp))
    ) {
        // Card Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(PanelRaised)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusTint(session.status))
            )
            Spacer(Modifier.width(6.dp))
            AgentMark(session.task.agent.icon, 16.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = sessionDisplayLabel(session.task),
                color = White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(onClick = onRename, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "编辑实例名称", tint = Muted, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(4.dp))
            HourlyQuotaLabel(quota = session.quota)
            IconButton(onClick = onMinimize, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "最小化到左侧实例列表", tint = Muted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMaximize, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Fullscreen, contentDescription = "最大化标签模式", tint = Muted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭会话", tint = Muted, modifier = Modifier.size(14.dp))
            }
        }
        Divider(color = Edge)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onSelect
                )
        ) {
            TerminalPage(session = session, isCurrent = isCurrent)
        }
    }
}

@Composable
private fun PadSidebar(
    modifier: Modifier = Modifier,
    hosts: List<HostProfile>,
    agents: List<AgentPreset>,
    sessions: List<TerminalSessionState>,
    tiled: Boolean,
    selectedHost: HostProfile?,
    selectedAgent: AgentPreset?,
    activePage: Int,
    onSelectHost: (HostProfile) -> Unit,
    onSelectAgent: (AgentPreset) -> Unit,
    onSelectSession: (Int) -> Unit,
    onStartTask: (HostProfile, ProjectDirectory, AgentPreset) -> Unit,
    onCloseSession: (TerminalSessionState) -> Unit,
    onRenameSession: (TerminalSessionState) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    syncLoading: Boolean,
    offlineMode: Boolean,
    autoSyncEnabled: Boolean,
    quotaFor: (HostProfile, AgentPreset) -> List<QuotaWindow>?,
    onAddHost: () -> Unit,
    onEditHost: (HostProfile) -> Unit,
    onDeleteHost: (HostProfile) -> Unit,
    onMoveHost: (Int, Int) -> Unit,
    onAddDirectory: (HostProfile) -> Unit,
    onEditDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onDeleteDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onMoveDirectory: (HostProfile, Int, Int) -> Unit,
    onAddAgent: () -> Unit,
    onEditAgent: (AgentPreset) -> Unit,
    onDeleteAgent: (AgentPreset) -> Unit,
    onMoveAgent: (Int, Int) -> Unit,
) {
    var editMode by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .background(Panel)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Brand & Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                NeonGlowBrandLogo(fontSize = 17.sp, letterSpacing = 0.8.sp)
                Text(
                    text = "远程开发工作台 · v${BuildConfig.VERSION_NAME}",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
            // Edit toggle capsule
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (editMode) Mint else PanelRaised)
                    .border(1.dp, if (editMode) Mint else Edge, RoundedCornerShape(12.dp))
                    .clickable { editMode = !editMode }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (editMode) "完成" else "编辑",
                    color = if (editMode) Ink else White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                if (syncLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Mint, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = White, modifier = Modifier.size(18.dp))
                }
            }
            val jarvisState by dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.state.collectAsState()
            IconButton(
                onClick = {
                    dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.triggerManualWake()
                },
                modifier = Modifier.size(32.dp)
            ) {
                val iconColor = when (jarvisState) {
                    dev.remote.terminal.jarvis.api.JarvisState.LISTENING -> Coral
                    dev.remote.terminal.jarvis.api.JarvisState.PROCESSING -> Blue
                    dev.remote.terminal.jarvis.api.JarvisState.EXECUTING -> Mint
                    dev.remote.terminal.jarvis.api.JarvisState.SPEAKING -> Mint
                    dev.remote.terminal.jarvis.api.JarvisState.STANDBY -> Mint
                    dev.remote.terminal.jarvis.api.JarvisState.DISABLED -> Muted
                }
                Icon(
                    imageVector = if (jarvisState == dev.remote.terminal.jarvis.api.JarvisState.DISABLED) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "贾维斯语音中枢",
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = White, modifier = Modifier.size(18.dp))
            }
        }

        // Section: 主机
        SectionTitle(title = "主机", trailing = if (hosts.isEmpty()) "" else "${hosts.size} 台")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hosts.forEachIndexed { index, host ->
                val isSelected = host.id == selectedHost?.id
                if (!editMode) {
                    Surface(
                        onClick = { onSelectHost(host) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF162A24) else PanelRaised,
                        border = BorderStroke(1.dp, if (isSelected) Mint else Edge)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(host.icon.ifBlank { "⌘" }, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(host.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (host.syncFingerprint.isNotBlank()) Mint else Color(0xFFFFB86C))
                                    )
                                }
                                Text("${host.address}:${host.port}", color = Muted, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelRaised)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(host.name, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (index > 0) {
                            IconButton(onClick = { onMoveHost(index, -1) }, modifier = Modifier.size(28.dp)) {
                                Text("↑", color = White, fontSize = 14.sp)
                            }
                        }
                        if (index < hosts.lastIndex) {
                            IconButton(onClick = { onMoveHost(index, 1) }, modifier = Modifier.size(28.dp)) {
                                Text("↓", color = White, fontSize = 14.sp)
                            }
                        }
                        IconButton(onClick = { onEditHost(host) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = White, modifier = Modifier.size(15.dp))
                        }
                        IconButton(onClick = { onDeleteHost(host) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除", tint = Coral, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
            if (editMode) {
                OutlinedButton(
                    onClick = onAddHost,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Edge)
                ) {
                    Text("＋ 主机", color = Mint, fontSize = 12.sp)
                }
            }
            Text(
                text = if (offlineMode) "离线模式（仅本机）" else if (autoSyncEnabled) "自动同步已开启" else "手动同步",
                color = Muted,
                fontSize = 10.sp
            )
        }

        // Section: 工作实例 (if sessions exist)
        val instanceItems = sessions.withIndex().filter { (_, session) ->
            !tiled || session.isMinimized
        }
        if (instanceItems.isNotEmpty()) {
            SectionTitle(title = "工作实例", trailing = "${instanceItems.size} 个")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                instanceItems.forEach { (index, session) ->
                    val isSelected = !session.isMinimized && index == activePage
                    Surface(
                        onClick = { onSelectSession(index) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF162A24) else PanelRaised,
                        border = BorderStroke(1.dp, if (isSelected) Mint else Edge)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusTint(session.status))
                            )
                            Spacer(Modifier.width(8.dp))
                            AgentMark(session.task.agent.icon, 16.dp)
                            Spacer(Modifier.width(5.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = sessionDisplayLabel(session.task),
                                    color = White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (session.isMinimized) {
                                    Text("已最小化 · 点击恢复", color = Muted, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            IconButton(onClick = { onRenameSession(session) }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "修改名称", tint = Muted, modifier = Modifier.size(14.dp))
                            }
                            IconButton(onClick = { onCloseSession(session) }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Muted, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        // Section: 开发者
        SectionTitle(title = "开发者", trailing = if (agents.isEmpty()) "" else "${agents.size} 个")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            agents.forEachIndexed { index, agent ->
                val isSelected = agent.id == selectedAgent?.id
                if (!editMode) {
                    Surface(
                        onClick = { onSelectAgent(agent) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF162A24) else PanelRaised,
                        border = BorderStroke(1.dp, if (isSelected) Mint else Edge)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AgentMark(agent.icon, 17.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(agent.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            selectedHost?.let { host ->
                                HourlyQuotaLabel(quota = quotaFor(host, agent))
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelRaised)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AgentMark(agent.icon, 17.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(agent.name, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (index > 0) {
                            IconButton(onClick = { onMoveAgent(index, -1) }, modifier = Modifier.size(28.dp)) {
                                Text("↑", color = White, fontSize = 14.sp)
                            }
                        }
                        if (index < agents.lastIndex) {
                            IconButton(onClick = { onMoveAgent(index, 1) }, modifier = Modifier.size(28.dp)) {
                                Text("↓", color = White, fontSize = 14.sp)
                            }
                        }
                        IconButton(onClick = { onEditAgent(agent) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑", tint = White, modifier = Modifier.size(15.dp))
                        }
                        IconButton(onClick = { onDeleteAgent(agent) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除", tint = Coral, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
            if (editMode) {
                OutlinedButton(
                    onClick = onAddAgent,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Edge)
                ) {
                    Text("＋ 开发者", color = Mint, fontSize = 12.sp)
                }
            }
        }

        // Section: 工作区 · 点击直达
        val directories = selectedHost?.directories.orEmpty()
        SectionTitle(title = "工作区 · 点击直达", trailing = if (directories.isEmpty()) "" else "${directories.size} 个")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (directories.isEmpty()) {
                Surface(
                    onClick = { selectedHost?.let { onAddDirectory(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = PanelRaised,
                    border = BorderStroke(1.dp, Edge)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("＋ 添加开发目录", color = Mint, fontSize = 12.sp)
                    }
                }
            } else {
                directories.forEachIndexed { index, dir ->
                    if (!editMode) {
                        Surface(
                            onClick = {
                                val runningIndex = sessions.indexOfFirst { it.task.host.id == selectedHost?.id && it.task.directory.id == dir.id }
                                if (runningIndex >= 0) {
                                    onSelectSession(runningIndex)
                                } else if (selectedHost != null && selectedAgent != null) {
                                    onStartTask(selectedHost, dir, selectedAgent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = PanelRaised,
                            border = BorderStroke(1.dp, Edge)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(dir.icon.ifBlank { "📁" }, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dir.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(dir.path, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PanelRaised)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dir.name, color = White, fontSize = 13.sp, maxLines = 1)
                                Text(dir.path, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            }
                            if (index > 0) {
                                IconButton(onClick = { selectedHost?.let { onMoveDirectory(it, index, -1) } }, modifier = Modifier.size(28.dp)) {
                                    Text("↑", color = White, fontSize = 14.sp)
                                }
                            }
                            if (index < directories.lastIndex) {
                                IconButton(onClick = { selectedHost?.let { onMoveDirectory(it, index, 1) } }, modifier = Modifier.size(28.dp)) {
                                    Text("↓", color = White, fontSize = 14.sp)
                                }
                            }
                            IconButton(onClick = { selectedHost?.let { onEditDirectory(it, dir) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = White, modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = { selectedHost?.let { onDeleteDirectory(it, dir) } }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "删除", tint = Coral, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                if (editMode) {
                    OutlinedButton(
                        onClick = { selectedHost?.let { onAddDirectory(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Edge)
                    ) {
                        Text("＋ 工作区", color = Mint, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PadWorkspaceMain(
    modifier: Modifier = Modifier,
    sessions: List<TerminalSessionState>,
    activePage: Int,
    tiled: Boolean,
    sidebarVisible: Boolean,
    onToggleSidebar: () -> Unit,
    onToggleTiled: (Boolean) -> Unit,
    onSelectSession: (Int) -> Unit,
    onMinimizeSession: (TerminalSessionState) -> Unit,
    onCloseSession: (TerminalSessionState) -> Unit,
    onRenameSession: (TerminalSessionState) -> Unit,
    selectedHost: HostProfile?,
    selectedAgent: AgentPreset?,
    onStartTask: (HostProfile, ProjectDirectory, AgentPreset) -> Unit,
) {
    val visibleSessionEntries = sessions.withIndex().filterNot { it.value.isMinimized }
    val visibleSessions = visibleSessionEntries.map { it.value }
    val originalSessionIndex: (Int) -> Int = { visibleIndex -> visibleSessionEntries[visibleIndex].index }
    val activeVisibleIndex = visibleSessionEntries.indexOfFirst { it.index == activePage }.coerceAtLeast(0)
    Column(modifier = modifier.background(Ink)) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Panel)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleSidebar, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Menu, contentDescription = "切换侧边栏", tint = if (sidebarVisible) Mint else White)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = "工作区",
                color = White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${sessions.size} / 8",
                color = Muted,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            // Capsule Mode Switcher: [ 平铺模式 | 标签模式 ]
            Row(
                modifier = Modifier
                    .width(148.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelRaised)
                    .border(1.dp, Edge, RoundedCornerShape(16.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tiled Mode Pill
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (tiled) Mint else Color.Transparent)
                        .clickable { onToggleTiled(true) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.GridView, contentDescription = null, tint = if (tiled) Ink else Muted, modifier = Modifier.size(13.dp))
                    Text("平铺", color = if (tiled) Ink else Muted, fontSize = 11.sp, fontWeight = if (tiled) FontWeight.Bold else FontWeight.Normal)
                }
                // Tab Mode Pill
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (!tiled) Mint else Color.Transparent)
                        .clickable { onToggleTiled(false) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.ViewAgenda, contentDescription = null, tint = if (!tiled) Ink else Muted, modifier = Modifier.size(13.dp))
                    Text("标签", color = if (!tiled) Ink else Muted, fontSize = 11.sp, fontWeight = if (!tiled) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Divider(color = Edge)

        // Body Content
        if (visibleSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Edge, modifier = Modifier.size(64.dp))
                    Text(
                        if (sessions.isEmpty()) "暂无活动终端" else "所有终端都已最小化",
                        color = White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (sessions.isEmpty()) "从左侧工作区列表中点击项目目录，即可直接启动远程终端并连接 AI Agent。"
                        else "从左侧工作实例列表中点击实例，即可恢复终端。",
                        color = Muted,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(max = 400.dp)
                    )
                    val dirs = selectedHost?.directories.orEmpty()
                    if (selectedHost != null && selectedAgent != null && dirs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("快捷启动：", color = White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            dirs.take(4).forEach { dir ->
                                OutlinedButton(
                                    onClick = { onStartTask(selectedHost, dir, selectedAgent) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Mint)
                                ) {
                                    Text("${dir.icon.ifBlank { "📁" }} ${dir.name}", color = Mint, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else if (!tiled) {
            // Tab Mode
            val currentIndex = activeVisibleIndex.coerceIn(0, visibleSessions.lastIndex)
            val currentSession = visibleSessions[currentIndex]
            val currentSessionIndex = originalSessionIndex(currentIndex)
            // Tabs Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                visibleSessions.forEachIndexed { index, session ->
                    val sessionIndex = originalSessionIndex(index)
                    val selected = sessionIndex == currentSessionIndex
                    SessionTab(
                        session = session,
                        selected = selected,
                        onClick = { onSelectSession(sessionIndex) },
                        onRename = { onRenameSession(session) },
                        onClose = { onCloseSession(session) },
                        modifier = Modifier.widthIn(min = 145.dp, max = 220.dp),
                    )
                }
            }
            Divider(color = Edge)
            Row(
                modifier = Modifier.fillMaxWidth().background(PanelRaised).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentMark(currentSession.task.agent.icon, 16.dp)
                Text(
                    sessionDisplayLabel(currentSession.task), color = White, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Divider(color = Edge)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TerminalPage(session = currentSession, isCurrent = true)
            }
        } else {
            // Tiled Mode
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
            Box(Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                when {
                    visibleSessions.size == 1 -> {
                        TerminalTileCard(
                            session = visibleSessions[0],
                            isCurrent = true,
                            onSelect = { onSelectSession(originalSessionIndex(0)) },
                            onRename = { onRenameSession(visibleSessions[0]) },
                            onMinimize = { onMinimizeSession(visibleSessions[0]) },
                            onClose = { onCloseSession(visibleSessions[0]) },
                            onMaximize = { onSelectSession(originalSessionIndex(0)); onToggleTiled(false) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    visibleSessions.size == 2 -> {
                        if (isLandscape) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TerminalTileCard(
                                    session = visibleSessions[0],
                                    isCurrent = (activePage == originalSessionIndex(0)),
                                    onSelect = { onSelectSession(originalSessionIndex(0)) },
                                    onRename = { onRenameSession(visibleSessions[0]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[0]) },
                                    onClose = { onCloseSession(visibleSessions[0]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(0)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                TerminalTileCard(
                                    session = visibleSessions[1],
                                    isCurrent = (activePage == originalSessionIndex(1)),
                                    onSelect = { onSelectSession(originalSessionIndex(1)) },
                                    onRename = { onRenameSession(visibleSessions[1]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[1]) },
                                    onClose = { onCloseSession(visibleSessions[1]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(1)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TerminalTileCard(
                                    session = visibleSessions[0],
                                    isCurrent = (activePage == originalSessionIndex(0)),
                                    onSelect = { onSelectSession(originalSessionIndex(0)) },
                                    onRename = { onRenameSession(visibleSessions[0]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[0]) },
                                    onClose = { onCloseSession(visibleSessions[0]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(0)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                TerminalTileCard(
                                    session = visibleSessions[1],
                                    isCurrent = (activePage == originalSessionIndex(1)),
                                    onSelect = { onSelectSession(originalSessionIndex(1)) },
                                    onRename = { onRenameSession(visibleSessions[1]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[1]) },
                                    onClose = { onCloseSession(visibleSessions[1]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(1)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                            }
                        }
                    }
                    visibleSessions.size in 3..4 -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TerminalTileCard(
                                    session = visibleSessions[0],
                                    isCurrent = (activePage == originalSessionIndex(0)),
                                    onSelect = { onSelectSession(originalSessionIndex(0)) },
                                    onRename = { onRenameSession(visibleSessions[0]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[0]) },
                                    onClose = { onCloseSession(visibleSessions[0]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(0)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                TerminalTileCard(
                                    session = visibleSessions[1],
                                    isCurrent = (activePage == originalSessionIndex(1)),
                                    onSelect = { onSelectSession(originalSessionIndex(1)) },
                                    onRename = { onRenameSession(visibleSessions[1]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[1]) },
                                    onClose = { onCloseSession(visibleSessions[1]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(1)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TerminalTileCard(
                                    session = visibleSessions[2],
                                    isCurrent = (activePage == originalSessionIndex(2)),
                                    onSelect = { onSelectSession(originalSessionIndex(2)) },
                                    onRename = { onRenameSession(visibleSessions[2]) },
                                    onMinimize = { onMinimizeSession(visibleSessions[2]) },
                                    onClose = { onCloseSession(visibleSessions[2]) },
                                    onMaximize = { onSelectSession(originalSessionIndex(2)); onToggleTiled(false) },
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                                if (visibleSessions.size > 3) {
                                    TerminalTileCard(
                                        session = visibleSessions[3],
                                        isCurrent = (activePage == originalSessionIndex(3)),
                                        onSelect = { onSelectSession(originalSessionIndex(3)) },
                                        onRename = { onRenameSession(visibleSessions[3]) },
                                        onMinimize = { onMinimizeSession(visibleSessions[3]) },
                                        onClose = { onCloseSession(visibleSessions[3]) },
                                        onMaximize = { onSelectSession(originalSessionIndex(3)); onToggleTiled(false) },
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(visibleSessions, key = { _, s -> s.task.id }) { idx, session ->
                                TerminalTileCard(
                                    session = session,
                                    isCurrent = (activePage == originalSessionIndex(idx)),
                                    onSelect = { onSelectSession(originalSessionIndex(idx)) },
                                    onRename = { onRenameSession(session) },
                                    onMinimize = { onMinimizeSession(session) },
                                    onClose = { onCloseSession(session) },
                                    onMaximize = { onSelectSession(originalSessionIndex(idx)); onToggleTiled(false) },
                                    modifier = Modifier.height(340.dp).fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadWorkspace(
    hosts: List<HostProfile>,
    agents: List<AgentPreset>,
    sessions: List<TerminalSessionState>,
    selectedHost: HostProfile?,
    selectedAgent: AgentPreset?,
    activePage: Int,
    tiled: Boolean,
    onToggleTiled: (Boolean) -> Unit,
    onSelectHost: (HostProfile) -> Unit,
    onSelectAgent: (AgentPreset) -> Unit,
    onSelectSession: (Int) -> Unit,
    onStartTask: (HostProfile, ProjectDirectory, AgentPreset) -> Unit,
    onCloseSession: (TerminalSessionState) -> Unit,
    onRenameSession: (TerminalSessionState) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    syncLoading: Boolean,
    offlineMode: Boolean,
    autoSyncEnabled: Boolean,
    quotaFor: (HostProfile, AgentPreset) -> List<QuotaWindow>?,
    onAddHost: () -> Unit,
    onEditHost: (HostProfile) -> Unit,
    onDeleteHost: (HostProfile) -> Unit,
    onMoveHost: (Int, Int) -> Unit,
    onAddDirectory: (HostProfile) -> Unit,
    onEditDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onDeleteDirectory: (HostProfile, ProjectDirectory) -> Unit,
    onMoveDirectory: (HostProfile, Int, Int) -> Unit,
    onAddAgent: () -> Unit,
    onEditAgent: (AgentPreset) -> Unit,
    onDeleteAgent: (AgentPreset) -> Unit,
    onMoveAgent: (Int, Int) -> Unit,
) {
    var sidebarVisible by rememberSaveable { mutableStateOf(true) }
    val minimizeSession: (TerminalSessionState) -> Unit = { session ->
        session.isMinimized = true
        if (sessions.getOrNull(activePage)?.task?.id == session.task.id) {
            sessions.indexOfFirst { !it.isMinimized }.takeIf { it >= 0 }?.let(onSelectSession)
        }
    }
    Row(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = sidebarVisible,
            enter = expandHorizontally(),
            exit = shrinkHorizontally()
        ) {
            Row(Modifier.width(300.dp).fillMaxHeight()) {
                PadSidebar(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    hosts = hosts,
                    agents = agents,
                    sessions = sessions,
                    tiled = tiled,
                    selectedHost = selectedHost,
                    selectedAgent = selectedAgent,
                    activePage = activePage,
                    onSelectHost = onSelectHost,
                    onSelectAgent = onSelectAgent,
                    onSelectSession = onSelectSession,
                    onStartTask = onStartTask,
                    onCloseSession = onCloseSession,
                    onRenameSession = onRenameSession,
                    onOpenSettings = onOpenSettings,
                    onRefresh = onRefresh,
                    syncLoading = syncLoading,
                    offlineMode = offlineMode,
                    autoSyncEnabled = autoSyncEnabled,
                    quotaFor = quotaFor,
                    onAddHost = onAddHost,
                    onEditHost = onEditHost,
                    onDeleteHost = onDeleteHost,
                    onMoveHost = onMoveHost,
                    onAddDirectory = onAddDirectory,
                    onEditDirectory = onEditDirectory,
                    onDeleteDirectory = onDeleteDirectory,
                    onMoveDirectory = onMoveDirectory,
                    onAddAgent = onAddAgent,
                    onEditAgent = onEditAgent,
                    onDeleteAgent = onDeleteAgent,
                    onMoveAgent = onMoveAgent,
                )
                Divider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = Edge
                )
            }
        }
        PadWorkspaceMain(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            sessions = sessions,
            activePage = activePage,
            tiled = tiled,
            sidebarVisible = sidebarVisible,
            onToggleSidebar = { sidebarVisible = !sidebarVisible },
            onToggleTiled = onToggleTiled,
            onSelectSession = onSelectSession,
            onMinimizeSession = minimizeSession,
            onCloseSession = onCloseSession,
            onRenameSession = onRenameSession,
            selectedHost = selectedHost,
            selectedAgent = selectedAgent,
            onStartTask = onStartTask,
        )
    }
}

@Composable
private fun SettingsScreen(
    store: LocalStore,
    hosts: List<HostProfile> = emptyList(),
    agents: List<AgentPreset> = emptyList(),
    syncUrl: String,
    syncToken: String,
    offlineMode: Boolean,
    autoSyncEnabled: Boolean,
    syncIntervalSeconds: Int,
    onOfflineModeChange: (Boolean) -> Unit,
    onAutoSyncEnabledChange: (Boolean) -> Unit,
    onSyncIntervalSecondsChange: (Int) -> Unit,
    syncUsername: String,
    onSyncSettingsChange: (String, String) -> Unit,
    onLogin: () -> Unit,
    enabled: Boolean,
    delaySeconds: Int,
    quotaIntervalSeconds: Int,
    onQuotaIntervalChange: (Int) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelaySecondsChange: (Int) -> Unit,
    deviceLayoutMode: String,
    onDeviceLayoutModeChange: (String) -> Unit,
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var publicKey by remember { mutableStateOf(store.sshPublicKey()) }
    var generating by remember { mutableStateOf(false) }
    var keyMessage by remember { mutableStateOf("") }
    var draftSyncUrl by rememberSaveable(syncUrl) { mutableStateOf(syncUrl) }
    var draftSyncInterval by remember(syncIntervalSeconds) { mutableStateOf(syncIntervalSeconds.toFloat()) }
    var draftDelay by remember(delaySeconds) { mutableStateOf(delaySeconds.toFloat()) }
    var draftQuotaInterval by remember(quotaIntervalSeconds) { mutableStateOf(quotaIntervalSeconds.toFloat()) }
    fun finish() {
        onSyncSettingsChange(draftSyncUrl.trim(), syncToken)
        onBack()
    }
    BackHandler { finish() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finish() }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = White) }
            Column(Modifier.weight(1f)) {
                Text("设置", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("管理布局、密钥、同步与自动回车", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = { finish() }) { Text("完成", color = Mint) }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("界面与设备模式", color = White, fontWeight = FontWeight.SemiBold)
            Text("根据设备类型选择布局。Pad 模式按 PC 端的双栏侧边栏、工作区直达和平铺/标签交互运行。", color = Muted, fontSize = 12.sp)
            var draftDeviceLayoutMode by remember(deviceLayoutMode) { mutableStateOf(deviceLayoutMode) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Panel)
                    .border(1.dp, Edge, RoundedCornerShape(24.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val options = listOf(
                    "auto" to "自动检测",
                    "pad" to "Pad 模式",
                    "phone" to "手机模式"
                )
                options.forEach { (mode, label) ->
                    val selected = draftDeviceLayoutMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Mint else Color.Transparent)
                            .clickable {
                                draftDeviceLayoutMode = mode
                                onDeviceLayoutModeChange(mode)
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Ink else White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                text = when (draftDeviceLayoutMode) {
                    "pad" -> "已强制开启 Pad 模式：启用 PC 端双栏侧边栏、工作区直接连接及平铺/标签交互。"
                    "phone" -> "已强制开启手机模式：采用单栏紧凑视图与独立工作区导航。"
                    else -> if (isTablet) "当前设备识别为 Pad / 平板设备（宽屏），自动使用 PC 布局与交互。" else "当前设备识别为手机，自动使用单栏模式。"
                },
                color = Mint,
                fontSize = 11.sp
            )
            Divider()
            Text("SSH 密钥", color = White, fontWeight = FontWeight.SemiBold)
            Text("生成本机密钥后，将公钥添加到服务器用户的 ~/.ssh/authorized_keys。连接时会优先尝试密钥认证。", color = Muted, fontSize = 12.sp)
            if (publicKey == null) {
                FilledTonalButton(enabled = !generating, onClick = {
                    generating = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { store.generateSshKey() } }
                        result.onSuccess { publicKey = it; keyMessage = "密钥已生成" }
                            .onFailure { keyMessage = "生成失败：${it.localizedMessage ?: "未知错误"}" }
                        generating = false
                    }
                }) { Text(if (generating) "正在生成…" else "生成本机密钥") }
            } else {
                Text(publicKey.orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                FilledTonalButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SSH 公钥", publicKey.orEmpty()))
                    keyMessage = "公钥已复制"
                }) { Text("复制公钥") }
            }
            if (keyMessage.isNotEmpty()) Text(keyMessage, color = Mint, fontSize = 12.sp)
            Divider()
            Text("系统同步", color = White, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(draftSyncUrl, { draftSyncUrl = it }, label = { Text("同步服务地址") }, placeholder = { Text("https://地址:端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(when {
                offlineMode -> "离线模式（仅本机独立使用）"
                syncUsername.isBlank() -> "未登录"
                else -> "当前账号：$syncUsername"
            }, color = Muted, fontSize = 12.sp)
            FilledTonalButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录同步账号") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("离线模式", color = White, fontSize = 13.sp)
                    Text("开启后仅本机独立使用，不进行任何服务器同步。", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = offlineMode, onCheckedChange = onOfflineModeChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("跨设备自动同步", color = White, fontSize = 13.sp)
                    Text("默认关闭，仅控制本机；主机配置手动保存仍会上传。", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = autoSyncEnabled, onCheckedChange = onAutoSyncEnabledChange)
            }
            Text("自动同步周期：${((draftSyncInterval / 5f).roundToInt() * 5).coerceIn(5, 300)} 秒", color = White, fontSize = 13.sp)
            Slider(
                value = draftSyncInterval,
                onValueChange = { draftSyncInterval = it },
                onValueChangeFinished = { onSyncIntervalSecondsChange(((draftSyncInterval / 5f).roundToInt() * 5).coerceIn(5, 300)) },
                valueRange = 5f..300f,
                steps = 58,
            )
            Divider()
            Text("开发者额度", color = White, fontWeight = FontWeight.SemiBold)
            val quotaSeconds = ((draftQuotaInterval / 5f).roundToInt() * 5).coerceIn(15, 300)
            Text("刷新周期 ${quotaSeconds} 秒", color = White, fontSize = 13.sp)
            Slider(
                value = draftQuotaInterval,
                onValueChange = { draftQuotaInterval = it },
                onValueChangeFinished = { onQuotaIntervalChange(((draftQuotaInterval / 5f).roundToInt() * 5).coerceIn(15, 300)) },
                valueRange = 15f..300f,
                steps = 56,
            )
            Divider()
            Text("自动回车", color = White, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("对默认“是”或选中允许的确认提示自动回车", color = White, fontSize = 13.sp)
                    Text("断线时暂停倒计时，恢复连接后重新等待；[y/N] 等默认否的提示需要手动确认", color = Muted, fontSize = 11.sp)
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
            Divider()
            Text("贾维斯 (JARVIS) 智能语音助手", color = White, fontWeight = FontWeight.SemiBold)
            Text("全程黑屏通过蓝牙耳机语音交互，低功耗一直在线、零 Token 毫秒级推进任务。", color = Muted, fontSize = 12.sp)

            var jarvisEnabled by remember { mutableStateOf(store.loadJarvisEnabled()) }
            var jarvisWakeWord by remember { mutableStateOf(store.loadJarvisWakeWord()) }
            var jarvisNotifyFinished by remember { mutableStateOf(store.loadJarvisNotifyFinished()) }
            var jarvisBluetoothSco by remember { mutableStateOf(store.loadJarvisBluetoothSco()) }
            var jarvisAsrMode by remember { mutableStateOf(store.loadJarvisAsrMode()) }
            var jarvisTargetHostId by remember { mutableStateOf(store.loadJarvisTargetHostId()) }
            var jarvisTargetAgentCmd by remember { mutableStateOf(store.loadJarvisTargetAgentCommand()) }
            var jarvisModel by remember { mutableStateOf(store.loadJarvisModel()) }
            var jarvisEffort by remember { mutableStateOf(store.loadJarvisEffort()) }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("启用贾维斯语音控制", color = White, fontSize = 13.sp)
                    Text("启用麦克风低功耗监听与唤醒词检测，支持放入口袋全盲操", color = Muted, fontSize = 11.sp)
                }
                val jarvisPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true ||
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (audioGranted) {
                        jarvisEnabled = true
                        store.saveJarvisEnabled(true)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.toggleEnabled(true)
                    } else {
                        jarvisEnabled = false
                        store.saveJarvisEnabled(false)
                        keyMessage = "需要麦克风权限才能响应「贾维斯」唤醒"
                    }
                }
                Switch(checked = jarvisEnabled, onCheckedChange = { wantEnable ->
                    if (!wantEnable) {
                        jarvisEnabled = false
                        store.saveJarvisEnabled(false)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.toggleEnabled(false)
                    } else {
                        val audioOk = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        val btOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (audioOk && btOk) {
                            jarvisEnabled = true
                            store.saveJarvisEnabled(true)
                            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.toggleEnabled(true)
                        } else {
                            val reqs = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                reqs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
                            }
                            jarvisPermissionLauncher.launch(reqs.toTypedArray())
                        }
                    }
                })
            }

            if (jarvisEnabled) {
                val jarvisDiagStatus by dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.diagnosticStatus.collectAsState()
                OutlinedTextField(
                    value = jarvisWakeWord,
                    onValueChange = {
                        jarvisWakeWord = it
                        store.saveJarvisWakeWord(it)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.wakeWord = it
                    },
                    label = { Text("唤醒词 (默认：贾维斯)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "监听状态：$jarvisDiagStatus",
                    color = Mint,
                    fontSize = 11.sp
                )

                Text("借力开发者能力", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val selectedHostLabel = hosts.firstOrNull { it.id == jarvisTargetHostId }?.name ?: "自动/当前主机"
                val selectedAgentLabel = agents.firstOrNull { it.command == jarvisTargetAgentCmd }?.name ?: "自动/当前开发者"
                Text(
                    "选择特定主机下的开发者供贾维斯调用（当前：$selectedHostLabel · $selectedAgentLabel）",
                    color = Muted,
                    fontSize = 11.sp
                )
                Text("目标主机", color = Muted, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    @Composable
                    fun ChoicePill(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
                        Surface(
                            color = if (selected) Mint.copy(alpha = 0.2f) else Panel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (selected) Mint else Edge),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                content()
                            }
                        }
                    }

                    val autoSelected = jarvisTargetHostId.isBlank()
                    ChoicePill(
                        selected = autoSelected,
                        onClick = {
                            jarvisTargetHostId = ""
                            store.saveJarvisTargetHostId("")
                            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetHostId = ""
                        }
                    ) { Text("自动", color = if (autoSelected) Mint else White, fontSize = 12.sp) }
                    hosts.forEach { h ->
                        val isSel = jarvisTargetHostId == h.id
                        ChoicePill(
                            selected = isSel,
                            onClick = {
                                jarvisTargetHostId = h.id
                                store.saveJarvisTargetHostId(h.id)
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetHostId = h.id
                            }
                        ) { Text("${h.icon} ${h.name}".trim(), color = if (isSel) Mint else White, fontSize = 12.sp) }
                    }
                }
                Text("目标开发者", color = Muted, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    @Composable
                    fun AgentPill(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
                        Surface(
                            color = if (selected) Mint.copy(alpha = 0.2f) else Panel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (selected) Mint else Edge),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                content()
                            }
                        }
                    }

                    val autoAgentSel = jarvisTargetAgentCmd.isBlank()
                    AgentPill(
                        selected = autoAgentSel,
                        onClick = {
                            jarvisTargetAgentCmd = ""
                            store.saveJarvisTargetAgentCommand("")
                            dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetAgentCommand = ""
                        }
                    ) { Text("自动", color = if (autoAgentSel) Mint else White, fontSize = 12.sp) }
                    agents.forEach { a ->
                        val isSel = jarvisTargetAgentCmd == a.command
                        AgentPill(
                            selected = isSel,
                            onClick = {
                                jarvisTargetAgentCmd = a.command
                                store.saveJarvisTargetAgentCommand(a.command)
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetAgentCommand = a.command
                                dev.remote.terminal.jarvis.coordinator.BackgroundDeveloperBridge.resetWorkers()
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.warmupDeveloperWorker()
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                AgentMark(a.icon, 14.dp)
                                Text(a.name, color = if (isSel) Mint else White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Text("模型选择", color = Muted, fontSize = 11.sp)
                val modelPresets = listOf(
                    "" to "默认",
                    "Gemini 3.8 Flash" to "Flash 3.8 (极速)",
                    "Gemini 4 Hydrogen" to "Hydrogen 4",
                    "Gemini 4 Neon" to "Neon 4",
                    "Claude Sonnet 5.5" to "Sonnet 5.5",
                    "gpt-6-luna" to "Codex 6 Luna",
                    "gpt-5.6-luna" to "Codex 5.6 Luna",
                    "gpt-5.6-terra" to "Codex 5.6 Terra"
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modelPresets.forEach { (value, label) ->
                        val isSel = jarvisModel == value
                        Surface(
                            color = if (isSel) Mint.copy(alpha = 0.2f) else Panel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSel) Mint else Edge),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                jarvisModel = value
                                store.saveJarvisModel(value)
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetModel = value
                                dev.remote.terminal.jarvis.coordinator.BackgroundDeveloperBridge.resetWorkers()
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.warmupDeveloperWorker()
                            }
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Text(label, color = if (isSel) Mint else White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = jarvisModel,
                    onValueChange = {
                        jarvisModel = it
                        store.saveJarvisModel(it)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetModel = it
                    },
                    label = { Text("自定义模型名称 (留空走开发者默认)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("推理思考等级 (Effort)", color = Muted, fontSize = 11.sp)
                val effortPresets = listOf("low" to "低 (极速)", "medium" to "中", "high" to "高", "xhigh" to "极高", "max" to "极限", "auto" to "默认")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    effortPresets.forEach { (value, label) ->
                        val isSel = jarvisEffort == value
                        Surface(
                            color = if (isSel) Mint.copy(alpha = 0.2f) else Panel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSel) Mint else Edge),
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                jarvisEffort = value
                                store.saveJarvisEffort(value)
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.targetEffort = value
                                dev.remote.terminal.jarvis.coordinator.BackgroundDeveloperBridge.resetWorkers()
                                dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.warmupDeveloperWorker()
                            }
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Text(label, color = if (isSel) Mint else White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("蓝牙耳机 SCO 通话通道", color = White, fontSize = 13.sp)
                        Text("仅在拾音时短暂开启蓝牙通道，播报使用高清媒体通道稳定音量", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = jarvisBluetoothSco, onCheckedChange = {
                        jarvisBluetoothSco = it
                        store.saveJarvisBluetoothSco(it)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.bluetoothScoEnabled = it
                    })
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("任务完成主动耳语通报", color = White, fontSize = 13.sp)
                        Text("开发任务执行完毕退出时，耳机主动汇报完成状态", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = jarvisNotifyFinished, onCheckedChange = {
                        jarvisNotifyFinished = it
                        store.saveJarvisNotifyFinished(it)
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.config.notifyOnTaskFinished = it
                    })
                }

                FilledTonalButton(
                    onClick = {
                        dev.remote.terminal.jarvis.coordinator.JarvisCoordinator.triggerManualWake(speakTestPhrase = true)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试唤醒与发音")
                }
            }
            Divider()
            Text("后台保活与熄屏运行", color = White, fontWeight = FontWeight.SemiBold)
            val isIgnoringBattery = remember { mutableStateOf(TerminalKeepAliveService.isIgnoringBatteryOptimizations(context)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("无限制后台运行 / 忽略电池优化", color = White, fontSize = 13.sp)
                    Text(
                        if (isIgnoringBattery.value) "系统已允许无限制后台运行，熄屏后终端与自动确认持续生效。"
                        else "系统可能会在手机熄屏后冻结网络或限制后台定时器，建议开启以保证熄屏自动确认。",
                        color = if (isIgnoringBattery.value) Mint else Coral, fontSize = 11.sp
                    )
                }
                if (!isIgnoringBattery.value) {
                    FilledTonalButton(onClick = {
                        TerminalKeepAliveService.requestIgnoreBatteryOptimizations(context)
                    }) {
                        Text("去设置")
                    }
                } else {
                    Text("已允许", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HostEditorDialog(initial: HostProfile?, onDismiss: () -> Unit, onSave: (HostProfile) -> Unit) {
    val initialLan = initial?.lanAddress.orEmpty()
    val initialExt = initial?.externalAddress.orEmpty()
    val initialAddr = initial?.address.orEmpty()
    val defaultLan = if (initialLan.isBlank() && initialExt.isBlank()) initialAddr else initialLan
    val defaultPort = initial?.port?.takeIf { it in 1..65535 }?.toString().orEmpty()
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var lanAddress by remember(initial) { mutableStateOf(defaultLan) }
    var lanPort by remember(initial) { mutableStateOf(if (initialLan.isBlank() && initialExt.isBlank()) defaultPort else initial?.lanPort?.takeIf { it in 1..65535 }?.toString().orEmpty().ifBlank { defaultPort }) }
    var externalAddress by remember(initial) { mutableStateOf(initialExt) }
    var externalPort by remember(initial) { mutableStateOf(initial?.externalPort?.takeIf { it in 1..65535 }?.toString().orEmpty().ifBlank { defaultPort }) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var icon by remember(initial) { mutableStateOf(initial?.icon ?: "⌘") }
    val primary = lanAddress.trim().ifBlank { externalAddress.trim() }
    val parsedLanPort = lanPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val parsedExternalPort = externalPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val portsValid = (lanAddress.isBlank() || parsedLanPort != null) && (externalAddress.isBlank() || parsedExternalPort != null)
    EditorDialogFrame(if (initial == null) "添加主机" else "编辑主机", onDismiss, canSave = name.isNotBlank() && primary.isNotBlank() && username.isNotBlank() && portsValid, onConfirm = {
        val lan = lanAddress.trim()
        val external = externalAddress.trim()
        val lPort = parsedLanPort ?: parsedExternalPort ?: 0
        val ePort = parsedExternalPort ?: parsedLanPort ?: 0
        val legacyPort = if (lan.isNotBlank()) lPort else ePort
        onSave(HostProfile(initial?.id ?: UUID.randomUUID().toString(), name.trim(), lan.ifBlank { external }, legacyPort, username.trim(), password, icon.ifBlank { "⌘" }, initial?.directories.orEmpty(), initial?.syncFingerprint.orEmpty(), initial?.syncId.orEmpty(), lan, external, initial?.legacyAddress ?: initial?.address.orEmpty(), lanPort = lPort, externalPort = ePort))
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("主机名称") }, placeholder = { Text("例如：Mac Studio") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        IconPicker(icon, { icon = it }, listOf("⌘", "🖥️", "☁️", "🐧", "⚡", "🧪", "🏠", "💻", "🔧", "🌐"))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(lanAddress, { lanAddress = it }, label = { Text("内网地址（优先）") }, placeholder = { Text("例如：192.168.1.20") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(lanPort, { lanPort = it.filter(Char::isDigit).take(5) }, label = { Text("内网端口") }, placeholder = { Text("必填") }, singleLine = true, modifier = Modifier.width(96.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(externalAddress, { externalAddress = it }, label = { Text("外网地址（备用）") }, placeholder = { Text("域名或公网 IP") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(externalPort, { externalPort = it.filter(Char::isDigit).take(5) }, label = { Text("外网端口") }, placeholder = { Text("必填") }, singleLine = true, modifier = Modifier.width(96.dp))
        }
        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("优先连接内网地址；无法连接时自动切换外网地址。主机配置会按同步账号分发。", color = Muted, fontSize = 11.sp)
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
    var pendingApproval by remember(host.id) { mutableStateOf<HostKeyApprovalRequiredException?>(null) }
    var directoryQuery by remember(host.id) { mutableStateOf("") }
    val filteredDirectories = remember(directories, directoryQuery) {
        if (directoryQuery.isBlank()) directories
        else directories.filter { it.name.contains(directoryQuery.trim(), ignoreCase = true) }
    }
    val scope = rememberCoroutineScope()

    fun loadDirectory(path: String) {
        currentPath = path
        directories = emptyList()
        directoryQuery = ""
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
                pendingApproval = approval
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { loadDirectory(remoteParentPath(currentPath)) }, enabled = !loading && currentPath != "/") {
                        Text("上一级", color = if (loading || currentPath == "/") Muted else Mint)
                    }
                    TextButton(onClick = { loadDirectory("/") }, enabled = !loading && currentPath != "/") {
                        Text("根目录", color = if (loading || currentPath == "/") Muted else Mint)
                    }
                    OutlinedTextField(
                        value = directoryQuery,
                        onValueChange = { directoryQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索文件夹", fontSize = 11.sp, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(17.dp)) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp),
                        shape = RoundedCornerShape(10.dp),
                    )
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
                if (!loading && errorMessage == null && directories.isNotEmpty() && filteredDirectories.isEmpty()) {
                    Text("当前路径下没有匹配的文件夹。", color = Muted, fontSize = 11.sp)
                }
                LazyColumn(Modifier.heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(filteredDirectories, key = { it.path }) { directory ->
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

    pendingApproval?.let { approval ->
        AlertDialog(
            onDismissRequest = {
                pendingApproval = null
                errorMessage = "未确认主机指纹，无法浏览目录。"
            },
            containerColor = Panel,
            title = { Text("首次连接这台主机？", color = White, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请核对 SSH 主机指纹。接受后会保存在本机，后续连接会自动校验。", color = Muted)
                    Text("${approval.address}:${approval.port}", color = White, fontWeight = FontWeight.Medium)
                    Text(approval.fingerprint, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.trustHostKey(approval.address, approval.port, approval.fingerprint)
                    pendingApproval = null
                    loadDirectory(currentPath)
                }) { Text("信任并继续", color = Mint) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingApproval = null
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
    EditorDialogFrame(if (initial == null) "添加开发者" else "编辑开发者", onDismiss, canSave = name.isNotBlank() && command.isNotBlank(), onConfirm = {
        onSave(AgentPreset(initial?.id ?: UUID.randomUUID().toString(), name.trim(), command.trim(), icon.ifBlank { "✳" }))
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, placeholder = { Text("例如：Codex") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(command, { command = it }, label = { Text("启动命令") }, placeholder = { Text("codex --full-auto") }, singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
        Text("支持带参数的命令（codex resume）和登录 Shell 别名（auto codex）；别名需在 SSH 用户的 Shell 配置中定义。", color = Muted, fontSize = 11.sp)
        AgentIconPicker(icon) { icon = it }
    }
}

private val agentBrandKey = Regex("[a-z0-9-]+")

@Composable
private fun AgentMark(value: String, size: Dp) {
    val assets = LocalContext.current.assets
    val bitmap = remember(assets, value) {
        val key = value.removePrefix("brand:")
        if (key != value && agentBrandKey.matches(key)) {
            runCatching {
                assets.open("agent-icons/$key.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        } else null
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        val symbol = if (value.isBlank() || value.startsWith("brand:")) "✳" else value
        Text(symbol, fontSize = (size.value * 0.85f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AgentIconPicker(value: String, onChange: (String) -> Unit) {
    val assets = LocalContext.current.assets
    val options = remember(assets) {
        runCatching {
            val catalog = assets.open("agent-icons/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
            catalog.keys().asSequence().map { "brand:$it" to catalog.getString(it) }.toList()
        }.getOrDefault(emptyList())
    }
    Text("图标", color = Muted, fontSize = 12.sp)
    for (row in options.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in row) {
                val selected = value == key
                Surface(
                    color = if (selected) Color(0xFF20342F) else PanelRaised,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (selected) Mint else Edge),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(key) }),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AgentMark(key, 21.dp)
                        Text(label, color = White, fontSize = 12.sp)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    val customIcon = value.takeUnless { it.startsWith("brand:") }.orEmpty()
    IconPicker(customIcon, onChange, listOf("✳", "◈", "⌘", "\$_", "⚙️", "🧠", "🤖", "✨", "🛠️"))
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
