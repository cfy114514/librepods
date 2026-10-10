package me.kavishdevar.librepods.translation

import android.content.Context
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class TranslationException(val reason: TranslationError) : Exception(reason.name)

/** OEM capability lookup can block. Share a single lookup rather than piling up cancelled UI jobs. */
internal object PlatformTranslationCapabilities {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Deferred<List<TranslationCapability>>? = null
    private var cached: List<TranslationCapability> = emptyList()
    private var cachedAt = Long.MIN_VALUE

    suspend fun load(context: Context, refresh: Boolean = false): List<TranslationCapability> {
        val query = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (!refresh && cachedAt != Long.MIN_VALUE && now - cachedAt in 0..60_000) return cached
            pending?.takeIf { !it.isCompleted } ?: scope.async {
                val manager = context.applicationContext.getSystemService(TranslationManager::class.java)
                val result = manager?.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT,
                    TranslationSpec.DATA_FORMAT_TEXT)?.toList().orEmpty()
                synchronized(this@PlatformTranslationCapabilities) {
                    cached = result
                    cachedAt = SystemClock.elapsedRealtime()
                }
                result
            }.also { pending = it }
        }
        return query.await()
    }
}

internal fun TranslationCapability.pair(): TranslationPair = TranslationPair(
    sourceSpec.locale.toLanguageTag(), targetSpec.locale.toLanguageTag())

internal class PlatformTranslationClient(private val context: Context) {
    suspend fun create(capability: TranslationCapability): Translator = suspendCancellableCoroutine { continuation ->
        val owned = AtomicReference<Translator?>()
        continuation.invokeOnCancellation { owned.getAndSet(null)?.let { runCatching { it.destroy() } } }
        val manager = context.getSystemService(TranslationManager::class.java)
        if (manager == null || capability.state != TranslationCapability.STATE_ON_DEVICE) {
            continuation.resumeWithException(TranslationException(TranslationError.NO_TRANSLATOR))
            return@suspendCancellableCoroutine
        }
        val model = TranslationContext.Builder(capability.sourceSpec, capability.targetSpec)
            .setTranslationFlags(capability.supportedTranslationFlags and TranslationContext.FLAG_LOW_LATENCY).build()
        try {
            manager.createOnDeviceTranslator(model, context.mainExecutor) { translator ->
                owned.set(translator)
                if (!continuation.isActive) owned.getAndSet(null)?.let { runCatching { it.destroy() } }
                else if (translator == null) continuation.resumeWithException(TranslationException(TranslationError.NO_TRANSLATOR))
                else continuation.resume(translator)
            }
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }

    suspend fun translate(translator: Translator, text: String): String = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        val request = TranslationRequest.Builder().setFlags(TranslationRequest.FLAG_TRANSLATION_RESULT)
            .setTranslationRequestValues(listOf(TranslationRequestValue.forText(text))).build()
        try {
            translator.translate(request, cancellation, context.mainExecutor) { response ->
                if (!response.isFinalResponse || !continuation.isActive) return@translate
                val value = response.translationResponseValues[0]
                val translated = value?.text?.toString()?.trim()
                if (response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS ||
                    value?.statusCode != TranslationResponseValue.STATUS_SUCCESS || translated.isNullOrBlank() || translated.length > 4000) {
                    continuation.resumeWithException(TranslationException(TranslationError.TRANSLATION))
                } else continuation.resume(translated)
            }
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
    }
}
