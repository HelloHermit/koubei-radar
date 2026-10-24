package com.koubeiradar.core.scoring

import java.util.Locale

internal fun clamp(value: Double, min: Double, max: Double): Double = when {
    value.isNaN() -> min
    value < min -> min
    value > max -> max
    else -> value
}

internal fun clamp01(value: Double): Double = clamp(value, 0.0, 1.0)

/** Half-up rounding to one decimal; scores are stored this way. */
internal fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

/** Half-up rounding to two decimals; the adjusted rating is stored this way. */
internal fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

/** A platform rating is only meaningful in (0, 5]; anything else is treated as unknown. */
internal fun Double?.asRating(): Double? = this?.takeIf { it.isFinite() && it > 0.0 && it <= 5.0 }

/** Prices, fees and similar amounts: negative or non-finite values are treated as unknown. */
internal fun Double?.asAmount(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

/** Counts, distances and minutes: negative values are treated as unknown. */
internal fun Int?.asCount(): Int? = this?.takeIf { it >= 0 }

/** Median of an ascending array; `null` when empty. */
internal fun medianOfSorted(sorted: DoubleArray): Double? {
    if (sorted.isEmpty()) return null
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * Percentile rank (0 = lowest, 100 = highest) of [value] within the ascending array [sorted]. Ties share the
 * average rank, so a population of identical values yields 50 for everyone.
 */
internal fun percentileRank(sorted: DoubleArray, value: Double): Double {
    if (sorted.size < 2) return 50.0
    val below = lowerBound(sorted, value)
    val equal = maxOf(upperBound(sorted, value) - below, 1)
    return (below + (equal - 1) / 2.0) / (sorted.size - 1) * 100.0
}

private fun lowerBound(sorted: DoubleArray, value: Double): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] < value) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun upperBound(sorted: DoubleArray, value: Double): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] <= value) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Locale-independent number formatting for the Chinese explanation strings. */
internal object Fmt {
    /** `4.8`, `5.0`, `4.75`: at least one and at most two decimals. */
    fun rating(value: Double): String {
        val twoDecimals = String.format(Locale.ROOT, "%.2f", value)
        return if (twoDecimals.endsWith("0")) twoDecimals.dropLast(1) else twoDecimals
    }

    fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /** `¥32` for whole amounts, `¥32.5` otherwise. */
    fun yuan(value: Double): String {
        val oneDecimal = String.format(Locale.ROOT, "%.1f", value)
        return "¥" + oneDecimal.removeSuffix(".0")
    }

    /** `600m` below one kilometre, `1.2km` above. */
    fun distance(meters: Int): String =
        if (meters < 1000) "${meters}m" else String.format(Locale.ROOT, "%.1f", meters / 1000.0).removeSuffix(".0") + "km"
}
