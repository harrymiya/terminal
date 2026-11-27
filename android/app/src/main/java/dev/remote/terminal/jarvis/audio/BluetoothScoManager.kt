package dev.remote.terminal.jarvis.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dev.remote.terminal.jarvis.api.AudioChannelManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 蓝牙耳机 SCO (Synchronous Connection-Oriented) 宽频通话通道控制器。
 * 负责打通蓝牙耳机的麦克风拾音与听筒耳语播报闭环，支持热插拔与瞬态音频焦点。
 */
class BluetoothScoManager(private val context: Context) : AudioChannelManager {

    companion object {
        private const val TAG = "JarvisScoManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile override var isScoActive: Boolean = false
        private set

    @Volatile override var isHeadsetConnected: Boolean = false
        private set

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isReceiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            Log.d(TAG, "Bluetooth SCO connected (Microphone & Speaker routed to headset)")
                            isScoActive = true
                            notifyListeners(true)
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            Log.d(TAG, "Bluetooth SCO disconnected")
                            isScoActive = false
                            notifyListeners(false)
                        }
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                            Log.d(TAG, "Bluetooth SCO connecting...")
                        }
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    isHeadsetConnected = (state == BluetoothProfile.STATE_CONNECTED)
                    Log.d(TAG, "Bluetooth headset connection changed: isConnected=$isHeadsetConnected")
                    if (isHeadsetConnected) {
                        startChannel()
                    } else {
                        stopChannel()
                    }
                }
            }
        }
    }

    init {
        checkInitialHeadsetState()
        registerReceiver()
    }

    private fun checkInitialHeadsetState() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                isHeadsetConnected = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check bluetooth headset status: ${e.message}")
        }
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            context.registerReceiver(scoReceiver, filter)
            isReceiverRegistered = true
        }
    }

    override fun startChannel() {
        try {
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                Log.i(TAG, "Requested Bluetooth SCO start")
            } else {
                Log.w(TAG, "Bluetooth SCO is not available off call on this device")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth SCO: ${e.message}")
        }
    }

    override fun stopChannel() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                isScoActive = false
                Log.i(TAG, "Stopped Bluetooth SCO")
                notifyListeners(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Bluetooth SCO: ${e.message}")
        }
    }

    fun requestAudioFocus(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Focus change listener */ }
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus: ${e.message}")
            false
        }
    }

    fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    override fun addStateListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(isScoActive)
    }

    override fun removeStateListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(active: Boolean) {
        listeners.forEach { it.invoke(active) }
    }

    override fun release() {
        stopChannel()
        abandonAudioFocus()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(scoReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver already unregistered: ${e.message}")
            }
            isReceiverRegistered = false
        }
        listeners.clear()
    }
}
