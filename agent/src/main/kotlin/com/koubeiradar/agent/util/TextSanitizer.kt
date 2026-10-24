package com.koubeiradar.agent.util

/**
 * Helpers for text that comes from outside (reviews, shop names, pasted text) before it is shown to a model.
 * The goal is not to "detect" prompt injection but to make sure such text can never fake the structure around it.
 */

/** Removes control and invisible format characters (zero-width, bidi overrides, Unicode "tag" characters ...). */
internal fun String.stripInvisible(keepNewlines: Boolean = false): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        val isFormat = type == Character.FORMAT.toInt()
        val isControl = type == Character.CONTROL.toInt() && !(keepNewlines && (cp == '\n'.code || cp == '\t'.code))
        val isOtherSeparator = type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        when {
            isFormat -> Unit
            isOtherSeparator -> out.append('\n')
            isControl && (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code) -> out.append(' ')
            isControl -> Unit
            else -> out.appendCodePoint(cp)
        }
    }
    return out.toString()
}

/** Replaces `<` and `>` with their full-width twins so that text can never contain a tag, in particular a fence tag. */
internal fun String.neutralizeAngleBrackets(): String = replace('<', '＜').replace('>', '＞')

internal fun String.collapseWhitespace(): String = replace(WHITESPACE_RUN, " ").trim()

/** At most [max] UTF-16 units of the text (never splitting a surrogate pair), followed by [ellipsis] if it was cut. */
internal fun String.truncate(max: Int, ellipsis: String = "…"): String {
    if (length <= max) return this
    var end = max.coerceAtLeast(0)
    if (end > 0 && end < length && Character.isHighSurrogate(this[end - 1]) && Character.isLowSurrogate(this[end])) end--
    return substring(0, end) + ellipsis
}

/** Text written by the user or the model, for display: invisible characters removed, whitespace collapsed to single spaces. */
internal fun String.toCleanText(): String = stripInvisible().collapseWhitespace()

/** One-line, tag-free, length-limited rendition of untrusted text, safe to embed in a tool result. */
internal fun String.toSafeLine(max: Int): String =
    stripInvisible().collapseWhitespace().neutralizeAngleBrackets().truncate(max)

private val WHITESPACE_RUN = Regex("\\s+")
