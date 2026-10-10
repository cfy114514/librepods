package me.kavishdevar.librepods.services

import android.Manifest
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
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.translation.TranslationCapability
import android.view.translation.Translator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.AudioRelayException
import me.kavishdevar.librepods.audio.TranslationAudioPlayback
import me.kavishdevar.librepods.audio.microphoneOwnership
import me.kavishdevar.librepods.translation.LiveTranslationController
import me.kavishdevar.librepods.translation.PlatformTranslationCapabilities
import me.kavishdevar.librepods.translation.PlatformTranslationClient
import me.kavishdevar.librepods.translation.TranslationError
import me.kavishdevar.librepods.translation.TranslationException
import me.kavishdevar.librepods.translation.TranslationLine
import me.kavishdevar.librepods.translation.TranslationOutput
import me.kavishdevar.librepods.translation.RecognitionTurns
import me.kavishdevar.librepods.translation.TranslationPair
import me.kavishdevar.librepods.translation.TranslationSentenceQueue
import me.kavishdevar.librepods.translation.TranslationSessions
import me.kavishdevar.librepods.translation.pair
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit UI start only. Uses installed system on-device engines; never falls back to cloud ASR. */
class LiveTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lease: TranslationSessions.Lease? = null
    private lateinit var audio: AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var initialization: Job? = null
    private var processing: Job? = null
    private var retry: Job? = null
    private var recognitionWatchdog: Job? = null
    private var synthesis: Pair<String, CancellableContinuation<Unit>>? = null
    private var nextSynthesis = 0L
    private val recognitionTurns = RecognitionTurns()
    private var playbackDevice: AudioDeviceInfo? = null
    private var recognitionFailures = 0
    private var cleanupStarted = false
    private var destroyed = false
    private var startCommandId = 0
    private var foregroundStarted = false
    private var bootstrapOnly = false
    private val sentences = TranslationSentenceQueue()
    private var speechIntent: Intent? = null
    @Volatile private var outputDeviceId: Int? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (lease?.output == TranslationOutput.AIRPODS) requestStop(TranslationError.ROUTE_CHANGED)
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == outputDeviceId }) requestStop(TranslationError.ROUTE_CHANGED)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        audio.registerAudioDeviceCallback(devices, android.os.Handler(Looper.getMainLooper()))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            try {
                showForeground(null, intent?.getLongExtra(EXTRA_SESSION, -1) ?: -1)
            } catch (error: Exception) {
                val pending = LiveTranslationController.sessions.find(intent?.getLongExtra(EXTRA_SESSION, -1) ?: -1)
                pending?.let {
                    if (microphoneOwnership.cancelPending(it)) {
                        LiveTranslationController.sessions.stop(it, TranslationError.PERMISSION)
                        LiveTranslationController.sessions.finish(it)
                    }
                }
                android.util.Log.w("LiveTranslationService", "Could not acknowledge foreground start", error)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_STOP) {
            if (intent.getLongExtra(EXTRA_SESSION, -1) == lease?.id) requestStop()
            if (lease == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val session = LiveTranslationController.sessions.find(intent?.getLongExtra(EXTRA_SESSION, -1) ?: -1)
        if (intent?.action != ACTION_START || session == null || !session.active) {
            if (lease == null) {
                session?.let { if (microphoneOwnership.cancelPending(it)) LiveTranslationController.sessions.finish(it) }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (lease != null) return START_NOT_STICKY
        if (!microphoneOwnership.claim(session)) { stopSelf(startId); return START_NOT_STICKY }
        lease = session
        startCommandId = startId
        initialization = scope.launch {
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                    (session.output == TranslationOutput.AIRPODS && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED))
                    throw TranslationException(TranslationError.PERMISSION)
                showForeground(session)
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this@LiveTranslationService))
                    throw TranslationException(TranslationError.NO_RECOGNIZER)
                val capability = withTimeout(10_000) {
                    PlatformTranslationCapabilities.load(this@LiveTranslationService).firstOrNull {
                        it.state == TranslationCapability.STATE_ON_DEVICE && it.pair() == session.pair
                    }
                } ?: throw TranslationException(TranslationError.NO_TRANSLATOR)
                translator = withTimeout(10_000) { PlatformTranslationClient(this@LiveTranslationService).create(capability) }
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this@LiveTranslationService)
                speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, session.pair.source)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                withTimeout(10_000) { checkSpeechLanguage(session.pair.source) }
                if (session.output != TranslationOutput.CAPTIONS) {
                    playbackDevice = TranslationAudioPlayback(this@LiveTranslationService).selectDevice(session.output)
                    outputDeviceId = playbackDevice!!.id
                    withTimeout(10_000) { initializeVoice(session.pair.target) }
                    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes).setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { change -> if (lease === session && change != AudioManager.AUDIOFOCUS_GAIN) requestStop(TranslationError.AUDIO) }.build()
                    focus = request
                    if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        throw TranslationException(TranslationError.AUDIO)
                }
                LiveTranslationController.sessions.listening(session)
                listen(session)
            } catch (error: TimeoutCancellationException) { requestStop(TranslationError.TIMEOUT) }
            catch (error: CancellationException) { throw error }
            catch (error: SecurityException) { requestStop(TranslationError.PERMISSION) }
            catch (error: Exception) { requestStop((error as? TranslationException)?.reason ?: TranslationError.SERVICE) }
        }
        return START_NOT_STICKY
    }

    private suspend fun checkSpeechLanguage(tag: String) = suspendCancellableCoroutine<Unit> { continuation ->
        recognizer!!.checkRecognitionSupport(speechIntent!!, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (!continuation.isActive) return
                val installed = support.installedOnDeviceLanguages
                val language = Locale.forLanguageTag(tag).language
                val available = installed.find { it.equals(tag, true) } ?: installed.find { Locale.forLanguageTag(it).language == language }
                if (available == null) continuation.resumeWithException(TranslationException(TranslationError.LANGUAGE_MODEL))
                else { speechIntent!!.putExtra(RecognizerIntent.EXTRA_LANGUAGE, available); continuation.resume(Unit) }
            }
            override fun onError(error: Int) {
                if (!continuation.isActive) return
                // Some offline providers cannot check support; the offline recognizer still
                // validates the language when listening starts. There is no network fallback.
                if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) continuation.resume(Unit)
                else continuation.resumeWithException(TranslationException(TranslationError.LANGUAGE_MODEL))
            }
        })
    }

    private suspend fun initializeVoice(tag: String) {
        val ready = suspendCancellableCoroutine<Boolean> { continuation ->
            tts = TextToSpeech(this) { status -> if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS) }
        }
        val engine = tts ?: throw TranslationException(TranslationError.NO_VOICE)
        val locale = Locale.forLanguageTag(tag)
        val voice = engine.voices?.filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features && it.locale.language == locale.language }
            ?.sortedByDescending { it.locale == locale }?.firstOrNull()
        if (!ready || voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS)
            throw TranslationException(TranslationError.NO_VOICE)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = completeSynthesis(utteranceId, true)
            @Deprecated("Compatibility callback")
            override fun onError(utteranceId: String?) = completeSynthesis(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = completeSynthesis(utteranceId, false)
        })
    }

    private fun completeSynthesis(id: String?, succeeded: Boolean) {
        mainExecutor.execute {
            val pending = synthesis?.takeIf { it.first == id } ?: return@execute
            synthesis = null
            if (pending.second.isActive) {
                if (succeeded) pending.second.resume(Unit)
                else pending.second.resumeWithException(TranslationException(TranslationError.NO_VOICE))
            }
        }
    }

    private fun installRecognitionListener(session: TranslationSessions.Lease, turn: Long) {
        recognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(results: Bundle?) {
                if (!session.active || !recognitionTurns.accepts(turn)) return
                LiveTranslationController.sessions.partial(session, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onResults(results: Bundle?) {
                if (!session.active || !recognitionTurns.complete(turn)) return
                recognitionWatchdog?.cancel()
                recognitionFailures = 0
                val offered = sentences.offer(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                if (offered.skipped) LiveTranslationController.sessions.skipped(session)
                offered.accepted?.let { LiveTranslationController.sessions.addLine(session, TranslationLine(it.id, it.text)) }
                processSentences(session)
                scheduleListen(session, 500)
            }
            override fun onError(error: Int) {
                if (!session.active || !recognitionTurns.complete(turn)) return
                recognitionWatchdog?.cancel()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleListen(session, 1000)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        if (++recognitionFailures <= 2) scheduleListen(session, recognitionFailures * 1500L)
                        else requestStop(TranslationError.SERVICE)
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> requestStop(TranslationError.LANGUAGE_MODEL)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> requestStop(TranslationError.PERMISSION)
                    SpeechRecognizer.ERROR_AUDIO -> requestStop(TranslationError.AUDIO)
                    else -> requestStop(TranslationError.SERVICE)
                }
            }
        })
    }

    private fun listen(session: TranslationSessions.Lease) {
        if (!session.active) return
        val turn = recognitionTurns.begin() ?: return
        try {
            installRecognitionListener(session, turn)
            recognizer!!.startListening(speechIntent!!)
            recognitionWatchdog?.cancel()
            recognitionWatchdog = scope.launch {
                delay(45_000)
                if (session.active && recognitionTurns.accepts(turn)) {
                    recognizer?.stopListening()
                    delay(3000)
                    if (session.active && recognitionTurns.accepts(turn)) requestStop(TranslationError.TIMEOUT)
                }
            }
        } catch (_: SecurityException) { requestStop(TranslationError.PERMISSION) }
        catch (_: Exception) { requestStop(TranslationError.SERVICE) }
    }
    private fun scheduleListen(session: TranslationSessions.Lease, millis: Long) {
        retry?.cancel()
        retry = scope.launch { delay(millis); listen(session) }
    }

    private fun processSentences(session: TranslationSessions.Lease) {
        if (processing?.isActive == true || !session.active) return
        processing = scope.launch {
            try {
                while (session.active) {
                    val sentence = sentences.take() ?: break
                    val result = withTimeout(10_000) { PlatformTranslationClient(this@LiveTranslationService).translate(translator!!, sentence.text) }
                    LiveTranslationController.sessions.translated(session, sentence.id, result)
                    if (session.output != TranslationOutput.CAPTIONS) speak(session, result)
                }
            } catch (_: TimeoutCancellationException) { requestStop(TranslationError.TIMEOUT) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { requestStop((error as? TranslationException)?.reason ?: if (error is AudioRelayException) TranslationError.AUDIO else TranslationError.TRANSLATION) }
        }
    }

    private suspend fun speak(session: TranslationSessions.Lease, text: String) {
        // Never transcribe our own spoken translation; late ASR callbacks lose their turn.
        recognitionTurns.pause()
        retry?.cancel()
        recognitionWatchdog?.cancel()
        runCatching { recognizer?.cancel() }
        try { synthesizeAndPlay(session, text) }
        finally {
            recognitionTurns.resume()
            if (session.active) scheduleListen(session, 500)
        }
    }

    private suspend fun synthesizeAndPlay(session: TranslationSessions.Lease, text: String) = withContext(Dispatchers.IO) {
        val directory = File(cacheDir, "translation-audio").apply { mkdirs() }
        val file = File.createTempFile("phrase-${session.id}-", ".wav", directory)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { descriptor ->
                // TTS needs a seekable WAV destination. Unlink it while the descriptor is open:
                // stop/crash/process death cannot leave a recording in the application cache.
                if (!file.delete()) throw TranslationException(TranslationError.AUDIO)
                withContext(Dispatchers.Main.immediate) {
                    val utterance = "${session.id}-${++nextSynthesis}"
                    withTimeout(20_000) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            synthesis = utterance to continuation
                            continuation.invokeOnCancellation {
                                mainExecutor.execute {
                                    if (synthesis?.first == utterance) { synthesis = null; tts?.stop() }
                                }
                            }
                            if (tts!!.synthesizeToFile(text, Bundle(), descriptor, utterance) != TextToSpeech.SUCCESS) {
                                synthesis = null
                                continuation.resumeWithException(TranslationException(TranslationError.NO_VOICE))
                            }
                        }
                    }
                }
                if (!session.active) return@withContext
                if (Os.fstat(descriptor.fileDescriptor).st_size !in 44..(8L * 1024 * 1024 + 65536))
                    throw TranslationException(TranslationError.AUDIO)
                val worker = coroutineContext[Job]!!
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                    TranslationAudioPlayback(this@LiveTranslationService).play(input,
                        playbackDevice ?: throw TranslationException(TranslationError.AUDIO),
                        active = { session.active && worker.isActive })
                }
            }
        } finally { file.delete() }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun showForeground(session: TranslationSessions.Lease?, pendingId: Long = -1) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.live_translation), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, LiveTranslationService::class.java)
            .setAction(ACTION_STOP).putExtra(EXTRA_SESSION, session?.id ?: pendingId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.airpods)
            .setContentTitle(getString(R.string.live_translation)).setContentText(getString(if (session == null) R.string.translation_starting else R.string.translation_listening))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.live_listen_stop), stop).build()
        val type = if (session == null) {
            if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (session.output == TranslationOutput.CAPTIONS) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        startForeground(41, notification, type)
        foregroundStarted = true
        bootstrapOnly = session == null
    }

    private fun requestStop(error: TranslationError? = null) {
        val session = lease ?: return
        LiveTranslationController.sessions.stop(session, error)
        if (cleanupStarted) return
        cleanupStarted = true
        recognitionTurns.pause()
        retry?.cancel()
        recognitionWatchdog?.cancel()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop() }
        initialization?.cancel()
        processing?.cancel()
        scope.launch {
            initialization?.join()
            processing?.join() // AudioTrack's finally releases resources before another capture may start.
            translator?.let { runCatching { it.destroy() } }
            translator = null
            runCatching { tts?.shutdown() }
            tts = null
            synthesis = null
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
            sentences.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            bootstrapOnly = false
            val finishedStartId = startCommandId
            lease = null
            initialization = null
            processing = null
            playbackDevice = null
            outputDeviceId = null
            recognitionFailures = 0
            cleanupStarted = false
            recognitionTurns.resume()
            microphoneOwnership.release(session)
            if (!destroyed) stopSelf(finishedStartId)
            LiveTranslationController.sessions.finish(session)
            // A new start may reach this same instance before Android destroys it.
            if (destroyed) scope.cancel()
        }
    }

    override fun onTimeout(startId: Int) {
        if (!bootstrapOnly) return
        requestStop(TranslationError.TIMEOUT)
        stopSelf(startId)
    }

    override fun onDestroy() {
        destroyed = true
        requestStop()
        if (lease == null) {
            scope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            bootstrapOnly = false
        }
        runCatching { unregisterReceiver(noisy) }
        audio.unregisterAudioDeviceCallback(devices)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "me.kavishdevar.librepods.LIVE_TRANSLATION_START"
        private const val ACTION_STOP = "me.kavishdevar.librepods.LIVE_TRANSLATION_STOP"
        private const val EXTRA_SESSION = "session"
        private const val CHANNEL = "live_translation"
        internal fun start(context: Context, pair: TranslationPair, output: TranslationOutput) {
            if (Looper.myLooper() != Looper.getMainLooper()) { context.mainExecutor.execute { start(context, pair, output) }; return }
            val session = LiveTranslationController.sessions.begin(pair, output) ?: return
            if (!microphoneOwnership.acquire(session)) {
                LiveTranslationController.sessions.stop(session, TranslationError.MICROPHONE_BUSY)
                LiveTranslationController.sessions.finish(session)
                return
            }
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                (output == TranslationOutput.AIRPODS && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                LiveTranslationController.sessions.stop(session, TranslationError.PERMISSION)
                microphoneOwnership.release(session)
                LiveTranslationController.sessions.finish(session)
                return
            }
            try {
                context.startForegroundService(Intent(context, LiveTranslationService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_SESSION, session.id))
            } catch (_: Exception) {
                LiveTranslationController.sessions.stop(session, TranslationError.SERVICE)
                microphoneOwnership.release(session)
                LiveTranslationController.sessions.finish(session)
            }
        }
        fun stop(context: Context) {
            if (Looper.myLooper() != Looper.getMainLooper()) { context.mainExecutor.execute { stop(context) }; return }
            val session = LiveTranslationController.sessions.stop() ?: return
            if (microphoneOwnership.cancelPending(session)) LiveTranslationController.sessions.finish(session)
            else context.stopService(Intent(context, LiveTranslationService::class.java))
        }
    }
}
