package com.koubeiradar.core.scoring

import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import kotlin.math.exp

/**
 * Computes the reputation score of shops. The algorithm is specified in `docs/DESIGN.md` §4.
 *
 * CONTRACT (frozen, other modules rely on it):
 *  - returns exactly one [ScoredShop] per input shop, **in input order**;
 *  - population statistics (prior mean, percentiles, medians) are computed over the whole [shops] list;
 *  - deterministic and pure: same input -> same output, no I/O, no clock;
 *  - never throws for missing data; unknown values lower confidence or add warnings.
 *
 * Input hygiene: a rating outside (0, 5] counts as unknown (the shop is then INSUFFICIENT_DATA); negative counts,
 * prices, distances, delivery times and fees count as unknown; review stars outside 1..5 and blank review texts are
 * ignored; invalid [ScoringConfig] values fall back to their defaults. Outputs are always finite. Scores are
 * stored with one decimal, the adjusted rating with two; verdicts and rankings use the stored values.
 */
class ReputationScorer(val config: ScoringConfig = ScoringConfig()) {

    private val params = ScoringParams(config)

    fun score(shops: List<Shop>, criteria: Criteria = Criteria()): List<ScoredShop> {
        if (shops.isEmpty()) return emptyList()
        val facts = shops.map { ShopFacts(it, params) }
        val stats = PopulationStats.build(facts, params)
        val maxDistance = criteria.maxDistanceMeters ?: DEFAULT_MAX_DISTANCE_METERS
        return facts.map { scoreShop(it, stats, maxDistance) }
    }

    private fun scoreShop(facts: ShopFacts, stats: PopulationStats, maxDistance: Int): ScoredShop {
        val rating = facts.rating ?: return withoutRating(facts.shop)
        val count = facts.count

        val adjusted = bayesianAverage(rating, count.value, stats.prior, params.priorStrength)
        val absolute = clamp((adjusted - ABSOLUTE_FLOOR) / ABSOLUTE_SPAN, 0.0, 1.0) * 100.0
        val relative = stats.ratingPercentile(adjusted) ?: absolute
        val ratingScore = ABSOLUTE_SHARE * absolute + (1.0 - ABSOLUTE_SHARE) * relative

        val confidence = if (count.basis == CountBasis.ASSUMED) null else confidenceScore(count.value)
        val analysis = ReviewAnalyzer.analyze(facts.shop.reviews)
        val sentiment = analysis.sentimentScore
        val value = valueScore(facts, ratingScore, stats)
        val convenience = convenienceScore(facts, maxDistance)

        val base = WeightedMean()
            .add(params.weightRating, ratingScore)
            .add(params.weightConfidence, confidence)
            .add(params.weightSentiment, sentiment)
            .add(params.weightValue, value)
            .add(params.weightConvenience, convenience)
            .result(fallback = ratingScore)
        val penalties = Penalties.of(rating, count.value, facts.shop.openNow, analysis.hygieneFlag)
        val score = round1(clamp(base - penalties.total, 0.0, 100.0))

        val storedAdjusted = round2(adjusted)
        val explanation = ExplanationInput(facts, rating, storedAdjusted, convenience, analysis, penalties, stats.medianPrice)
        return ScoredShop(
            shop = facts.shop,
            score = score,
            verdict = verdictOf(rating, score, penalties, analysis),
            breakdown = ScoreBreakdown(
                adjustedRating = storedAdjusted,
                ratingScore = round1(ratingScore),
                confidenceScore = confidence?.let(::round1),
                sentimentScore = sentiment?.let(::round1),
                valueScore = value?.let(::round1),
                convenienceScore = convenience?.let(::round1),
                penalty = penalties.total,
                effectiveRatingCount = count.value,
                ratingCountIsEstimated = count.basis != CountBasis.REPORTED,
            ),
            reasons = Explanations.reasons(explanation),
            warnings = Explanations.warnings(explanation),
        )
    }

    private fun withoutRating(shop: Shop) = ScoredShop(
        shop = shop,
        score = 0.0,
        verdict = Verdict.INSUFFICIENT_DATA,
        breakdown = ScoreBreakdown(),
        reasons = emptyList(),
        warnings = listOf(Explanations.NO_RATING),
    )

    private fun verdictOf(rating: Double, score: Double, penalties: Penalties, analysis: ReviewAnalysis): Verdict = when {
        analysis.hygieneFlag || rating < Penalties.LOW_RATING || score < params.okayThreshold -> Verdict.AVOID
        score >= params.recommendedThreshold && !penalties.blocksRecommendation -> Verdict.RECOMMENDED
        else -> Verdict.OKAY
    }

    private fun confidenceScore(ratingCount: Int): Double =
        (1.0 - exp(-ratingCount / params.volumeHalfLifeCount)) * 100.0

    private fun valueScore(facts: ShopFacts, ratingScore: Double, stats: PopulationStats): Double? {
        val price = facts.avgPrice ?: return null
        val percentile = stats.pricePercentile(price) ?: return null
        return 0.5 * (100.0 - percentile) + 0.5 * ratingScore
    }

    private fun convenienceScore(facts: ShopFacts, maxDistance: Int): Double? {
        var total = 0.0
        var parts = 0
        facts.distanceMeters?.let {
            total += distanceScore(it, maxDistance)
            parts++
        }
        facts.deliveryMinutes?.let {
            total += clamp01(1.0 - (it - FAST_DELIVERY_MINUTES) / DELIVERY_MINUTES_SPAN) * 100.0
            parts++
        }
        facts.deliveryFee?.let {
            total += clamp01(1.0 - it / FEE_CEILING) * 100.0
            parts++
        }
        return if (parts == 0) null else total / parts
    }

    private fun distanceScore(distance: Int, maxDistance: Int): Double =
        if (maxDistance <= 0) {
            if (distance == 0) 100.0 else 0.0
        } else {
            clamp01(1.0 - distance.toDouble() / maxDistance) * 100.0
        }

    /** Weighted mean over the components that exist; weights are implicitly renormalised. */
    private class WeightedMean {
        private var weightSum = 0.0
        private var weighted = 0.0

        fun add(weight: Double, score: Double?): WeightedMean {
            if (score != null && weight > 0.0) {
                weightSum += weight
                weighted += weight * score
            }
            return this
        }

        fun result(fallback: Double): Double = if (weightSum > 0.0) weighted / weightSum else fallback
    }

    private companion object {
        const val DEFAULT_MAX_DISTANCE_METERS = 5000
        const val ABSOLUTE_FLOOR = 3.5
        const val ABSOLUTE_SPAN = 1.5
        const val ABSOLUTE_SHARE = 0.7
        const val FAST_DELIVERY_MINUTES = 20
        const val DELIVERY_MINUTES_SPAN = 40.0
        const val FEE_CEILING = 10.0
    }
}
