package com.koubeiradar.agent.advisor

import com.koubeiradar.agent.util.toSafeLine
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import kotlin.math.pow
import kotlin.math.round
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON and prose renditions of shops and criteria for the model. Null fields are omitted, numbers rounded sensibly. */
internal object ShopJson {
    private const val MAX_NAME = 60
    private const val MAX_TEXT = 40

    /** Rounds to [decimals] places; whole numbers are written without a fractional part (`32`, not `32.0`). */
    fun number(value: Double, decimals: Int): JsonPrimitive {
        val factor = 10.0.pow(decimals)
        val rounded = round(value * factor) / factor
        return if (rounded == rounded.toLong().toDouble()) JsonPrimitive(rounded.toLong()) else JsonPrimitive(rounded)
    }

    fun plain(value: Double, decimals: Int = 2): String = number(value, decimals).content

    fun wireName(platform: Platform): String = platform.name.lowercase()

    /** The compact per-shop row of `list_candidates`. */
    fun listRow(scored: ScoredShop): JsonObject {
        val shop = scored.shop
        return buildJsonObject {
            put("id", shop.id)
            put("name", shop.name.toSafeLine(MAX_NAME))
            shop.category?.let { put("category", it.toSafeLine(MAX_TEXT)) }
            shop.rating?.let { put("rating", number(it, 2)) }
            shop.ratingCount?.let { put("rating_count", it) }
            shop.monthlySales?.let { put("monthly_sales", it) }
            shop.avgPrice?.let { put("avg_price", number(it, 1)) }
            shop.distanceMeters?.let { put("distance_m", it) }
            shop.deliveryMinutes?.let { put("delivery_min", it) }
            shop.deliveryFee?.let { put("delivery_fee", number(it, 1)) }
            put("score", number(scored.score, 1))
            put("verdict", scored.verdict.name)
            if (scored.reasons.isNotEmpty()) put("top_reasons", strings(scored.reasons.take(2)))
            if (scored.warnings.isNotEmpty()) put("warnings", strings(scored.warnings.take(3)))
        }
    }

    /** The full record of `get_shop_details`. Coordinates and phone numbers are of no use to the decision and left out. */
    fun details(scored: ScoredShop): JsonObject {
        val shop = scored.shop
        val breakdown = scored.breakdown
        return buildJsonObject {
            put("id", shop.id)
            put("name", shop.name.toSafeLine(MAX_NAME))
            put("source", shop.source)
            shop.category?.let { put("category", it.toSafeLine(MAX_TEXT)) }
            shop.rating?.let { put("rating", number(it, 2)) }
            shop.ratingCount?.let { put("rating_count", it) }
            shop.monthlySales?.let { put("monthly_sales", it) }
            shop.avgPrice?.let { put("avg_price", number(it, 1)) }
            shop.distanceMeters?.let { put("distance_m", it) }
            shop.deliveryMinutes?.let { put("delivery_min", it) }
            shop.deliveryFee?.let { put("delivery_fee", number(it, 1)) }
            shop.minOrder?.let { put("min_order", number(it, 1)) }
            shop.address?.let { put("address", it.toSafeLine(MAX_TEXT * 2)) }
            if (shop.tags.isNotEmpty()) put("tags", strings(shop.tags.take(8).map { it.toSafeLine(MAX_TEXT) }))
            shop.openNow?.let { put("open_now", it) }
            if (shop.platforms.isNotEmpty()) put("platforms", strings(shop.platforms.map(::wireName)))
            put("review_texts_available", shop.reviews.size)
            put("score", number(scored.score, 1))
            put("verdict", scored.verdict.name)
            put(
                "breakdown",
                buildJsonObject {
                    breakdown.adjustedRating?.let { put("adjusted_rating", number(it, 2)) }
                    breakdown.ratingScore?.let { put("rating_score", number(it, 1)) }
                    breakdown.confidenceScore?.let { put("confidence_score", number(it, 1)) }
                    breakdown.sentimentScore?.let { put("sentiment_score", number(it, 1)) }
                    breakdown.valueScore?.let { put("value_score", number(it, 1)) }
                    breakdown.convenienceScore?.let { put("convenience_score", number(it, 1)) }
                    put("penalty", number(breakdown.penalty, 1))
                    breakdown.effectiveRatingCount?.let { put("effective_rating_count", it) }
                    if (breakdown.ratingCountIsEstimated) put("rating_count_is_estimated", true)
                },
            )
            if (scored.reasons.isNotEmpty()) put("reasons", strings(scored.reasons))
            if (scored.warnings.isNotEmpty()) put("warnings", strings(scored.warnings))
        }
    }

    /** Only the filters that are actually set; [sort] and [limit] ride along when given. */
    fun criteria(criteria: Criteria, sort: String? = null, limit: Int? = null): JsonObject = buildJsonObject {
        criteria.minRating?.let { put("min_rating", number(it, 2)) }
        criteria.minRatingCount?.let { put("min_rating_count", it) }
        criteria.maxDistanceMeters?.let { put("max_distance_m", it) }
        criteria.minAvgPrice?.let { put("min_avg_price", number(it, 1)) }
        criteria.maxAvgPrice?.let { put("max_avg_price", number(it, 1)) }
        criteria.maxDeliveryMinutes?.let { put("max_delivery_minutes", it) }
        criteria.maxDeliveryFee?.let { put("max_delivery_fee", number(it, 1)) }
        if (criteria.includeKeywords.isNotEmpty()) put("include_keywords", strings(criteria.includeKeywords))
        if (criteria.excludeKeywords.isNotEmpty()) put("exclude_keywords", strings(criteria.excludeKeywords))
        if (criteria.platforms.isNotEmpty()) put("platforms", strings(criteria.platforms.map(::wireName)))
        if (criteria.onlyOpen) put("only_open", true)
        criteria.minScore?.let { put("min_score", number(it, 1)) }
        sort?.let { put("sort", it) }
        limit?.let { put("limit", it) }
    }

    /** "评分不低于 4.3；人均不超过 ¥40 …", or "无" when nothing is set. */
    fun describeCriteria(criteria: Criteria): String {
        val parts = ArrayList<String>()
        criteria.minRating?.let { parts += "评分不低于 ${plain(it)}" }
        criteria.minRatingCount?.let { parts += "评价数不少于 $it" }
        criteria.maxDistanceMeters?.let { parts += "距离不超过 $it 米" }
        criteria.minAvgPrice?.let { parts += "人均不低于 ¥${plain(it, 1)}" }
        criteria.maxAvgPrice?.let { parts += "人均不超过 ¥${plain(it, 1)}" }
        criteria.maxDeliveryMinutes?.let { parts += "送达不超过 $it 分钟" }
        criteria.maxDeliveryFee?.let { parts += "配送费不超过 ¥${plain(it, 1)}" }
        if (criteria.includeKeywords.isNotEmpty()) parts += "包含关键词：${criteria.includeKeywords.joinToString("、")}"
        if (criteria.excludeKeywords.isNotEmpty()) parts += "排除关键词：${criteria.excludeKeywords.joinToString("、")}"
        if (criteria.platforms.isNotEmpty()) parts += "平台：${criteria.platforms.joinToString("、") { it.displayName }}"
        if (criteria.onlyOpen) parts += "只看营业中的店"
        criteria.minScore?.let { parts += "综合口碑分不低于 ${plain(it, 1)}" }
        return if (parts.isEmpty()) "无" else parts.joinToString("；")
    }

    fun displayName(shop: Shop?): String? = shop?.name?.toSafeLine(MAX_NAME)

    private fun strings(values: Iterable<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}
