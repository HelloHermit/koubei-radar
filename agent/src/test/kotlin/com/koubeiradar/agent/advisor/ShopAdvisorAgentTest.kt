package com.koubeiradar.agent.advisor

import com.koubeiradar.agent.llm.ContentPart
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.agent.llm.LlmMessage
import com.koubeiradar.agent.llm.LlmRequest
import com.koubeiradar.agent.llm.StopReason
import com.koubeiradar.agent.llm.TokenUsage
import com.koubeiradar.agent.support.FakePool
import com.koubeiradar.agent.support.FakeRanker
import com.koubeiradar.agent.support.FakeSource
import com.koubeiradar.agent.support.ScriptedLlm
import com.koubeiradar.agent.support.Shops
import com.koubeiradar.agent.support.args
import com.koubeiradar.agent.support.call
import com.koubeiradar.agent.support.lastToolResults
import com.koubeiradar.agent.support.textTurn
import com.koubeiradar.agent.support.toolTurn
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.Shop
import com.koubeiradar.data.ShopSourceException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ShopAdvisorAgentTest {
    private val ranker = FakeRanker()
    private val pool = FakePool(Shops.all)
    private val center = GeoPoint(31.23, 121.47)
    private val context = AdvisorContext(center, Criteria(minRating = 4.0), FakeSource(), extraNotes = "现在是 12:10，午餐时间")
    private val events = mutableListOf<AgentEvent>()

    private fun agent(llm: ScriptedLlm, maxIterations: Int = 8) = ShopAdvisorAgent(llm, pool, ranker, maxIterations)

    private suspend fun ScriptedLlm.run(
        request: String = "想吃川菜，30 以内",
        ctx: AdvisorContext = context,
        previous: List<ConversationTurn> = emptyList(),
        maxIterations: Int = 8,
    ) = agent(this, maxIterations).advise(request, ctx, previous, events::add)

    private fun submit(id: String, vararg shopIds: String, summary: String = "按口碑挑了几家") = call(
        id,
        "submit_recommendations",
        args {
            put("summary", summary)
            put("picks", JsonArray(shopIds.map { shop -> buildJsonObject { put("shop_id", shop); put("reason", "评分高：$shop") } }))
        },
    )

    private fun AgentEvent.label(): String = when (this) {
        is AgentEvent.Started -> "started"
        is AgentEvent.ModelTurn -> "turn$iteration"
        is AgentEvent.ToolCallStarted -> "start:$tool"
        is AgentEvent.ToolCallFinished -> "finish:$tool:${if (isError) "error" else "ok"}"
    }

    private fun LlmRequest.firstUserText(): String =
        (messages.first() as LlmMessage.User).parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

    // ---- the happy path -------------------------------------------------------------------------------------------

    @Test
    fun `a normal run of list, reviews and submit returns the model's picks`() = runTest {
        val states = List(3) { Any() }
        val llm = ScriptedLlm(
            toolTurn(call("c1", "list_candidates", args { put("max_avg_price", 35) }), state = states[0], usage = TokenUsage(100, 10)),
            toolTurn(call("c2", "get_shop_reviews", args { put("shop_id", "demo:1") }), state = states[1], usage = TokenUsage(200, 20)),
            toolTurn(
                call(
                    "c3",
                    "submit_recommendations",
                    args {
                        put("summary", "老王川菜馆口碑最稳")
                        putJsonArray("picks") {
                            add(
                                buildJsonObject {
                                    put("shop_id", "demo:1")
                                    put("reason", "评分 4.8，3200 条评价")
                                    putJsonArray("dishes_to_try") { add("水煮鱼") }
                                    putJsonArray("cautions") { add("偶尔偏咸") }
                                },
                            )
                        }
                    },
                ),
                state = states[2],
                usage = TokenUsage(300, 30),
            ),
        )

        val result = llm.run()

        assertFalse(result.degraded)
        assertEquals(3, result.iterations)
        assertEquals(TokenUsage(600, 60), result.usage)
        assertEquals("老王川菜馆口碑最稳", result.summary)
        val pick = result.picks.single()
        assertEquals("demo:1", pick.scored.shop.id)
        assertEquals("评分 4.8，3200 条评价", pick.reason)
        assertEquals(listOf("水煮鱼"), pick.dishesToTry)
        assertEquals(listOf("偶尔偏咸"), pick.cautions)
        assertEquals(context.criteria.copy(maxAvgPrice = 35.0), result.criteriaUsed)
        assertEquals(3, llm.requests.size)
    }

    @Test
    fun `events come in order with Chinese step descriptions`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(call("c1", "list_candidates")),
            toolTurn(call("c2", "get_shop_reviews", args { put("shop_id", "demo:1") })),
            toolTurn(call("c3", "submit_recommendations", args { put("summary", "ok") }), call("c4", "list_candidates")),
            toolTurn(submit("c5", "demo:1")),
        )

        llm.run()

        assertEquals(
            listOf(
                "started", "turn1", "start:list_candidates", "finish:list_candidates:ok",
                "turn2", "start:get_shop_reviews", "finish:get_shop_reviews:ok",
                "turn3", "start:submit_recommendations", "finish:submit_recommendations:error", "start:list_candidates", "finish:list_candidates:ok",
                "turn4", "start:submit_recommendations", "finish:submit_recommendations:ok",
            ),
            events.map { it.label() },
        )
        assertEquals("想吃川菜，30 以内", (events.first() as AgentEvent.Started).userRequest)
        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>()
        assertEquals("正在按条件筛选候选店铺…", started[0].display)
        assertTrue("老王川菜馆" in started[1].display && "评价" in started[1].display, started[1].display)
        assertEquals("demo:1", started[1].arguments.getValue("shop_id").jsonPrimitive.content)
        assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().all { it.display.isNotBlank() })
    }

    @Test
    fun `requests keep system prompt and tools constant and every earlier message untouched, provider state goes back as is`() = runTest {
        val states = List(2) { Any() }
        val llm = ScriptedLlm(
            toolTurn(call("c1", "list_candidates"), state = states[0]),
            toolTurn(call("c2", "get_shop_details", args { put("shop_id", "demo:1") }), state = states[1]),
            toolTurn(submit("c3", "demo:1")),
        )

        llm.run()

        val requests = llm.requests
        assertEquals(1, requests.map { it.system }.toSet().size)
        assertEquals(1, requests.map { it.tools }.toSet().size)
        for (i in 1 until requests.size) {
            assertEquals(requests[i - 1].messages, requests[i].messages.take(requests[i - 1].messages.size), "request #$i must extend #${i - 1}")
        }
        val last = requests.last().messages
        assertEquals(listOf("User", "Assistant", "ToolResults", "Assistant", "ToolResults"), last.map { it::class.simpleName })
        assertSame(states[0], (last[1] as LlmMessage.Assistant).providerState)
        assertSame(states[1], (last[3] as LlmMessage.Assistant).providerState)
    }

    @Test
    fun `the first user message carries the request, the situation and earlier turns`() = runTest {
        val llm = ScriptedLlm(toolTurn(submit("c1", "demo:1")))
        val earlier = listOf(ConversationTurn("想吃辣的", "推荐了老王川菜馆", listOf("demo:1", "gone:1")))

        llm.run(request = "换一家不辣的", previous = earlier)

        val text = llm.requests.single().firstUserText()
        for (expected in listOf("换一家不辣的", "想吃辣的", "推荐了老王川菜馆", "「老王川菜馆」(demo:1)", "gone:1", "评分不低于 4", "12:10", "测试数据源", "共 6 家")) {
            assertTrue(expected in text, "`$expected` missing in:\n$text")
        }
        assertTrue("已获取" in text)
        assertTrue(llm.requests.single().system.contains("submit_recommendations"))
    }

    @Test
    fun `without location or source the model is told that refresh_nearby is unavailable`() = runTest {
        val llm = ScriptedLlm(toolTurn(submit("c1", "demo:1")))

        llm.run(ctx = context.copy(center = null, source = null))

        assertTrue("refresh_nearby 不可用" in llm.requests.single().firstUserText())
    }

    // ---- tool-calling mechanics -----------------------------------------------------------------------------------

    @Test
    fun `all results of one turn go into a single ToolResults message in call order`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(
                call("a", "get_shop_reviews", args { put("shop_id", "demo:1") }),
                call("b", "get_shop_details", args { put("shop_id", "amap:B1") }),
                call("c", "list_candidates"),
            ),
            toolTurn(submit("d", "demo:1")),
        )

        llm.run()

        val second = llm.requests[1]
        assertEquals(3, second.messages.size) // user, assistant, ONE tool results message
        val results = second.lastToolResults()
        assertEquals(listOf("a", "b", "c"), results.map { it.callId })
        assertTrue(results.none { it.isError })
        assertTrue("<untrusted_reviews>" in results[0].content)
        assertTrue("高德小馆" in results[1].content)
    }

    @Test
    fun `bad calls become tool errors the model can see and the loop carries on`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(
                call("unknown", "delete_everything"),
                call("noid", "get_shop_details"),
                call("badnum", "list_candidates", args { put("min_rating", "abc") }),
                call("ghost", "get_shop_reviews", args { put("shop_id", "nope") }),
            ),
            toolTurn(submit("ok", "demo:1")),
        )

        val result = llm.run()

        val results = llm.requests[1].lastToolResults().associateBy { it.callId }
        assertTrue(results.values.all { it.isError })
        assertTrue("delete_everything" in results.getValue("unknown").content && "list_candidates" in results.getValue("unknown").content)
        assertTrue("shop_id" in results.getValue("noid").content)
        assertTrue("min_rating" in results.getValue("badnum").content)
        assertTrue("nope" in results.getValue("ghost").content)
        assertFalse(result.degraded)
    }

    @Test
    fun `a tool that crashes is reported as a tool error instead of crashing the run`() = runTest {
        val crashing = FakePool(Shops.all).apply { onRefresh = { throw IllegalStateException("boom: internal detail") } }
        val llm = ScriptedLlm(toolTurn(call("r", "refresh_nearby", args { put("keyword", "奶茶") })), toolTurn(submit("s", "demo:1")))

        val result = ShopAdvisorAgent(llm, crashing, ranker).advise("x", context)

        val error = llm.requests[1].lastToolResults().single()
        assertTrue(error.isError)
        assertFalse("boom" in error.content)
        assertFalse(result.degraded)
    }

    @Test
    fun `a turn cut off by max_tokens runs no tools but answers every call`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(call("a", "get_shop_details", args { put("shop_id", "demo:1") }), call("b", "list_candidates"), stop = StopReason.MAX_TOKENS),
            toolTurn(submit("c", "demo:1")),
        )

        llm.run()

        val results = llm.requests[1].lastToolResults()
        assertEquals(listOf("a", "b"), results.map { it.callId })
        assertTrue(results.all { it.isError && "截断" in it.content })
        assertEquals(1, ranker.calls.size) // only the submission itself ranked the pool
    }

    @Test
    fun `more than twelve calls in one turn are answered but the excess is not executed`() = runTest {
        val calls = (1..14).map { call("c$it", "list_candidates") }.toTypedArray()
        val llm = ScriptedLlm(toolTurn(*calls), toolTurn(submit("s", "demo:1")))

        llm.run()

        val results = llm.requests[1].lastToolResults()
        assertEquals(14, results.size)
        assertEquals(12, results.count { !it.isError })
        assertTrue(results.takeLast(2).all { it.isError })
    }

    @Test
    fun `a call after a successful submission in the same turn is not executed and the run ends`() = runTest {
        val llm = ScriptedLlm(toolTurn(submit("s", "demo:1"), call("late", "list_candidates")))

        val result = llm.run()

        assertEquals(1, llm.requests.size)
        assertEquals("demo:1", result.picks.single().scored.shop.id)
        assertFalse(events.any { it is AgentEvent.ToolCallStarted && it.tool == "list_candidates" })
    }

    @Test
    fun `a throwing event callback does not break the run`() = runTest {
        val llm = ScriptedLlm(toolTurn(submit("s", "demo:1")))

        val result = agent(llm).advise("x", context) { throw IllegalStateException("ui is gone") }

        assertFalse(result.degraded)
    }

    // ---- submit_recommendations validation through the loop -------------------------------------------------------

    @Test
    fun `an unknown shop id is rejected and the corrected submission is accepted`() = runTest {
        val llm = ScriptedLlm(toolTurn(submit("s1", "demo:1", "nope")), toolTurn(submit("s2", "demo:1")))

        val result = llm.run()

        val rejection = llm.requests[1].lastToolResults().single()
        assertTrue(rejection.isError && "nope" in rejection.content && "list_candidates" in rejection.content)
        assertEquals(listOf("demo:1"), result.picks.map { it.scored.shop.id })
        assertFalse(result.degraded)
        assertEquals(2, result.iterations)
    }

    @Test
    fun `more than five picks and duplicate picks are rejected`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(submit("six", "demo:1", "demo:2", "amap:B1", "demo:4", "demo:5", "demo:6")),
            toolTurn(submit("dup", "demo:1", "demo:1")),
            toolTurn(submit("ok", "demo:2", "demo:1", "amap:B1", "demo:4", "demo:5")),
        )

        val result = llm.run()

        assertTrue("最多推荐 5 家" in llm.requests[1].lastToolResults().single().content)
        assertTrue("重复" in llm.requests[2].lastToolResults().single().content)
        assertEquals(listOf("demo:2", "demo:1", "amap:B1", "demo:4", "demo:5"), result.picks.map { it.scored.shop.id }) // order kept
    }

    @Test
    fun `an empty pool lets the model explain instead of picking`() = runTest {
        pool.shops = emptyList()
        val llm = ScriptedLlm(toolTurn(call("s", "submit_recommendations", args { put("summary", "附近没有数据"); putJsonArray("picks") {} })))

        val result = llm.run()

        assertTrue(result.picks.isEmpty())
        assertEquals("附近没有数据", result.summary)
        assertFalse(result.degraded)
    }

    // ---- stopping without submitting ------------------------------------------------------------------------------

    @Test
    fun `a model that stops without submitting gets exactly one reminder and may then succeed`() = runTest {
        val llm = ScriptedLlm(textTurn("我推荐老王川菜馆。"), toolTurn(submit("s", "demo:1")))

        val result = llm.run()

        assertFalse(result.degraded)
        assertEquals(2, llm.requests.size)
        val messages = llm.requests[1].messages
        assertEquals(listOf("User", "Assistant", "User"), messages.map { it::class.simpleName })
        assertEquals("我推荐老王川菜馆。", (messages[1] as LlmMessage.Assistant).text)
        val reminder = (messages[2] as LlmMessage.User).parts.filterIsInstance<ContentPart.Text>().single().text
        assertTrue("请调用 submit_recommendations 提交结果" in reminder)
    }

    @Test
    fun `still no submission after the reminder degrades to the deterministic top three`() = runTest {
        val llm = ScriptedLlm(textTurn("嗯。", TokenUsage(10, 1)), textTurn("没想好。", TokenUsage(20, 2)))

        val result = llm.run()

        assertTrue(result.degraded)
        assertEquals(2, llm.requests.size) // the reminder is sent once, not again
        assertEquals(2, result.iterations)
        assertEquals(TokenUsage(30, 3), result.usage)
        assertEquals(listOf("demo:2", "demo:1", "demo:4"), result.picks.map { it.scored.shop.id })
        assertEquals(context.criteria, ranker.calls.last()) // the fallback ranks with exactly the user's own criteria
        assertEquals("评分 5.0（共 6 条评价）", result.picks.first().reason)
        assertEquals(context.criteria, result.criteriaUsed)
        assertTrue("没有给出结构化" in result.summary && "前 3 家" in result.summary, result.summary)
    }

    @Test
    fun `running out of iterations degrades and says so`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(call("1", "list_candidates")),
            toolTurn(call("2", "list_candidates")),
            toolTurn(call("3", "list_candidates")),
            toolTurn(submit("never", "demo:1")),
        )

        val result = llm.run(maxIterations = 3)

        assertTrue(result.degraded)
        assertEquals(3, result.iterations)
        assertEquals(3, llm.requests.size)
        assertTrue("3 步" in result.summary, result.summary)
        assertEquals(3, result.picks.size)
    }

    // ---- model failures -------------------------------------------------------------------------------------------

    @Test
    fun `a failing first model call is rethrown`() = runTest {
        val llm = ScriptedLlm(LlmException("认证失败", LlmException.Kind.AUTH, 401))

        val failure = assertFailsWith<LlmException> { llm.run() }

        assertEquals(LlmException.Kind.AUTH, failure.kind)
        assertEquals(401, failure.httpStatus)
        assertEquals(listOf("started", "turn1"), events.map { it.label() })
    }

    @Test
    fun `a failure after the first call degrades and names the failure in Chinese`() = runTest {
        val llm = ScriptedLlm(
            toolTurn(call("1", "list_candidates"), usage = TokenUsage(100, 10)),
            LlmException("down", LlmException.Kind.NETWORK),
        )

        val result = llm.run()

        assertTrue(result.degraded)
        assertEquals(2, result.iterations)
        assertEquals(TokenUsage(100, 10), result.usage)
        assertTrue("网络连接失败" in result.summary, result.summary)
        assertEquals(3, result.picks.size)
    }

    @Test
    fun `an unexpected exception from the client is reported as an LlmException on the first call`() = runTest {
        val failure = assertFailsWith<LlmException> { ScriptedLlm(IllegalStateException("bug")).run() }

        assertEquals(LlmException.Kind.OTHER, failure.kind)
    }

    @Test
    fun `a refusal response is treated like a failed call`() = runTest {
        val refusal = toolTurn(stop = StopReason.REFUSAL)

        assertEquals(LlmException.Kind.REFUSAL, assertFailsWith<LlmException> { ScriptedLlm(refusal).run() }.kind)
        val later = ScriptedLlm(toolTurn(call("1", "list_candidates")), refusal).run()
        assertTrue(later.degraded && "拒绝" in later.summary)
    }

    @Test
    fun `cancellation propagates instead of degrading`() = runTest {
        val first = ScriptedLlm(CancellationException("cancelled"))
        assertFailsWith<CancellationException> { first.run() }

        val later = ScriptedLlm(toolTurn(call("1", "list_candidates")), CancellationException("cancelled"))
        assertFailsWith<CancellationException> { later.run() }

        val inTool = FakePool(Shops.all).apply { onRefresh = { throw CancellationException("cancelled") } }
        assertFailsWith<CancellationException> {
            ShopAdvisorAgent(ScriptedLlm(toolTurn(call("r", "refresh_nearby"))), inTool, ranker).advise("x", context)
        }
    }

    // ---- refresh_nearby through the loop --------------------------------------------------------------------------

    @Test
    fun `refresh_nearby fetches new shops that the next list call then sees`() = runTest {
        pool.onRefresh = { listOf(Shop("amap:N1", "新开奶茶店", "amap", category = "奶茶", rating = 4.9, ratingCount = 80)) }
        val llm = ScriptedLlm(
            toolTurn(call("r", "refresh_nearby", args { put("keyword", "奶茶") })),
            toolTurn(call("l", "list_candidates", args { putJsonArray("include_keywords") { add("奶茶") } })),
            toolTurn(submit("s", "amap:N1")),
        )

        val result = llm.run()

        assertEquals("奶茶", pool.refreshes.single().query.keyword)
        assertTrue("新开奶茶店" in llm.requests[2].lastToolResults().single().content)
        assertEquals("amap:N1", result.picks.single().scored.shop.id)
    }

    @Test
    fun `a failing refresh leaves the existing pool usable`() = runTest {
        pool.onRefresh = { throw ShopSourceException("quota", ShopSourceException.Kind.QUOTA) }
        val llm = ScriptedLlm(toolTurn(call("r", "refresh_nearby")), toolTurn(submit("s", "demo:1")))

        val result = llm.run()

        assertTrue(llm.requests[1].lastToolResults().single().isError)
        assertFalse(result.degraded)
        assertContentEquals(Shops.all, pool.all())
        assertTrue(events.filterIsInstance<AgentEvent.ToolCallFinished>().first().isError)
    }
}
