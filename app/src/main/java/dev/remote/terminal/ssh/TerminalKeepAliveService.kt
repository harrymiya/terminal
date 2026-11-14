package dev.remote.terminal.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.remote.terminal.MainActivity
import dev.remote.terminal.R
import java.util.concurrent.CopyOnWriteArraySet

object TerminalConnectionRegistry {
    private val controllers = CopyOnWriteArraySet<SshTerminalController>()

    fun register(controller: SshTerminalController) { controllers.add(controller) }
    fun unregister(controller: SshTerminalController) { controllers.remove(controller) }
    fun onDefaultNetworkAvailable() { controllers.forEach(SshTerminalController::onNetworkAvailable) }
}

/** Keeps the app process and CPU alive while its in-memory SSH terminal sessions are active. */
class TerminalKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var defaultNetworkValidated = false
    @Volatile private var hasObservedNetworkCapabilities = false
    @Volatile private var networkRecoveryPending = false
    private val networkChangeHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingNetworkChange: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        monitorDefaultNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ssh-terminal")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pendingNetworkChange?.let(networkChangeHandler::removeCallbacks)
        pendingNetworkChange = null
        networkCallback?.let { callback -> runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        connectivityManager = null
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun monitorDefaultNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        defaultNetwork = manager.activeNetwork
        val initialCapabilities = defaultNetwork?.let { network -> manager.getNetworkCapabilities(network) }
        defaultNetworkValidated = initialCapabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        hasObservedNetworkCapabilities = initialCapabilities != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = defaultNetwork != network
                defaultNetwork = network
                if (changed) {
                    defaultNetworkValidated = false
                    hasObservedNetworkCapabilities = false
                    networkRecoveryPending = true
                    cancelNetworkRecovery()
                }
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    defaultNetworkValidated = false
                    hasObservedNetworkCapabilities = false
                    networkRecoveryPending = true
                    cancelNetworkRecovery()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) {
                if (defaultNetwork != network) return
                val validated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val becameAvailable = validated && networkRecoveryPending &&
                    (!hasObservedNetworkCapabilities || !defaultNetworkValidated)
                defaultNetworkValidated = validated
                hasObservedNetworkCapabilities = true
                if (becameAvailable) scheduleNetworkRecovery()
                else if (!validated) cancelNetworkRecovery()
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun scheduleNetworkRecovery() {
        cancelNetworkRecovery()
        val pending = Runnable {
            pendingNetworkChange = null
            if (!networkRecoveryPending || !defaultNetworkValidated || defaultNetwork == null) return@Runnable
            networkRecoveryPending = false
            TerminalConnectionRegistry.onDefaultNetworkAvailable()
        }
        pendingNetworkChange = pending
        networkChangeHandler.postDelayed(pending, NETWORK_STABLE_DELAY_MS)
    }

    private fun cancelNetworkRecovery() {
        pendingNetworkChange?.let(networkChangeHandler::removeCallbacks)
        pendingNetworkChange = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "SSH 后台会话", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持正在运行的远程开发会话连接"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle("远程开发终端正在运行")
            .setContentText("SSH / Agent 会话在后台保持连接")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ssh_terminal_keep_alive"
        private const val NOTIFICATION_ID = 4017
        private const val NETWORK_STABLE_DELAY_MS = 2_000L
        private const val ACTION_START = "dev.remote.terminal.action.START_KEEP_ALIVE"
        private const val ACTION_STOP = "dev.remote.terminal.action.STOP_KEEP_ALIVE"

        fun start(context: Context) {
            val intent = Intent(context, TerminalKeepAliveService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalKeepAliveService::class.java).setAction(ACTION_STOP))
        }
    }
}
