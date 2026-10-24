package com.koubeiradar.agent.support

import com.koubeiradar.agent.llm.LlmClient
import com.koubeiradar.agent.llm.LlmMessage
import com.koubeiradar.agent.llm.LlmRequest
import com.koubeiradar.agent.llm.LlmResponse
import com.koubeiradar.agent.llm.StopReason
import com.koubeiradar.agent.llm.TokenUsage
import com.koubeiradar.agent.llm.ToolCall
import com.koubeiradar.agent.llm.ToolResult
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Review
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.core.selection.ShopRanker
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.repo.ShopPool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/**
 * Plays back a script: every step is an [LlmResponse] or a [Throwable] to throw. Records each request (the message list is
 * snapshotted by the agent, so earlier requests keep their original content).
 */
class ScriptedLlm(vararg steps: Any) : LlmClient {
    private val script = ArrayDeque(steps.toList())
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return when (val step = checkNotNull(script.removeFirstOrNull()) { "the script has no step left for request #${requests.size}" }) {
            is Throwable -> throw step
            is LlmResponse -> step
            else -> error("unsupported script step: $step")
        }
    }
}

fun args(block: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject(block)

fun call(id: String, name: String, arguments: JsonObject = args()) = ToolCall(id, name, arguments)

fun toolTurn(
    vararg calls: ToolCall,
    state: Any? = null,
    usage: TokenUsage? = TokenUsage(100, 20),
    stop: StopReason = StopReason.TOOL_USE,
    text: String = "",
) = LlmResponse(text, calls.toList(), stop, usage, state)

fun textTurn(text: String, usage: TokenUsage? = TokenUsage(100, 20)) = LlmResponse(text, emptyList(), StopReason.END_TURN, usage)

/** The results the agent sent back in the last message of [this] request. */
fun LlmRequest.lastToolResults(): List<ToolResult> = (messages.last() as LlmMessage.ToolResults).results

class FakeSource(override val id: String = "fake", override val displayName: String = "测试数据源") : ShopSource {
    override suspend fun nearby(query: NearbyQuery): List<Shop> = emptyList()
}

class FakePool(var shops: List<Shop>) : ShopPool {
    class Refresh(val source: ShopSource, val query: NearbyQuery, val force: Boolean)

    val refreshes = mutableListOf<Refresh>()

    /** Called by [refreshNearby]; returns the shops "loaded" (they are added to the pool) or throws. */
    var onRefresh: suspend () -> List<Shop> = { emptyList() }

    override fun all(): List<Shop> = shops

    override fun find(id: String): Shop? = shops.firstOrNull { it.id == id }

    override suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean): List<Shop> {
        refreshes += Refresh(source, query, force)
        val loaded = onRefresh()
        shops = shops.filter { existing -> loaded.none { it.id == existing.id } } + loaded
        return loaded
    }
}

/** A ranker that honours the filter semantics documented on [Criteria] and scores by rating alone. */
class FakeRanker(private val scorer: (Shop) -> ScoredShop = ::defaultScore) : ShopRanker {
    val calls = mutableListOf<Criteria>()

    override fun select(shops: List<Shop>, criteria: Criteria): List<ScoredShop> {
        calls += criteria
        return shops.filter { matches(it, criteria) }
            .map(scorer)
            .sortedWith(compareByDescending<ScoredShop> { it.score }.thenBy { it.shop.name })
            .take(criteria.limit)
    }

    private fun matches(shop: Shop, c: Criteria): Boolean {
        val rating = shop.rating
        val count = shop.ratingCount
        val distance = shop.distanceMeters
        val price = shop.avgPrice
        val minutes = shop.deliveryMinutes
        val haystack = listOfNotNull(shop.name, shop.category) + shop.tags
        val minRating = c.minRating
        val minCount = c.minRatingCount
        val maxDistance = c.maxDistanceMeters
        val minPrice = c.minAvgPrice
        val maxPrice = c.maxAvgPrice
        val maxMinutes = c.maxDeliveryMinutes
        return (minRating == null || (rating != null && rating >= minRating)) &&
            (minCount == null || count == null || count >= minCount) &&
            (maxDistance == null || distance == null || distance <= maxDistance) &&
            (minPrice == null || price == null || price >= minPrice) &&
            (maxPrice == null || price == null || price <= maxPrice) &&
            (maxMinutes == null || minutes == null || minutes <= maxMinutes) &&
            (c.includeKeywords.isEmpty() || c.includeKeywords.any { k -> haystack.any { k in it } }) &&
            c.excludeKeywords.none { k -> haystack.any { k in it } } &&
            !(c.onlyOpen && shop.openNow == false)
    }
}

fun defaultScore(shop: Shop): ScoredShop {
    val rating = shop.rating
    val count = shop.ratingCount
    val score = (rating ?: 0.0) * 20
    val verdict = when {
        rating == null -> Verdict.INSUFFICIENT_DATA
        score >= 93 -> Verdict.RECOMMENDED
        else -> Verdict.OKAY
    }
    return ScoredShop(
        shop = shop,
        score = score,
        verdict = verdict,
        breakdown = ScoreBreakdown(adjustedRating = rating, ratingScore = score, penalty = 0.0, effectiveRatingCount = count),
        reasons = if (rating == null) emptyList() else listOf("评分 $rating" + (count?.let { "（共 $it 条评价）" } ?: "")),
        warnings = if (count == null) listOf("评价数未知，可信度无法判断") else emptyList(),
    )
}

object Shops {
    val laoWang = Shop(
        id = "demo:1", name = "老王川菜馆", source = "demo", category = "川菜", rating = 4.8, ratingCount = 3200,
        monthlySales = 4200, avgPrice = 32.0, distanceMeters = 600, deliveryMinutes = 28, deliveryFee = 3.0,
        tags = listOf("老字号"),
        reviews = listOf(
            Review("味道很好，分量足", 5.0),
            Review("太咸了，而且送得很慢", 2.0),
            Review("一般般", 3.0),
            Review("回购了很多次，推荐", 4.5),
        ),
    )
    val newPerfect = Shop(id = "demo:2", name = "新店满分", source = "demo", category = "快餐", rating = 5.0, ratingCount = 6, avgPrice = 25.0, distanceMeters = 300, deliveryMinutes = 20)
    val amapOnly = Shop(id = "amap:B1", name = "高德小馆", source = "amap", category = "面馆", rating = 4.6, avgPrice = 40.0, distanceMeters = 900)
    val farSlow = Shop(id = "demo:4", name = "又远又慢", source = "demo", category = "湘菜", rating = 4.6, ratingCount = 500, avgPrice = 30.0, distanceMeters = 4600, deliveryMinutes = 65)
    val closed = Shop(id = "demo:5", name = "休息中的店", source = "demo", category = "粥", rating = 4.5, ratingCount = 100, avgPrice = 20.0, openNow = false)
    val unrated = Shop(id = "demo:6", name = "无评分的店", source = "demo")

    val all = listOf(laoWang, newPerfect, amapOnly, farSlow, closed, unrated)
}
