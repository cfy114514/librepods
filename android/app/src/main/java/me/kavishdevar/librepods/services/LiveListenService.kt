package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.AudioRelay
import me.kavishdevar.librepods.audio.AudioRelayException
import me.kavishdevar.librepods.audio.LiveListenController
import me.kavishdevar.librepods.audio.LiveListenError
import me.kavishdevar.librepods.audio.LiveListenSessions
import me.kavishdevar.librepods.audio.microphoneOwnership

/** User-started microphone relay; never starts from boot or a Bluetooth broadcast. */
class LiveListenService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var lease: LiveListenSessions.Lease? = null
    private var worker: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var targetDeviceId: Int? = null
    private var callbacksRegistered = false
    private var foregroundStarted = false
    private var bootstrapOnly = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopWithError(LiveListenError.ROUTE_CHANGED)
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == targetDeviceId }) stopWithError(LiveListenError.ROUTE_CHANGED)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        callbacksRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A queued foreground start still needs acknowledgment after cancellation.
        if (!foregroundStarted) {
            try {
                showForegroundNotification(intent?.getLongExtra(EXTRA_SESSION, -1) ?: -1, bootstrap = true)
            } catch (error: Exception) {
                val pending = LiveListenController.sessions.find(intent?.getLongExtra(EXTRA_SESSION, -1) ?: -1)
                pending?.let {
                    if (microphoneOwnership.cancelPending(it)) {
                        it.stop(LiveListenError.PERMISSION)
                        LiveListenController.sessions.finish(it)
                    }
                }
                android.util.Log.w("LiveListenService", "Could not acknowledge foreground start", error)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_STOP) {
            // Ignore a delayed action from an earlier notification/session.
            if (intent.getLongExtra(EXTRA_SESSION, -1) == lease?.id) {
                LiveListenController.sessions.stop()
                worker?.interrupt()
                stopSelf()
            }
            if (lease == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val session = LiveListenController.sessions.find(intent?.getLongExtra(EXTRA_SESSION, -1) ?: -1)
        if (intent?.action != ACTION_START || session == null || !session.active) {
            session?.let { if (microphoneOwnership.cancelPending(it)) LiveListenController.sessions.finish(it) }
            if (lease == null && worker == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        if (!microphoneOwnership.claim(session)) { stopSelf(startId); return START_NOT_STICKY }
        lease = session
        try {
            requirePermission(Manifest.permission.RECORD_AUDIO)
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
            showForegroundNotification(session.id)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (lease === session && change != AudioManager.AUDIOFOCUS_GAIN) stopWithError(LiveListenError.FOCUS_LOST)
                }, mainHandler).build()
            focusRequest = focus
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw AudioRelayException(LiveListenError.FOCUS_LOST)
            }
            worker = Thread({ runRelay(session, startId) }, "LibrePods-LiveListen").apply {
                isDaemon = true
                start()
            }
        } catch (_: SecurityException) {
            session.stop(LiveListenError.PERMISSION)
            finishOnMain(session, startId)
        } catch (e: Exception) {
            session.stop((e as? AudioRelayException)?.reason ?: LiveListenError.INITIALIZATION)
            finishOnMain(session, startId)
        }
        return START_NOT_STICKY
    }

    private fun requirePermission(permission: String) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException(permission)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun showForegroundNotification(sessionId: Long, bootstrap: Boolean = false) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.live_listen), NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(this, 1,
            Intent(this, LiveListenService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SESSION, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.airpods).setContentTitle(getString(R.string.live_listen))
            .setContentText(getString(if (bootstrap) R.string.live_listen_starting else R.string.live_listen_notification)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.live_listen_stop), stop).build()
        val type = if (bootstrap) {
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, notification, type)
        foregroundStarted = true
        bootstrapOnly = bootstrap
    }

    @SuppressLint("MissingPermission")
    private fun runRelay(session: LiveListenSessions.Lease, startId: Int) {
        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        var routing: AudioRouting.OnRoutingChangedListener? = null
        var recordingCallback: AudioManager.AudioRecordingCallback? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (!session.active) return
            val address = getSharedPreferences("settings", MODE_PRIVATE).getString("mac_address", "") ?: ""
            val output = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                address.isNotBlank() && it.address.equals(address, ignoreCase = true) &&
                    (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            } ?: throw AudioRelayException(LiveListenError.NO_HEADPHONES)
            targetDeviceId = output.id
            val microphone = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            } ?: throw AudioRelayException(LiveListenError.NO_MICROPHONE)
            val recordMinimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val playMinimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (recordMinimum <= 0 || playMinimum <= 0) throw AudioRelayException(LiveListenError.INITIALIZATION)
            val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(recordMinimum, CHUNK_SAMPLES * 4)).build()
            recorder = input
            val recordingMonitor = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    if (lease === session && session.active && configs?.any {
                        it.clientAudioSessionId == input.audioSessionId && it.isClientSilenced
                    } == true) stopWithError(LiveListenError.NO_MICROPHONE)
                }
            }
            input.registerAudioRecordingCallback(mainExecutor, recordingMonitor)
            recordingCallback = recordingMonitor
            val track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(playMinimum, CHUNK_SAMPLES * 4)).build()
            player = track
            if (input.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED ||
                !input.setPreferredDevice(microphone) || !track.setPreferredDevice(output)) {
                throw AudioRelayException(LiveListenError.INITIALIZATION)
            }
            fun outputSafe(): Boolean = if (Build.VERSION.SDK_INT >= 36) {
                val devices = track.routedDevices
                devices.isNotEmpty() && devices.all { it.id == output.id }
            } else track.routedDevice?.id == output.id
            // Play silence first: setPreferredDevice alone is not proof of the route.
            track.setStartThresholdInFrames(CHUNK_SAMPLES)
            track.play()
            val silence = ShortArray(CHUNK_SAMPLES)
            val deadline = SystemClock.elapsedRealtime() + 1500
            while (session.active && !outputSafe()) {
                if (SystemClock.elapsedRealtime() >= deadline) throw AudioRelayException(LiveListenError.NO_HEADPHONES)
                if (track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) < 0) {
                    throw AudioRelayException(LiveListenError.WRITE_FAILED)
                }
                Thread.sleep(4)
            }
            if (!session.active) return
            input.startRecording()
            val inputDeadline = SystemClock.elapsedRealtime() + 1500
            while (session.active && input.routedDevice?.id != microphone.id) {
                if (!outputSafe()) throw AudioRelayException(LiveListenError.ROUTE_CHANGED)
                if (SystemClock.elapsedRealtime() >= inputDeadline) throw AudioRelayException(LiveListenError.NO_MICROPHONE)
                if (input.read(silence, 0, silence.size, AudioRecord.READ_NON_BLOCKING) < 0) {
                    throw AudioRelayException(LiveListenError.READ_FAILED)
                }
                Thread.sleep(4)
            }
            if (!session.active) return
            if (input.activeRecordingConfiguration?.isClientSilenced == true) throw AudioRelayException(LiveListenError.NO_MICROPHONE)
            val listener = AudioRouting.OnRoutingChangedListener {
                if (lease === session && session.active && (!outputSafe() || input.routedDevice?.id != microphone.id)) {
                    stopWithError(LiveListenError.ROUTE_CHANGED)
                }
            }
            routing = listener
            track.addOnRoutingChangedListener(listener, mainHandler)
            input.addOnRoutingChangedListener(listener, mainHandler)
            LiveListenController.sessions.listening(session)
            AudioRelay(
                read = { input.read(it, 0, it.size, AudioRecord.READ_NON_BLOCKING) },
                write = { data, offset, count -> track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING) },
                active = { session.active },
                routeSafe = { outputSafe() && input.routedDevice?.id == microphone.id },
                gain = { LiveListenController.gain }, idle = { Thread.sleep(4) },
                nowMillis = { SystemClock.elapsedRealtime() }, chunkSamples = CHUNK_SAMPLES
            ).run()
        } catch (_: InterruptedException) {
            // Explicit stop, noisy route, focus loss, and onDestroy interrupt this worker.
        } catch (_: SecurityException) {
            session.stop(LiveListenError.PERMISSION)
        } catch (e: Exception) {
            session.stop((e as? AudioRelayException)?.reason ?: LiveListenError.INITIALIZATION)
        } finally {
            recordingCallback?.let { callback -> runCatching { recorder?.unregisterAudioRecordingCallback(callback) } }
            routing?.let { listener ->
                runCatching { recorder?.removeOnRoutingChangedListener(listener) }
                runCatching { player?.removeOnRoutingChangedListener(listener) }
            }
            runCatching { recorder?.stop() }
            runCatching { player?.pause(); player?.flush() }
            runCatching { recorder?.release() }
            runCatching { player?.release() }
            // Release the global lease only after both native audio resources are gone.
            mainHandler.post { finishOnMain(session, startId) }
        }
    }

    private fun finishOnMain(session: LiveListenSessions.Lease, startId: Int) {
        if (lease === session) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focusRequest = null
            lease = null
            targetDeviceId = null
            worker = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            bootstrapOnly = false
        }
        microphoneOwnership.release(session)
        LiveListenController.sessions.finish(session)
        stopSelf(startId)
    }

    private fun stopWithError(reason: LiveListenError) {
        val session = lease ?: return
        LiveListenController.sessions.stop(session, reason)
        worker?.interrupt()
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        if (!bootstrapOnly) return
        lease?.stop(LiveListenError.INITIALIZATION)
        worker?.interrupt()
        stopSelf(startId)
    }

    override fun onDestroy() {
        lease?.stop()
        worker?.interrupt()
        if (callbacksRegistered) {
            unregisterReceiver(noisyReceiver)
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            callbacksRegistered = false
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (worker == null) lease?.let { microphoneOwnership.release(it); LiveListenController.sessions.finish(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        bootstrapOnly = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "me.kavishdevar.librepods.LIVE_LISTEN_START"
        private const val ACTION_STOP = "me.kavishdevar.librepods.LIVE_LISTEN_STOP"
        private const val EXTRA_SESSION = "session"
        private const val CHANNEL = "live_listen"
        private const val NOTIFICATION_ID = 40
        private const val SAMPLE_RATE = 48000
        private const val CHUNK_SAMPLES = 480

        fun start(context: Context) {
            if (Looper.myLooper() != Looper.getMainLooper()) { context.mainExecutor.execute { start(context) }; return }
            val session = LiveListenController.sessions.begin() ?: return
            if (!microphoneOwnership.acquire(session)) {
                session.stop(LiveListenError.MICROPHONE_BUSY)
                LiveListenController.sessions.finish(session)
                return
            }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                session.stop(LiveListenError.PERMISSION)
                microphoneOwnership.release(session)
                LiveListenController.sessions.finish(session)
                return
            }
            try {
                context.startForegroundService(Intent(context, LiveListenService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_SESSION, session.id))
            } catch (_: Exception) {
                session.stop(LiveListenError.PERMISSION)
                microphoneOwnership.release(session)
                LiveListenController.sessions.finish(session)
            }
        }

        fun stop(context: Context) {
            if (Looper.myLooper() != Looper.getMainLooper()) { context.mainExecutor.execute { stop(context) }; return }
            val session = LiveListenController.sessions.stop() ?: return
            // Let an accepted queued command acknowledge foreground before it stops.
            if (microphoneOwnership.cancelPending(session)) LiveListenController.sessions.finish(session)
            else context.stopService(Intent(context, LiveListenService::class.java))
        }
    }
}
