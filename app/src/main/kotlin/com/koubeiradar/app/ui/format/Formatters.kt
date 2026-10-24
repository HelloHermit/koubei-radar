package com.koubeiradar.app.ui.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Number formatting for shop facts. Only digits and unit symbols are produced here; the Chinese words around them
 * ("月售", "人均", "分钟") come from string resources so that all UI text lives in one place.
 */
object Formatters {
    /** `850m`, `1.2km`, `12km`. */
    fun distance(meters: Int): String {
        val m = meters.coerceAtLeast(0)
        if (m < 1000) return "${m}m"
        val km = m / 1000.0
        val text = String.format(Locale.ROOT, if (km < 10) "%.1f" else "%.0f", km)
        return text.removeSuffix(".0") + "km"
    }

    /** `¥32`, `¥32.5`, `¥32.55`. */
    fun price(yuan: Double): String {
        if (!yuan.isFinite()) return "¥-"
        val amount = BigDecimal(yuan).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
        return "¥" + amount.toPlainString()
    }

    /** One decimal, as delivery platforms show ratings: `4.8`. */
    fun rating(rating: Double): String = String.format(Locale.ROOT, "%.1f", rating)

    /** Composite score as a whole number in 0..100. */
    fun score(score: Double): Int = score.roundToInt().coerceIn(0, 100)

    /** `3200` -> `3200`; plain digits are clearer than "3.2千" and always fit. */
    fun count(count: Int): String = count.toString()
}
