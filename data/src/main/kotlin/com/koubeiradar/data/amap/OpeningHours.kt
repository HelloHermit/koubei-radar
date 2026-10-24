package com.koubeiradar.data.amap

import java.time.Instant
import java.time.ZoneId

/**
 * Evaluates Amap's `opentime_today` (`08:30-21:30`, `09:00-14:00 17:00-21:00`, `00:00-24:00`, `18:00-02:00`)
 * against a point in time, in the zone the shops operate in.
 */
internal object OpeningHours {
    private val SHOP_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private const val MINUTES_PER_DAY = 24 * 60
    private const val SEPARATORS = ",;，；、"
    private val RANGE = Regex("""(\d{1,2})[:：](\d{2})\s*[-~～–—至到]\s*(\d{1,2})[:：](\d{2})""")

    /** `true`/`false` when [openTime] consists solely of time ranges; `null` for any other text. */
    fun isOpenAt(openTime: String, epochMillis: Long): Boolean? {
        val spans = parse(openTime) ?: return null
        val now = Instant.ofEpochMilli(epochMillis).atZone(SHOP_ZONE)
        val minuteOfDay = now.hour * 60 + now.minute
        return spans.any { it.contains(minuteOfDay) }
    }

    private fun parse(openTime: String): List<Span>? {
        val ranges = RANGE.findAll(openTime).toList()
        if (ranges.isEmpty()) return null
        if (RANGE.replace(openTime, " ").any { !it.isWhitespace() && it !in SEPARATORS }) return null
        return ranges.map { range ->
            val (openHour, openMinute, closeHour, closeMinute) = range.destructured
            val open = minuteOfDay(openHour, openMinute, isClosingTime = false) ?: return null
            val close = minuteOfDay(closeHour, closeMinute, isClosingTime = true) ?: return null
            if (open == close) return null
            Span(open, close)
        }
    }

    private fun minuteOfDay(hour: String, minute: String, isClosingTime: Boolean): Int? {
        val h = hour.toInt()
        val m = minute.toInt()
        return when {
            m > 59 -> null
            h < 24 -> h * 60 + m
            h == 24 && m == 0 && isClosingTime -> MINUTES_PER_DAY
            else -> null
        }
    }

    /** `[open, close)` in minutes since midnight; a span with `close < open` runs past midnight. */
    private class Span(val open: Int, val close: Int) {
        fun contains(minuteOfDay: Int): Boolean =
            if (open < close) minuteOfDay in open until close else minuteOfDay >= open || minuteOfDay < close
    }
}
