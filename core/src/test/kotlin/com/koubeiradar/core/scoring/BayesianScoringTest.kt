package com.koubeiradar.core.scoring

import com.koubeiradar.core.PINNED_PRIOR
import com.koubeiradar.core.backdrop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.scoreProbe
import com.koubeiradar.core.shop
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BayesianScoringTest {

    private fun probe(
        rating: Double? = 4.8,
        count: Int? = 500,
        sales: Int? = null,
        config: ScoringConfig = PINNED_PRIOR,
    ) = ReputationScorer(config).scoreProbe(shop("探针", rating = rating, ratingCount = count, monthlySales = sales))

    private fun adjusted(rating: Double, count: Int?, sales: Int? = null, config: ScoringConfig = PINNED_PRIOR): Double =
        assertNotNull(probe(rating, count, sales, config).breakdown.adjustedRating)

    // ---- the Bayesian average ----

    @Test
    fun aPerfectRatingFromThreeVotersScoresBelowASolidRatingFromThousands() {
        val shops = backdrop() + shop("三条满分", rating = 5.0, ratingCount = 3) + shop("三千好评", rating = 4.7, ratingCount = 3000)
        val scored = ReputationScorer().score(shops)
        val few = scored[scored.size - 2]
        val many = scored.last()
        assertTrue(few.score < many.score, "${few.score} < ${many.score}")
        assertTrue(assertNotNull(few.breakdown.adjustedRating) < assertNotNull(many.breakdown.adjustedRating))
    }

    @Test
    fun adjustedRatingMovesTowardThePriorAsTheCountShrinks() {
        val counts = listOf(100_000, 3000, 300, 100, 30, 10, 3, 1, 0)
        val adjustedRatings = counts.map { adjusted(4.9, it) }
        // adjustedRating is rounded to 2 decimals, so very large counts may tie; it must never increase as the count shrinks
        assertTrue(adjustedRatings.zipWithNext().all { (more, fewer) -> more >= fewer }, "$adjustedRatings")
        assertTrue(adjustedRatings.first() > adjustedRatings.last())
        assertEquals(4.9, adjustedRatings.first(), 0.005)
        assertEquals(4.4, adjustedRatings.last(), 1e-9)

        val lowRated = counts.map { adjusted(3.9, it) }
        assertTrue(lowRated.zipWithNext().all { (more, fewer) -> more <= fewer }, "a low rating is pulled up: $lowRated")
        assertTrue(lowRated.first() < lowRated.last())
        assertEquals(4.4, lowRated.last(), 1e-9)
    }

    @Test
    fun adjustedRatingFollowsTheBayesianFormula() {
        assertEquals((120 * 4.8 + 30 * 4.4) / 150, adjusted(4.8, 120), 0.005)
        assertEquals(4.72, adjusted(4.8, 120), 1e-9)
        assertEquals(4.45, adjusted(5.0, 3), 1e-9)
        val stronger = PINNED_PRIOR.copy(priorStrength = 60.0)
        assertEquals((120 * 4.8 + 60 * 4.4) / 180, adjusted(4.8, 120, config = stronger), 0.005)
    }

    @Test
    fun aPriorStrengthOfZeroTrustsTheDisplayedRatingEvenWithoutAnyRatings() {
        val trusting = PINNED_PRIOR.copy(priorStrength = 0.0)
        assertEquals(4.8, adjusted(4.8, 120, config = trusting), 1e-9)
        assertEquals(4.8, adjusted(4.8, 0, config = trusting), 1e-9)
    }

    @Test
    fun aReportedCountOfZeroMeansThePriorExactlyAndIsNotUnknown() {
        val result = probe(rating = 4.9, count = 0)
        assertEquals(4.4, result.breakdown.adjustedRating)
        assertEquals(0, result.breakdown.effectiveRatingCount)
        assertFalse(result.breakdown.ratingCountIsEstimated)
        assertEquals(0.0, result.breakdown.confidenceScore)
        assertFalse(result.warnings.contains(Explanations.UNKNOWN_RATING_COUNT))
    }

    // ---- the prior ----

    private fun priorSeenBy(otherRatings: List<Double?>, probeRating: Double, config: ScoringConfig = ScoringConfig()): Double {
        val others = otherRatings.mapIndexed { index, rating -> shop("其他$index", rating = rating, ratingCount = 400) }
        val result = ReputationScorer(config).score(others + shop("探针", rating = probeRating, ratingCount = 0)).last()
        return assertNotNull(result.breakdown.adjustedRating)
    }

    @Test
    fun theDefaultPriorIsTheMedianRatingOfTheWholeSet() {
        // sorted: 4.2 4.3 4.3 4.4 4.5 4.6 -> median (4.3 + 4.4) / 2
        assertEquals(4.35, priorSeenBy(listOf(4.2, 4.3, 4.4, 4.5, 4.6), 4.3), 1e-9)
        // sorted: 4.1 4.3 4.5 -> median 4.3
        assertEquals(4.3, priorSeenBy(listOf(4.1, 4.5), 4.3), 1e-9)
    }

    @Test
    fun theDefaultPriorIsClampedToFourPointZeroThroughFourPointSix() {
        assertEquals(4.6, priorSeenBy(List(6) { 4.9 }, 4.9), 1e-9)
        assertEquals(4.0, priorSeenBy(List(6) { 3.5 }, 3.5), 1e-9)
        assertEquals(4.6, priorSeenBy(listOf(4.6, 4.6), 4.6), 1e-9)
        assertEquals(4.0, priorSeenBy(listOf(4.0, 4.0), 4.0), 1e-9)
    }

    @Test
    fun shopsWithoutAUsableRatingDoNotInfluenceThePrior() {
        val unusable = listOf(null, null, null, null, null, 7.0, 7.0, 0.0, -1.0, Double.NaN)
        assertEquals(4.2, priorSeenBy(unusable, 4.2), 1e-9)
    }

    @Test
    fun anExplicitPriorOverridesTheMedianAndIsNotClamped() {
        assertEquals(3.9, priorSeenBy(List(6) { 4.9 }, 4.9, ScoringConfig(priorMeanRating = 3.9)), 1e-9)
        assertEquals(4.8, priorSeenBy(List(6) { 4.0 }, 4.0, ScoringConfig(priorMeanRating = 4.8)), 1e-9)
    }

    @Test
    fun anUnusableExplicitPriorFallsBackToTheMedian() {
        for (invalid in listOf(0.0, -2.0, 7.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(4.2, priorSeenBy(listOf(4.2, 4.2), 4.2, ScoringConfig(priorMeanRating = invalid)), 1e-9, "prior=$invalid")
        }
    }

    // ---- the effective rating count ----

    @Test
    fun anUnknownRatingCountUsesTheAssumedDefaultAndWarns() {
        val result = probe(count = null, sales = null)
        assertEquals(20, result.breakdown.effectiveRatingCount)
        assertTrue(result.breakdown.ratingCountIsEstimated)
        assertNull(result.breakdown.confidenceScore, "unknown is not the same as few")
        assertTrue(Explanations.UNKNOWN_RATING_COUNT in result.warnings, "${result.warnings}")
        assertEquals((20 * 4.8 + 30 * 4.4) / 50, assertNotNull(result.breakdown.adjustedRating), 0.005)
    }

    @Test
    fun theAssumedCountIsConfigurable() {
        val result = probe(count = null, config = PINNED_PRIOR.copy(assumedRatingCountWhenUnknown = 50))
        assertEquals(50, result.breakdown.effectiveRatingCount)
        assertTrue(result.breakdown.ratingCountIsEstimated)
    }

    @Test
    fun aNegativeRatingCountIsTreatedAsUnknown() {
        val result = probe(count = -5, sales = null)
        assertEquals(20, result.breakdown.effectiveRatingCount)
        assertTrue(result.breakdown.ratingCountIsEstimated)
        assertTrue(Explanations.UNKNOWN_RATING_COUNT in result.warnings)

        val fromSales = probe(count = -5, sales = 1000)
        assertEquals(150, fromSales.breakdown.effectiveRatingCount)
        assertFalse(Explanations.UNKNOWN_RATING_COUNT in fromSales.warnings)
    }

    @Test
    fun aReportedCountTakesPrecedenceOverSales() {
        val result = probe(count = 800, sales = 100_000)
        assertEquals(800, result.breakdown.effectiveRatingCount)
        assertFalse(result.breakdown.ratingCountIsEstimated)
    }

    @Test
    fun salesBasedEstimatesAreRoundedAndFlaggedAsEstimated() {
        val thousand = probe(count = null, sales = 1000)
        assertEquals(150, thousand.breakdown.effectiveRatingCount)
        assertTrue(thousand.breakdown.ratingCountIsEstimated)
        assertNotNull(thousand.breakdown.confidenceScore, "a sales estimate is good enough for a confidence score")
        assertFalse(Explanations.UNKNOWN_RATING_COUNT in thousand.warnings)

        assertEquals(5, probe(count = null, sales = 33).breakdown.effectiveRatingCount, "4.95 rounds up")
        assertEquals(2, probe(count = null, sales = 10).breakdown.effectiveRatingCount, "1.5 rounds half up")
        assertEquals(0, probe(count = null, sales = 0).breakdown.effectiveRatingCount)
        assertEquals(4200 / 2, probe(count = null, sales = 4200, config = PINNED_PRIOR.copy(salesToRatingCountRatio = 0.5)).breakdown.effectiveRatingCount)
    }

    @Test
    fun negativeSalesAreIgnored() {
        val result = probe(count = null, sales = -40)
        assertEquals(20, result.breakdown.effectiveRatingCount)
        assertTrue(Explanations.UNKNOWN_RATING_COUNT in result.warnings)
    }

    @Test
    fun aHugeSalesFigureCannotOverflowTheCount() {
        val result = probe(count = null, sales = Int.MAX_VALUE, config = PINNED_PRIOR.copy(salesToRatingCountRatio = 1e9))
        assertEquals(Int.MAX_VALUE, result.breakdown.effectiveRatingCount)
        assertTrue(result.score in 0.0..100.0)
    }

    // ---- confidence ----

    @Test
    fun confidenceFollowsTheSaturatingFormula() {
        for (count in listOf(1, 10, 50, 150, 400, 1000)) {
            val expected = (1 - exp(-count / 150.0)) * 100
            assertEquals(expected, assertNotNull(probe(count = count).breakdown.confidenceScore), 0.06, "n=$count")
        }
        assertEquals(63.2, probe(count = 150).breakdown.confidenceScore)
        assertEquals(0.0, probe(count = 0).breakdown.confidenceScore)
        assertEquals(100.0, probe(count = 100_000).breakdown.confidenceScore)
    }

    @Test
    fun confidenceGrowsWithTheCountAndHonoursTheConfiguredScale() {
        val counts = listOf(0, 1, 5, 20, 80, 150, 600, 3000)
        val scores = counts.map { assertNotNull(probe(count = it).breakdown.confidenceScore) }
        assertTrue(scores.zipWithNext().all { (a, b) -> a < b }, "$scores")

        val slow = assertNotNull(probe(count = 150, config = PINNED_PRIOR.copy(volumeHalfLifeCount = 500.0)).breakdown.confidenceScore)
        assertEquals((1 - exp(-150 / 500.0)) * 100, slow, 0.06)
    }

    @Test
    fun confidenceIsOnlyMissingWhenNothingAtAllIsKnownAboutTheCount() {
        assertNull(probe(count = null, sales = null).breakdown.confidenceScore)
        assertNotNull(probe(count = null, sales = 80).breakdown.confidenceScore)
        assertNotNull(probe(count = 0, sales = null).breakdown.confidenceScore)
    }
}
