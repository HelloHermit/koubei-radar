package com.koubeiradar.data

import com.koubeiradar.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachingReverseGeocoderTest {
    private class Recording(var answer: (GeoPoint) -> String? = { "label@${it.lat},${it.lng}" }) : ReverseGeocoder {
        val calls = AtomicInteger()
        var failure: ShopSourceException? = null

        override suspend fun reverseGeocode(point: GeoPoint): String? {
            calls.incrementAndGet()
            failure?.let { throw it }
            return answer(point)
        }
    }

    private val inner = Recording()
    private val cache = CachingReverseGeocoder(inner, maxEntries = 3)
    private val spot = GeoPoint(lat = 39.99001, lng = 116.48001)

    @Test
    fun `the second lookup of a position is served from the cache`() = runTest {
        val first = cache.reverseGeocode(spot)

        assertEquals(first, cache.reverseGeocode(spot))
        assertEquals(1, inner.calls.get())
    }

    @Test
    fun `positions within the same thousandth share an answer, others do not`() = runTest {
        cache.reverseGeocode(spot)
        cache.reverseGeocode(GeoPoint(lat = 39.99040, lng = 116.47960))
        assertEquals(1, inner.calls.get(), "both round to 39.990 / 116.480")

        cache.reverseGeocode(GeoPoint(lat = 39.99060, lng = 116.48001))
        cache.reverseGeocode(GeoPoint(lat = 39.99001, lng = 116.48060))
        assertEquals(3, inner.calls.get())
    }

    @Test
    fun `nulls and failures are not cached`() = runTest {
        inner.answer = { null }
        assertNull(cache.reverseGeocode(spot))
        assertNull(cache.reverseGeocode(spot))
        assertEquals(2, inner.calls.get())

        inner.failure = ShopSourceException("down", ShopSourceException.Kind.NETWORK)
        assertFailsWith<ShopSourceException> { cache.reverseGeocode(spot) }
        inner.failure = null
        inner.answer = { "ok" }
        assertEquals("ok", cache.reverseGeocode(spot))
        assertEquals("ok", cache.reverseGeocode(spot))
        assertEquals(4, inner.calls.get())
    }

    @Test
    fun `the least recently used entry is dropped first`() = runTest {
        val a = GeoPoint(30.001, 120.0)
        val b = GeoPoint(30.002, 120.0)
        val c = GeoPoint(30.003, 120.0)
        val d = GeoPoint(30.004, 120.0)
        listOf(a, b, c).forEach { cache.reverseGeocode(it) }
        cache.reverseGeocode(a)
        cache.reverseGeocode(d)
        assertEquals(4, inner.calls.get())

        cache.reverseGeocode(a)
        cache.reverseGeocode(c)
        cache.reverseGeocode(d)
        assertEquals(4, inner.calls.get(), "a, c and d are cached")
        cache.reverseGeocode(b)
        assertEquals(5, inner.calls.get(), "b was the least recently used")
    }

    @Test
    fun `concurrent use returns consistent answers and keeps the cache bounded`() = runBlocking {
        val shared = CachingReverseGeocoder(inner, maxEntries = 8)
        val points = List(40) { GeoPoint(30.0 + (it % 20) / 1000.0, 120.0) }

        val answers = (0 until 400).map { i -> async(Dispatchers.Default) { shared.reverseGeocode(points[i % points.size]) } }.awaitAll()

        answers.forEachIndexed { i, label -> assertEquals("label@${points[i % points.size].lat},120.0", label) }
        val before = inner.calls.get()
        points.take(20).forEach { shared.reverseGeocode(it) }
        assertTrue(inner.calls.get() >= before + 12, "at most 8 of the 20 cells can have been remembered")
    }
}
