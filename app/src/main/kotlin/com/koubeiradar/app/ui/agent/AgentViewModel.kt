package com.koubeiradar.app.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koubeiradar.agent.advisor.AdvisorContext
import com.koubeiradar.agent.advisor.AdvisorResult
import com.koubeiradar.agent.advisor.AgentEvent
import com.koubeiradar.agent.advisor.ConversationTurn
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.service.AdvisorService
import com.koubeiradar.app.service.AgentNotes
import com.koubeiradar.app.service.DataSources
import com.koubeiradar.app.service.LlmNotConfiguredException
import com.koubeiradar.app.service.PlaceStore
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.service.centerFor
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.store.ShopStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDateTime

/**
 * The conversation with the shop advisor agent. One request runs at a time; follow-ups carry the earlier turns so
 * "换一家不辣的" works. When the model cannot be reached the user is offered the deterministic ranking instead.
 */
class AgentViewModel(
    private val settings: SettingsRepository,
    private val advisor: AdvisorService,
    private val store: ShopStore,
    private val rankers: RankerProvider,
    private val dataSources: DataSources,
    private val places: PlaceStore,
    private val clock: Clock,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _state.asStateFlow()

    private var runJob: Job? = null

    /**
     * Identifies the run whose outcome counts; a cancelled or superseded run must not touch the conversation any more.
     * Volatile because the agent reports its steps from a background thread.
     */
    @Volatile
    private var currentRun = 0L
    private var nextId = 0L
    private var previousTurns: List<ConversationTurn> = emptyList()

    init {
        viewModelScope.launch {
            advisor.isConfigured.collect { configured -> _state.update { it.copy(configured = configured) } }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(input = text.take(MAX_REQUEST_CHARS)) }
    }

    /** Sends the text currently in the input box. */
    fun send() = ask(_state.value.input)

    /** Sends [text] directly (an example chip). */
    fun ask(text: String) {
        val request = text.trim().take(MAX_REQUEST_CHARS)
        if (request.isEmpty() || runJob?.isActive == true) return
        append { ChatItem.User(it, request) }
        run(request)
    }

    fun cancel() {
        if (runJob?.isActive != true) return
        currentRun++
        runJob?.cancel()
        _state.update { it.copy(running = null) }
        append { ChatItem.Cancelled(it) }
    }

    /** Runs the request of a failed turn again; the failure card is replaced by the new attempt. */
    fun retry(failureId: Long) {
        if (runJob?.isActive == true) return
        val failure = _state.value.items.filterIsInstance<ChatItem.Failure>().firstOrNull { it.id == failureId } ?: return
        _state.update { state -> state.copy(items = state.items.filterNot { it.id == failureId }) }
        run(failure.request)
    }

    /** "改用规则排序": shows the deterministic top shops, replacing the failure card [failureId] if given. */
    fun showRuleBased(failureId: Long? = null) {
        viewModelScope.launch {
            val current = settings.settings.first()
            val criteria = current.criteria.copy(limit = RULE_BASED_RESULTS)
            val ranked = withContext(computeDispatcher) { rankers.forPreset(current.preset).select(store.shops.value, criteria) }
            if (failureId == null) {
                append { ChatItem.RuleBased(it, ranked, criteria) }
            } else {
                val item = ChatItem.RuleBased(failureId, ranked, criteria)
                _state.update { state -> state.copy(items = state.items.map { if (it.id == failureId) item else it }) }
            }
        }
    }

    fun newConversation() {
        currentRun++
        runJob?.cancel()
        previousTurns = emptyList()
        _state.update { it.copy(items = emptyList(), running = null) }
    }

    private fun run(request: String) {
        val runId = ++currentRun
        _state.update { it.copy(input = "", running = emptyList()) }
        runJob = viewModelScope.launch {
            try {
                val current = settings.settings.first()
                val source = dataSources.sourceFor(current)
                val context = AdvisorContext(
                    center = places.place.value.centerFor(source),
                    criteria = current.criteria,
                    source = source,
                    extraNotes = AgentNotes.forNow(LocalDateTime.now(clock)),
                )
                val result = advisor.advise(request, context, previousTurns) { event -> onEvent(runId, event) }
                if (runId == currentRun) {
                    previousTurns = (previousTurns + result.toTurn(request)).takeLast(MAX_REMEMBERED_TURNS)
                    append { result.toAnswer(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmNotConfiguredException) {
                fail(runId, request, AgentError.NotConfigured)
            } catch (e: LlmException) {
                fail(runId, request, AgentError.Llm(e.kind))
            } catch (e: Exception) {
                // The agent is another module's code: whatever it throws becomes a failure card, never a crash.
                fail(runId, request, AgentError.Unexpected)
            } finally {
                if (runId == currentRun) _state.update { it.copy(running = null) }
            }
        }
    }

    private fun onEvent(runId: Long, event: AgentEvent) {
        if (runId != currentRun) return
        _state.update { state -> state.running?.let { state.copy(running = it.after(event)) } ?: state }
    }

    private fun fail(runId: Long, request: String, error: AgentError) {
        if (runId == currentRun) append { ChatItem.Failure(it, request, error) }
    }

    /** Ids are allocated outside the state update, which may run its lambda more than once. */
    private fun append(create: (id: Long) -> ChatItem) {
        val item = create(nextId++)
        _state.update { it.copy(items = it.items + item) }
    }

    private fun AdvisorResult.toTurn(request: String) = ConversationTurn(
        userRequest = request,
        summary = summary,
        pickedShopIds = picks.map { it.scored.shop.id },
    )

    private fun AdvisorResult.toAnswer(id: Long) = ChatItem.Answer(
        id = id,
        summary = summary,
        criteria = criteriaUsed,
        picks = picks.mapIndexed { index, pick ->
            PickUi(index + 1, pick.scored, pick.reason, pick.dishesToTry, pick.cautions)
        },
        degraded = degraded,
    )

    companion object {
        const val MAX_REQUEST_CHARS = 500
        const val RULE_BASED_RESULTS = 5
        private const val MAX_REMEMBERED_TURNS = 6
    }
}
