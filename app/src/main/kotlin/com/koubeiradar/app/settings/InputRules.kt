package com.koubeiradar.app.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class InputProblem {
    EMPTY,
    TOO_LONG,
    CONTAINS_WHITESPACE,
    NOT_PLAIN_ASCII,
    NOT_HTTPS,
    INVALID_URL,
    URL_HAS_CREDENTIALS,
}

sealed interface Checked {
    /** [value] is trimmed and normalised, ready to be stored. */
    data class Ok(val value: String) : Checked

    data class Rejected(val problem: InputProblem) : Checked
}

/** Validation and normalisation of the free-text settings fields. Pure, so it is unit-tested without Android. */
object InputRules {
    private const val MAX_KEY_LENGTH = 512
    private const val MAX_MODEL_LENGTH = 100
    private const val MAX_URL_LENGTH = 300
    private const val CHAT_COMPLETIONS_SUFFIX = "/chat/completions"

    /**
     * API keys travel in HTTP headers / query strings, so only visible ASCII is accepted; anything else
     * (full-width punctuation from a copy-paste, zero-width spaces) would make the HTTP stack throw at request time.
     */
    fun credential(raw: String): Checked {
        val value = raw.trim()
        return when {
            value.isEmpty() -> Checked.Rejected(InputProblem.EMPTY)
            value.length > MAX_KEY_LENGTH -> Checked.Rejected(InputProblem.TOO_LONG)
            value.any { it.isWhitespace() } -> Checked.Rejected(InputProblem.CONTAINS_WHITESPACE)
            value.any { it !in VISIBLE_ASCII } -> Checked.Rejected(InputProblem.NOT_PLAIN_ASCII)
            else -> Checked.Ok(value)
        }
    }

    fun modelName(raw: String, required: Boolean): Checked {
        val value = raw.trim()
        return when {
            value.isEmpty() -> if (required) Checked.Rejected(InputProblem.EMPTY) else Checked.Ok("")
            value.length > MAX_MODEL_LENGTH -> Checked.Rejected(InputProblem.TOO_LONG)
            value.any { it.isWhitespace() } -> Checked.Rejected(InputProblem.CONTAINS_WHITESPACE)
            value.any { it !in VISIBLE_ASCII } -> Checked.Rejected(InputProblem.NOT_PLAIN_ASCII)
            else -> Checked.Ok(value)
        }
    }

    /**
     * HTTPS only (the app forbids cleartext traffic). A missing scheme is completed to `https://`; a pasted full
     * endpoint (`…/chat/completions`) is cut back to the base URL; a trailing slash is dropped.
     */
    fun baseUrl(raw: String, required: Boolean): Checked {
        var value = raw.trim()
        if (value.isEmpty()) return if (required) Checked.Rejected(InputProblem.EMPTY) else Checked.Ok("")
        if (value.length > MAX_URL_LENGTH) return Checked.Rejected(InputProblem.TOO_LONG)
        if (value.any { it.isWhitespace() }) return Checked.Rejected(InputProblem.CONTAINS_WHITESPACE)
        if ("://" !in value) value = "https://$value"
        val url = value.toHttpUrlOrNull() ?: return Checked.Rejected(InputProblem.INVALID_URL)
        return when {
            !url.isHttps -> Checked.Rejected(InputProblem.NOT_HTTPS)
            url.username.isNotEmpty() || url.password.isNotEmpty() -> Checked.Rejected(InputProblem.URL_HAS_CREDENTIALS)
            url.query != null || url.fragment != null -> Checked.Rejected(InputProblem.INVALID_URL)
            else -> Checked.Ok(url.toString().trimEnd('/').removeSuffix(CHAT_COMPLETIONS_SUFFIX).trimEnd('/'))
        }
    }

    private val VISIBLE_ASCII = '!'..'~'
}
