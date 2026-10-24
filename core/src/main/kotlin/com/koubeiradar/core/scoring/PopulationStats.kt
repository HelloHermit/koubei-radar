package com.koubeiradar.core.scoring

/**
 * Statistics over the whole candidate set (DESIGN.md §4.0), computed before any filtering so that a strict filter
 * does not skew the prior or the percentiles.
 */
internal class PopulationStats private constructor(
    val prior: Double,
    private val adjustedRatings: DoubleArray,
    private val prices: DoubleArray,
) {
    /** Median price of the set, `null` when too few shops have a price to call it "typical". */
    val medianPrice: Double? = if (prices.size < MIN_PRICED_SHOPS) null else medianOfSorted(prices)

    /** Percentile (0..100) of an adjusted rating, or `null` when too few shops are rated for it to mean anything. */
    fun ratingPercentile(adjusted: Double): Double? =
        if (adjustedRatings.size < MIN_RATED_SHOPS) null else percentileRank(adjustedRatings, adjusted)

    /** Percentile (0..100, 0 = cheapest) of a price, or `null` when too few shops have a price. */
    fun pricePercentile(price: Double): Double? =
        if (prices.size < MIN_PRICED_SHOPS) null else percentileRank(prices, price)

    companion object {
        const val MIN_RATED_SHOPS = 5
        const val MIN_PRICED_SHOPS = 3
        private const val FALLBACK_PRIOR = 4.4
        private const val PRIOR_FLOOR = 4.0
        private const val PRIOR_CEILING = 4.6

        fun build(facts: List<ShopFacts>, params: ScoringParams): PopulationStats {
            val rated = facts.filter { it.rating != null }
            val ratings = DoubleArray(rated.size) { rated[it].rating ?: FALLBACK_PRIOR }.also { it.sort() }
            val prior = params.priorMean
                ?: medianOfSorted(ratings)?.let { clamp(it, PRIOR_FLOOR, PRIOR_CEILING) }
                ?: FALLBACK_PRIOR

            val adjusted = DoubleArray(rated.size) {
                val shop = rated[it]
                bayesianAverage(shop.rating ?: prior, shop.count.value, prior, params.priorStrength)
            }.also { it.sort() }

            val prices = facts.mapNotNull { it.avgPrice }.toDoubleArray().also { it.sort() }
            return PopulationStats(prior, adjusted, prices)
        }
    }
}
