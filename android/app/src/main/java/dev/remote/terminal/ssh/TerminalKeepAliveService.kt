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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.remote.terminal.MainActivity
import dev.remote.terminal.R
import java.util.concurrent.ConcurrentHashMap

object TerminalConnectionRegistry {
    private val controllers = ConcurrentHashMap<String, SshTerminalController>()

    fun register(id: String, controller: SshTerminalController) {
        controllers.put(id, controller)?.close()
    }
    fun unregister(id: String, controller: SshTerminalController) { controllers.remove(id, controller) }
    fun onDefaultNetworkAvailable(recoveryStartedAt: Long) {
        controllers.values.forEach { it.onNetworkAvailable(recoveryStartedAt) }
    }
}

/** Keeps the app process and CPU alive while its in-memory SSH terminal sessions are active. */
class TerminalKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var hasObservedDefaultNetwork = false
    @Volatile private var defaultNetworkValidated = false
    @Volatile private var hasObservedNetworkCapabilities = false
    @Volatile private var networkRecoveryPending = false
    @Volatile private var networkRecoveryStartedAt = 0L
    private val networkChangeHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingNetworkChange: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        AutoConfirmEngine.init(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(mode, "$packageName:ssh-wifi")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
        return START_STICKY
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
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
        super.onDestroy()
    }

    private fun monitorDefaultNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        defaultNetwork = manager.activeNetwork
        hasObservedDefaultNetwork = defaultNetwork != null
        val initialCapabilities = defaultNetwork?.let { network -> manager.getNetworkCapabilities(network) }
        defaultNetworkValidated = initialCapabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        hasObservedNetworkCapabilities = initialCapabilities != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = defaultNetwork != network
                val previouslyObserved = hasObservedDefaultNetwork
                defaultNetwork = network
                hasObservedDefaultNetwork = true
                if (changed) {
                    defaultNetworkValidated = false
                    hasObservedNetworkCapabilities = false
                    if (previouslyObserved || networkRecoveryPending) {
                        networkRecoveryPending = true
                        networkRecoveryStartedAt = SystemClock.elapsedRealtime()
                    }
                    cancelNetworkRecovery()
                }
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    defaultNetworkValidated = false
                    hasObservedNetworkCapabilities = false
                    networkRecoveryPending = true
                    networkRecoveryStartedAt = SystemClock.elapsedRealtime()
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
            TerminalConnectionRegistry.onDefaultNetworkAvailable(networkRecoveryStartedAt)
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
            .setContentText("SSH / Agent 会话与自动确认在后台运行")
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

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun requestIgnoreBatteryOptimizations(context: Context) {
            runCatching {
                val intent = if (!isIgnoringBatteryOptimizations(context)) {
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                } else {
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}
