package com.koubeiradar.agent.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Builders for the server-sent events of the Anthropic Messages API (`stream: true`). */
object AnthropicSse {
    fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

    fun messageStart(inputTokens: Int) = event(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$inputTokens,"output_tokens":1}}}""",
    )

    val MESSAGE_START = messageStart(12)
    val PING = event("ping", """{"type": "ping"}""")
    val MESSAGE_STOP = event("message_stop", """{"type":"message_stop"}""")

    fun blockStart(index: Int, block: String) =
        event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":$block}""")

    fun blockDelta(index: Int, delta: String) =
        event("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":$delta}""")

    fun textDelta(index: Int, text: String) = blockDelta(index, """{"type":"text_delta","text":${JsonPrimitive(text)}}""")

    fun inputDelta(index: Int, partialJson: String) =
        blockDelta(index, """{"type":"input_json_delta","partial_json":${JsonPrimitive(partialJson)}}""")

    fun blockStop(index: Int) = event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    fun messageDelta(stopReason: String?, outputTokens: Int, stopDetails: String? = null) = event(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":${stopReason?.let { "\"$it\"" } ?: "null"},"stop_sequence":null,"stop_details":${stopDetails ?: "null"}},"usage":{"output_tokens":$outputTokens}}""",
    )

    /** A complete event stream for a message whose [content] (a JSON array of finished blocks) is streamed block by block. */
    fun stream(
        content: String,
        stopReason: String?,
        stopDetails: String? = null,
        inputTokens: Int = 12,
        outputTokens: Int = 7,
    ): String = buildString {
        append(messageStart(inputTokens))
        Json.parseToJsonElement(content).jsonArray.forEachIndexed { index, element ->
            val block = element.jsonObject
            when (block.str("type")) {
                "text" -> {
                    append(blockStart(index, """{"type":"text","text":""}"""))
                    val text = block.str("text").orEmpty()
                    if (text.isNotEmpty()) {
                        append(textDelta(index, text.substring(0, text.length / 2)))
                        append(textDelta(index, text.substring(text.length / 2)))
                    }
                }

                "thinking" -> {
                    append(blockStart(index, """{"type":"thinking","thinking":"","signature":""}"""))
                    block.str("thinking")?.takeIf { it.isNotEmpty() }
                        ?.let { append(blockDelta(index, """{"type":"thinking_delta","thinking":${JsonPrimitive(it)}}""")) }
                    append(blockDelta(index, """{"type":"signature_delta","signature":${JsonPrimitive(block.str("signature"))}}"""))
                }

                "tool_use", "server_tool_use" -> {
                    append(blockStart(index, """{"type":${JsonPrimitive(block.str("type"))},"id":${JsonPrimitive(block.str("id"))},"name":${JsonPrimitive(block.str("name"))},"input":{}}"""))
                    block.getValue("input").toString().chunked(7).forEach { append(inputDelta(index, it)) }
                }

                else -> append(blockStart(index, block.toString())) // redacted_thinking arrives complete
            }
            append(blockStop(index))
        }
        append(messageDelta(stopReason, outputTokens, stopDetails))
        append(MESSAGE_STOP)
    }
}
