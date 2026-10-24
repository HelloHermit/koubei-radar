package com.koubeiradar.app

import com.koubeiradar.app.platform.Downscaler
import com.koubeiradar.app.platform.Gcj02
import com.koubeiradar.app.platform.ImportLimits
import com.koubeiradar.app.platform.LocationResult
import com.koubeiradar.app.platform.RawFix
import com.koubeiradar.app.platform.Wgs84Point
import com.koubeiradar.app.platform.newestWithin
import com.koubeiradar.app.platform.orderedDeliveryApps
import com.koubeiradar.app.service.AgentNotes
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Platform
import com.koubeiradar.data.DeliveryApps
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
    return 2 * 6_371_000 * asin(sqrt(h))
}

class Gcj02Test {
    private val beijing = Wgs84Point(39.9042, 116.4074)
    private val shanghai = Wgs84Point(31.2304, 121.4737)
    private val shenzhen = Wgs84Point(22.5431, 114.0579)

    @Test
    fun `points outside mainland China are returned unchanged`() {
        listOf(
            Wgs84Point(35.6762, 139.6503), // Tokyo
            Wgs84Point(51.5074, -0.1278), // London
            Wgs84Point(40.7128, -74.0060), // New York
            Wgs84Point(-33.8688, 151.2093), // Sydney
            Wgs84Point(55.7558, 37.6173), // Moscow
        ).forEach { assertEquals(GeoPoint(it.lat, it.lng), Gcj02.fromWgs84(it), "$it") }
    }

    @Test
    fun `shifts the big Chinese cities by a few hundred metres`() {
        listOf(beijing, shanghai, shenzhen).forEach { wgs ->
            val shifted = Gcj02.fromWgs84(wgs)
            val offset = metersBetween(GeoPoint(wgs.lat, wgs.lng), shifted)
            assertTrue(offset in 100.0..700.0, "offset $offset m for $wgs")
        }
    }

    @Test
    fun `is deterministic`() {
        assertEquals(Gcj02.fromWgs84(beijing), Gcj02.fromWgs84(beijing))
    }

    @Test
    fun `converting an already converted point shifts it again, so a fix must be converted exactly once`() {
        val once = Gcj02.fromWgs84(beijing)
        val twice = Gcj02.fromWgs84(Wgs84Point(once.lat, once.lng))
        assertNotEquals(once, twice)
        assertTrue(metersBetween(once, twice) > 100.0)
    }

    @Test
    fun `bounding box edges`() {
        assertTrue(Gcj02.isOutsideChina(lat = 0.8, lng = 100.0))
        assertTrue(Gcj02.isOutsideChina(lat = 56.0, lng = 100.0))
        assertTrue(Gcj02.isOutsideChina(lat = 30.0, lng = 71.9))
        assertTrue(Gcj02.isOutsideChina(lat = 30.0, lng = 138.0))
        assertTrue(!Gcj02.isOutsideChina(lat = 30.0, lng = 100.0))
    }
}

class RawFixTest {
    @Test
    fun `a fix is converted once and keeps its accuracy`() {
        val fix = RawFix(lat = 39.9042, lng = 116.4074, accuracyMeters = 25.4f, timeMillis = 1L)
        val found = fix.toLocation()
        assertEquals(Gcj02.fromWgs84(Wgs84Point(39.9042, 116.4074)), found.point)
        assertEquals(25, found.accuracyMeters)
        assertEquals(LocationResult.Found(found.point, 25), found)
    }

    @Test
    fun `last known fix is the newest recent one and ties prefer accuracy`() {
        val now = 10_000_000L
        val old = RawFix(1.0, 1.0, 10f, now - 2 * HOUR)
        val older = RawFix(2.0, 2.0, 10f, now - HOUR / 2)
        val newerCoarse = RawFix(3.0, 3.0, 500f, now - 60_000)
        val newerFine = RawFix(4.0, 4.0, 15f, now - 60_000)
        assertEquals(newerFine, listOf(old, older, newerCoarse, newerFine).newestWithin(now, HOUR))
        assertEquals(older, listOf(old, older).newestWithin(now, HOUR))
        assertNull(listOf(old).newestWithin(now, HOUR))
        assertNull(listOf(RawFix(1.0, 1.0, null, now + 1)).newestWithin(now, HOUR), "fixes from the future are ignored")
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}

class ImportLimitsTest {
    @Test
    fun `short text is kept and long text is capped`() {
        assertEquals("abc", ImportLimits.capText("abc"))
        assertEquals(ImportLimits.MAX_TEXT_CHARS, ImportLimits.capText("x".repeat(50_000)).length)
    }

