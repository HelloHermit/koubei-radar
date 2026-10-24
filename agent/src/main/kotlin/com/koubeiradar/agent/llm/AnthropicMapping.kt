package com.koubeiradar.agent.llm

import com.anthropic.core.JsonBoolean
import com.anthropic.core.JsonMissing
import com.anthropic.core.JsonNumber
import com.anthropic.core.JsonString
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason as SdkStopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.koubeiradar.agent.llm.LlmException.Kind
import com.koubeiradar.agent.util.objectOrNull
import com.koubeiradar.agent.util.stringOrNull
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.anthropic.core.JsonArray as SdkJsonArray
import com.anthropic.core.JsonNull as SdkJsonNull
import com.anthropic.core.JsonObject as SdkJsonObject

/**
 * Opaque [LlmMessage.Assistant.providerState] of [AnthropicLlmClient]: the untouched SDK message. Replaying its content
 * blocks is what keeps signed `thinking` blocks valid across the calls of a tool loop.
 */
internal class AnthropicAssistantState(val message: Message)

/** Translation between the provider-neutral model and the Anthropic SDK types. Pure functions, no I/O. */
internal object AnthropicMapping {

    // ---- requests -------------------------------------------------------------------------------------------------

    /**
     * Maps the conversation to alternating user/assistant messages. Adjacent turns of the same role are merged
     * (tool results followed by a plain user text become ONE user message with the `tool_result` blocks first) and turns
     * without any content are dropped, because the API rejects both consecutive same-role messages and empty ones.
     */
    fun toMessageParams(messages: List<LlmMessage>): List<MessageParam> {
        val turns = ArrayList<Turn>()
        fun add(role: Role, blocks: List<ContentBlockParam>) {
            if (blocks.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.role == role) last.blocks += blocks else turns += Turn(role, blocks.toMutableList())
        }
        for (message in messages) {
            when (message) {
                is LlmMessage.User -> add(Role.USER, message.parts.mapNotNull(::userBlock))
                is LlmMessage.ToolResults -> add(Role.USER, message.results.map(::toolResultBlock))
                is LlmMessage.Assistant -> add(Role.ASSISTANT, assistantBlocks(message))
            }
        }
        return turns.map { turn ->
            when (turn.role) {
                Role.USER -> MessageParam.ofUserOfContentBlockParams(turn.blocks)
                Role.ASSISTANT -> MessageParam.ofAssistantOfContentBlockParams(turn.blocks)
            }
        }
    }

    private enum class Role { USER, ASSISTANT }

    private class Turn(val role: Role, val blocks: MutableList<ContentBlockParam>)

