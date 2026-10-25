package com.koubeiradar.agent.llm

import com.koubeiradar.agent.support.AnthropicSse.blockDelta
import com.koubeiradar.agent.support.AnthropicSse.blockStart
import com.koubeiradar.agent.support.AnthropicSse.blockStop
import com.koubeiradar.agent.support.AnthropicSse.event
import com.koubeiradar.agent.support.AnthropicSse.inputDelta
import com.koubeiradar.agent.support.AnthropicSse.MESSAGE_START
import com.koubeiradar.agent.support.AnthropicSse.MESSAGE_STOP
import com.koubeiradar.agent.support.AnthropicSse.messageDelta
import com.koubeiradar.agent.support.AnthropicSse.messageStart
import com.koubeiradar.agent.support.AnthropicSse.PING
import com.koubeiradar.agent.support.AnthropicSse.stream
import com.koubeiradar.agent.support.AnthropicSse.textDelta
import com.koubeiradar.agent.support.arr
import com.koubeiradar.agent.support.at
import com.koubeiradar.agent.support.bodyJson
import com.koubeiradar.agent.support.jsonResponse
import com.koubeiradar.agent.support.nextRequest
import com.koubeiradar.agent.support.obj
import com.koubeiradar.agent.support.sseResponse
import com.koubeiradar.agent.support.str
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before

class AnthropicLlmClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun client(
        maxTokens: Int = 16000,
        effort: String? = null,
        baseUrl: String = server.url("/").toString(),
        apiKey: String = API_KEY,
        maxRetries: Int = 0,
        timeoutSeconds: Long = 30,
    ) = AnthropicLlmClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        maxTokens = maxTokens,
        effort = effort,
        maxRetries = maxRetries,
        timeoutSeconds = timeoutSeconds,
    )

    private fun simpleRequest(maxTokens: Int = 16000) =
        LlmRequest(system = "你是外卖顾问", messages = listOf(LlmMessage.User("附近有什么好吃的？")), maxTokens = maxTokens)

    // ---- request shape --------------------------------------------------------------------------------------------

    @Test
    fun `request carries credentials model budget system and tools but never sampling thinking or tool_choice`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))

        client().complete(
            LlmRequest(
                system = "你是外卖顾问",
                messages = listOf(LlmMessage.User("附近有什么好吃的？")),
                tools = listOf(LIST_CANDIDATES),
                maxTokens = 16000,
            ),
        )

        val recorded = server.nextRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals(API_KEY, recorded.headers["x-api-key"])
        assertNull(recorded.headers["Authorization"], "the key must not be sent twice")
        assertNotNull(recorded.headers["anthropic-version"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))

        val body = recorded.bodyJson()
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals(16000, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("你是外卖顾问", body.str("system"))
        val tool = body.arr("tools").at(0)
        assertEquals("list_candidates", tool.str("name"))
        assertEquals("按条件筛选候选店铺", tool.str("description"))
        val schema = tool.obj("input_schema")
        assertEquals("object", schema.str("type"))
        assertEquals("integer", schema.obj("properties").obj("limit").str("type"))
        assertEquals("array", schema.obj("properties").obj("include_keywords").str("type"))
        assertEquals(listOf("limit"), schema.arr("required").map { it.jsonPrimitive.content })
        assertEquals("true", body.str("stream"), "the call must stream so that cancelling can close the connection")
        for (forbidden in listOf("temperature", "top_p", "top_k", "thinking", "tool_choice", "output_config")) {
            assertFalse(forbidden in body, "request must not contain `$forbidden`")
        }
        assertFalse("eager_input_streaming" in tool, "eager input streaming would disable API-side validation of tool inputs")
        val user = body.arr("messages").at(0)
        assertEquals("user", user.str("role"))
        assertEquals("text", user.arr("content").at(0).str("type"))
        assertEquals("附近有什么好吃的？", user.arr("content").at(0).str("text"))
    }

    @Test
    fun `omits tools and system when there are none`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))

        client().complete(LlmRequest(system = " ", messages = listOf(LlmMessage.User("你好"))))

        val body = server.nextRequest().bodyJson()
        assertFalse("tools" in body)
        assertFalse("system" in body)
    }

    @Test
    fun `base url with trailing slash or v1 suffix reaches the messages endpoint`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))
        server.enqueue(sseResponse(TEXT_STREAM))

        client(baseUrl = server.url("/").toString()).complete(simpleRequest())
        client(baseUrl = server.url("/v1/").toString()).complete(simpleRequest())

        assertEquals("/v1/messages", server.nextRequest().url.encodedPath)
        assertEquals("/v1/messages", server.nextRequest().url.encodedPath)
    }

    @Test
    fun `the request budget can only lower the configured cap`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))
        server.enqueue(sseResponse(TEXT_STREAM))

        client(maxTokens = 8000).complete(simpleRequest(maxTokens = 16000))
        client(maxTokens = 8000).complete(simpleRequest(maxTokens = 1000))

        assertEquals("8000", server.nextRequest().bodyJson()["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("1000", server.nextRequest().bodyJson()["max_tokens"]?.jsonPrimitive?.content)
    }

    // ---- effort ---------------------------------------------------------------------------------------------------

    @Test
    fun `effort is passed through as output_config effort`() = runBlocking<Unit> {
        for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
            server.enqueue(sseResponse(TEXT_STREAM))
            client(effort = effort).complete(simpleRequest())
            assertEquals(effort, server.nextRequest().bodyJson().obj("output_config").str("effort"))
        }
    }

    @Test
    fun `effort is case-insensitive and blank means not configured`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))
        server.enqueue(sseResponse(TEXT_STREAM))

        client(effort = " HIGH ").complete(simpleRequest())
        client(effort = "  ").complete(simpleRequest())

        assertEquals("high", server.nextRequest().bodyJson().obj("output_config").str("effort"))
        assertFalse("output_config" in server.nextRequest().bodyJson())
    }

    @Test
    fun `unknown effort fails at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> { client(effort = "turbo") }
        assertTrue("turbo" in failure.message.orEmpty())
        assertFalse(API_KEY in failure.message.orEmpty())
    }

    @Test
    fun `blank api key fails at construction without echoing anything`() {
        assertFailsWith<IllegalArgumentException> { client(apiKey = "  ") }
    }

    // ---- responses ------------------------------------------------------------------------------------------------

    @Test
    fun `maps a text-only answer`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))

        val response = client().complete(simpleRequest())

        assertEquals("这是推荐结果。", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals(TokenUsage(12, 7), response.usage)
        assertNotNull(response.providerState)
    }

    @Test
    fun `joins several text blocks`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                stream(
                    content = """[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]""",
                    stopReason = "end_turn",
                ),
            ),
        )

        assertEquals("第一段\n第二段", client().complete(simpleRequest()).text)
    }

    @Test
    fun `maps tool_use blocks, stop reason and usage`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TOOL_USE_STREAM))

        val response = client().complete(simpleRequest())

        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(TokenUsage(10, 5), response.usage)
        assertEquals("", response.text)
        val call = response.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("list_candidates", call.name)
        assertEquals(JsonPrimitive(5), call.arguments["limit"])
    }

    @Test
    fun `tool_use input with nested values survives the round trip as kotlinx json`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                stream(
                    content = """[{"type":"tool_use","id":"toolu_9","name":"submit_recommendations","input":
                        {"summary":"好","picks":[{"shop_id":"a","dishes_to_try":["鱼","肉"],"score":4.5,"ok":true,"none":null}]}}]""",
                    stopReason = "tool_use",
                ),
            ),
        )

        val args = client().complete(simpleRequest()).toolCalls.single().arguments

        val pick = args.getValue("picks").jsonArray[0].jsonObject
        assertEquals("a", pick.getValue("shop_id").jsonPrimitive.content)
        assertEquals(listOf("鱼", "肉"), pick.getValue("dishes_to_try").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(4.5, pick.getValue("score").jsonPrimitive.content.toDouble())
        assertEquals(true, pick.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, pick.getValue("none"))
    }

    @Test
    fun `max_tokens and an unknown stop reason are mapped`() = runBlocking<Unit> {
        server.enqueue(sseResponse(stream("""[{"type":"text","text":"被截断"}]""", "max_tokens")))
        server.enqueue(sseResponse(stream("""[{"type":"text","text":"x"}]""", "pause_turn")))

        assertEquals(StopReason.MAX_TOKENS, client().complete(simpleRequest()).stopReason)
        assertEquals(StopReason.OTHER, client().complete(simpleRequest()).stopReason)
    }

    @Test
    fun `a refusal becomes an LlmException of kind REFUSAL`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                stream(
                    content = "[]",
                    stopReason = "refusal",
                    stopDetails = """{"type":"refusal","category":null,"explanation":"无法协助此请求"}""",
                ),
            ),
        )

        val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.REFUSAL, failure.kind)
        assertTrue("无法协助此请求" in failure.message.orEmpty())
        assertNull(failure.httpStatus)
    }

    @Test
    fun `rebuilds text, stop reason and usage from a raw event stream with pings and split deltas`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                PING + MESSAGE_START + PING + blockStart(0, """{"type":"text","text":""}""") + PING +
                    textDelta(0, "这是") + textDelta(0, "推") + PING + textDelta(0, "荐结果。") + blockStop(0) +
                    messageDelta("end_turn", outputTokens = 7) + PING + MESSAGE_STOP,
            ),
        )

        val response = client().complete(simpleRequest())

        assertEquals("这是推荐结果。", response.text)
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals(TokenUsage(12, 7), response.usage, "input from message_start, output from message_delta")
    }

    @Test
    fun `tool_use input is assembled from partial json deltas and an empty partial json gives an empty object`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                MESSAGE_START +
                    blockStart(0, """{"type":"tool_use","id":"toolu_1","name":"submit_recommendations","input":{}}""") +
                    inputDelta(0, "") + inputDelta(0, """{"summary":"口碑""") + inputDelta(0, """好","picks":[{"shop_id":"a","sc""") +
                    inputDelta(0, """ore":4""") + inputDelta(0, """.5,"ok":tr""") + inputDelta(0, """ue}]}""") + blockStop(0) +
                    blockStart(1, """{"type":"tool_use","id":"toolu_2","name":"list_candidates","input":{}}""") +
                    inputDelta(1, "") + blockStop(1) +
                    messageDelta("tool_use", outputTokens = 30) + MESSAGE_STOP,
            ),
        )

        val calls = client().complete(simpleRequest()).toolCalls

        assertEquals(listOf("toolu_1", "toolu_2"), calls.map { it.id })
        val pick = calls[0].arguments.getValue("picks").jsonArray[0].jsonObject
        assertEquals("口碑好", calls[0].arguments.getValue("summary").jsonPrimitive.content)
        assertEquals(4.5, pick.getValue("score").jsonPrimitive.content.toDouble())
        assertEquals(true, pick.getValue("ok").jsonPrimitive.content.toBoolean())
        assertTrue(calls[1].arguments.isEmpty())
    }

    @Test
    fun `a streamed thinking block keeps text and signature and is replayed unchanged next to the assembled tool_use`() =
        runBlocking<Unit> {
            server.enqueue(
                sseResponse(
                    MESSAGE_START +
                        blockStart(0, """{"type":"thinking","thinking":"","signature":""}""") +
                        blockDelta(0, """{"type":"thinking_delta","thinking":"先看看"}""") + PING +
                        blockDelta(0, """{"type":"thinking_delta","thinking":"候选店铺。"}""") +
                        blockDelta(0, """{"type":"signature_delta","signature":"EqQBCkYIARgC/sig+=="}""") + blockStop(0) +
                        blockStart(1, """{"type":"tool_use","id":"toolu_1","name":"list_candidates","input":{}}""") +
                        inputDelta(1, """{"limit""") + inputDelta(1, """": 5}""") + blockStop(1) +
                        messageDelta("tool_use", outputTokens = 9) + MESSAGE_STOP,
                ),
            )
            server.enqueue(sseResponse(TEXT_STREAM))
            val client = client()
            val first = client.complete(simpleRequest())
            server.nextRequest()

            client.complete(
                LlmRequest(
                    "s",
                    listOf(
                        LlmMessage.User("q"),
                        LlmMessage.Assistant(first.text, first.toolCalls, first.providerState),
                        LlmMessage.ToolResults(listOf(ToolResult("toolu_1", "{}"))),
                    ),
                ),
            )

            val assistant = server.nextRequest().bodyJson().arr("messages").at(1).arr("content")
            assertEquals(listOf("thinking", "tool_use"), assistant.map { it.jsonObject.str("type") })
            assertEquals("先看看候选店铺。", assistant.at(0).str("thinking"))
            assertEquals("EqQBCkYIARgC/sig+==", assistant.at(0).str("signature"))
            assertEquals("5", assistant.at(1).obj("input").str("limit"))
        }

    // ---- the tool loop --------------------------------------------------------------------------------------------

    @Test
    fun `second request of a tool loop replays the thinking block unchanged and groups all tool results in one user message`() =
        runBlocking<Unit> {
            server.enqueue(sseResponse(TWO_TOOLS_STREAM))
            server.enqueue(sseResponse(TEXT_STREAM))
            val client = client()
            val first = client.complete(LlmRequest("系统提示", listOf(LlmMessage.User("帮我选一家")), listOf(LIST_CANDIDATES)))
            server.nextRequest()

            client.complete(
                LlmRequest(
                    system = "系统提示",
                    messages = listOf(
                        LlmMessage.User("帮我选一家"),
                        LlmMessage.Assistant(first.text, first.toolCalls, first.providerState),
                        LlmMessage.ToolResults(
                            listOf(
                                ToolResult("toolu_1", """{"total_in_pool":3}"""),
                                ToolResult("toolu_2", "错误：找不到店铺", isError = true),
                            ),
                        ),
                    ),
                    tools = listOf(LIST_CANDIDATES),
                ),
            )

            val messages = server.nextRequest().bodyJson().arr("messages")
            assertEquals(3, messages.size)
            assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject.str("role") })

            val assistant = messages.at(1).arr("content")
            assertEquals(3, assistant.size)
            assertEquals("thinking", assistant.at(0).str("type"))
            assertEquals("SIG123", assistant.at(0).str("signature"))
            assertEquals("", assistant.at(0).str("thinking"))
            assertEquals("tool_use", assistant.at(1).str("type"))
            assertEquals("toolu_1", assistant.at(1).str("id"))
            assertEquals("list_candidates", assistant.at(1).str("name"))
            assertEquals("5", assistant.at(1).obj("input").str("limit"))
            assertEquals("toolu_2", assistant.at(2).str("id"))
            assertEquals("get_shop_details", assistant.at(2).str("name"))
            assertEquals("amap:1", assistant.at(2).obj("input").str("shop_id"))

            val results = messages.at(2).arr("content")
            assertEquals(2, results.size)
            assertEquals(listOf("tool_result", "tool_result"), results.map { it.jsonObject.str("type") })
            assertEquals("toolu_1", results.at(0).str("tool_use_id"))
            assertEquals("""{"total_in_pool":3}""", results.at(0).str("content"))
            assertTrue(results.at(0).str("is_error") != "true")
            assertEquals("toolu_2", results.at(1).str("tool_use_id"))
            assertEquals("true", results.at(1).str("is_error"))
        }

    @Test
    fun `replay keeps redacted thinking blocks and skips empty text blocks`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(
                stream(
                    content = """[{"type":"redacted_thinking","data":"OPAQUE"},{"type":"text","text":""},
                        {"type":"tool_use","id":"toolu_1","name":"list_candidates","input":{}}]""",
                    stopReason = "tool_use",
                ),
            ),
        )
        server.enqueue(sseResponse(TEXT_STREAM))
        val client = client()
        val first = client.complete(simpleRequest())
        server.nextRequest()

        client.complete(
            LlmRequest(
                "s",
                listOf(
                    LlmMessage.User("q"),
                    LlmMessage.Assistant(first.text, first.toolCalls, first.providerState),
                    LlmMessage.ToolResults(listOf(ToolResult("toolu_1", "{}"))),
                ),
            ),
        )

        val assistant = server.nextRequest().bodyJson().arr("messages").at(1).arr("content")
        assertEquals(listOf("redacted_thinking", "tool_use"), assistant.map { it.jsonObject.str("type") })
        assertEquals("OPAQUE", assistant.at(0).str("data"))
    }

    @Test
    fun `an assistant turn without Claude state is rebuilt from text and tool calls`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))

        client().complete(
            LlmRequest(
                "s",
                listOf(
                    LlmMessage.User("q"),
                    LlmMessage.Assistant(
                        "我先查一下",
                        listOf(ToolCall("call_7", "list_candidates", buildJsonObject { put("limit", 3); put("only_open", true) })),
                        providerState = "state of another provider",
                    ),
                    LlmMessage.ToolResults(listOf(ToolResult("call_7", "ok"))),
                ),
            ),
        )

        val assistant = server.nextRequest().bodyJson().arr("messages").at(1).arr("content")
        assertEquals("text", assistant.at(0).str("type"))
        assertEquals("我先查一下", assistant.at(0).str("text"))
        assertEquals("tool_use", assistant.at(1).str("type"))
        assertEquals("call_7", assistant.at(1).str("id"))
        assertEquals("3", assistant.at(1).obj("input").str("limit"))
        assertEquals("true", assistant.at(1).obj("input").str("only_open"))
    }

    @Test
    fun `tool results followed by a user text become one user message and empty turns are dropped`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TOOL_USE_STREAM))
        server.enqueue(sseResponse(TEXT_STREAM))
        val client = client()
        val first = client.complete(simpleRequest())
        server.nextRequest()

        client.complete(
            LlmRequest(
                "s",
                listOf(
                    LlmMessage.User("q"),
                    LlmMessage.Assistant(first.text, first.toolCalls, first.providerState),
                    LlmMessage.ToolResults(listOf(ToolResult("toolu_1", "{}"))),
                    LlmMessage.Assistant(text = "", toolCalls = emptyList(), providerState = null),
                    LlmMessage.User("请调用 submit_recommendations"),
                ),
            ),
        )

        val messages = server.nextRequest().bodyJson().arr("messages")
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject.str("role") })
        val last = messages.at(2).arr("content")
        assertEquals(listOf("tool_result", "text"), last.map { it.jsonObject.str("type") })
        assertEquals("请调用 submit_recommendations", last.at(1).str("text"))
    }

    @Test
    fun `images are sent as base64 blocks with their media type`() = runBlocking<Unit> {
        server.enqueue(sseResponse(TEXT_STREAM))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        client().complete(
            LlmRequest(
                "s",
                listOf(
                    LlmMessage.User(
                        listOf(
                            ContentPart.Text("第1张图片"),
                            ContentPart.Image("image/png", png),
                            ContentPart.Text("第2张图片"),
                            ContentPart.Image("image/jpg", jpeg),
                        ),
                    ),
                ),
            ),
        )

        val content = server.nextRequest().bodyJson().arr("messages").at(0).arr("content")
        assertEquals(listOf("text", "image", "text", "image"), content.map { it.jsonObject.str("type") })
        val firstSource = content.at(1).obj("source")
        assertEquals("base64", firstSource.str("type"))
        assertEquals("image/png", firstSource.str("media_type"))
        assertContentEquals(png, Base64.getDecoder().decode(firstSource.str("data")))
        val secondSource = content.at(3).obj("source")
        assertEquals("image/jpeg", secondSource.str("media_type"))
        assertContentEquals(jpeg, Base64.getDecoder().decode(secondSource.str("data")))
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    @Test
    fun `http statuses map to the documented kinds and keep the status`() = runBlocking<Unit> {
        val expected = mapOf(
            401 to LlmException.Kind.AUTH,
            403 to LlmException.Kind.AUTH,
            429 to LlmException.Kind.RATE_LIMIT,
            400 to LlmException.Kind.BAD_REQUEST,
            404 to LlmException.Kind.BAD_REQUEST,
            413 to LlmException.Kind.BAD_REQUEST,
            422 to LlmException.Kind.BAD_REQUEST,
            500 to LlmException.Kind.SERVER,
            503 to LlmException.Kind.SERVER,
            529 to LlmException.Kind.SERVER,
            402 to LlmException.Kind.OTHER,
            418 to LlmException.Kind.OTHER,
        )
        for ((status, kind) in expected) {
            server.enqueue(jsonResponse(errorBody("提供方的错误说明 $status"), status))

            val failure = assertFailsWith<LlmException>("HTTP $status") { client().complete(simpleRequest()) }

            assertEquals(kind, failure.kind, "HTTP $status")
            assertEquals(status, failure.httpStatus, "HTTP $status")
            assertTrue("提供方的错误说明 $status" in failure.message.orEmpty(), "HTTP $status: ${failure.message}")
            server.nextRequest()
        }
    }

    @Test
    fun `error messages never contain the api key even when the provider echoes it`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(errorBody("invalid x-api-key: $API_KEY"), 401))

        val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.AUTH, failure.kind)
        assertFalse(API_KEY in failure.message.orEmpty())
    }

    @Test
    fun `an error body that is not json still maps by status`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("<html>Bad Gateway</html>", 502))

        val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.SERVER, failure.kind)
        assertEquals(502, failure.httpStatus)
    }

    @Test
    fun `a 200 response that is not an event stream is reported as unreadable`() = runBlocking<Unit> {
        // an HTML page, and a gateway that ignores `stream` and answers with one plain JSON message
        server.enqueue(jsonResponse("<html>not the api</html>"))
        server.enqueue(jsonResponse("""{"id":"msg_1","type":"message","role":"assistant","content":[],"stop_reason":"end_turn"}"""))

        repeat(2) {
            val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

            assertEquals(LlmException.Kind.OTHER, failure.kind)
            assertNull(failure.httpStatus)
        }
    }

    @Test
    fun `an error event in the middle of the stream maps by its error type and keeps the key out`() = runBlocking<Unit> {
        val expected = mapOf(
            "overloaded_error" to LlmException.Kind.SERVER,
            "api_error" to LlmException.Kind.SERVER,
            "rate_limit_error" to LlmException.Kind.RATE_LIMIT,
            "authentication_error" to LlmException.Kind.AUTH,
            "invalid_request_error" to LlmException.Kind.BAD_REQUEST,
            "something_new" to LlmException.Kind.OTHER,
        )
        for ((type, kind) in expected) {
            server.enqueue(
                sseResponse(
                    MESSAGE_START + blockStart(0, """{"type":"text","text":""}""") + textDelta(0, "已经生成了一半") + PING +
                        event("error", """{"type":"error","error":{"type":"$type","message":"说明 $type $API_KEY"}}"""),
                ),
            )

            val failure = assertFailsWith<LlmException>(type) { client().complete(simpleRequest()) }

            assertEquals(kind, failure.kind, type)
            assertNull(failure.httpStatus, "the HTTP status of a started stream is 200 and says nothing")
            assertTrue("说明 $type" in failure.message.orEmpty(), "$type: ${failure.message}")
            assertFalse(API_KEY in failure.message.orEmpty(), type)
            server.nextRequest()
        }
    }

    @Test
    fun `a stream that ends without message_stop is a NETWORK failure, not a partial answer`() = runBlocking<Unit> {
        server.enqueue(
            sseResponse(MESSAGE_START + blockStart(0, """{"type":"text","text":""}""") + textDelta(0, "只说了一半") + blockStop(0)),
        )

        val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.NETWORK, failure.kind)
        assertNull(failure.httpStatus)
    }

    @Test
    fun `a connection cut in the middle of the stream is a NETWORK failure`() = runBlocking<Unit> {
        val partial = MESSAGE_START + blockStart(0, """{"type":"text","text":""}""") + textDelta(0, "只说了一半")
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .addHeader("Content-Length", partial.toByteArray().size + 10_000) // promises more than is ever sent
                .body(partial)
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.NETWORK, failure.kind)
        assertNull(failure.httpStatus)
    }

    @Test
    fun `the overall deadline also bounds a stream that keeps trickling events`() = runBlocking<Unit> {
        val pings = PING.repeat(200)
        server.enqueue(sseResponse(MESSAGE_START + pings + MESSAGE_STOP).newBuilder().throttleBody(50, 100, TimeUnit.MILLISECONDS).build())

        val started = System.nanoTime()
        val failure = assertFailsWith<LlmException> { client(timeoutSeconds = 1).complete(simpleRequest()) }

        assertEquals(LlmException.Kind.NETWORK, failure.kind)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 6_000, "the call must give up after about timeoutSeconds")
    }

    @Test
    fun `an unreachable host is a NETWORK failure`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }

        val failure = assertFailsWith<LlmException> {
            client(baseUrl = "http://127.0.0.1:$closedPort").complete(simpleRequest())
        }

        assertEquals(LlmException.Kind.NETWORK, failure.kind)
        assertNull(failure.httpStatus)
    }

    @Test
    fun `the sdk does not retry when maxRetries is zero`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(errorBody("overloaded"), 529))
        server.enqueue(sseResponse(TEXT_STREAM))

        assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the sdk retries a failed request start but never a stream that broke after it began`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(errorBody("overloaded"), 529).newBuilder().addHeader("retry-after-ms", "10").build())
        server.enqueue(sseResponse(TEXT_STREAM))
        assertEquals("这是推荐结果。", client(maxRetries = 2).complete(simpleRequest()).text)
        assertEquals(2, server.requestCount)

        server.enqueue(sseResponse(MESSAGE_START + textDelta(0, "x") + event("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")))
        server.enqueue(sseResponse(TEXT_STREAM))
        assertFailsWith<LlmException> { client(maxRetries = 2).complete(simpleRequest()) }
        assertEquals(3, server.requestCount)
    }

    // ---- cancellation ---------------------------------------------------------------------------------------------

    @Test
    fun `cancelling the coroutine abandons an in-flight call and propagates CancellationException`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val thrown = AtomicReference<Throwable?>()

        val job = launch(Dispatchers.Default) {
            try {
                client().complete(simpleRequest())
            } catch (t: Throwable) {
                thrown.set(t)
                throw t
            }
        }
        server.nextRequest() // the request has reached the server, the call is now blocked waiting for the response
        job.cancel()
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertIs<CancellationException>(thrown.get())
    }

    // ---- JSON bridge ----------------------------------------------------------------------------------------------

    @Test
    fun `kotlinx json survives the trip through the sdk json model`() {
        val original = buildJsonObject {
            put("string", "中文 \"引号\"")
            put("long", 12345678901L)
            put("int", 7)
            put("double", 3.25)
            put("true", true)
            put("false", false)
            put("null", JsonNull)
            putJsonArray("array") { add(1); add("two"); add(buildJsonObject { put("k", "v") }) }
            putJsonObject("object") { putJsonArray("empty") {} }
        }

        val roundTripped = AnthropicMapping.toKotlinx(AnthropicMapping.toSdk(original))

        assertEquals(original, roundTripped)
        assertEquals(JsonArray(emptyList()), roundTripped.jsonObject.getValue("object").jsonObject.getValue("empty"))
        assertSame(JsonNull, roundTripped.jsonObject.getValue("null"))
    }

    private companion object {
        const val API_KEY = "sk-ant-test-0123456789"

        val LIST_CANDIDATES = ToolSpec(
            name = "list_candidates",
            description = "按条件筛选候选店铺",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") { put("type", "integer"); put("description", "最多返回几家") }
                    putJsonObject("include_keywords") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                }
                putJsonArray("required") { add("limit") }
            },
        )

        val TEXT_STREAM = stream("""[{"type":"text","text":"这是推荐结果。"}]""", "end_turn")

        val TOOL_USE_STREAM = stream(
            """[{"type":"thinking","thinking":"","signature":"SIG123"},{"type":"tool_use","id":"toolu_1","name":"list_candidates","input":{"limit":5}}]""",
            "tool_use",
            inputTokens = 10,
            outputTokens = 5,
        )

        val TWO_TOOLS_STREAM = MESSAGE_START +
            blockStart(0, """{"type":"thinking","thinking":"","signature":""}""") +
            blockDelta(0, """{"type":"signature_delta","signature":"SIG123"}""") + blockStop(0) +
            blockStart(1, """{"type":"tool_use","id":"toolu_1","name":"list_candidates","input":{}}""") +
            inputDelta(1, "") + inputDelta(1, """{"li""") + inputDelta(1, """mit": 5}""") + blockStop(1) +
            blockStart(2, """{"type":"tool_use","id":"toolu_2","name":"get_shop_details","input":{}}""") +
            inputDelta(2, """{"shop_id":""") + PING + inputDelta(2, """"amap:1"}""") + blockStop(2) +
            messageDelta("tool_use", outputTokens = 5) + MESSAGE_STOP

        fun errorBody(message: String) = """{"type":"error","error":{"type":"api_error","message":"$message"}}"""
    }
}
