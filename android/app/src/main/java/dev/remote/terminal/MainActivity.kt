package dev.remote.terminal

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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
import dev.remote.terminal.data.hasValidConfiguredPorts
import dev.remote.terminal.data.SessionStore
import dev.remote.terminal.data.SyncClient
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.data.ProjectDirectory
import dev.remote.terminal.data.TerminalTask
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
private val ActionRequestRegex = Regex("(?i)(approve (this|the)? ?(command|action)?|allow (this|the)? ?(command|action)?|would you like to|do you want to|run this command\\?|continue\\? ?\\[|\\[(y/n|yes/no)\\]|\\((y/n|yes/no)\\)|press\\s+(enter|return)\\s+to\\s+(continue|confirm|approve)|permission required|needs your approval|批准|是否允许|需要确认|是否继续)")
private val AutoEnterPromptRegex = Regex("(?:\\[Y/n\\]|\\(Y/n\\)|\\[Yes/no\\]|(?i:press\\s+(?:enter|return)\\s+to\\s+(?:continue|confirm|approve)))\\s*$")

private val SelectedApprovalRegex = Regex(
    "(?i)^\\s*[›❯>➜]\\s*(?:\\d+[.)]\\s*)?(?:yes\\b|allow\\b|approve\\b|proceed\\b|continue\\b|允许|批准|同意|继续|确认)"
)

private fun quotaLabel(minutes: Int, percent: Int): String {
    val period = when {
        minutes % 10080 == 0 -> "${minutes / 10080}周"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes}分"
    }
    return "$period $percent%"
}