    @Test
    fun `capping never splits a surrogate pair`() {
        val text = "a".repeat(ImportLimits.MAX_TEXT_CHARS - 1) + "😀😀"
        val capped = ImportLimits.capText(text)
        assertEquals(ImportLimits.MAX_TEXT_CHARS - 1, capped.length)
        assertTrue(capped.none { it.isSurrogate() })
    }

    @Test
    fun `only content URIs are accepted, each once, at most six`() {
        val refs = listOf("file:///data/data/x", "content://a/1", "content://a/1") + (2..9).map { "content://a/$it" }
        val capped = ImportLimits.capImages(refs)
        assertEquals(6, capped.size)
        assertEquals("content://a/1", capped.first())
        assertTrue(capped.all { it.startsWith("content://") })
        assertEquals(9, ImportLimits.acceptableImages(refs).size)
    }

    @Test
    fun `a share without usable content is dropped`() {
        assertNull(ImportLimits.sanitizeShare("   ", listOf("file:///x")))
        assertNull(ImportLimits.sanitizeShare(null, emptyList()))
        val payload = ImportLimits.sanitizeShare("店铺列表", listOf("content://a/1"))
        assertEquals("店铺列表", payload?.text)
        assertEquals(listOf("content://a/1"), payload?.imageRefs)
    }
}

class DownscalerTest {
    @Test
    fun `fits the long edge and keeps the aspect ratio`() {
        assertEquals(1176 to 1568, Downscaler.fitWithin(3000, 4000).let { it.width to it.height })
        assertEquals(1568 to 882, Downscaler.fitWithin(3840, 2160).let { it.width to it.height })
    }

    @Test
    fun `never upscales`() {
        val size = Downscaler.fitWithin(800, 600)
        assertEquals(800 to 600, size.width to size.height)
    }

    @Test
    fun `extreme aspect ratios keep at least one pixel`() {
        val size = Downscaler.fitWithin(20_000, 3)
        assertEquals(1568, size.width)
        assertEquals(1, size.height)
    }

    @Test
    fun `sample size is a power of two that keeps the long edge above the target`() {
        assertEquals(1, Downscaler.sampleSize(1568, 1000))
        assertEquals(1, Downscaler.sampleSize(3135, 2000))
        assertEquals(2, Downscaler.sampleSize(3136, 2000))
        assertEquals(8, Downscaler.sampleSize(13_000, 9_000))
    }

    @Test
    fun `rejects non positive sizes`() {
        assertFailsWith<IllegalArgumentException> { Downscaler.fitWithin(0, 10) }
        assertFailsWith<IllegalArgumentException> { Downscaler.sampleSize(10, -1) }
    }
}

class DeliveryOrderTest {
    @Test
    fun `apps the shop is on come first, the rest keep their order`() {
        assertEquals(listOf(Platform.ELEME, Platform.MEITUAN), orderedDeliveryApps(setOf(Platform.ELEME)).map { it.platform })
        assertEquals(DeliveryApps.all, orderedDeliveryApps(emptySet()))
        assertEquals(DeliveryApps.all, orderedDeliveryApps(Platform.entries.toSet()))
    }
}

class AgentNotesTest {
    @Test
    fun `meal periods change at the documented boundaries`() {
        fun at(h: Int, m: Int) = AgentNotes.mealPeriod(LocalTime.of(h, m))
        assertEquals(AgentNotes.MealPeriod.BREAKFAST, at(5, 0))
        assertEquals(AgentNotes.MealPeriod.BREAKFAST, at(10, 29))
        assertEquals(AgentNotes.MealPeriod.LUNCH, at(10, 30))
        assertEquals(AgentNotes.MealPeriod.LUNCH, at(13, 59))
        assertEquals(AgentNotes.MealPeriod.AFTERNOON_TEA, at(14, 0))
        assertEquals(AgentNotes.MealPeriod.DINNER, at(17, 0))
        assertEquals(AgentNotes.MealPeriod.DINNER, at(20, 29))
        assertEquals(AgentNotes.MealPeriod.LATE_NIGHT, at(20, 30))
        assertEquals(AgentNotes.MealPeriod.LATE_NIGHT, at(23, 59))
        assertEquals(AgentNotes.MealPeriod.LATE_NIGHT, at(0, 0))
        assertEquals(AgentNotes.MealPeriod.LATE_NIGHT, at(4, 59))
    }

    @Test
    fun `note reads like the example in the spec`() {
        assertEquals("现在是 12:10（午餐时间）", AgentNotes.forNow(LocalDateTime.of(2025, 3, 3, 12, 10)))
    }
}
