package com.koubeiradar.agent.advisor

import com.koubeiradar.agent.llm.LlmClient
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.agent.llm.LlmMessage
import com.koubeiradar.agent.llm.LlmRequest
import com.koubeiradar.agent.llm.LlmResponse
import com.koubeiradar.agent.llm.StopReason
import com.koubeiradar.agent.llm.TokenUsage
import com.koubeiradar.agent.llm.ToolCall
import com.koubeiradar.agent.llm.ToolResult
import com.koubeiradar.agent.tools.AgentTool
import com.koubeiradar.agent.tools.ToolOutput
import com.koubeiradar.agent.util.toSafeLine
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.selection.ShopRanker
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.repo.ShopPool
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject

/** What the agent knows about the user's situation. */
data class AdvisorContext(
    /** User location (GCJ-02). `null` disables the `refresh_nearby` tool. */
    val center: GeoPoint?,
    /** The user's default filter; the agent may tighten/loosen it to fit the request. */
    val criteria: Criteria,
    /** Active data source for `refresh_nearby`; `null` disables that tool. */
    val source: ShopSource?,
    /** Free text such as "现在是 12:10，午餐时间". */
    val extraNotes: String? = null,
)

/** One earlier exchange, so follow-ups like "换一家不辣的" work without replaying whole transcripts. */
data class ConversationTurn(
    val userRequest: String,
    val summary: String,
    val pickedShopIds: List<String>,
)

sealed interface AgentEvent {
    data class Started(val userRequest: String) : AgentEvent

    /** Model is thinking about the next step; [iteration] starts at 1. */
    data class ModelTurn(val iteration: Int) : AgentEvent

    /** [display] is a short Chinese sentence for the step log, e.g. "正在按条件筛选候选店铺…". */
    data class ToolCallStarted(val tool: String, val display: String, val arguments: JsonObject) : AgentEvent

    data class ToolCallFinished(val tool: String, val display: String, val isError: Boolean) : AgentEvent
}

data class Pick(
    val scored: ScoredShop,
    /** Why this shop, in the user's terms (Chinese, 1-2 sentences). */
    val reason: String,
    val dishesToTry: List<String> = emptyList(),
    val cautions: List<String> = emptyList(),
)

data class AdvisorResult(
    val summary: String,
    /** Ordered best first; every pick refers to a shop that exists in the pool. */
    val picks: List<Pick>,
    /** The criteria the agent finally applied (shown to the user as "已为你筛选：…"). */
    val criteriaUsed: Criteria,
    val usage: TokenUsage?,
    val iterations: Int,
    /** True when the model never submitted a structured answer and the result was derived from the deterministic ranking. */
    val degraded: Boolean = false,
)

/**
 * Tool-calling agent that turns a natural-language request into a ranked shortlist. Spec: `docs/DESIGN.md` §5.
 *
 * CONTRACT (frozen):
 *  - tools: `list_candidates`, `get_shop_details`, `get_shop_reviews`, `refresh_nearby`, and the terminating
 *    `submit_recommendations` (the model must call it to finish; picks with unknown shop ids are rejected);
 *  - bounded by [maxIterations] model calls; on exhaustion returns a `degraded` result built from the deterministic ranking;
 *  - throws `LlmException` only when the very first model call fails (the UI then falls back to the plain ranking);
 *  - review texts and imported text are untrusted: they are fenced in tool results and the system prompt tells the
 *    model never to follow instructions found inside them.
 */
