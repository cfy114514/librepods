/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.services.ServiceManager

object MediaController {
    private var owner: Any? = null
    private var audioManager: AudioManager? = null
    private var playbackSession: OwnedCallbackLifecycle.Session? = null
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private val handler = Handler(Looper.getMainLooper())
    private val conversationVolumes = ConversationVolumeSession(Dispatchers.IO,
        onError = { Log.w("MediaController", "Conversation volume update failed", it) })
    private var conversationVolumeLease: ConversationVolumeSession.Lease? = null
    private val callbackLifecycle = OwnedCallbackLifecycle()
    private data class PlaybackEvent(val flags: Int, val epoch: Long)
    private val playbackQueries = PlaybackStateQueries(Dispatchers.IO, Dispatchers.Main.immediate,
        onError = { Log.w("MediaController", "Playback state query failed", it) })
    private var playbackRefreshEpoch = 0L
    private var playbackActionRevision = 0L
    private val mediaCommands = MediaCommandGate()
    private var mediaCommandInFlight = false
    private var deferredPlaybackCallback: (() -> Unit)? = null
    private var deferredPlaybackRefresh: (() -> Unit)? = null
    private val replayCommands = KeyedWorkSession<Unit, Runnable>(setOf(Unit), Dispatchers.IO,
        onError = { Log.w("MediaController", "Replay command failed", it) }) { it.run() }

    var pausedWhileTakingOver = false
    var pausedForOtherDevice = false

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private const val HAS_PLAYBACK_CONFIG = 1
    private const val HAS_MUSIC_OR_MOVIE = 2
    private var lastKnownIsMusicActive: Boolean? = null

    private const val PAUSED_FOR_OTHER_DEVICE_CLEAR_MS = 500L
    private var clearPausedForOtherDeviceRunnable: Runnable? = null
    private var playbackRefreshRunnable: Runnable? = null