    private fun userBlock(part: ContentPart): ContentBlockParam? = when (part) {
        is ContentPart.Text ->
            if (part.text.isBlank()) null else ContentBlockParam.ofText(TextBlockParam.builder().text(part.text).build())

        is ContentPart.Image -> ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .data(Base64.getEncoder().encodeToString(part.bytes))
                        .mediaType(Base64ImageSource.MediaType.of(normalizeImageMediaType(part.mediaType)))
                        .build(),
                )
                .build(),
        )
    }

    private fun toolResultBlock(result: ToolResult): ContentBlockParam =
        ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder()
                .toolUseId(result.callId)
                .content(result.content)
                .apply { if (result.isError) isError(true) }
                .build(),
        )

    private fun assistantBlocks(message: LlmMessage.Assistant): List<ContentBlockParam> {
        val state = message.providerState as? AnthropicAssistantState
        if (state != null) {
            // Replayed exactly as received (thinking signatures included); only blocks the API refuses to accept are skipped.
            return state.message.content().mapNotNull { block -> if (block.isBlankText()) null else block.toParam() }
        }
        val blocks = ArrayList<ContentBlockParam>()
        if (message.text.isNotBlank()) blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(message.text).build())
        for (call in message.toolCalls) {
            blocks += ContentBlockParam.ofToolUse(
                ToolUseBlockParam.builder()
                    .id(call.id)
                    .name(call.name)
                    .input(
                        ToolUseBlockParam.Input.builder()
                            .putAllAdditionalProperties(call.arguments.mapValues { (_, value) -> toSdk(value) })
                            .build(),
                    )
                    .build(),
            )
        }
        return blocks
    }

    /** `{"type":"object","properties":{...},"required":[...], ...}` -> SDK [Tool]. */
    fun toTool(spec: ToolSpec): Tool {
        val schema = spec.parameters
        val properties = schema["properties"].objectOrNull().orEmpty()
        val required = schema["required"] as? JsonArray
        val inputSchema = Tool.InputSchema.builder()
            .properties(
                Tool.InputSchema.Properties.builder()
                    .putAllAdditionalProperties(properties.mapValues { (_, value) -> toSdk(value) })
                    .build(),
            )
            .apply {
                required?.mapNotNull { it.stringOrNull() }?.takeIf { it.isNotEmpty() }?.let { required(it) }
                for ((key, value) in schema) {
                    if (key != "type" && key != "properties" && key != "required") putAdditionalProperty(key, toSdk(value))
                }
            }
            .build()
        return Tool.builder().name(spec.name).description(spec.description).inputSchema(inputSchema).build()
    }

    // ---- responses ------------------------------------------------------------------------------------------------

    /** Throws [LlmException] of kind [Kind.REFUSAL] when the model declined. */
    fun toLlmResponse(message: Message): LlmResponse {
        val stop = message.stopReason().orElse(null)?.asString()
        if (stop == SdkStopReason.REFUSAL.asString()) throw LlmException(refusalText(message), Kind.REFUSAL)

        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n")
        val toolCalls = message.content().mapNotNull { block -> block.toolUse().orElse(null)?.let(::toolCall) }
        val stopReason = when (stop) {
            "tool_use" -> StopReason.TOOL_USE
            "end_turn", "stop_sequence" -> StopReason.END_TURN
            "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
            null -> if (toolCalls.isEmpty()) StopReason.END_TURN else StopReason.TOOL_USE
            else -> StopReason.OTHER
        }
        // Gateways occasionally omit `usage`; a missing figure must not fail an otherwise good answer.
        val usage = runCatching { message.usage() }.getOrNull()
        return LlmResponse(
            text = text,
            toolCalls = toolCalls,
            stopReason = stopReason,
            usage = usage?.let { TokenUsage(it.inputTokens().toIntClamped(), it.outputTokens().toIntClamped()) },
            providerState = AnthropicAssistantState(message),
        )
    }

    private fun toolCall(block: com.anthropic.models.messages.ToolUseBlock): ToolCall {
        val input = toKotlinx(block._input())
        return ToolCall(block.id(), block.name(), input as? JsonObject ?: JsonObject(emptyMap()))
    }

    private fun refusalText(message: Message): String {
        val details = message.stopDetails().orElse(null)
        val explanation = details?.explanation()?.orElse(null)?.trim()?.takeIf { it.isNotEmpty() }
        return if (explanation == null) "Claude 拒绝了这次请求，请换一种问法，或在设置里换用其他模型。"
        else "Claude 拒绝了这次请求：$explanation"
    }

    private fun Long.toIntClamped(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun ContentBlock.isBlankText(): Boolean = text().orElse(null)?.text()?.isBlank() == true

    // ---- JSON bridge: kotlinx <-> SDK JsonValue -----------------------------------------------------------------

    fun toSdk(element: JsonElement): JsonValue = when (element) {
        is JsonNull -> SdkJsonNull.of()
        is JsonPrimitive -> when {
            element.isString -> JsonString.of(element.content)
            element.content == "true" -> JsonBoolean.of(true)
            element.content == "false" -> JsonBoolean.of(false)
            else -> JsonNumber.of(element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: 0L)
        }

        is JsonArray -> SdkJsonArray.of(element.map(::toSdk))
        is JsonObject -> SdkJsonObject.of(element.mapValues { (_, value) -> toSdk(value) })
    }

    fun toKotlinx(value: JsonValue): JsonElement = when (value) {
        is JsonString -> JsonPrimitive(value.value)
        is JsonBoolean -> JsonPrimitive(value.value)
        is JsonNumber -> numberToJson(value.value)
        is SdkJsonArray -> JsonArray(value.values.map(::toKotlinx))
        is SdkJsonObject -> JsonObject(value.values.mapValues { (_, item) -> toKotlinx(item) })
        is SdkJsonNull, is JsonMissing -> JsonNull
    }

    private fun numberToJson(number: Number): JsonPrimitive = when (number) {
        is Int, is Long, is Short, is Byte -> JsonPrimitive(number.toLong())
        is java.math.BigInteger -> JsonPrimitive(number)
        else -> JsonPrimitive(number.toDouble())
    }
}
