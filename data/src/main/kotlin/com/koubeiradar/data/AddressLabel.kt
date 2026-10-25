package com.koubeiradar.data

/** Longest label a [ReverseGeocoder] returns; the home row is one line and the meal settings a short sentence. */
const val MAX_ADDRESS_LABEL_LENGTH = 30

private const val SEPARATOR = " · "
private const val NEARBY = "附近"
private const val ELLIPSIS = "…"

/**
 * The short label both providers build from their reverse geocoding reply: "[area] · [hint]附近" ([area] = district + township,
 * [hint] = the nearest building / business district / street). Without an area the hint stands alone, without a hint the
 * area does; with neither, [formatted] is used with the leading [prefixes] (province, city) removed. Blank pieces count as absent.
 * The result is never longer than [MAX_ADDRESS_LABEL_LENGTH] and is `null` when nothing is left.
 */
internal fun shortAddressLabel(area: String?, hint: String?, formatted: String?, vararg prefixes: String?): String? {
    val place = area?.trim().orEmpty()
    val near = hint?.trim().orEmpty()
    val label = when {
        place.isNotEmpty() && near.isNotEmpty() -> "$place$SEPARATOR$near$NEARBY"
        place.isNotEmpty() -> place
        near.isNotEmpty() -> "$near$NEARBY"
        else -> stripPrefixes(formatted?.trim().orEmpty(), prefixes)
    }
    return label.takeIf { it.isNotEmpty() }?.let(::clamp)
}

private fun stripPrefixes(address: String, prefixes: Array<out String?>): String {
    var rest = address
    for (prefix in prefixes) {
        val p = prefix?.trim().orEmpty()
        if (p.isNotEmpty() && rest.length > p.length && rest.startsWith(p)) rest = rest.substring(p.length)
    }
    return rest
}

private fun clamp(label: String): String =
    if (label.length <= MAX_ADDRESS_LABEL_LENGTH) label else label.take(MAX_ADDRESS_LABEL_LENGTH - ELLIPSIS.length).trimEnd() + ELLIPSIS
