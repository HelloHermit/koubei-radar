package com.koubeiradar.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Verdict(val label: String) {
    /** Score >= recommended threshold and no red flags. */
    RECOMMENDED("口碑优秀"),

    /** Decent but unremarkable, or good with caveats. */
    OKAY("口碑尚可"),

    /** Hygiene/safety red flags, a poor score, or a rating below 4.0. */
    AVOID("建议避开"),

    /** No rating available at all. */
    INSUFFICIENT_DATA("数据不足"),
}

/** Component scores are 0..100, `null` = the component could not be computed for this shop. */
@Serializable
data class ScoreBreakdown(
    /** Bayesian-adjusted rating on the 0..5 scale. */
    val adjustedRating: Double? = null,
    val ratingScore: Double? = null,
    val confidenceScore: Double? = null,
    val sentimentScore: Double? = null,
    val valueScore: Double? = null,
    val convenienceScore: Double? = null,
    /** Points subtracted from the weighted base score (hygiene flag, suspicious perfect rating, ...). */
    val penalty: Double = 0.0,
    /** Rating count actually used in the Bayesian average (known, estimated from sales, or the assumed default). */
    val effectiveRatingCount: Int? = null,
    /** True when [effectiveRatingCount] was not reported by the source. */
    val ratingCountIsEstimated: Boolean = false,
)

@Serializable
data class ScoredShop(
    val shop: Shop,
    /** Composite reputation score, 0..100 (0 for [Verdict.INSUFFICIENT_DATA]). */
    val score: Double,
    val verdict: Verdict,
    val breakdown: ScoreBreakdown,
    /** Short Chinese bullets explaining why the shop scores well. */
    val reasons: List<String> = emptyList(),
    /** Short Chinese bullets about risks / data gaps. */
    val warnings: List<String> = emptyList(),
)

/** Tunable parameters of [com.koubeiradar.core.scoring.ReputationScorer]. Weights are renormalised over available components. */
@Serializable
data class ScoringConfig(
    val weightRating: Double = 0.45,
    val weightConfidence: Double = 0.15,
    val weightSentiment: Double = 0.15,
    val weightValue: Double = 0.05,
    val weightConvenience: Double = 0.20,
    /** Prior mean of the Bayesian average. `null` = median rating of the candidate set clamped to 4.0..4.6 (4.4 if empty). */
    val priorMeanRating: Double? = null,
    /** Strength of the prior, in "virtual ratings". */
    val priorStrength: Double = 30.0,
    /** Rating count assumed for the shrinkage when the source reports neither a count nor sales. */
    val assumedRatingCountWhenUnknown: Int = 20,
    /** n0 in confidence = 1 - exp(-n / n0). */
    val volumeHalfLifeCount: Double = 150.0,
    /** Estimated ratings per unit of monthly sales when only sales are known. */
    val salesToRatingCountRatio: Double = 0.15,
    val recommendedThreshold: Double = 72.0,
    val okayThreshold: Double = 52.0,
) {
    companion object {
        /** Default: reputation first. */
        val REPUTATION_FIRST = ScoringConfig()

        /** Cheaper shops with good ratings float up. */
        val VALUE_FIRST = ScoringConfig(
            weightRating = 0.35, weightConfidence = 0.10, weightSentiment = 0.10,
            weightValue = 0.30, weightConvenience = 0.15,
        )

        /** Near and fast shops float up. */
        val FAST_FIRST = ScoringConfig(
            weightRating = 0.30, weightConfidence = 0.10, weightSentiment = 0.10,
            weightValue = 0.05, weightConvenience = 0.45,
        )
    }
}