private fun detectAutoEnterPromptSignature(text: String): String? {
    val visibleText = text.trimEnd()
    val lines = visibleText.lineSequence().filter { it.isNotBlank() }.toList()
    val promptIndex = lines.indexOfLast { AutoEnterPromptRegex.containsMatchIn(it) }
    if (promptIndex >= 0) {
        return lines.subList(maxOf(0, promptIndex - 4), promptIndex + 1).joinToString("\n")
    }
    // Interactive CLIs draw a menu and often put a keyboard hint below it.
    // Only accept an explicitly selected affirmative option in an approval prompt.
    val selectedIndex = lines.indexOfLast { it.trimStart().firstOrNull() in listOf('›', '❯', '>', '➜') }
    if (selectedIndex < 0 || !SelectedApprovalRegex.containsMatchIn(lines[selectedIndex])) return null
    val requestIndex = (selectedIndex - 1 downTo maxOf(0, selectedIndex - 20))
        .firstOrNull { ActionRequestRegex.containsMatchIn(lines[it]) } ?: return null
    return lines.subList(maxOf(0, requestIndex - 4), selectedIndex + 1).joinToString("\n")
}

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
    var task: TerminalTask,
    private val mainHandler: Handler,
    private val appContext: Context,
) : AutoConfirmEngine.SessionCallback {
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
    var codexQuota by mutableStateOf<List<Pair<Int, Int>>?>(null)
    var hostFingerprint by mutableStateOf<String?>(null)
    var hostKeyAddress by mutableStateOf("")
    var hostKeyPort by mutableStateOf(0)
    var resolveHostKey: ((Boolean) -> Unit)? = null
    var autoEnterDeadline by mutableStateOf<Long?>(null)
        private set
    lateinit var controller: SshTerminalController

    override fun isConnected(): Boolean = status.startsWith("已连接") && controller.isConnected()
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
        AutoConfirmEngine.cancel(task.id)
        autoEnterDeadline = null
        autoEnterAwaitingResponse = false
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

private enum class AppRoute { HOME, MANAGE, TERMINAL, AGENTS, SETTINGS, LOGIN }

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
        var route by rememberSaveable { mutableStateOf(if (store.loadSyncUrl().isNotBlank() && store.loadSyncToken().isBlank()) AppRoute.LOGIN else AppRoute.HOME) }
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
        AutoConfirmEngine.init(appContext)
        AutoConfirmEngine.globalEnabled = autoEnterEnabled
        AutoConfirmEngine.globalDelaySeconds = autoEnterDelaySeconds
        var syncUrl by remember { mutableStateOf(store.loadSyncUrl()) }
        var syncToken by remember { mutableStateOf(store.loadSyncToken()) }
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
        var sharedSessions by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
        var sharedHost by remember { mutableStateOf<HostProfile?>(null) }
        var startupShared by remember { mutableStateOf<List<Pair<HostProfile, org.json.JSONObject>>>(emptyList()) }
        var syncLoading by remember { mutableStateOf(false) }
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val dirtyWorkspaces = remember { store.loadDirtyWorkspaces().toMutableSet() }
        val dirtyHostProfiles = remember { store.loadDirtyHostProfiles().toMutableSet() }
        val workspaceVersions = remember { mutableMapOf<String, Int>() }
        val agentVersions = remember { mutableMapOf<String, Int>() }
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val selectedHost = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()
        val selectedAgent = agents.firstOrNull { it.id == selectedAgentId } ?: agents.firstOrNull()
        SideEffect { SyncClient.configure(syncUrl, syncToken) }

        fun selectHost(host: HostProfile) {
            selectedHostId = host.id
            val savedAgentId = store.loadHostSelectedAgent(host.id)
            if (savedAgentId.isNotBlank() && agents.any { it.id == savedAgentId }) {
                selectedAgentId = savedAgentId
            }
        }

        LaunchedEffect(selectedHostId) {
            val savedAgentId = store.loadHostSelectedAgent(selectedHostId)
            if (savedAgentId.isNotBlank() && agents.any { it.id == savedAgentId }) {
                selectedAgentId = savedAgentId
            }
        }

        fun saveHosts(updated: List<HostProfile>) {
            val previous = hosts.toList()
            val updatedIds = updated.mapTo(mutableSetOf()) { it.id }
            previous.filterNot { it.id in updatedIds }.forEach { HostRouteResolver.invalidate(it.id) }
            updated.forEach { host ->
                val oldHost = previous.firstOrNull { it.id == host.id }
                if (oldHost == null || oldHost.endpoints() != host.endpoints()) {
                    HostRouteResolver.invalidate(host.id)
                    scope.launch(Dispatchers.IO) { HostRouteResolver.resolveHostRoute(host, force = true) }
                }
            }
            hosts.clear(); hosts.addAll(updated); store.saveHosts(updated)
            if (selectedHostId !in updated.map { it.id }) selectedHostId = updated.firstOrNull()?.id.orEmpty()
        }

        fun syncHostProfile(host: HostProfile) {
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
            if (syncUrl.isBlank()) return
            val version = (workspaceVersions[host.id] ?: 0) + 1
            workspaceVersions[host.id] = version
            dirtyWorkspaces.add(host.id)
            store.saveDirtyWorkspaces(dirtyWorkspaces)
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
                        if (workspaceVersions[host.id] == version) {
                            store.saveWorkspaceRevision(identity, response.optInt("revision", baseRevision))
                            dirtyWorkspaces.remove(host.id)
                            store.saveDirtyWorkspaces(dirtyWorkspaces)
                            val array = response.optJSONArray("workspaces") ?: JSONArray()
                            val merged = (0 until array.length()).map { index ->
                                array.getJSONObject(index).let { ProjectDirectory(it.getString("id"), it.getString("name"), it.getString("path"), it.optString("icon", "📁")) }
                            }
                            hosts.firstOrNull { it.id == host.id }?.takeIf { it.directories != merged }?.let { current ->
                                saveHosts(hosts.map { if (it.id == current.id) it.copy(directories = merged) else it })
                            }
                        }
                    }
                    .onFailure { snackbars.showSnackbar("工作区同步失败，将自动重试：${it.message}") }
            }
        }

        fun syncAgentList(host: HostProfile, items: List<AgentPreset>) {
            if (syncUrl.isBlank()) return
            val identity = SyncClient.agentRevisionIdentity(store.loadSyncUsername())
            val version = (agentVersions[identity] ?: 0) + 1
            agentVersions[identity] = version
            scope.launch {
                runCatching {
                    withContext(SyncClient.dispatcher) {
                        val baseRevision = store.loadAgentRevision(identity).coerceAtLeast(0)
                        val response = SyncClient.saveAgents(host, items, baseRevision)
                        store.saveAgentRevision(identity, response.optInt("revision", baseRevision))
                        response
                    }
                }
                    .onSuccess { response ->
                        if (agentVersions[identity] == version) {
                            val merged = SyncClient.agentsFromSnapshot(response)
                            if (agents.toList() != merged) {
                                agents.clear(); agents.addAll(merged); store.saveAgents(merged)
                            }
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
                        username = profile.optString("username", local.username),
                        icon = profile.optString("icon", local.icon))
                }
            }.toMutableList()
            active.filterNot { it.optString("id") in matched }.forEach { profile ->
                val lan = profile.optString("lan_address")
                val external = profile.optString("external_address")
                val port = profile.getInt("port")
                val lanPort = if (profile.has("lan_port") && !profile.isNull("lan_port")) profile.getInt("lan_port") else port
                val externalPort = if (profile.has("external_port") && !profile.isNull("external_port")) profile.getInt("external_port") else port
                result.add(HostProfile(profile.optString("id"), profile.optString("name", "主机"), external.ifBlank { lan },
                    port, profile.optString("username"), icon = profile.optString("icon", "⌘"),
                    lanAddress = lan, externalAddress = external, legacyAddress = "",
                    lanPort = lanPort, externalPort = externalPort))
            }
            val uniqueResult = result.distinctBy { it.id }
            withContext(Dispatchers.Main) {
                if (uniqueResult != hosts.toList()) {
                    val oldSelected = selectedHostId
                    saveHosts(uniqueResult)
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
                val canonicalById = uniqueResult.associateBy { it.id }
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
            return uniqueResult
        }

        fun closeSession(session: TerminalSessionState, syncDeletion: Boolean = true) {
            val index = sessions.indexOf(session)
            if (index < 0) return
            sessionStore.remove(session.task.id)
            if (syncDeletion) store.enqueueSessionSync(session.task, "DELETE")
            AutoConfirmEngine.unregisterSession(session.task.id)
            session.cancelAutoEnter()
            TerminalConnectionRegistry.unregister(session.task.id, session.controller)
            session.controller.close()
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

        fun openTask(host: HostProfile, directory: ProjectDirectory, agent: AgentPreset, restoredTask: TerminalTask? = null) {
            runCatching { TerminalKeepAliveService.start(appContext) }
                .onFailure { error -> scope.launch { snackbars.showSnackbar("后台 SSH 保活未能启动：${error.message ?: "系统拒绝启动服务"}") } }
            val same = sessions.count { it.task.host.id == host.id && it.task.directory.id == directory.id && it.task.agent.id == agent.id }
            val task = restoredTask ?: TerminalTask(UUID.randomUUID().toString(), host, directory, agent,
                if (same == 0) agent.name else "${agent.name} ${same + 1}")
            if (restoredTask == null) {
                sessionStore.save(task)
                store.enqueueSessionSync(task, "PUT", SyncClient.sessionItem(task))
            }
            val state = TerminalSessionState(task, mainHandler, appContext)
            state.controller = SshTerminalController(
                task = task, store = store, restoreOnly = restoredTask != null,
                onStatus = { value -> mainHandler.post {
                    state.status = value
                    if (!value.startsWith("已连接")) state.codexQuota = null
                    if (value.contains("重连") || value.contains("恢复 SSH") || value == "已断开" || value.startsWith("远端终端已结束")) {
                        state.onConnectionUnavailable()
                    }
                    if (value.startsWith("已连接")) state.resumeAutoEnter(autoEnterDelaySeconds, autoEnterEnabled)
                } },
                onQuota = { quota -> mainHandler.post { state.codexQuota = quota } },
                onBytes = { bytes ->
                    state.buffer.feed(bytes)
                    AutoConfirmEngine.onOutputReceived(task.id)
                    mainHandler.post {
                        state.lastOutputAt = android.os.SystemClock.elapsedRealtime()
                        state.isStreaming = true
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
            if (restoredTask == null) {
                activePage = sessions.lastIndex
                route = AppRoute.TERMINAL
            }
            state.controller.start()
        }

        fun openSharedSession(host: HostProfile, item: org.json.JSONObject, activate: Boolean = true) {
            val id = item.getString("id")
            // Session IDs are globally unique; a profile ID remap must not open a duplicate terminal.
            val existing = sessions.indexOfFirst { it.task.id.replace("-", "") == id }
            if (existing >= 0) { if (activate) { activePage = existing; route = AppRoute.TERMINAL }; return }
            val currentHost = hosts.firstOrNull { it.id == host.id } ?: return
            val path = item.getString("directory_path")
            val directory = currentHost.directories.firstOrNull { it.path == path }
                ?: ProjectDirectory(UUID.randomUUID().toString(), item.getString("directory_name"), path)
            val updatedHost = if (directory in currentHost.directories) currentHost else currentHost.copy(directories = currentHost.directories + directory)
            if (updatedHost != currentHost) saveHosts(hosts.map { if (it.id == currentHost.id) updatedHost else it })
            val command = item.getString("agent_command")
            val agent = agents.firstOrNull { it.command == command }
                ?: AgentPreset(UUID.randomUUID().toString(), item.getString("agent_name"), command, "✳").also { agents.add(it); store.saveAgents(agents) }
            val task = TerminalTask(id, updatedHost, directory, agent, item.getString("display_name"))
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
            val remoteIds = items.map { it.optString("id") }.toSet()
            val hadSessions = sessions.isNotEmpty()
            sessions.toList().filter { session ->
                session.task.host.id == host.id && session.task.id.replace("-", "") !in remoteIds &&
                    android.os.SystemClock.elapsedRealtime() - session.startedAt > 15_000L
            }.forEach { closeSession(it, syncDeletion = false) }
            items.forEach { item -> openSharedSession(host, item, activate = false) }
            if (!hadSessions && sessions.isNotEmpty() && route == AppRoute.HOME) { activePage = 0; route = AppRoute.TERMINAL }
            if (sharedHost?.id == host.id && sharedSessions != null) sharedSessions = items
            store.clearPendingSessionSync(batch.completedMutationIds)
        }

        fun refreshShared(host: HostProfile) {
            if (!autoSyncEnabled) return
            scope.launch {
                runCatching { syncSharedSessions(host) }
                    .onFailure { snackbars.showSnackbar("${host.name} 同步失败：${it.message}") }
            }
        }

        LaunchedEffect(Unit) {
            sessionStore.load(hosts, agents).forEach { task ->
                if (!autoSyncEnabled || syncUrl.isBlank() || syncToken.isBlank()) openTask(task.host, task.directory, task.agent, task)
            }
            activePage = activePage.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))
            sessionsRestored = true
        }

        LaunchedEffect(syncUrl, syncToken, autoSyncEnabled, syncIntervalSeconds) {
            val syncEnabled = autoSyncEnabled && syncUrl.isNotBlank() && syncToken.isNotBlank()
            syncLoading = syncEnabled && hosts.isNotEmpty()
            if (!syncEnabled) startupShared = emptyList()
            while (true) {
                if (!syncEnabled) {
                    syncLoading = false
                    kotlinx.coroutines.delay(syncIntervalSeconds * 1_000L)
                    continue
                }
                val resolvedHosts = if (syncEnabled) runCatching {
                    withContext(SyncClient.dispatcher) { syncHostProfiles() }
                }.getOrElse { error ->
                    if (error.message.orEmpty().contains("401") || error.message.orEmpty().contains("403")) {
                        syncToken = ""; store.saveSyncSettings(syncUrl, ""); route = AppRoute.LOGIN
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
                            if (localRevision < remoteRevision) {
                                syncWorkspaceList(localHost, localHost.directories)
                            } else if (!configuredWorkspaces && localHost.directories.isNotEmpty()) {
                                syncWorkspaceList(localHost, localHost.directories)
                            } else {
                                if (localHost.directories != remoteWorkspaces)
                                    saveHosts(hosts.map { if (it.id == host.id) it.copy(directories = remoteWorkspaces) else it })
                                store.saveWorkspaceRevision(revisionIdentity, remoteRevision)
                            }
                        }
                        val agentRevisionIdentity = SyncClient.agentRevisionIdentity(store.loadSyncUsername())
                        val localAgentRevision = store.loadAgentRevision(agentRevisionIdentity)
                        val remoteAgentRevision = agentsSnapshot.optInt("revision", 0)
                        if ((!configuredAgents && agents.isNotEmpty()) || localAgentRevision < remoteAgentRevision) {
                            syncAgentList(localHost, agents.toList())
                        } else {
                            if (agents.toList() != remoteAgents) {
                                agents.clear(); agents.addAll(remoteAgents); store.saveAgents(remoteAgents)
                            }
                            store.saveAgentRevision(agentRevisionIdentity, remoteAgentRevision)
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
                        if (message.contains("401") || message.contains("403")) {
                            syncToken = ""
                            store.saveSyncSettings(syncUrl, "")
                            route = AppRoute.LOGIN
                        } else {
                            snackbars.showSnackbar("${host.name} 同步失败：$message")
                        }
                    }
                    runCatching { syncSharedSessions(host) }
                        .onFailure { error -> snackbars.showSnackbar("${host.name} 会话同步失败：${error.message}") }
                }
                syncLoading = false
                kotlinx.coroutines.delay(syncIntervalSeconds * 1_000L)
            }
        }

        BackHandler(enabled = route != AppRoute.HOME) {
            if (route == AppRoute.TERMINAL) route = AppRoute.HOME else route = AppRoute.HOME
        }

        Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().imePadding().navigationBarsPadding()) {
            when (route) {
                AppRoute.HOME -> HomeScreen(
                    hosts = hosts, agents = agents, selectedHost = selectedHost, selectedAgent = selectedAgent,
                    sessions = sessions, onSelectHost = { selectHost(it) }, onSelectAgent = { agent ->
                        selectedAgentId = agent.id
                        selectedHost?.let { host ->
                            store.saveHostSelectedAgent(host.id, agent.id)
                            scope.launch { runCatching { withContext(SyncClient.dispatcher) { SyncClient.updateCurrentAgent(host, agent.command) } } }
                        }
                    },
                    syncLoading = syncLoading,
                    onManage = { route = AppRoute.MANAGE }, onAddHost = { addingHost = true },
                    onAddDirectory = { selectedHost?.let { editingDirectory = it.id to null } },
                    onReorderDirectories = { host, ordered ->
                        saveHosts(hosts.map { if (it.id == host.id) it.copy(directories = ordered) else it })
                        syncWorkspaceList(host, ordered)
                    },
                    onOpenAgents = { route = AppRoute.AGENTS },
                    onOpenSettings = { route = AppRoute.SETTINGS },
                    onStartTask = { host, dir, agent -> openTask(host, dir, agent) },
                    onResumeSession = { index -> activePage = index; sessions[index].unread = false; route = AppRoute.TERMINAL },
                )
                AppRoute.MANAGE -> ManageScreen(
                    hosts = hosts, selectedHost = selectedHost,
                    onBack = { route = AppRoute.HOME }, onSelectHost = { selectHost(it) }, onAddHost = { addingHost = true },
                    onEditHost = { editingHost = it }, onDeleteHost = { deletingHost = it },
                    onAddDirectory = { selectedHost?.let { editingDirectory = it.id to null } },
                    onEditDirectory = { host, dir -> editingDirectory = host.id to dir },
                    onDeleteDirectory = { host, dir -> deletingDirectory = host.id to dir },
                )
                AppRoute.AGENTS -> AgentsScreen(
                    agents = agents, onBack = { route = AppRoute.HOME }, onAdd = { addingAgent = true },
                    onEdit = { editingAgent = it }, onDelete = { deletingAgent = it },
                )
                AppRoute.SETTINGS -> SettingsScreen(
                    store = store,
                    syncUrl = syncUrl,
                    syncToken = syncToken,
                    autoSyncEnabled = autoSyncEnabled,
                    syncIntervalSeconds = syncIntervalSeconds,
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
                    onBack = { route = if (syncUrl.isNotBlank() && syncToken.isBlank()) AppRoute.LOGIN else AppRoute.HOME },
                )
                AppRoute.LOGIN -> SyncLoginScreen(
                    syncUrl = syncUrl,
                    initialUsername = store.loadSyncUsername(),
                    initialPassword = store.loadSyncPassword(),
                    onBack = { route = AppRoute.HOME },
                    onSuccess = { username, password, token, remember ->
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
                )
            }
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
                if (autoSyncEnabled && syncUrl.isNotBlank() && syncToken.isNotBlank()) refreshShared(host)
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
                if (syncUrl.isNotBlank() && syncToken.isNotBlank()) scope.launch { runCatching { withContext(SyncClient.dispatcher) { SyncClient.deleteHostProfile(host.id) } } }
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
            title = "删除 Agent？", detail = "${agent.name} 的自定义配置将被移除。",
            onConfirm = {
                agents.removeAll { it.id == agent.id }; store.saveAgents(agents.toList())
                selectedHost?.let { syncAgentList(it, agents.toList()) }
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

        sessions.forEach { session ->
            session.hostFingerprint?.let { fingerprint ->
                AlertDialog(
                    onDismissRequest = { session.resolveHostKey?.invoke(false); session.hostFingerprint = null },
                    containerColor = Panel,
                    title = { Text("首次连接这台主机？", color = White, fontWeight = FontWeight.SemiBold) },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请核对 SSH 主机指纹。接受后会保存在本机，后续连接会自动校验。", color = Muted)
                        Text("${session.hostKeyAddress}:${session.hostKeyPort}", color = White, fontWeight = FontWeight.Medium)
                        Text(fingerprint, color = Mint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    } },
                    confirmButton = { TextButton(onClick = {
                        store.trustHostKey(session.hostKeyAddress, session.hostKeyPort, fingerprint)
                        val trustedHost = session.task.host.copy(syncFingerprint = fingerprint)
                        saveHosts(hosts.map { if (it.id == trustedHost.id) trustedHost else it })
                        session.task = session.task.copy(host = trustedHost)
                        session.resolveHostKey?.invoke(true)
                        session.hostFingerprint = null
                    }) { Text("信任并连接", color = Mint) } },
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
    syncLoading: Boolean,
    onReorderDirectories: (HostProfile, List<ProjectDirectory>) -> Unit,
) {
    var draggingDirectoryId by remember(selectedHost?.id) { mutableStateOf<String?>(null) }
    var dragOffsetY by remember(selectedHost?.id) { mutableStateOf(0f) }

    LaunchedEffect(hosts) {
        hosts.forEach { host ->
            launch(Dispatchers.IO) {
                HostRouteResolver.resolveHostRoute(host)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Terminal, null, tint = Mint, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("REMOTE DEV", color = White, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 17.sp)
                Text("随时接回你的开发现场 · v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
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

        if (syncLoading) Text("正在同步共享会话…", color = Muted, fontSize = 12.sp)

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
                Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                    displayedSessions.forEachIndexed { dispIndex, session ->
                        val originalIndex = sessions.indexOf(session).coerceAtLeast(0)
                        Row(
                            Modifier.fillMaxWidth().clickable { onResumeSession(originalIndex) }.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(session.task.agent.icon, fontSize = 15.sp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.task.displayName, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    if (session.needsAction) "需要确认" else "${session.task.host.name} · ${session.task.directory.name}",
                                    color = if (session.needsAction) Coral else Muted, fontSize = 10.sp, maxLines = 1,
                                )
                            }
                            when {
                                session.needsAction -> Text("!", color = Coral, fontWeight = FontWeight.Bold)
                                session.unread -> Box(Modifier.size(7.dp).background(Blue, CircleShape))
                            }
                            if (codexExecutable(session.task.agent.command) != null) {
                                val quota = session.codexQuota
                                Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.End) {
                                    if (quota == null) Text("额度 --", color = Muted, fontSize = 9.sp, lineHeight = 11.sp)
                                    else quota.take(2).forEach { (minutes, percent) ->
                                        Text(quotaLabel(minutes, percent), color = if (percent <= 20) Coral else Muted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (dispIndex < displayedSessions.lastIndex) Divider(color = Edge, thickness = 1.dp)
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
                        Row(Modifier.widthIn(min = 165.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (selected) "◉" else "○", color = if (selected) Mint else Muted, fontSize = 17.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(host.icon, fontSize = 21.sp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(host.name, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                val route = HostRouteResolver.getRoute(host.id)
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
                                Text(routeText, color = routeColor, fontSize = 10.sp, maxLines = 1)
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
                        host.directories.forEachIndexed { index, dir ->
                            val isDragging = draggingDirectoryId == dir.id
                            val rowHeightPx = with(LocalDensity.current) { 90.dp.toPx() }
                            ProjectRow(
                                directory = dir, selectedAgent = selectedAgent,
                                enabled = selectedAgent != null,
                                onClick = { if (selectedAgent != null) onStartTask(host, dir, selectedAgent) },
                                modifier = Modifier
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffsetY else 0f
                                        scaleX = if (isDragging) 1.025f else 1f
                                        scaleY = if (isDragging) 1.025f else 1f
                                        shadowElevation = if (isDragging) 14.dp.toPx() else 0f
                                    }
                                    .pointerInput(host.id, host.directories, dir.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { draggingDirectoryId = dir.id; dragOffsetY = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                                val delta = (dragOffsetY / rowHeightPx).toInt()
                                                val target = (index + delta).coerceIn(0, host.directories.lastIndex)
                                                if (target != index) {
                                                    val reordered = host.directories.toMutableList().apply { add(target, removeAt(index)) }
                                                    onReorderDirectories(host, reordered)
                                                    dragOffsetY -= (target - index) * rowHeightPx
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
                            Text(if (selected) "◉" else "○", color = if (selected) Mint else Muted, fontSize = 17.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(host.icon, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 5.dp))
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(host.name, color = White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                val route = HostRouteResolver.getRoute(host.id)
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
private fun ProjectRow(directory: ProjectDirectory, selectedAgent: AgentPreset?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, color = Panel, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Edge)) {
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
    val sessionIds = sessions.map { it.task.id }
    var pagerReady by remember(sessionIds) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Pager may briefly retain a removed page index while it reconciles the new count.
    val fallbackSession = sessions.getOrNull(initialIndex.coerceIn(0, sessions.lastIndex)) ?: sessions.lastOrNull() ?: return
    val currentSession = sessions.getOrNull(pager.currentPage) ?: fallbackSession
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
                    Text(currentSession.task.directory.path, color = Muted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ·  ${currentSession.task.agent.name}", color = Muted, fontSize = 10.sp, maxLines = 1)
                }
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
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), userScrollEnabled = false, key = { sessions.getOrNull(it)?.task?.id ?: "removed-$it" }) { page ->
            TerminalPage(sessions.getOrNull(page) ?: currentSession, page == pager.currentPage)
        }
    }
}

@Composable
private fun SessionTab(session: TerminalSessionState, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "activity")
    val pulse by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val color = when { session.needsAction -> Coral; selected -> Mint; session.unread -> Blue; else -> statusTint(session.status) }
    val deadline = session.autoEnterDeadline
    var remainingSeconds by remember(session, deadline) { mutableIntStateOf(0) }
    LaunchedEffect(deadline) {
        while (deadline != null) {
            val remaining = deadline - android.os.SystemClock.uptimeMillis()
            if (remaining <= 0) { remainingSeconds = 0; break }
            remainingSeconds = ((remaining + 999) / 1000).toInt()
            kotlinx.coroutines.delay((remaining - (remainingSeconds - 1) * 1000L).coerceAtLeast(1))
        }
    }
    Surface(onClick = onClick, color = if (selected) PanelRaised else Panel, shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color.copy(alpha = .7f) else Edge)) {
        Row(Modifier.padding(start = 10.dp, end = 5.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).alpha(if (session.isStreaming) pulse else 1f).background(color, CircleShape))
            val supportsQuota = codexExecutable(session.task.agent.command) != null || session.task.agent.id == "antigravity"
            Box(Modifier.width(if (supportsQuota) 64.dp else 20.dp), contentAlignment = Alignment.Center) {
                if (remainingSeconds > 0) {
                    Text(remainingSeconds.toString(), color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                } else if (supportsQuota) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val quota = session.codexQuota
                        if (quota == null) Text("额度 --", color = Muted, fontSize = 9.sp, lineHeight = 10.sp)
                        else quota.take(2).forEach { (minutes, percent) ->
                            Text(quotaLabel(minutes, percent), color = if (percent <= 20) Coral else Muted, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
            Text(session.task.displayName, color = if (selected) White else Muted, fontSize = 11.sp, maxLines = 1)
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Close, null, tint = Muted, modifier = Modifier.size(13.dp)) }
        }
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
    // Keep the remote grid stable while the keyboard moves; resize it once the
    // viewport has settled so tmux can redraw for the final dimensions.
    LaunchedEffect(session.task.id, terminalViewport, keyboardVisible) {
        if (terminalViewport.width > 0 && terminalViewport.height > 0) {
            kotlinx.coroutines.delay(220)
            session.buffer.resize(viewportColumns, viewportRows)
            // Re-send the settled dimensions even if our cached dimensions match:
            // the remote program may not have applied an earlier window change.
            session.controller.resize(viewportColumns, viewportRows, force = true)
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
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("返回", color = Muted) }
    }
}

@Composable
private fun SettingsScreen(
    store: LocalStore,
    syncUrl: String,
    syncToken: String,
    autoSyncEnabled: Boolean,
    syncIntervalSeconds: Int,
    onAutoSyncEnabledChange: (Boolean) -> Unit,
    onSyncIntervalSecondsChange: (Int) -> Unit,
    syncUsername: String,
    onSyncSettingsChange: (String, String) -> Unit,
    onLogin: () -> Unit,
    enabled: Boolean,
    delaySeconds: Int,
    onEnabledChange: (Boolean) -> Unit,
    onDelaySecondsChange: (Int) -> Unit,
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
                Text("管理密钥、同步与自动回车", color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = { finish() }) { Text("完成", color = Mint) }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Text(if (syncUsername.isBlank()) "未登录" else "当前账号：$syncUsername", color = Muted, fontSize = 12.sp)
            FilledTonalButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("登录同步账号") }
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
