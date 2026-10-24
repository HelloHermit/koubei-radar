package com.koubeiradar.core.scoring

import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.numbers
import com.koubeiradar.core.repeated
import com.koubeiradar.core.reviews
import com.koubeiradar.core.scoreProbe
import com.koubeiradar.core.shop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PenaltiesAndVerdictTest {

    /** A shop that is RECOMMENDED as long as nothing is wrong with it. */
    private fun solid(
        rating: Double? = 4.7,
        count: Int? = 2000,
        sales: Int? = null,
        openNow: Boolean? = null,
        reviews: List<Review> = emptyList(),
    ): Shop = shop(
        name = "稳妥店",
        rating = rating,
        ratingCount = count,
        monthlySales = sales,
        avgPrice = 25.0,
        distanceMeters = 600,
        deliveryMinutes = 22,
        deliveryFee = 1.0,
        openNow = openNow,
        reviews = reviews,
    )

    private fun result(probe: Shop, config: ScoringConfig = ScoringConfig()): ScoredShop =
        ReputationScorer(config).scoreProbe(probe)

    private fun penaltyOf(rating: Double? = 4.7, count: Int? = 2000, sales: Int? = null, config: ScoringConfig = ScoringConfig()) =
        result(solid(rating = rating, count = count, sales = sales), config).breakdown.penalty

    private val hygieneComplaints: List<Review> = repeated("吃完拉肚子了", 2) + repeated("还行", 8)

    /** Thresholds so low that only the explicit AVOID / RECOMMENDED blockers matter. */
    private val permissive = ScoringConfig(recommendedThreshold = 0.0, okayThreshold = 0.0)

    // ---- the penalties of DESIGN.md 4.9, one by one ----

    @Test
    fun aSolidShopIsRecommendedWithoutPenaltiesOrWarnings() {
        val solid = result(solid())
        assertEquals(Verdict.RECOMMENDED, solid.verdict, "score ${solid.score}")
        assertEquals(0.0, solid.breakdown.penalty)
        assertEquals(emptyList(), solid.warnings)
    }

    @Test
    fun hygieneComplaintsCostFifteenPointsAndForceAvoid() {
        val flagged = result(solid(reviews = hygieneComplaints))
        assertEquals(15.0, flagged.breakdown.penalty)
        assertEquals(Verdict.AVOID, flagged.verdict)
        assertTrue(flagged.warnings.first().contains("卫生/食安"), flagged.warnings.toString())
        assertEquals(0.0, result(solid(reviews = repeated("还行", 10))).breakdown.penalty)
    }

    @Test
    fun aNearPerfectRatingWithFewerThanThirtyRatingsCostsEightPoints() {
        assertEquals(8.0, penaltyOf(rating = 4.9, count = 29))
        assertEquals(8.0, penaltyOf(rating = 4.9, count = 10))
        assertEquals(8.0, penaltyOf(rating = 5.0, count = 15))
        assertEquals(0.0, penaltyOf(rating = 4.9, count = 30))
        assertEquals(0.0, penaltyOf(rating = 4.9, count = 3000))
        assertEquals(0.0, penaltyOf(rating = 4.89, count = 20))
        assertEquals(0.0, penaltyOf(rating = 4.8, count = 20))
    }

    @Test
    fun theNearPerfectRatingCheckAlsoAppliesToEstimatedCounts() {
        assertEquals(8.0, penaltyOf(rating = 4.9, count = null, sales = 190), "190 sales -> about 29 ratings")
        assertEquals(0.0, penaltyOf(rating = 4.9, count = null, sales = 220), "220 sales -> about 33 ratings")
        assertEquals(8.0, penaltyOf(rating = 4.9, count = null, sales = null), "unknown count -> assumed 20")
        assertEquals(0.0, penaltyOf(rating = 4.9, count = null, sales = null, config = ScoringConfig(assumedRatingCountWhenUnknown = 40)))
    }

    @Test
    fun fewerThanTenRatingsCostFivePoints() {
        assertEquals(5.0, penaltyOf(count = 9))
        assertEquals(5.0, penaltyOf(count = 0))
        assertEquals(0.0, penaltyOf(count = 10))
        assertEquals(5.0, penaltyOf(count = null, sales = 60), "60 sales -> about 9 ratings")
        assertEquals(0.0, penaltyOf(count = null, sales = 100), "100 sales -> about 15 ratings")
        assertEquals(0.0, penaltyOf(count = null, sales = null), "an unknown count is assumed to be 20, not penalised")
        assertEquals(5.0, penaltyOf(count = null, sales = null, config = ScoringConfig(assumedRatingCountWhenUnknown = 5)))
    }

    @Test
    fun aShopThatIsClosedRightNowLosesTenPoints() {
        val unknown = result(solid(openNow = null))
        val open = result(solid(openNow = true))
        val closed = result(solid(openNow = false))
        assertEquals(10.0, closed.breakdown.penalty)
        assertEquals(0.0, open.breakdown.penalty)
        assertEquals(unknown, open.copy(shop = unknown.shop))
        assertEquals(10.0, unknown.score - closed.score, 0.11)
        assertTrue(closed.warnings.contains("当前未营业"))
    }

    @Test
    fun penaltiesStackAndAreStoredAsTheirSum() {
        val everything = result(solid(rating = 5.0, count = 5, openNow = false, reviews = hygieneComplaints))
        assertEquals(15.0 + 8.0 + 5.0 + 10.0, everything.breakdown.penalty)
        assertEquals(Verdict.AVOID, everything.verdict)
        assertEquals(13.0, penaltyOf(rating = 5.0, count = 5))
    }

    @Test
    fun theScoreIsNeverBelowZero() {
        val onlyTheRating = ScoringConfig(weightConfidence = 0.0)
        val hopeless = result(solid(rating = 3.0, count = 100_000, openNow = false).copy(avgPrice = null, distanceMeters = null, deliveryMinutes = null, deliveryFee = null), onlyTheRating)
        assertEquals(0.0, hopeless.breakdown.ratingScore)
        assertEquals(10.0, hopeless.breakdown.penalty)
        assertEquals(0.0, hopeless.score)
        assertEquals(Verdict.AVOID, hopeless.verdict)
    }

    @Test
    fun theScoreIsNeverAboveOneHundred() {
        val best = shop(
            name = "满分店",
            rating = 5.0,
            ratingCount = 1_000_000,
            avgPrice = 5.0,
            distanceMeters = 0,
            deliveryMinutes = 10,
            deliveryFee = 0.0,
            reviews = repeated("好吃，分量足，新鲜", 10),
        )
        val scored = result(best)
        assertEquals(100.0, scored.score)
        assertEquals(Verdict.RECOMMENDED, scored.verdict)
        assertTrue(scored.numbers().all { it in 0.0..100.0 }, scored.numbers().toString())
    }

    // ---- verdicts (DESIGN.md 4.10) ----

    @Test
    fun theThresholdsComeFromTheConfigAndAreInclusive() {
        val score = result(solid()).score
        fun verdictWith(recommended: Double, okay: Double) =
            result(solid(), ScoringConfig(recommendedThreshold = recommended, okayThreshold = okay)).verdict

        assertEquals(Verdict.RECOMMENDED, verdictWith(recommended = score, okay = score - 10))
        assertEquals(Verdict.OKAY, verdictWith(recommended = score + 0.1, okay = score - 10))
        assertEquals(Verdict.OKAY, verdictWith(recommended = score + 20, okay = score))
        assertEquals(Verdict.AVOID, verdictWith(recommended = score + 20, okay = score + 0.1))
    }

    @Test
    fun theVerdictIsDerivedFromTheStoredScore() {
        val scores = (1..40).map { index ->
            result(solid(rating = 4.0 + index * 0.02, count = 50 + index * 37)).let { it.score to it }
        }
        for ((score, scored) in scores) {
            val config = ScoringConfig(recommendedThreshold = score, okayThreshold = score)
            assertEquals(Verdict.RECOMMENDED, result(scored.shop, config).verdict, "threshold == stored score $score")
        }
    }

    @Test
    fun aRatingBelowFourIsAlwaysAvoid() {
        assertEquals(Verdict.AVOID, result(solid(rating = 3.99), permissive).verdict)
        assertEquals(Verdict.AVOID, result(solid(rating = 3.0), permissive).verdict)
        assertEquals(Verdict.RECOMMENDED, result(solid(rating = 4.0), permissive).verdict)
    }

    @Test
    fun theHygieneFlagIsAlwaysAvoid() {
        assertEquals(Verdict.AVOID, result(solid(reviews = hygieneComplaints), permissive).verdict)
        assertEquals(Verdict.AVOID, result(solid(rating = 4.9, count = 100_000, reviews = hygieneComplaints), permissive).verdict)
    }

    @Test
    fun aScoreBelowTheOkayThresholdIsAvoid() {
        val weak = result(shop("弱店", rating = 4.1, ratingCount = 12))
        assertTrue(weak.score < 52.0, "${weak.score}")
        assertEquals(Verdict.AVOID, weak.verdict)
    }

    @Test
    fun anUntrustworthyRatingNeverMakesAShopRecommended() {
        assertEquals(Verdict.OKAY, result(solid(count = 5), permissive).verdict, "fewer than 10 ratings")
        assertEquals(Verdict.OKAY, result(solid(rating = 4.9, count = 20), permissive).verdict, "near-perfect with few ratings")
        assertEquals(Verdict.RECOMMENDED, result(solid(rating = 4.9, count = 50), permissive).verdict)
        assertEquals(Verdict.RECOMMENDED, result(solid(count = 10), permissive).verdict)
    }

    @Test
    fun aClosedShopCanStillBeRecommended() {
        assertEquals(Verdict.RECOMMENDED, result(solid(openNow = false), permissive).verdict)
    }

    // ---- no rating at all ----

    @Test
    fun aShopWithoutARatingIsInsufficientData() {
        val scored = result(solid(rating = null, reviews = hygieneComplaints, sales = 5000).copy(openNow = false))
        assertEquals(Verdict.INSUFFICIENT_DATA, scored.verdict)
        assertEquals(0.0, scored.score)
        assertEquals(ScoreBreakdown(), scored.breakdown)
        assertEquals(emptyList(), scored.reasons)
        assertEquals(listOf("暂无评分，无法判断口碑"), scored.warnings)
    }

    @Test
    fun anUnusableRatingIsTreatedLikeAMissingOne() {
        for (rating in listOf(0.0, -1.0, 5.0001, 7.0, 100.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val scored = result(solid(rating = rating))
            assertEquals(Verdict.INSUFFICIENT_DATA, scored.verdict, "rating=$rating")
            assertEquals(0.0, scored.score)
        }
    }

    @Test
    fun theRatingRangeIsExclusiveOfZeroAndInclusiveOfFive() {
        assertFalse(result(solid(rating = 0.01)).verdict == Verdict.INSUFFICIENT_DATA)
        assertFalse(result(solid(rating = 5.0)).verdict == Verdict.INSUFFICIENT_DATA)
        assertEquals(Verdict.INSUFFICIENT_DATA, result(solid(rating = 0.0)).verdict)
    }

    @Test
    fun praiseInTheReviewsCarriesNoPenalty() {
        assertEquals(0.0, result(solid(reviews = reviews("很好吃", "不错", "推荐"))).breakdown.penalty)
    }
}
