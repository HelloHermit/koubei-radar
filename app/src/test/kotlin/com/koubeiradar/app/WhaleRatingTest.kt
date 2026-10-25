package com.koubeiradar.app

import com.koubeiradar.app.ui.components.clipEdge
import com.koubeiradar.app.ui.components.whaleFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The arithmetic behind the five-whale rating: which whale is how full, and where the partial fill is cut. */
class WhaleRatingTest {
    private fun fills(rating: Double) = (0 until 5).map { whaleFill(rating, it) }

    @Test
    fun aWholeRatingFillsThatManyWhalesAndNoMore() {
        assertEquals(listOf(1f, 1f, 1f, 0f, 0f), fills(3.0))
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), fills(5.0))
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), fills(0.0))
    }

    @Test
    fun aFractionPartlyFillsTheNextWhale() {
        val fills = fills(4.8)
        assertEquals(listOf(1f, 1f, 1f, 1f), fills.take(4))
        assertEquals(0.8f, fills[4], 1e-6f)
    }

    @Test
    fun outOfRangeAndMissingValuesSaturateInsteadOfOverflowing() {
        assertEquals(fills(5.0), fills(7.3))
        assertEquals(fills(0.0), fills(-2.0))
        assertEquals(fills(0.0), fills(Double.NaN))
        assertEquals(fills(5.0), fills(Double.POSITIVE_INFINITY))
    }

    @Test
    fun theClipEdgeFollowsTheSilhouetteNotTheWholeIcon() {
        assertEquals(1f / 24f, clipEdge(0f), 1e-6f)
        assertEquals(23f / 24f, clipEdge(1f), 1e-6f)
        assertEquals(12f / 24f, clipEdge(0.5f), 1e-6f)
        assertTrue(clipEdge(0.8f) < 19f / 24f, "80 % must not already cover the tail")
    }
}
