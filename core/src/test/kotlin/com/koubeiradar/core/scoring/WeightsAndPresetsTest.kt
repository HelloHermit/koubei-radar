package com.koubeiradar.core.scoring

import com.koubeiradar.core.backdrop
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.numbers
import com.koubeiradar.core.reviews
import com.koubeiradar.core.scoreProbe
import com.koubeiradar.core.shop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeightsAndPresetsTest {

    private fun ScoredShop.part(value: Double?) = assertNotNull(value)

    private fun weighted(vararg parts: Pair<Double, Double?>): Double {
        val available = parts.filter { it.second != null }
        return available.sumOf { it.first * (it.second ?: 0.0) } / available.sumOf { it.first }
    }

    // ---- renormalisation ----

    @Test
    fun weightsAreRenormalisedOverTheComponentsThatExist() {
        val w = ScoringConfig()
        val onlyRating = ReputationScorer().scoreProbe(shop("探针", ratingCount = null))
        assertEquals(onlyRating.part(onlyRating.breakdown.ratingScore), onlyRating.score, 0.06)

        val withConfidence = ReputationScorer().scoreProbe(shop("探针", ratingCount = 300))
        assertEquals(
            weighted(w.weightRating to withConfidence.breakdown.ratingScore, w.weightConfidence to withConfidence.breakdown.confidenceScore),
            withConfidence.score,
            0.15,
        )

        val withConvenience = ReputationScorer().scoreProbe(shop("探针", ratingCount = 300, distanceMeters = 1000, deliveryMinutes = 35))
        val b = withConvenience.breakdown
        assertEquals(
            weighted(w.weightRating to b.ratingScore, w.weightConfidence to b.confidenceScore, w.weightConvenience to b.convenienceScore),
            withConvenience.score,
            0.15,
        )
    }

    @Test
    fun allFiveComponentsAreCombinedWithTheConfiguredWeights() {
        val full = ReputationScorer().scoreProbe(
            shop(
                "探针",
                rating = 4.6,
                ratingCount = 300,
                avgPrice = 28.0,
                distanceMeters = 1200,
                deliveryMinutes = 30,
                deliveryFee = 2.0,
                reviews = reviews("好吃", "新鲜", "还行", "难吃"),
            ),
        )
        val b = full.breakdown
        val w = ScoringConfig()
        assertTrue(b.ratingScore != null && b.confidenceScore != null && b.sentimentScore != null && b.valueScore != null && b.convenienceScore != null)
        assertEquals(
            weighted(
                w.weightRating to b.ratingScore,
                w.weightConfidence to b.confidenceScore,
                w.weightSentiment to b.sentimentScore,
                w.weightValue to b.valueScore,
                w.weightConvenience to b.convenienceScore,
            ),
            full.score,
            0.15,
        )
    }

    @Test
    fun scalingAllWeightsByTheSameFactorChangesNothing() {
        val probe = shop("探针", ratingCount = 300, avgPrice = 28.0, distanceMeters = 1200, reviews = reviews("好吃", "新鲜", "还行"))
        val base = ReputationScorer().scoreProbe(probe)
        val scaled = ReputationScorer(
            ScoringConfig(weightRating = 4.5, weightConfidence = 1.5, weightSentiment = 1.5, weightValue = 0.5, weightConvenience = 2.0),
        ).scoreProbe(probe)
        assertEquals(base, scaled)
    }

    @Test
    fun allZeroWeightsFallBackToTheRatingScoreAlone() {
        val zero = ScoringConfig(0.0, 0.0, 0.0, 0.0, 0.0)
        val probe = shop("探针", ratingCount = 300, avgPrice = 28.0, distanceMeters = 1200, reviews = reviews("好吃", "新鲜", "还行"))
        val scored = ReputationScorer(zero).scoreProbe(probe)
        assertEquals(scored.part(scored.breakdown.ratingScore), scored.score, 0.06)
        assertTrue(scored.score > 0.0)
    }

    @Test
    fun whenOnlyZeroWeightedComponentsExistTheRatingScoreIsUsed() {
        val config = ScoringConfig(weightRating = 0.0, weightConfidence = 0.0, weightConvenience = 0.0, weightSentiment = 1.0, weightValue = 1.0)
        val bare = ReputationScorer(config).scoreProbe(shop("探针", ratingCount = 300, distanceMeters = 1000))
        assertEquals(bare.part(bare.breakdown.ratingScore), bare.score, 0.06)

        val priced = ReputationScorer(config).scoreProbe(shop("探针", ratingCount = 300, distanceMeters = 1000, avgPrice = 30.0))
        assertEquals(priced.part(priced.breakdown.valueScore), priced.score, 0.06)
    }

    @Test
    fun invalidWeightsCountAsZero() {
        val garbage = ScoringConfig(
            weightRating = Double.NaN,
            weightConfidence = -1.0,
            weightSentiment = Double.POSITIVE_INFINITY,
            weightValue = Double.NEGATIVE_INFINITY,
            weightConvenience = -0.0,
        )
        val scored = ReputationScorer(garbage).scoreProbe(shop("探针", ratingCount = 300, distanceMeters = 1000, reviews = reviews("好吃", "新鲜", "还行")))
        assertTrue(scored.numbers().all { it.isFinite() })
        assertEquals(scored.part(scored.breakdown.ratingScore), scored.score, 0.06)

        val oneGood = ScoringConfig(weightRating = Double.NaN, weightConfidence = 1.0)
        val confidenceOnly = ReputationScorer(oneGood).scoreProbe(shop("探针", ratingCount = 300))
        assertEquals(confidenceOnly.part(confidenceOnly.breakdown.confidenceScore), confidenceOnly.score, 0.06)
    }

    @Test
    fun aZeroWeightSilencesAComponentButItIsStillReported() {
        val config = ScoringConfig(weightConvenience = 0.0)
        fun scored(distance: Int) = ReputationScorer(config).scoreProbe(shop("探针", ratingCount = 300, distanceMeters = distance))
        val near = scored(100)
        val far = scored(4900)
        assertEquals(near.score, far.score)
        assertTrue(assertNotNull(near.breakdown.convenienceScore) > assertNotNull(far.breakdown.convenienceScore))
    }

    @Test
    fun weightsChangeTheMixButNeverTheComponentScores() {
        val probe = shop("探针", ratingCount = 300, avgPrice = 28.0, distanceMeters = 1200, deliveryMinutes = 30, reviews = reviews("好吃", "新鲜", "还行"))
        val reference = ReputationScorer(ScoringConfig.REPUTATION_FIRST).scoreProbe(probe).breakdown
        for (preset in listOf(ScoringConfig.VALUE_FIRST, ScoringConfig.FAST_FIRST)) {
            assertEquals(reference, ReputationScorer(preset).scoreProbe(probe).breakdown)
        }
    }

    // ---- presets ----

    @Test
    fun theDefaultConfigIsTheReputationFirstPreset() {
        assertEquals(ScoringConfig.REPUTATION_FIRST, ScoringConfig())
        assertEquals(ScoringConfig.REPUTATION_FIRST, ReputationScorer().config)
    }

    private fun scoresIn(config: ScoringConfig, shops: List<Shop>): Map<String, Double> =
        ReputationScorer(config).score(backdrop() + shops).drop(backdrop().size).associate { it.shop.name to it.score }

    @Test
    fun valueFirstPutsTheCheapShopAheadOfTheMoreReputableExpensiveOne() {
        val renowned = shop("名店", rating = 4.7, ratingCount = 1500, avgPrice = 95.0, distanceMeters = 1000, deliveryMinutes = 28, deliveryFee = 2.0)
        val bargain = shop("平价", rating = 4.5, ratingCount = 800, avgPrice = 14.0, distanceMeters = 1000, deliveryMinutes = 28, deliveryFee = 2.0)

        val reputation = scoresIn(ScoringConfig.REPUTATION_FIRST, listOf(renowned, bargain))
        val value = scoresIn(ScoringConfig.VALUE_FIRST, listOf(renowned, bargain))

        assertTrue(assertNotNull(reputation["名店"]) > assertNotNull(reputation["平价"]), "reputation first: $reputation")
        assertTrue(assertNotNull(value["平价"]) > assertNotNull(value["名店"]), "value first: $value")
    }

    @Test
    fun fastFirstPutsTheNearbyShopAheadOfTheMoreReputableFarOne() {
        val renowned = shop("名店", rating = 4.8, ratingCount = 3000, avgPrice = 30.0, distanceMeters = 4300, deliveryMinutes = 58, deliveryFee = 5.0)
        val nearby = shop("近店", rating = 4.3, ratingCount = 600, avgPrice = 30.0, distanceMeters = 300, deliveryMinutes = 21, deliveryFee = 0.0)

        val reputation = scoresIn(ScoringConfig.REPUTATION_FIRST, listOf(renowned, nearby))
        val fast = scoresIn(ScoringConfig.FAST_FIRST, listOf(renowned, nearby))

        assertTrue(assertNotNull(reputation["名店"]) > assertNotNull(reputation["近店"]), "reputation first: $reputation")
        assertTrue(assertNotNull(fast["近店"]) > assertNotNull(fast["名店"]), "fast first: $fast")
    }

    @Test
    fun thePresetsDifferInTheirScoresForTheSameShop() {
        val probe = shop("探针", ratingCount = 300, avgPrice = 12.0, distanceMeters = 4500, deliveryMinutes = 55, deliveryFee = 4.0)
        val scores = listOf(ScoringConfig.REPUTATION_FIRST, ScoringConfig.VALUE_FIRST, ScoringConfig.FAST_FIRST)
            .map { ReputationScorer(it).scoreProbe(probe).score }
        assertEquals(3, scores.toSet().size, "$scores")
        assertNotEquals(scores[0], scores[1])
    }
}
