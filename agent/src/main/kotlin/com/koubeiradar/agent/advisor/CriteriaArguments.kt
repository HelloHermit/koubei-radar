package com.koubeiradar.agent.advisor

import com.koubeiradar.agent.tools.ArgReader
import com.koubeiradar.core.model.Criteria

/**
 * Reads the filter arguments shared by `list_candidates` and `submit_recommendations.criteria_used`.
 *
 * Arguments that are given override the user's [defaults]; with `ignore_default_filters=true` the starting point is the
 * empty [Criteria] instead. Unreadable values are reported through [reader] and leave the corresponding filter unchanged.
 */
internal fun readCriteria(reader: ArgReader, defaults: Criteria): Criteria {
    val base = if (reader.boolean("ignore_default_filters") == true) Criteria() else defaults
    val criteria = base.copy(
        minRating = reader.number("min_rating", min = 0.0, max = 5.0) ?: base.minRating,
        minRatingCount = reader.int("min_rating_count", min = 0) ?: base.minRatingCount,
        maxDistanceMeters = reader.int("max_distance_m", min = 1) ?: base.maxDistanceMeters,
        minAvgPrice = reader.number("min_avg_price", min = 0.0) ?: base.minAvgPrice,
        maxAvgPrice = reader.number("max_avg_price", min = 0.0) ?: base.maxAvgPrice,
        maxDeliveryMinutes = reader.int("max_delivery_minutes", min = 1) ?: base.maxDeliveryMinutes,
        includeKeywords = reader.stringList("include_keywords")?.toCollection(LinkedHashSet()) ?: base.includeKeywords,
        excludeKeywords = reader.stringList("exclude_keywords")?.toCollection(LinkedHashSet()) ?: base.excludeKeywords,
        onlyOpen = reader.boolean("only_open") ?: base.onlyOpen,
    )
    val low = criteria.minAvgPrice
    val high = criteria.maxAvgPrice
    if (low != null && high != null && low > high) {
        reader.problem(
            "人均下限 min_avg_price（${ShopJson.plain(low, 1)}）大于上限 max_avg_price（${ShopJson.plain(high, 1)}）；" +
                "注意没传的那一项会沿用用户的默认筛选，可以同时传两项，或设 ignore_default_filters=true",
        )
    }
    return criteria
}