class ShopAdvisorAgent(
    private val llm: LlmClient,
    private val pool: ShopPool,
    private val ranker: ShopRanker,
    private val maxIterations: Int = 8,
) {
    suspend fun advise(
        userRequest: String,
        context: AdvisorContext,
        previousTurns: List<ConversationTurn> = emptyList(),
        onEvent: (AgentEvent) -> Unit = {},
    ): AdvisorResult {
        fun emit(event: AgentEvent) {
            try {
                onEvent(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A faulty progress callback must not abort the run.
            }
        }

        emit(AgentEvent.Started(userRequest))
        val toolbox = AdvisorToolbox(pool, ranker, context)
        val toolSpecs = toolbox.tools.map { it.spec }
        val toolsByName = toolbox.tools.associateBy { it.spec.name }
        // Append-only: Claude binds signed thinking blocks to the exact prefix, so the system prompt, the tool list and
        // earlier messages must stay byte-identical for the whole run.
        val messages = ArrayList<LlmMessage>()
        messages += LlmMessage.User(
            AdvisorPrompts.firstUserMessage(userRequest, context, previousTurns, pool.all().size) { id -> ShopJson.displayName(pool.find(id)) },
        )

        var usage: TokenUsage? = null
        var iterations = 0
        var reminded = false
        while (iterations < maxIterations) {
            iterations++
            emit(AgentEvent.ModelTurn(iterations))
            val response = try {
                callModel(LlmRequest(AdvisorPrompts.SYSTEM, messages.toList(), toolSpecs, MODEL_MAX_TOKENS))
            } catch (e: LlmException) {
                if (iterations == 1) throw e
                return degrade(context, usage, iterations, "调用模型失败（${describe(e.kind)}）")
            }
            usage = usage.plus(response.usage)
            messages += LlmMessage.Assistant(response.text, response.toolCalls, response.providerState)

            if (response.toolCalls.isEmpty()) {
                if (reminded) break
                reminded = true
                messages += LlmMessage.User(AdvisorPrompts.SUBMIT_REMINDER)
                continue
            }
            // A cut-off turn may carry half-written arguments: answer every call (the API requires it) but run none.
            val truncated = response.stopReason == StopReason.MAX_TOKENS
            val results = ArrayList<ToolResult>(response.toolCalls.size)
            for ((index, call) in response.toolCalls.withIndex()) {
                emit(AgentEvent.ToolCallStarted(call.name, toolbox.describeStart(call), call.arguments))
                val output = when {
                    truncated -> ToolOutput(TRUNCATED_TURN_MESSAGE, isError = true)
                    index >= MAX_TOOL_CALLS_PER_TURN -> ToolOutput(TOO_MANY_CALLS_MESSAGE, isError = true)
                    else -> execute(call, toolsByName)
                }
                results += ToolResult(call.id, output.content, output.isError)
                emit(AgentEvent.ToolCallFinished(call.name, toolbox.describeFinish(call, output), output.isError))
                toolbox.submission?.let { return resultOf(it, usage, iterations) }
            }
            messages += LlmMessage.ToolResults(results)
        }
        val why = if (iterations >= maxIterations) "智能体在 $maxIterations 步内没有完成分析" else "智能体没有给出结构化的推荐结果"
        return degrade(context, usage, iterations, why)
    }

    private suspend fun callModel(request: LlmRequest): LlmResponse {
        val response = try {
            llm.complete(request)
        } catch (e: LlmException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("调用模型时出现未知错误。", LlmException.Kind.OTHER, cause = e)
        }
        if (response.stopReason == StopReason.REFUSAL) throw LlmException("模型拒绝了这次请求。", LlmException.Kind.REFUSAL)
        return response
    }

    /** Runs one tool; unknown tools and crashing tools become tool errors the model can react to. */
    private suspend fun execute(call: ToolCall, toolsByName: Map<String, AgentTool>): ToolOutput {
        val tool = toolsByName[call.name]
            ?: return ToolOutput("错误：没有名为「${call.name.toSafeLine(60)}」的工具。可用工具：${toolsByName.keys.joinToString("、")}。", isError = true)
        return try {
            tool.execute(call.arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutput("错误：工具 ${call.name} 执行失败，请换一种方式，或直接用已有信息调用 submit_recommendations。", isError = true)
        }
    }

    private fun resultOf(submission: Submission, usage: TokenUsage?, iterations: Int) = AdvisorResult(
        summary = submission.summary,
        picks = submission.picks,
        criteriaUsed = submission.criteriaUsed,
        usage = usage,
        iterations = iterations,
    )

    /** The deterministic fallback: the plain ranking of the user's own criteria, best three. */
    private fun degrade(context: AdvisorContext, usage: TokenUsage?, iterations: Int, why: String): AdvisorResult {
        val picks = ranker.select(pool.all(), context.criteria).take(DEGRADED_PICKS).map { scored ->
            Pick(
                scored = scored,
                reason = scored.reasons.firstOrNull() ?: "综合口碑分 ${ShopJson.plain(scored.score, 1)}",
                cautions = scored.warnings.take(2),
            )
        }
        val summary = if (picks.isEmpty()) "$why，当前也没有符合条件的店铺可以推荐。"
        else "$why，已改按口碑评分为你列出前 ${picks.size} 家店铺。"
        return AdvisorResult(summary, picks, context.criteria, usage, iterations, degraded = true)
    }

    private fun describe(kind: LlmException.Kind): String = when (kind) {
        LlmException.Kind.AUTH -> "认证失败"
        LlmException.Kind.RATE_LIMIT -> "请求过于频繁"
        LlmException.Kind.NETWORK -> "网络连接失败"
        LlmException.Kind.BAD_REQUEST -> "请求被拒绝"
        LlmException.Kind.SERVER -> "服务暂时不可用"
        LlmException.Kind.REFUSAL -> "模型拒绝回答"
        LlmException.Kind.OTHER -> "未知错误"
    }

    private fun TokenUsage?.plus(other: TokenUsage?): TokenUsage? = when {
        this == null -> other
        other == null -> this
        else -> this + other
    }

    private companion object {
        /** What the advisor asks for per call; the client's own configured cap still applies. */
        const val MODEL_MAX_TOKENS = 16000
        const val DEGRADED_PICKS = 3
        const val MAX_TOOL_CALLS_PER_TURN = 12
        const val TRUNCATED_TURN_MESSAGE =
            "错误：上一轮输出因长度上限被截断，这次工具调用可能不完整，没有执行。请精简思考和输出后重新调用。"
        const val TOO_MANY_CALLS_MESSAGE = "错误：一轮里调用的工具太多，这次调用被忽略。请分批调用，或直接提交推荐。"
    }
}
