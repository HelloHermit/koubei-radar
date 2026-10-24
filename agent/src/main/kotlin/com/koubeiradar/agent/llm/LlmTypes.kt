package com.koubeiradar.agent.llm

import kotlinx.serialization.json.JsonObject

/**
 * Provider-neutral conversation model. The agent loop only talks to [LlmClient]; the two implementations
 * ([AnthropicLlmClient], [OpenAiCompatLlmClient]) translate to and from their wire formats.
 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    /** Raw image bytes; the client base64-encodes them for its wire format. */
    class Image(val mediaType: String, val bytes: ByteArray) : ContentPart
}

sealed interface LlmMessage {
    data class User(val parts: List<ContentPart>) : LlmMessage {
        constructor(text: String) : this(listOf(ContentPart.Text(text)))
    }

    /**
     * An assistant turn. [providerState] is an opaque value produced by the client that generated the turn
     * (for Claude: the SDK message, which carries signed thinking blocks that MUST be replayed unchanged in the
     * next request of a tool loop). The agent loop stores and re-sends it without looking inside.
     */
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val providerState: Any? = null,
    ) : LlmMessage

    /** Results for ALL tool calls of the preceding assistant turn, in one message. */
    data class ToolResults(val results: List<ToolResult>) : LlmMessage
}

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

data class ToolResult(val callId: String, val content: String, val isError: Boolean = false)

/** [parameters] is a JSON Schema object (`{"type":"object","properties":{...},"required":[...]}`). */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 4096,
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    operator fun plus(other: TokenUsage) =
        TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stopReason: StopReason,
    val usage: TokenUsage? = null,
    /** Opaque, to be stored in the matching [LlmMessage.Assistant.providerState]. */
    val providerState: Any? = null,
)

interface LlmClient {
    /** One model call. Throws [LlmException] for every failure (never a raw SDK or IO exception). */
    @Throws(LlmException::class)
    suspend fun complete(request: LlmRequest): LlmResponse
}

class LlmException(
    message: String,
    val kind: Kind,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { AUTH, RATE_LIMIT, NETWORK, BAD_REQUEST, SERVER, REFUSAL, OTHER }
}
