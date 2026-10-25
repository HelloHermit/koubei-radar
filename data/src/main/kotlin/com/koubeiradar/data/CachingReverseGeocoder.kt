package com.koubeiradar.data

import com.koubeiradar.core.model.GeoPoint
import kotlin.math.roundToLong

/**
 * Remembers the last [maxEntries] successful answers of [delegate], keyed by the position rounded to 3 decimals
 * (about 100 m), so pull-to-refresh and re-locating in the same spot do not spend the provider's daily quota.
 * `null` answers and failures are not cached. Safe for concurrent use; two simultaneous misses for the same cell may both reach [delegate].
 */
class CachingReverseGeocoder(
    private val delegate: ReverseGeocoder,
    private val maxEntries: Int = 32,
) : ReverseGeocoder {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private data class Cell(val latMilli: Long, val lngMilli: Long)

    private val lock = Any()
    private val entries = object : LinkedHashMap<Cell, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Cell, String>): Boolean = size > maxEntries
    }

    override suspend fun reverseGeocode(point: GeoPoint): String? {
        val cell = Cell((point.lat * 1000).roundToLong(), (point.lng * 1000).roundToLong())
        synchronized(lock) { entries[cell] }?.let { return it }
        val label = delegate.reverseGeocode(point) ?: return null
        synchronized(lock) { entries[cell] = label }
        return label
    }
}
