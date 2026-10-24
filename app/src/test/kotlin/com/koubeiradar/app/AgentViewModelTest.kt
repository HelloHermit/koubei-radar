package com.koubeiradar.app

import com.koubeiradar.agent.advisor.AdvisorResult
import com.koubeiradar.agent.advisor.AgentEvent
import com.koubeiradar.agent.advisor.ConversationTurn
import com.koubeiradar.agent.advisor.Pick
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.service.DEMO_CENTER
import com.koubeiradar.app.service.PlaceState
import com.koubeiradar.app.service.PlaceStore
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.ui.agent.AgentError
import com.koubeiradar.app.ui.agent.AgentStep
import com.koubeiradar.app.ui.agent.AgentViewModel
import com.koubeiradar.app.ui.agent.ChatItem
import com.koubeiradar.app.ui.agent.StepStatus
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettings()
    private val advisor = FakeAdvisor()
    private val store = FakeShopStore()
    private val rankers = FakeRankers()
    private val dataSources = FakeDataSources()
    private val places = PlaceStore()

    /** 12:10 in Shanghai. */
    private val clock = Clock.fixed(Instant.parse("2025-03-03T04:10:00Z"), ZoneId.of("Asia/Shanghai"))

    private fun TestScope.viewModel() =
        AgentViewModel(settings, advisor, store, rankers, dataSources, places, clock, StandardTestDispatcher(testScheduler))

    private fun answer(summary: String = "推荐老张", vararg ids: String, degraded: Boolean = false) = AdvisorResult(
        summary = summary,
        picks = ids.map { Pick(scored(shop(it)), reason = "理由$it", dishesToTry = listOf("招牌菜"), cautions = listOf("人多")) },
        criteriaUsed = Criteria(minRating = 4.5),
        usage = null,
        iterations = 2,
        degraded = degraded,
    )

    @Test
    fun `a request runs, shows its steps, and ends with the answer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        advisor.handler = { _, onEvent ->
            onEvent(AgentEvent.Started("想吃面"))
            onEvent(AgentEvent.ModelTurn(1))
            onEvent(AgentEvent.ToolCallStarted("list_candidates", "正在筛选候选店铺…", JsonObject(emptyMap())))
            gate.await()
            onEvent(AgentEvent.ToolCallFinished("list_candidates", "找到 3 家", isError = false))
            answer("推荐老张", "a", "b")
        }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onInputChanged("想吃面")

        viewModel.send()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals("", state.input)
        assertEquals(listOf(ChatItem.User(0, "想吃面")), state.items)
        assertEquals(
            listOf(AgentStep.Thinking(1), AgentStep.Tool("list_candidates", "正在筛选候选店铺…", StepStatus.RUNNING)),
            state.running,
        )

        gate.complete(Unit)
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertNull(state.running)
        val reply = assertIs<ChatItem.Answer>(state.items.last())
        assertEquals("推荐老张", reply.summary)
        assertEquals(listOf(1, 2), reply.picks.map { it.rank })
        assertEquals(listOf("a", "b"), reply.picks.map { it.scored.shop.id })
        assertEquals(Criteria(minRating = 4.5), reply.criteria)
        assertEquals(false, reply.degraded)
    }

    @Test
    fun `the agent is told where the user is, the default filter, the source and the time of day`() = runTest {
        advisor.handler = { _, _ -> answer() }
        settings.state.value = AppSettings(criteria = Criteria(maxAvgPrice = 30.0))
        places.set(PlaceState.Manual("家", GeoPoint(31.0, 121.0)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.ask("来点清淡的")
        advanceUntilIdle()

        val context = advisor.calls.single().context
        assertEquals(GeoPoint(31.0, 121.0), context.center)
        assertEquals(Criteria(maxAvgPrice = 30.0), context.criteria)
        assertEquals("demo", context.source?.id)
        assertEquals("现在是 12:10（午餐时间）", context.extraNotes)
    }

    @Test
    fun `without a position the demo data uses its default centre and real data has none`() = runTest {
        advisor.handler = { _, _ -> answer() }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ask("a")
        advanceUntilIdle()
        assertEquals(DEMO_CENTER, advisor.calls.last().context.center)

        settings.state.value = AppSettings(amapKey = Secret("k"))
        advanceUntilIdle()
        viewModel.ask("b")
        advanceUntilIdle()
        assertNull(advisor.calls.last().context.center)
        assertEquals("amap", advisor.calls.last().context.source?.id)
    }

    @Test
    fun `follow-ups carry the earlier turns, the oldest are forgotten after six`() = runTest {
        var counter = 0
        advisor.handler = { _, _ -> answer("总结${++counter}", "s$counter") }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.ask("第一问")
        advanceUntilIdle()
        viewModel.ask("换一家不辣的")
        advanceUntilIdle()

        assertEquals(emptyList(), advisor.calls[0].previousTurns)
        assertEquals(listOf(ConversationTurn("第一问", "总结1", listOf("s1"))), advisor.calls[1].previousTurns)

        repeat(6) {
            viewModel.ask("再问$it")
            advanceUntilIdle()
        }
        val last = advisor.calls.last().previousTurns
        assertEquals(6, last.size)
        assertEquals("总结2", last.first().summary)
    }

    @Test
    fun `a provider error becomes a card that offers the rule based ranking instead`() = runTest {
        advisor.handler = { _, _ -> throw LlmException("bad key", LlmException.Kind.AUTH, 401) }
        store.pool.value = listOf(shop("a", rating = 4.1), shop("b", rating = 4.9), shop("c", rating = 4.5))
        settings.state.value = AppSettings(criteria = Criteria(minRating = 4.3))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.ask("便宜的")
        advanceUntilIdle()

        val failure = assertIs<ChatItem.Failure>(viewModel.uiState.value.items.last())
        assertEquals(AgentError.Llm(LlmException.Kind.AUTH), failure.error)
        assertEquals("便宜的", failure.request)
        assertNull(viewModel.uiState.value.running)

        viewModel.showRuleBased(failure.id)
        advanceUntilIdle()

        val items = viewModel.uiState.value.items
        assertEquals(2, items.size)
        val rules = assertIs<ChatItem.RuleBased>(items.last())
        assertEquals(failure.id, rules.id, "the failure card is replaced in place")
        assertEquals(listOf("b", "c"), rules.shops.map { it.shop.id })
        assertEquals(AgentViewModel.RULE_BASED_RESULTS, rules.criteria.limit)
        assertEquals(4.3, rules.criteria.minRating)
    }

    @Test
    fun `the rule based ranking uses the scoring preset`() = runTest {
        store.pool.value = listOf(shop("dear", rating = 4.9, price = 90.0), shop("cheap", rating = 4.5, price = 10.0))
        settings.state.value = AppSettings(preset = ScoringPreset.VALUE_FIRST)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.showRuleBased()
        advanceUntilIdle()

        val rules = assertIs<ChatItem.RuleBased>(viewModel.uiState.value.items.single())
        assertEquals(listOf("cheap", "dear"), rules.shops.map { it.shop.id })
    }

    @Test
    fun `retry runs the same request again without repeating the question`() = runTest {
        var fail = true
        advisor.handler = { _, _ -> if (fail) throw LlmException("down", LlmException.Kind.SERVER, 503) else answer() }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ask("川菜")
        advanceUntilIdle()
        val failure = assertIs<ChatItem.Failure>(viewModel.uiState.value.items.last())

        fail = false
        viewModel.retry(failure.id)
        advanceUntilIdle()

        val items = viewModel.uiState.value.items
        assertEquals(listOf(ChatItem.User::class, ChatItem.Answer::class), items.map { it::class })
        assertEquals(listOf("川菜", "川菜"), advisor.calls.map { it.request })
    }

    @Test
    fun `cancel stops the run and leaves the conversation usable`() = runTest {
        var hang = true
        advisor.handler = { _, onEvent ->
            if (hang) {
                onEvent(AgentEvent.ModelTurn(1))
                awaitCancellation()
            }
            answer("好了")
        }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ask("慢请求")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.running != null)

        viewModel.cancel()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertNull(state.running)
        assertEquals(listOf(ChatItem.User::class, ChatItem.Cancelled::class), state.items.map { it::class })

        hang = false
        viewModel.ask("再来")
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(ChatItem.Answer::class, state.items.last()::class)
        assertEquals(emptyList(), advisor.calls.last().previousTurns, "a cancelled turn is not remembered")
        assertNull(state.running)
    }

    @Test
    fun `without a configured provider the user is guided and can still use the rule based ranking`() = runTest {
        advisor.configured.value = false
        store.pool.value = listOf(shop("a"))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.configured)

        viewModel.ask("随便")
        advanceUntilIdle()
        assertEquals(AgentError.NotConfigured, assertIs<ChatItem.Failure>(viewModel.uiState.value.items.last()).error)

        viewModel.showRuleBased()
        advanceUntilIdle()
        assertIs<ChatItem.RuleBased>(viewModel.uiState.value.items.last())
    }

    @Test
    fun `configured is unknown until the settings are read, then follows them`() = runTest {
        val viewModel = viewModel()
        assertNull(viewModel.uiState.value.configured)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.configured)
        advisor.configured.value = false
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.configured)
    }

    @Test
    fun `any other exception from the agent is contained`() = runTest {
        advisor.handler = { _, _ -> throw IllegalStateException("bug") }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ask("x")
        advanceUntilIdle()
        assertEquals(AgentError.Unexpected, assertIs<ChatItem.Failure>(viewModel.uiState.value.items.last()).error)
        assertNull(viewModel.uiState.value.running)
    }

    @Test
    fun `blank input is ignored, long input is cut, and only one request runs at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        advisor.handler = { _, _ -> gate.await(); answer() }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.ask("   ")
        viewModel.onInputChanged("x".repeat(900))
        assertEquals(AgentViewModel.MAX_REQUEST_CHARS, viewModel.uiState.value.input.length)
        advanceUntilIdle()
        assertTrue(advisor.calls.isEmpty())

        viewModel.ask("第一个")
        advanceUntilIdle()
        viewModel.ask("第二个")
        advanceUntilIdle()
        assertEquals(listOf("第一个"), advisor.calls.map { it.request })
        assertEquals(1, viewModel.uiState.value.items.size)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a new conversation forgets everything`() = runTest {
        val gate = CompletableDeferred<Unit>()
        advisor.handler = { call, _ -> if (call.request == "旧的") gate.await(); answer("好", "a") }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ask("先问")
        advanceUntilIdle()

        viewModel.ask("旧的")
        advanceUntilIdle()
        viewModel.newConversation()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), viewModel.uiState.value.items, "the abandoned request leaves nothing behind")
        assertNull(viewModel.uiState.value.running)

        viewModel.ask("新的")
        advanceUntilIdle()
        assertEquals(emptyList(), advisor.calls.last().previousTurns)
    }

    @Test
    fun `a degraded answer is marked`() = runTest {
        advisor.handler = { _, _ -> answer("兜底", "a", degraded = true) }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.ask("x")
        advanceUntilIdle()
        assertTrue(assertIs<ChatItem.Answer>(viewModel.uiState.value.items.last()).degraded)
    }
}
