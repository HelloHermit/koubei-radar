package com.koubeiradar.app

import com.koubeiradar.agent.advisor.AgentEvent
import com.koubeiradar.agent.importer.ImportResult
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.ui.agent.AgentStep
import com.koubeiradar.app.ui.agent.StepStatus
import com.koubeiradar.app.ui.agent.after
import com.koubeiradar.app.ui.criteria.CriteriaChip
import com.koubeiradar.app.ui.criteria.CriteriaDraft
import com.koubeiradar.app.ui.criteria.chips
import com.koubeiradar.app.ui.criteria.isUnrestricted
import com.koubeiradar.app.ui.criteria.parseKeywords
import com.koubeiradar.app.ui.criteria.relaxed
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.app.ui.importMessageRes
import com.koubeiradar.app.ui.importer.mergeImportResults
import com.koubeiradar.app.ui.messageRes
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.Platform
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.ShopSourceException
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FormattersTest {
    @Test
    fun distance() {
        assertEquals("0m", Formatters.distance(0))
        assertEquals("0m", Formatters.distance(-5))
        assertEquals("850m", Formatters.distance(850))
        assertEquals("999m", Formatters.distance(999))
        assertEquals("1km", Formatters.distance(1000))
        assertEquals("1.2km", Formatters.distance(1234))
        assertEquals("4.6km", Formatters.distance(4600))
        assertEquals("12km", Formatters.distance(12_345))
    }

    @Test
    fun price() {
        assertEquals("¥0", Formatters.price(0.0))
        assertEquals("¥32", Formatters.price(32.0))
        assertEquals("¥32.5", Formatters.price(32.5))
        assertEquals("¥32.55", Formatters.price(32.55))
        assertEquals("¥33", Formatters.price(32.999))
        assertEquals("¥-", Formatters.price(Double.NaN))
    }

    @Test
    fun `rating score and count`() {
        assertEquals("4.8", Formatters.rating(4.8))
        assertEquals("5.0", Formatters.rating(5.0))
        assertEquals("4.3", Formatters.rating(4.25))
        assertEquals(0, Formatters.score(-3.0))
        assertEquals(87, Formatters.score(86.5))
        assertEquals(100, Formatters.score(140.0))
        assertEquals("3200", Formatters.count(3200))
    }
}

class ErrorMessagesTest {
    @Test
    fun `every source error kind has its own message`() {
        val ids = ShopSourceException.Kind.entries.map { it.messageRes() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it != 0 })
    }

    @Test
    fun `every provider error kind has its own message`() {
        val ids = LlmException.Kind.entries.map { it.messageRes() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it != 0 })
    }

    @Test
    fun `a rejected request during import points at the vision model`() {
        assertNotEquals(LlmException.Kind.BAD_REQUEST.messageRes(), LlmException.Kind.BAD_REQUEST.importMessageRes())
        assertEquals(LlmException.Kind.AUTH.messageRes(), LlmException.Kind.AUTH.importMessageRes())
    }
}

class CriteriaModelTest {
    private val strict = Criteria(
        minRating = 4.8,
        minRatingCount = 800,
        maxDistanceMeters = 1500,
        minAvgPrice = 10.0,
        maxAvgPrice = 30.0,
        maxDeliveryMinutes = 30,
        maxDeliveryFee = 3.0,
        includeKeywords = setOf("川菜"),
        excludeKeywords = setOf("辣"),
        platforms = setOf(Platform.ELEME),
        onlyOpen = true,
        minScore = 70.0,
    )

    @Test
    fun `no restriction means no chips`() {
        assertTrue(Criteria().chips().isEmpty())
        assertTrue(Criteria(limit = 5).isUnrestricted(), "the result limit is not a restriction")
    }

    @Test
    fun `every restriction becomes one chip in display order`() {
        val chips = strict.chips()
        assertEquals(
            listOf(
                CriteriaChip.MinRating(4.8), CriteriaChip.MinRatingCount(800), CriteriaChip.MaxDistance(1500),
                CriteriaChip.PriceRange(10.0, 30.0), CriteriaChip.MaxDeliveryMinutes(30), CriteriaChip.MaxDeliveryFee(3.0),
                CriteriaChip.IncludeKeywords(listOf("川菜")), CriteriaChip.ExcludeKeywords(listOf("辣")),
                CriteriaChip.Platforms(listOf(Platform.ELEME)), CriteriaChip.OnlyOpen, CriteriaChip.MinScore(70.0),
            ),
            chips,
        )
    }

    @Test
    fun `relaxing loosens numbers by a notch and releases flags but keeps keywords`() {
        val relaxed = strict.relaxed()
        assertEquals(4.5, relaxed.minRating)
        assertEquals(400, relaxed.minRatingCount)
        assertEquals(2500, relaxed.maxDistanceMeters)
        assertEquals(null, relaxed.minAvgPrice)
        assertEquals(45.0, relaxed.maxAvgPrice)
        assertEquals(45, relaxed.maxDeliveryMinutes)
        assertEquals(null, relaxed.maxDeliveryFee)
        assertEquals(setOf("川菜"), relaxed.includeKeywords)
        assertEquals(setOf("辣"), relaxed.excludeKeywords)
        assertTrue(relaxed.platforms.isEmpty() && !relaxed.onlyOpen && relaxed.minScore == null)
    }

