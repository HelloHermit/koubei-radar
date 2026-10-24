package com.koubeiradar.core.scoring

import com.koubeiradar.core.model.ScoringConfig
import com.koubeiradar.core.model.Shop

/**
 * A [ScoringConfig] with every value coerced into a range the formulas can handle. The config comes from user
 * settings, so garbage (NaN, negatives, zero divisors) falls back to the defaults instead of poisoning the scores.
 */
internal class ScoringParams(config: ScoringConfig) {
    private val defaults = ScoringConfig()

    val weightRating = weight(config.weightRating)
    val weightConfidence = weight(config.weightConfidence)
    val weightSentiment = weight(config.weightSentiment)
    val weightValue = weight(config.weightValue)
    val weightConvenience = weight(config.weightConvenience)

    /** Explicit prior mean, or `null` to derive it from the candidate set. */
    val priorMean: Double? = config.priorMeanRating.asRating()
    val priorStrength: Double = config.priorStrength.takeIf { it.isFinite() && it >= 0.0 } ?: defaults.priorStrength
    val assumedRatingCount: Int = config.assumedRatingCountWhenUnknown.takeIf { it >= 0 } ?: defaults.assumedRatingCountWhenUnknown
    val volumeHalfLifeCount: Double = config.volumeHalfLifeCount.takeIf { it.isFinite() && it > 0.0 } ?: defaults.volumeHalfLifeCount
    val salesToRatingCountRatio: Double =
        config.salesToRatingCountRatio.takeIf { it.isFinite() && it >= 0.0 } ?: defaults.salesToRatingCountRatio
    val recommendedThreshold: Double = config.recommendedThreshold.takeUnless { it.isNaN() } ?: defaults.recommendedThreshold
    val okayThreshold: Double = config.okayThreshold.takeUnless { it.isNaN() } ?: defaults.okayThreshold

    private fun weight(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0
}

/** Where the rating count used for the Bayesian average came from. */
internal enum class CountBasis { REPORTED, ESTIMATED_FROM_SALES, ASSUMED }

internal class EffectiveCount(val value: Int, val basis: CountBasis)

/** The facts of one [Shop] after input sanitising: unusable values are `null`, exactly like missing ones. */
internal class ShopFacts(val shop: Shop, params: ScoringParams) {
    val rating: Double? = shop.rating.asRating()
    val monthlySales: Int? = shop.monthlySales.asCount()
    val avgPrice: Double? = shop.avgPrice.asAmount()
    val distanceMeters: Int? = shop.distanceMeters.asCount()
    val deliveryMinutes: Int? = shop.deliveryMinutes.asCount()
    val deliveryFee: Double? = shop.deliveryFee.asAmount()
    val count: EffectiveCount = effectiveCount(shop.ratingCount.asCount(), monthlySales, params)

    private fun effectiveCount(reported: Int?, sales: Int?, params: ScoringParams): EffectiveCount = when {
        reported != null -> EffectiveCount(reported, CountBasis.REPORTED)
        sales != null -> {
            val estimate = Math.round(sales * params.salesToRatingCountRatio).coerceIn(0L, Int.MAX_VALUE.toLong())
            EffectiveCount(estimate.toInt(), CountBasis.ESTIMATED_FROM_SALES)
        }
        else -> EffectiveCount(params.assumedRatingCount, CountBasis.ASSUMED)
    }
}

/** Bayesian average: shrinks [rating] towards [prior] by [priorStrength] virtual ratings. */
internal fun bayesianAverage(rating: Double, count: Int, prior: Double, priorStrength: Double): Double {
    val total = count + priorStrength
    return if (total <= 0.0) rating else (count * rating + priorStrength * prior) / total
}
