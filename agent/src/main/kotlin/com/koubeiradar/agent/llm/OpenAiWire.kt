package com.koubeiradar.agent.llm

import com.koubeiradar.agent.llm.LlmException.Kind
import com.koubeiradar.agent.util.arrayOrNull
import com.koubeiradar.agent.util.objectOrNull
import com.koubeiradar.agent.util.parseJsonOrNull
import com.koubeiradar.agent.util.salvageTruncatedJsonObject
import com.koubeiradar.agent.util.stringOrNull
import com.koubeiradar.agent.util.toCompactString
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Opaque [LlmMessage.Assistant.providerState] of [OpenAiCompatLlmClient]. Thinking-mode models (DeepSeek, Kimi ...)
 * return `reasoning_content` next to tool calls and expect it back while the tool loop is running.
 */
internal class OpenAiAssistantState(val reasoningContent: String)

/** Translation between the provider-neutral model and the `/chat/completions` JSON. Pure functions, no I/O. */
internal object OpenAiWire {

    // ---- requests -------------------------------------------------------------------------------------------------

    fun buildRequest(model: String, maxTokens: Int, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(buildMessages(request)))
        if (request.tools.isNotEmpty()) put("tools", JsonArray(request.tools.map(::toolJson)))
        put("max_tokens", maxTokens)
    }

    private fun buildMessages(request: LlmRequest): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        if (request.system.isNotBlank()) out += message("system", JsonPrimitive(request.system))
        for (message in request.messages) {
            when (message) {
                is LlmMessage.User -> userMessage(message)?.let(out::add)
                is LlmMessage.Assistant -> assistantMessage(message)?.let(out::add)
                is LlmMessage.ToolResults -> message.results.mapTo(out) { result ->
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId)
                        put("content", result.content)
                    }
                }
            }
        }
        return out
    }

    private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun userMessage(message: LlmMessage.User): JsonObject? {
        val parts = message.parts.filter { it !is ContentPart.Text || it.text.isNotBlank() }
        if (parts.isEmpty()) return null
        // Plain string unless there is an image: several OpenAI-style providers (DeepSeek ...) reject content arrays.
        if (parts.none { it is ContentPart.Image }) {
            return message("user", JsonPrimitive(parts.joinToString("\n") { (it as ContentPart.Text).text }))
        }
        val content = parts.map { part ->
            when (part) {
                is ContentPart.Text -> buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                }

                is ContentPart.Image -> buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", dataUri(part)) }
                }
            }
        }
        return message("user", JsonArray(content))
    }

    private fun dataUri(image: ContentPart.Image): String =
        "data:${normalizeImageMediaType(image.mediaType)};base64,${Base64.getEncoder().encodeToString(image.bytes)}"

    private fun assistantMessage(message: LlmMessage.Assistant): JsonObject? {
        if (message.text.isBlank() && message.toolCalls.isEmpty()) return null
        return buildJsonObject {
            put("role", "assistant")
            put("content", message.text)
            if (message.toolCalls.isNotEmpty()) {
                val reasoning = (message.providerState as? OpenAiAssistantState)?.reasoningContent
                if (!reasoning.isNullOrBlank()) put("reasoning_content", reasoning)
                put(
                    "tool_calls",
                    JsonArray(
                        message.toolCalls.map { call ->
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments.toCompactString())
                                }
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun toolJson(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", spec.parameters)
        }
    }

    // ---- responses ------------------------------------------------------------------------------------------------

    /**
     * @param nextCallId id generator for providers that return tool calls without an id.
     * @throws LlmException with [Kind.REFUSAL] for `content_filter`, with [Kind.OTHER] when the JSON is not a completion.
     */
    fun parseResponse(providerName: String, root: JsonElement, nextCallId: () -> String): LlmResponse {
        val body = root.objectOrNull() ?: throw LlmErrors.unreadableResponse(providerName)
        val choice = body["choices"].arrayOrNull()?.firstOrNull().objectOrNull()
        if (choice == null) {
            val error = errorMessage(body)
            throw if (error != null) {
                LlmException("$providerName 返回了错误：$error", Kind.OTHER)
            } else {
                LlmErrors.unreadableResponse(providerName)
            }
        }
        val message = choice["message"].objectOrNull()
        val finish = choice["finish_reason"].stringOrNull()?.lowercase()
        val toolCalls = message?.get("tool_calls").arrayOrNull().orEmpty().mapNotNull { toolCall(it, nextCallId, truncated = finish == "length") }
        if (finish == "content_filter") throw LlmException("$providerName 的内容安全策略拦截了这次请求或回答。", Kind.REFUSAL)

        val stopReason = when {
            finish == "length" -> StopReason.MAX_TOKENS
            toolCalls.isNotEmpty() -> StopReason.TOOL_USE // some providers say "stop" even when they return tool calls
            finish == null || finish == "stop" || finish == "end_turn" -> StopReason.END_TURN
            else -> StopReason.OTHER
        }
        val reasoning = message?.get("reasoning_content").stringOrNull()
        return LlmResponse(
            text = textOf(message?.get("content")),
            toolCalls = toolCalls,
            stopReason = stopReason,
            usage = usageOf(body["usage"].objectOrNull()),
            providerState = reasoning?.takeIf { it.isNotBlank() }?.let(::OpenAiAssistantState),
        )
    }

    /** `content` is a string, `null`, or (for a few providers) an array of `{type:"text", text}` parts. */
    private fun textOf(content: JsonElement?): String = when (content) {
        null, JsonNull -> ""
        is JsonArray -> content.mapNotNull { it.objectOrNull()?.get("text").stringOrNull() }.joinToString("\n")
        else -> content.stringOrNull().orEmpty()
    }

    private fun toolCall(element: JsonElement, nextCallId: () -> String, truncated: Boolean): ToolCall? {
        val call = element.objectOrNull() ?: return null
        val function = call["function"].objectOrNull() ?: return null
        val name = function["name"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val id = call["id"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: nextCallId()
        return ToolCall(id, name, argumentsOf(function["arguments"], truncated))
    }

    /** Arguments normally arrive as a JSON *string*; empty or malformed ones become an empty object. */
    private fun argumentsOf(arguments: JsonElement?, truncated: Boolean): JsonObject = when (arguments) {
        is JsonObject -> arguments
        else -> {
            val text = arguments.stringOrNull().orEmpty()
            parseJsonOrNull(text).objectOrNull()
                ?: (if (truncated) salvageTruncatedJsonObject(text) else null) // cut off by max_tokens: keep what is complete
                ?: JsonObject(emptyMap())
        }
    }

    private fun usageOf(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        val input = (usage["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
        val output = (usage["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
        return if (input == null && output == null) null else TokenUsage(input ?: 0, output ?: 0)
    }

    /** `{"error":{"message":..}}`, `{"error":".."}`, `{"message":..}`, `{"detail":..}` or a bare string. */
    fun errorMessage(root: JsonElement?): String? {
        val obj = root.objectOrNull() ?: return root.stringOrNull()
        val error = obj["error"]
        return error.stringOrNull()
            ?: error.objectOrNull()?.get("message").stringOrNull()
            ?: obj["message"].stringOrNull()
            ?: obj["detail"].stringOrNull()
            ?: obj["msg"].stringOrNull()
    }
}
