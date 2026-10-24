package com.koubeiradar.app.ui.agent

import com.koubeiradar.agent.advisor.AgentEvent
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoredShop

enum class StepStatus { RUNNING, DONE, FAILED }

/** One line of the live log shown while the agent works. */
sealed interface AgentStep {
    /** The model is deciding what to do next; [iteration] starts at 1. */
    data class Thinking(val iteration: Int) : AgentStep

    /** [text] is the Chinese sentence supplied by the agent, e.g. "正在按条件筛选候选店铺…". */
    data class Tool(val tool: String, val text: String, val status: StepStatus) : AgentStep
}

/** Folds the agent's event stream into the step log, keeping at most [MAX_STEPS] lines. */
fun List<AgentStep>.after(event: AgentEvent): List<AgentStep> = when (event) {
    is AgentEvent.Started -> this
    is AgentEvent.ModelTurn -> (this + AgentStep.Thinking(event.iteration)).takeLast(MAX_STEPS)
    is AgentEvent.ToolCallStarted -> (this + AgentStep.Tool(event.tool, event.display, StepStatus.RUNNING)).takeLast(MAX_STEPS)
    is AgentEvent.ToolCallFinished -> {
        val status = if (event.isError) StepStatus.FAILED else StepStatus.DONE
        val running = indexOfFirst { it is AgentStep.Tool && it.tool == event.tool && it.status == StepStatus.RUNNING }
        if (running >= 0) {
            toMutableList().also { it[running] = AgentStep.Tool(event.tool, event.display, status) }
        } else {
            (this + AgentStep.Tool(event.tool, event.display, status)).takeLast(MAX_STEPS)
        }
    }
}

private const val MAX_STEPS = 40

/** A recommendation card of the agent's answer; [rank] starts at 1. */
data class PickUi(
    val rank: Int,
    val scored: ScoredShop,
    val reason: String,
    val dishes: List<String>,
    val cautions: List<String>,
)

sealed interface AgentError {
    /** No provider is set up yet. */
    data object NotConfigured : AgentError

    data class Llm(val kind: LlmException.Kind) : AgentError

    data object Unexpected : AgentError
}

/** An entry of the conversation. [id] is stable, for list keys and for addressing an entry from a button. */
sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem

    data class Answer(
        override val id: Long,
        val summary: String,
        /** The filter the agent finally applied, shown as "已为你筛选：…". */
        val criteria: Criteria,
        val picks: List<PickUi>,
        /** The model never submitted a structured answer; the picks come from the deterministic ranking. */
        val degraded: Boolean,
    ) : ChatItem

    /** [request] is what to run again on retry. */
    data class Failure(override val id: Long, val request: String, val error: AgentError) : ChatItem

    /** The deterministic ranking, offered instead of an AI answer. */
    data class RuleBased(override val id: Long, val shops: List<ScoredShop>, val criteria: Criteria) : ChatItem

    data class Cancelled(override val id: Long) : ChatItem
}

data class AgentUiState(
    /** `null` until the settings are read. */
    val configured: Boolean? = null,
    val input: String = "",
    val items: List<ChatItem> = emptyList(),
    /** The live step log while a request runs, `null` when idle. */
    val running: List<AgentStep>? = null,
)