    @Test
    fun `relaxing never tightens anything`() {
        var current = strict
        repeat(12) {
            val next = current.relaxed()
            assertTrue((next.minRating ?: 0.0) <= (current.minRating ?: 0.0))
            assertTrue((next.minRatingCount ?: 0) <= (current.minRatingCount ?: 0))
            assertTrue(current.maxDistanceMeters == null || next.maxDistanceMeters == null || next.maxDistanceMeters!! >= current.maxDistanceMeters!!)
            assertTrue(current.maxDeliveryMinutes == null || next.maxDeliveryMinutes == null || next.maxDeliveryMinutes!! >= current.maxDeliveryMinutes!!)
            assertTrue(next.chips().size <= current.chips().size)
            current = next
        }
    }

    @Test
    fun `repeated relaxing ends unrestricted and stays there`() {
        var current = strict
        var steps = 0
        while (!current.isUnrestricted()) {
            current = current.relaxed()
            assertTrue(++steps < 12, "did not converge")
        }
        assertEquals(current, current.relaxed())
        assertEquals(strict.limit, current.limit)
    }

    @Test
    fun `keywords are the last thing to go`() {
        assertTrue(Criteria(includeKeywords = setOf("火锅")).relaxed().isUnrestricted())
    }

    @Test
    fun `draft round trip keeps what the form does not offer`() {
        val base = strict.copy(limit = 7)
        assertEquals(base, CriteriaDraft.of(base).applyTo(base))
    }

    @Test
    fun `draft applies edits`() {
        val draft = CriteriaDraft(minRating = 4.5, priceMax = 40, includeKeywords = "川菜, 火锅；烧烤", platforms = setOf(Platform.MEITUAN))
        val applied = draft.applyTo(Criteria(limit = 9, minScore = 60.0))
        assertEquals(4.5, applied.minRating)
        assertEquals(40.0, applied.maxAvgPrice)
        assertEquals(setOf("川菜", "火锅", "烧烤"), applied.includeKeywords)
        assertEquals(9, applied.limit)
        assertEquals(60.0, applied.minScore)
    }

    @Test
    fun `keyword parsing trims splits dedupes and bounds`() {
        assertEquals(setOf("川菜", "火锅", "烧烤", "a"), parseKeywords("  川菜,火锅、烧烤 ; a ，川菜 "))
        assertEquals(emptySet(), parseKeywords(" , ; "))
        assertEquals(CriteriaDraft.MAX_KEYWORDS, parseKeywords((1..30).joinToString(" ") { "k$it" }).size)
        assertEquals(CriteriaDraft.MAX_KEYWORD_LENGTH, parseKeywords("x".repeat(100)).single().length)
    }
}

class ImportMergeTest {
    private fun shop(id: String, rating: Double? = null, sales: Int? = null) = Shop(id = id, name = id, source = "import", rating = rating, monthlySales = sales)

    @Test
    fun `a shop seen in screenshots and text keeps its more complete record`() {
        val merged = mergeImportResults(
            listOf(
                ImportResult(listOf(shop("a", rating = 4.5), shop("b")), listOf("图片备注")),
                ImportResult(listOf(shop("a", rating = 4.6, sales = 100), shop("c")), listOf("文字备注")),
            ),
        )
        assertEquals(listOf("a", "b", "c"), merged.shops.map { it.id })
        assertEquals(100, merged.shops.first().monthlySales)
        assertEquals(listOf("图片备注", "文字备注"), merged.notes)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(ImportResult(emptyList(), emptyList()), mergeImportResults(emptyList()))
    }
}

class AgentStepsTest {
    private val args = JsonObject(emptyMap())

    @Test
    fun `tool steps start running and finish in place`() {
        var steps = emptyList<AgentStep>()
        steps = steps.after(AgentEvent.Started("想吃面"))
        assertTrue(steps.isEmpty())
        steps = steps.after(AgentEvent.ModelTurn(1))
        steps = steps.after(AgentEvent.ToolCallStarted("list_candidates", "正在筛选…", args))
        steps = steps.after(AgentEvent.ToolCallStarted("get_shop_reviews", "正在看评价…", args))
        steps = steps.after(AgentEvent.ToolCallFinished("list_candidates", "找到 8 家", isError = false))
        assertEquals(
            listOf(
                AgentStep.Thinking(1),
                AgentStep.Tool("list_candidates", "找到 8 家", StepStatus.DONE),
                AgentStep.Tool("get_shop_reviews", "正在看评价…", StepStatus.RUNNING),
            ),
            steps,
        )
        steps = steps.after(AgentEvent.ToolCallFinished("get_shop_reviews", "没有评价", isError = true))
        assertEquals(StepStatus.FAILED, (steps.last() as AgentStep.Tool).status)
    }

    @Test
    fun `parallel calls of one tool finish in start order and the log stays bounded`() {
        var steps = emptyList<AgentStep>()
        repeat(2) { steps = steps.after(AgentEvent.ToolCallStarted("get_shop_reviews", "看评价 $it", args)) }
        steps = steps.after(AgentEvent.ToolCallFinished("get_shop_reviews", "完成 0", isError = false))
        assertEquals(listOf(StepStatus.DONE, StepStatus.RUNNING), steps.map { (it as AgentStep.Tool).status })
        repeat(100) { steps = steps.after(AgentEvent.ModelTurn(it)) }
        assertEquals(40, steps.size)
    }

    @Test
    fun `a finish without a start is still shown`() {
        val steps = emptyList<AgentStep>().after(AgentEvent.ToolCallFinished("refresh_nearby", "已刷新", isError = false))
        assertEquals(listOf(AgentStep.Tool("refresh_nearby", "已刷新", StepStatus.DONE)), steps)
    }
}
