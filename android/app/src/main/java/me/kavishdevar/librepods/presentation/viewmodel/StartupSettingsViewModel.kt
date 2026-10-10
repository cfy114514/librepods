package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.services.CloseableResourceScope
import me.kavishdevar.librepods.services.DeferredRegistration
import me.kavishdevar.librepods.services.closeResourcesOnIo
import me.kavishdevar.librepods.utils.KeyedWorkSession
import java.util.concurrent.atomic.AtomicLong

data class StartupSettingsSnapshot(
    val preferences: SharedPreferences,
    val m3eEnabled: Boolean,
    val onboardingComplete: Boolean,
    val releaseNotesShown: Boolean,
    val releaseNotesPrefKey: String,
    val firstConnectionTime: Long,
    val reviewPrompted: Boolean
)

data class StartupSettingsState(val snapshot: StartupSettingsSnapshot? = null, val failed: Boolean = false)

/** Wait for actual startup flags before constructing navigation or binding the service. */
class StartupSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences by lazy { application.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val resources = CloseableResourceScope(closeResources = ::closeResourcesOnIo)
    private var registration: DeferredRegistration? = null
    private val revision = AtomicLong()
    @Volatile private var closed = false
    private val mutableState = MutableStateFlow(StartupSettingsState())
    val state = mutableState.asStateFlow()
    private val notesKey = "release_notes_shown_${BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-play")}"
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "m3e_enabled" || key == "onboarding_complete" || key == notesKey ||
            (BuildConfig.PLAY_BUILD && (key == "review_prompted" || key == "first_connection_successful_time"))) refresh()
    }
    private val reads = KeyedWorkSession<Unit, Long>(setOf(Unit), Dispatchers.IO) { version ->
        try { load(version) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publishFailure(version) }
    }

    init { refresh() }

    fun refresh() {
        if (!closed) reads.offer(Unit, revision.incrementAndGet())
    }

    private fun load(version: Long) {
        if (closed) return
        val prefs = preferences
        if (registration == null) {
            val callback = resources.track(DeferredRegistration {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            })
            try {
                prefs.registerOnSharedPreferenceChangeListener(listener)
                callback.didRegister()
                registration = callback
            } catch (error: Exception) {
                runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
                callback.close()
                resources.release(callback)
                throw error
            }
        }
        if (closed) return
        // getAll waits for the SDK's initial XML load and gives one coherent map.
        val values = prefs.all.toMap()
        val snapshot = StartupSettingsSnapshot(prefs,
            m3eEnabled = values["m3e_enabled"] as Boolean? ?: true,
            onboardingComplete = values["onboarding_complete"] as Boolean? ?: false,
            releaseNotesShown = values[notesKey] as Boolean? ?: false,
            releaseNotesPrefKey = notesKey,
            firstConnectionTime = if (BuildConfig.PLAY_BUILD) values["first_connection_successful_time"] as Long? ?: 0L else 0L,
            reviewPrompted = if (BuildConfig.PLAY_BUILD) values["review_prompted"] as Boolean? ?: false else false)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!closed && revision.get() == version) mutableState.value = StartupSettingsState(snapshot)
        }
    }

    private fun publishFailure(version: Long) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!closed && revision.get() == version) mutableState.value = mutableState.value.copy(failed = true)
        }
    }

    override fun onCleared() {
        closed = true
        resources.close()
        reads.close()
    }
}
