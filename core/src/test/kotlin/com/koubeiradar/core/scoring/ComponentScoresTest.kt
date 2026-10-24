package com.koubeiradar.core.scoring

import com.koubeiradar.core.PINNED_PRIOR
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.scoreProbe
import com.koubeiradar.core.shop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentScoresTest {

    /** With no prior strength the adjusted rating is the displayed rating, which makes percentiles easy to predict. */
    private val plain = ScoringConfig(priorMeanRating = 4.4, priorStrength = 0.0)

    private fun absolute(rating: Double) = (rating - 3.5) / 1.5 * 100.0

    private fun ratingScores(ratings: List<Double?>, config: ScoringConfig = plain): List<Double?> =
        ReputationScorer(config).score(ratings.mapIndexed { index, rating -> shop("店$index", rating = rating) })
            .map { it.breakdown.ratingScore }

    private fun assertScores(expected: List<Double>, actual: List<Double?>, delta: Double = 0.06) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (want, got) ->
            assertEquals(want, assertNotNull(got, "score $index"), delta, "shop $index")
        }
    }

    // ---- rating score and percentile ----

    @Test
    fun fewerThanFiveRatedShopsFallBackToTheAbsoluteScore() {
        val scores = ratingScores(listOf(4.0, 4.4, 4.6, 4.8, null, null, null))
        assertScores(listOf(4.0, 4.4, 4.6, 4.8).map(::absolute), scores.take(4))
        assertNull(scores[4])
    }

    @Test
    fun fiveRatedShopsSwitchOnThePercentile() {
        // percentiles 0, 25, 50, 75, 100
        val ratings = listOf(4.0, 4.2, 4.4, 4.6, 4.8)
        val expected = ratings.zip(listOf(0.0, 25.0, 50.0, 75.0, 100.0)).map { (rating, percentile) ->
            0.7 * absolute(rating) + 0.3 * percentile
        }
        assertScores(expected, ratingScores(ratings))
        assertEquals(90.7, ratingScores(ratings).last())
    }

    @Test
    fun tiedRatingsShareTheAverageRank() {
        // the two 4.4s occupy ranks 2 and 3 -> percentile (2.5 - 1) / 4 = 37.5
        val ratings = listOf(4.0, 4.4, 4.4, 4.6, 4.8)
        val expected = listOf(0.0, 37.5, 37.5, 75.0, 100.0).zip(ratings).map { (percentile, rating) ->
            0.7 * absolute(rating) + 0.3 * percentile
        }
        assertScores(expected, ratingScores(ratings))
    }

    @Test
    fun aPopulationOfIdenticalShopsGetsTheMiddlePercentile() {
        val expected = 0.7 * absolute(4.5) + 0.3 * 50.0
        assertScores(List(5) { expected }, ratingScores(List(5) { 4.5 }))
        assertScores(List(9) { expected }, ratingScores(List(9) { 4.5 }))
    }

    @Test
    fun unratedAndInvalidlyRatedShopsAreNotPartOfThePercentilePopulation() {
        val fourRated = listOf(4.0, 4.4, 4.6, 4.8)
        for (noise in listOf(List(10) { null }, listOf(7.0, 7.0, 0.0, -1.0, Double.NaN, null))) {
            val scores = ratingScores(fourRated + noise)
            assertScores(fourRated.map(::absolute), scores.take(4))
        }
        val fiveRated = listOf(4.0, 4.2, 4.4, 4.6, 4.8)
        assertEquals(ratingScores(fiveRated), ratingScores(fiveRated + List(10) { null }).take(5))
    }

    @Test
    fun thePercentileRanksTheAdjustedRatingNotTheDisplayedOne() {
        val shops = listOf(
            shop("四条评价", rating = 4.5, ratingCount = 4),
            shop("五千评价", rating = 4.5, ratingCount = 5000),
            shop("甲", rating = 4.1, ratingCount = 500),
            shop("乙", rating = 4.3, ratingCount = 500),
            shop("丙", rating = 4.8, ratingCount = 500),
        )
        val shrinking = ReputationScorer(PINNED_PRIOR).score(shops)
        assertTrue(assertNotNull(shrinking[1].breakdown.ratingScore) > assertNotNull(shrinking[0].breakdown.ratingScore))

        val identicalWithoutShrinkage = ReputationScorer(plain).score(shops)
        assertEquals(identicalWithoutShrinkage[0].breakdown.ratingScore, identicalWithoutShrinkage[1].breakdown.ratingScore)
    }

    @Test
    fun theAbsolutePartIsClampedToZeroAndOneHundred() {
        assertEquals(listOf(0.0, 0.0, 100.0, 100.0), ratingScores(listOf(3.0, 3.5, 5.0, 5.0)))
        assertEquals(0.0, ratingScores(listOf(1.0, 3.0, 3.5, 3.9, 4.0)).first())
    }

    // ---- value score ----

    private fun valueScores(prices: List<Double?>): List<Double?> =
        ReputationScorer(plain).score(prices.mapIndexed { index, price -> shop("店$index", rating = 4.5, avgPrice = price) })
            .map { it.breakdown.valueScore }

    @Test
    fun valueScoreNeedsAtLeastThreePricedShops() {
        assertEquals(listOf(null, null, null, null, null), valueScores(listOf(10.0, 20.0, null, null, null)))
        val three = valueScores(listOf(10.0, 20.0, 30.0, null, null))
        assertTrue(three.take(3).all { it != null })
        assertNull(three[3])
    }

    @Test
    fun valueScoreBlendsThePriceRankWithTheRatingScore() {
        val ratingScore = 0.7 * absolute(4.5) + 0.3 * 50.0
        val expected = listOf(100.0, 75.0, 50.0, 25.0, 0.0).map { 0.5 * it + 0.5 * ratingScore }
        assertScores(expected, valueScores(listOf(10.0, 20.0, 30.0, 40.0, 50.0)))
    }

    @Test
    fun cheaperShopsGetHigherValueScores() {
        val scores = valueScores(listOf(50.0, 40.0, 30.0, 20.0, 10.0)).map { assertNotNull(it) }
        assertTrue(scores.zipWithNext().all { (dearer, cheaper) -> cheaper > dearer }, "$scores")
    }

    @Test
    fun tiedPricesShareTheMiddlePercentile() {
        val ratingScore = 0.7 * absolute(4.5) + 0.3 * 50.0
        assertScores(List(5) { 0.5 * 50.0 + 0.5 * ratingScore }, valueScores(List(5) { 30.0 }))
    }

    @Test
    fun aShopWithoutAPriceHasNoValueScoreAndNegativePricesCountAsMissing() {
        assertNull(valueScores(listOf(10.0, 20.0, 30.0, 40.0, null)).last())
        val negative = valueScores(listOf(-5.0, 10.0, 20.0, 30.0, 40.0))
        assertNull(negative.first())
        assertTrue(negative.drop(1).all { it != null })
        assertEquals(listOf(null, null, null, null), valueScores(listOf(-5.0, -1.0, 10.0, 20.0)))
    }

    // ---- convenience score ----

    private fun convenience(
        distance: Int? = null,
        minutes: Int? = null,
        fee: Double? = null,
        criteria: Criteria = Criteria(),
    ): Double? = ReputationScorer(PINNED_PRIOR)
        .scoreProbe(shop("探针", distanceMeters = distance, deliveryMinutes = minutes, deliveryFee = fee), criteria = criteria)
        .breakdown.convenienceScore

    @Test
    fun convenienceIsTheAverageOfTheKnownParts() {
        assertEquals(60.0, convenience(distance = 1000, minutes = 40, fee = 5.0))
        assertEquals(80.0, convenience(distance = 1000))
        assertEquals(50.0, convenience(minutes = 40))
        assertEquals(75.0, convenience(fee = 2.5))
        assertEquals(90.0, convenience(distance = 1000, fee = 0.0))
        assertNull(convenience())
    }

    @Test
    fun theThreePartsSaturateAtTheirLimits() {
        assertEquals(100.0, convenience(distance = 0))
        assertEquals(0.0, convenience(distance = 5000))
        assertEquals(0.0, convenience(distance = 12_000))
        assertEquals(100.0, convenience(minutes = 20))
        assertEquals(100.0, convenience(minutes = 5))
        assertEquals(0.0, convenience(minutes = 60))
        assertEquals(0.0, convenience(minutes = 120))
        assertEquals(100.0, convenience(fee = 0.0))
        assertEquals(0.0, convenience(fee = 10.0))
        assertEquals(0.0, convenience(fee = 30.0))
    }

    @Test
    fun theDistanceScaleComesFromTheCriteria() {
        assertEquals(50.0, convenience(distance = 1000, criteria = Criteria(maxDistanceMeters = 2000)))
        assertEquals(0.0, convenience(distance = 2000, criteria = Criteria(maxDistanceMeters = 2000)))
        assertEquals(0.0, convenience(distance = 3000, criteria = Criteria(maxDistanceMeters = 2000)))
        assertEquals(80.0, convenience(distance = 1000, criteria = Criteria(maxDistanceMeters = null)))
    }

    @Test
    fun aMaximumDistanceOfZeroNeverDividesByZero() {
        for (limit in listOf(0, -1, -5000)) {
            val criteria = Criteria(maxDistanceMeters = limit)
            assertEquals(100.0, convenience(distance = 0, criteria = criteria), "limit=$limit")
            assertEquals(0.0, convenience(distance = 1, criteria = criteria), "limit=$limit")
            assertEquals(0.0, convenience(distance = 700, criteria = criteria), "limit=$limit")
        }
    }

    @Test
    fun negativeDistanceTimeAndFeeAreUnknown() {
        assertNull(convenience(distance = -1, minutes = -30, fee = -2.0))
        assertEquals(80.0, convenience(distance = 1000, minutes = -30, fee = -2.0))
        assertNull(convenience(fee = Double.NaN))
    }

    // ---- monotonicity ----

    private fun scoreOf(
        rating: Double = 4.5,
        count: Int? = 500,
        distance: Int? = null,
        minutes: Int? = null,
        fee: Double? = null,
        price: Double? = null,
    ): ScoredShop = ReputationScorer().scoreProbe(
        shop("探针", rating = rating, ratingCount = count, distanceMeters = distance, deliveryMinutes = minutes, deliveryFee = fee, avgPrice = price),
    )

    private fun assertNonDecreasing(values: List<Double>, what: String) {
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a }, "$what must not decrease: $values")
    }

    @Test
    fun aHigherRatingNeverLowersTheScoreOnceThereAreEnoughRatings() {
        val ratings = (30..50).map { it / 10.0 }
        for (count in listOf(30, 100, 1000, 10_000)) {
            val scores = ratings.map { scoreOf(rating = it, count = count) }
            assertNonDecreasing(scores.map { it.score }, "score with $count ratings")
            assertNonDecreasing(scores.map { assertNotNull(it.breakdown.adjustedRating) }, "adjusted rating with $count ratings")
            assertTrue(scores.last().score > scores.first().score)
        }
    }

    @Test
    fun aHigherRatingNeverLowersTheScoreBelowThePerfectRatingThreshold() {
        val ratings = (30..48).map { it / 10.0 }
        for (count in listOf(0, 5, 9, 10, 20, 29)) {
            assertNonDecreasing(ratings.map { scoreOf(rating = it, count = count).score }, "score with $count ratings")
        }
    }

    @Test
    fun aNearPerfectRatingWithFewRatingsDeliberatelyScoresBelowALowerOne() {
        val suspicious = scoreOf(rating = 4.9, count = 20)
        val plausible = scoreOf(rating = 4.8, count = 20)
        assertTrue(suspicious.score < plausible.score, "${suspicious.score} < ${plausible.score}")
        assertEquals(8.0, suspicious.breakdown.penalty)
    }

    @Test
    fun moreRatingsNeverLowerTheScoreOfAShopRatedAtLeastThePrior() {
        val counts = listOf(0, 1, 2, 3, 5, 8, 9, 10, 11, 20, 29, 30, 31, 50, 100, 300, 1000, 5000, 100_000)
        for (rating in listOf(4.5, 4.7, 4.9, 5.0)) {
            val scores = counts.map { scoreOf(rating = rating, count = it) }
            assertNonDecreasing(scores.map { it.score }, "score of a $rating shop")
            assertNonDecreasing(scores.map { assertNotNull(it.breakdown.confidenceScore) }, "confidence of a $rating shop")
            assertNonDecreasing(scores.map { assertNotNull(it.breakdown.adjustedRating) }, "adjusted rating of a $rating shop")
        }
    }

    @Test
    fun aShorterDistanceNeverLowersConvenienceOrTheScore() {
        val distances = (0..6000 step 250).toList().reversed()
        val scores = distances.map { scoreOf(distance = it) }
        assertNonDecreasing(scores.map { assertNotNull(it.breakdown.convenienceScore) }, "convenience")
        assertNonDecreasing(scores.map { it.score }, "score")
        assertTrue(assertNotNull(scores.last().breakdown.convenienceScore) > assertNotNull(scores.first().breakdown.convenienceScore))
    }

    @Test
    fun aFasterDeliveryNeverLowersConvenienceOrTheScore() {
        val minutes = (0..90 step 5).toList().reversed()
        val scores = minutes.map { scoreOf(minutes = it) }
        assertNonDecreasing(scores.map { assertNotNull(it.breakdown.convenienceScore) }, "convenience")
        assertNonDecreasing(scores.map { it.score }, "score")
        assertTrue(assertNotNull(scores.last().breakdown.convenienceScore) > assertNotNull(scores.first().breakdown.convenienceScore))
    }

    @Test
    fun aLowerDeliveryFeeNeverLowersConvenienceOrTheScore() {
        val fees = (0..24).map { it * 0.5 }.reversed()
        val scores = fees.map { scoreOf(fee = it) }
        assertNonDecreasing(scores.map { assertNotNull(it.breakdown.convenienceScore) }, "convenience")
        assertNonDecreasing(scores.map { it.score }, "score")
    }

    @Test
    fun aLowerPriceNeverLowersTheValueScoreOrTheScore() {
        val prices = (5..120 step 5).map { it.toDouble() }.reversed()
        val scores = prices.map { scoreOf(price = it) }
        assertNonDecreasing(scores.map { assertNotNull(it.breakdown.valueScore) }, "value")
        assertNonDecreasing(scores.map { it.score }, "score")
        assertTrue(assertNotNull(scores.last().breakdown.valueScore) > assertNotNull(scores.first().breakdown.valueScore))
    }
}
