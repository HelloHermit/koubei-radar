package com.koubeiradar.app.platform

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** What another app shared with us, already capped. [imageRefs] are `content://` URIs rendered as text. */
data class SharePayload(val text: String?, val imageRefs: List<String>)

/** Limits for everything that enters the import flow, whether shared by another app, picked or typed. */
object ImportLimits {
    const val MAX_TEXT_CHARS = 20_000
    const val MAX_IMAGES = 6

    private const val CONTENT_SCHEME_PREFIX = "content://"

    /** At most [MAX_TEXT_CHARS] characters, never cutting a surrogate pair in half. */
    fun capText(text: String): String {
        if (text.length <= MAX_TEXT_CHARS) return text
        val end = if (text[MAX_TEXT_CHARS - 1].isHighSurrogate()) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
        return text.substring(0, end)
    }

    /** Only `content://` references qualify (never `file://`, which could point into private storage), each once. */
    fun acceptableImages(refs: List<String>): List<String> =
        refs.filter { it.startsWith(CONTENT_SCHEME_PREFIX) }.distinct()

    /** [acceptableImages], at most [MAX_IMAGES] of them. */
    fun capImages(refs: List<String>): List<String> = acceptableImages(refs).take(MAX_IMAGES)

    /** `null` when nothing usable remains. */
    fun sanitizeShare(text: CharSequence?, imageRefs: List<String>): SharePayload? {
        val cappedText = text?.toString()?.let(::capText)?.takeIf { it.isNotBlank() }
        val cappedImages = capImages(imageRefs)
        return if (cappedText == null && cappedImages.isEmpty()) null else SharePayload(cappedText, cappedImages)
    }
}

/** Reads `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents. Extras of an exported activity are untrusted input. */
object ShareIntake {
    fun parse(intent: Intent?): SharePayload? {
        val action = intent?.action
        if (intent == null || (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE)) return null
        return try {
            val uris = if (action == Intent.ACTION_SEND) {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            ImportLimits.sanitizeShare(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), uris.map(Uri::toString))
        } catch (e: RuntimeException) {
            // Malformed or hostile extras (BadParcelableException, ClassCastException ...) are simply ignored.
            null
        }
    }
}

/** Hand-over point between the activity (which receives shares) and the import screen (which consumes them). */
class ShareInbox {
    private val _pending = MutableStateFlow<SharePayload?>(null)
    val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

    private val arrivalSignals = Channel<Unit>(Channel.CONFLATED)

    /**
     * One signal per [offer], held until the app's UI takes it. [pending] cannot tell the UI that a share arrived:
     * an import screen that is already alive consumes the payload at once, before the UI could observe it.
     */
    val arrivals: Flow<Unit> = arrivalSignals.receiveAsFlow()

    fun offer(payload: SharePayload) {
        _pending.value = payload
        arrivalSignals.trySend(Unit)
    }

    /** Clears [payload] only if it is still the pending one, so a newer share is not lost. */
    fun consume(payload: SharePayload) {
        _pending.compareAndSet(payload, null)
    }
}