    var recentlyLostOwnership: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    @Synchronized
    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences, owner: Any) {
        callbackLifecycle.start(owner) { session ->
            this.audioManager = audioManager
            this.owner = owner
            playbackSession = session
            resetPlaybackState()
            val queryLease = playbackQueries.claim { session.isActive }
            val volumeLease = conversationVolumes.claim(object : ConversationVolumeBackend {
                override fun volume() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                override fun maximum() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                override fun set(value: Int) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            }, valid = { session.isActive },
                onBegin = { pause, current ->
                    if (pause) captureMediaPause(
                        onError = { Log.w("MediaController", "Conversation pause attempted but dispatch failed", it) }
                    ) { entered -> pauseFor(session, true, current, entered) }
                    else false
                },
                onEnd = { paused, resume, current ->
                    if (paused && resume) playFor(session, false, false, current)
                    else session.isActive && current()
                }, readSettings = { ConversationVolumeSettings.from(sharedPreferences.all) })
            conversationVolumeLease = volumeLease


            clearPausedForOtherDeviceRunnable = Runnable {
                synchronized(this@MediaController) {
                    if (session.isActive) pausedForOtherDevice = false
                }
            }
            playbackRefreshRunnable = Runnable {
                synchronized(this@MediaController) {
                    if (session.isActive) {
                        queryPlaybackState(session, queryLease, audioManager,
                            PlaybackStateQueries.Kind.REFRESH, playbackRefreshEpoch)
                    }
                }
            }
            val playbackUpdates = LatestCallbackThrottle<PlaybackEvent>(
                intervalMillis = PLAYBACK_DEBOUNCE_MS,
                nowMillis = SystemClock::uptimeMillis,
                schedule = { delay, action ->
                    val callback = Runnable { synchronized(this@MediaController) { action() } }
                    handler.postDelayed(callback, delay)
                    val cancel: () -> Unit = { handler.removeCallbacks(callback) }
                    cancel
                },
                consume = { event ->
                    if (session.isActive) {
                        queryPlaybackState(session, queryLease, audioManager,
                            PlaybackStateQueries.Kind.CALLBACK, event.epoch, event.flags)
                    }
                }
            )
            val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                @RequiresApi(Build.VERSION_CODES.R)
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    synchronized(this@MediaController) {
                        if (!session.isActive) return
                        // Keep only flags, not the framework's potentially large player list.
                        val musicOrMovie = configs?.any {
                            it.audioAttributes.contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                                it.audioAttributes.contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE
                        } == true
                        val epoch = queryLease.advance() ?: return
                        val flags = (if (configs != null) HAS_PLAYBACK_CONFIG else 0) or
                            (if (musicOrMovie) HAS_MUSIC_OR_MOVIE else 0)
                        playbackUpdates.offer(PlaybackEvent(flags, epoch))
                    }
                }
            }
            val cleanup: () -> Unit = {
                playbackUpdates.close()
                queryLease.close()
                runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
                volumeLease.close()
                handler.removeCallbacksAndMessages(null)
                conversationVolumeLease = null
                clearPausedForOtherDeviceRunnable = null
                playbackRefreshRunnable = null
                playbackRefreshEpoch = 0L
                this.audioManager = null
                playbackSession = null
                this.owner = null
                resetPlaybackState()
            }
            try {
                audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            } catch (error: Throwable) {
                cleanup()
                throw error
            }
            cleanup
        }
    }

    @Synchronized
    fun release(owner: Any) {
        callbackLifecycle.stop(owner)
    }

    private fun resetPlaybackState() {
        iPausedTheMedia = false
        userPlayedTheMedia = false
        pausedWhileTakingOver = false
        pausedForOtherDevice = false
        recentlyLostOwnership = false
        lastSelfActionAt = 0L
        lastKnownIsMusicActive = null
        lastPlayWithReplay = false
        lastPlayTime = 0L
        playbackActionRevision = 0L
        mediaCommandInFlight = false
        deferredPlaybackCallback = null
        deferredPlaybackRefresh = null
    }

    /** Caller holds the controller monitor; platform reads never do. */
    private fun queryPlaybackState(
        session: OwnedCallbackLifecycle.Session, lease: PlaybackStateQueries.Lease,
        manager: AudioManager, kind: PlaybackStateQueries.Kind, epoch: Long, flags: Int = 0
    ) {
        val actionRevision = playbackActionRevision
        lease.offer(kind, epoch, query = { manager.isMusicActive }) { isActive, current ->
            synchronized(this) {
                if (session.isActive && current()) {
                    if (mediaCommandInFlight) {
                        val retry = {
                            if (session.isActive && current())
                                queryPlaybackState(session, lease, manager, kind, epoch, flags)
                        }
                        if (kind == PlaybackStateQueries.Kind.CALLBACK) deferredPlaybackCallback = retry
                        else deferredPlaybackRefresh = retry
                    } else if (actionRevision != playbackActionRevision) {
                        // A media command can change playback without another SDK notification.
                        queryPlaybackState(session, lease, manager, kind, epoch, flags)
                    } else if (kind == PlaybackStateQueries.Kind.CALLBACK) {
                        playbackRefreshEpoch = epoch
                        handlePlaybackConfigChanged(session, flags, isActive)
                    } else {
                        userPlayedTheMedia = isActive
                        if (isActive) pausedForOtherDevice = false
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun handlePlaybackConfigChanged(
        session: OwnedCallbackLifecycle.Session, flags: Int, isActive: Boolean
    ) {
        val now = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG) Log.d("MediaController", "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive, pausedForOtherDevice: $pausedForOtherDevice, lastKnownIsMusicActive: $lastKnownIsMusicActive")

        if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
            if (BuildConfig.DEBUG) Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
            lastPlayWithReplay = false
            // This callback runs on Main with the state monitor; enqueue only.
            replayCommands.offer(Unit, Runnable { playFor(session, false, false) { session.isActive } })
            lastKnownIsMusicActive = true
            return
        }

        if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
            if (BuildConfig.DEBUG) Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
            lastKnownIsMusicActive = isActive
            return
        }

        val hasNewMusicOrMovie = flags and HAS_MUSIC_OR_MOVIE != 0

        if (BuildConfig.DEBUG) Log.d("MediaController", "Has new music or movie: $hasNewMusicOrMovie")

        if (pausedForOtherDevice) {
            clearPausedForOtherDeviceRunnable?.let {
                handler.removeCallbacks(it)
                handler.postDelayed(it, PAUSED_FOR_OTHER_DEVICE_CLEAR_MS)
            }

            if (isActive) {
                if (BuildConfig.DEBUG) Log.d("MediaController", "Detected play while pausedForOtherDevice; attempting to take over")
                if (!recentlyLostOwnership && hasNewMusicOrMovie) {
                    pausedForOtherDevice = false
                    userPlayedTheMedia = true
                    if (!pausedWhileTakingOver) {
                        ServiceManager.getService()?.takeOver("music")
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.d("MediaController", "Skipping take-over due to recent ownership loss or no new music/movie")
                }
            } else {
                if (BuildConfig.DEBUG) Log.d("MediaController", "Still not active while pausedForOtherDevice; will clear state after timeout")
            }

            lastKnownIsMusicActive = isActive
            return
        }

        if (flags and HAS_PLAYBACK_CONFIG != 0 && !iPausedTheMedia) {
            val service = ServiceManager.getService() ?: return
            if (service.localMac == "") return
            service.sendMediaInformationAsync(isActive)
            if (BuildConfig.DEBUG) Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
            playbackRefreshRunnable?.let {
                handler.removeCallbacks(it)
                handler.postDelayed(it, 7)
            }
        }

        if (BuildConfig.DEBUG) Log.d("MediaController", "pausedWhileTakingOver: $pausedWhileTakingOver")
        if (!pausedWhileTakingOver && isActive && hasNewMusicOrMovie && lastKnownIsMusicActive != true) {
            if (!recentlyLostOwnership) {
                if (BuildConfig.DEBUG) Log.d("MediaController", "Music/movie is active and not pausedWhileTakingOver; requesting takeOver")
                ServiceManager.getService()?.takeOver("music")
            } else {
                if (BuildConfig.DEBUG) Log.d("MediaController", "Skipping take-over due to recent ownership loss")
            }
        }

        lastKnownIsMusicActive = hasNewMusicOrMovie && isActive
    }

    fun getMusicActive(): Boolean {
        val (manager, session) = synchronized(this) {
            val manager = audioManager ?: return false
            val session = playbackSession ?: return false
            manager to session
        }
        // A slow system query must not hold up release or a replacement registration.
        val isActive = manager.isMusicActive
        return session.isActive && isActive
    }

    private data class CommandContext(
        val manager: AudioManager, val session: OwnedCallbackLifecycle.Session,
        val current: () -> Boolean
    )

    private fun commandCurrent(command: CommandContext): Boolean =
        command.session.isActive && command.current()

    /** Captures the caller's owner before waiting; SDK calls use a separate serialization gate. */
    private fun commandFor(
        expected: OwnedCallbackLifecycle.Session? = null, current: () -> Boolean = { true },
        action: (CommandContext) -> Boolean
    ): Boolean {
        val command = synchronized(this) {
            val manager = audioManager ?: return false
            val session = playbackSession ?: return false
            if (expected != null && session !== expected) return false
            CommandContext(manager, session, current)
        }
        return mediaCommands.run({ commandCurrent(command) }) {
            val accepted = synchronized(this) {
                if (!commandCurrent(command)) false else {
                    mediaCommandInFlight = true
                    playbackActionRevision++
                    true
                }
            }
            if (!accepted) false else try { action(command) }
            finally {
                synchronized(this) {
                    if (command.session.isActive && playbackSession === command.session) {
                        mediaCommandInFlight = false
                        playbackActionRevision++
                        val callback = deferredPlaybackCallback
                        val refresh = deferredPlaybackRefresh
                        deferredPlaybackCallback = null; deferredPlaybackRefresh = null
                        callback?.invoke(); refresh?.invoke()
                    }
                }
            }
        } ?: false
    }

    private fun sendKeyPair(command: CommandContext, code: Int, onDispatch: () -> Unit = {}): Boolean {
        if (!commandCurrent(command)) return false
        dispatchMediaKeyPair { down ->
            if (down) onDispatch()
            command.manager.dispatchMediaKeyEvent(KeyEvent(
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code))
        }
        synchronized(this) {
            if (commandCurrent(command)) lastSelfActionAt = SystemClock.uptimeMillis()
        }
        return commandCurrent(command)
    }

    fun sendPlayPause() {
        commandFor { command ->
            val active = command.manager.isMusicActive
            if (!commandCurrent(command)) false
            else if (active) pauseCommand(command, false, active)
            else playCommand(command, false, false)
        }
    }

    fun sendPreviousTrack() {
        commandFor { sendKeyPair(it, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
    }

    fun sendNextTrack() {
        commandFor { sendKeyPair(it, KeyEvent.KEYCODE_MEDIA_NEXT) }
    }

    fun sendPause(force: Boolean = false) { pauseFor(null, force) }

    private fun pauseFor(expected: OwnedCallbackLifecycle.Session?, force: Boolean,
        current: () -> Boolean = { true }, onDispatch: () -> Unit = {}): Boolean = commandFor(expected, current) { command ->
        pauseCommand(command, force, command.manager.isMusicActive, onDispatch)
    }

    private fun pauseCommand(command: CommandContext, force: Boolean, active: Boolean,
        onDispatch: () -> Unit = {}): Boolean {
        val dispatch = synchronized(this) {
            if (!commandCurrent(command)) return false
            if (active && (!userPlayedTheMedia || force)) {
                iPausedTheMedia = true
                userPlayedTheMedia = false
                true
            } else false
        }
        return if (dispatch) sendKeyPair(command, KeyEvent.KEYCODE_MEDIA_PAUSE, onDispatch) else commandCurrent(command)
    }

    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        playFor(null, replayWhenPaused, force)
    }

    private fun playFor(expected: OwnedCallbackLifecycle.Session?, replayWhenPaused: Boolean,
        force: Boolean, current: () -> Boolean = { true }): Boolean = commandFor(expected, current) {
        playCommand(it, replayWhenPaused, force)
    }

    private fun playCommand(command: CommandContext, replayWhenPaused: Boolean, force: Boolean): Boolean {
        val dispatch = synchronized(this) {
            if (!commandCurrent(command)) return false
            if (replayWhenPaused) {
                lastPlayWithReplay = true
                lastPlayTime = SystemClock.uptimeMillis()
            }
            (iPausedTheMedia || force).also { if (it) userPlayedTheMedia = false }
        }
        if (dispatch && !sendKeyPair(command, KeyEvent.KEYCODE_MEDIA_PLAY)) return false
        if (!commandCurrent(command)) return false
        val active = command.manager.isMusicActive
        return synchronized(this) {
            if (!commandCurrent(command)) false else {
                if (!active) iPausedTheMedia = false
                if (pausedWhileTakingOver) pausedWhileTakingOver = false
                true
            }
        }
    }

    @Synchronized
    fun startSpeaking(owner: Any) {
        if (this.owner !== owner) return
        conversationVolumeLease?.start()
    }

    @Synchronized
    fun stopSpeaking(owner: Any, resumeMedia: Boolean = true) {
        if (this.owner !== owner) return
        conversationVolumeLease?.stop(resumeMedia)
    }
}
