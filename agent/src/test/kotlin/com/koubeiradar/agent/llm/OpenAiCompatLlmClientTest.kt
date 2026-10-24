package com.koubeiradar.agent.llm

import com.koubeiradar.agent.support.arr
import com.koubeiradar.agent.support.at
import com.koubeiradar.agent.support.bodyJson
import com.koubeiradar.agent.support.jsonResponse
import com.koubeiradar.agent.support.nextRequest
import com.koubeiradar.agent.support.obj
import com.koubeiradar.agent.support.str
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before

class OpenAiCompatLlmClientTest {
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
        baseUrl: String = server.url("/v1/").toString(),
        maxTokens: Int = 4096,
        apiKey: String = API_KEY,
    ) = OpenAiCompatLlmClient(apiKey = apiKey, model = "deepseek-chat", baseUrl = baseUrl, maxTokens = maxTokens, timeoutSeconds = 30)

    private fun simpleRequest(maxTokens: Int = 4096) =
        LlmRequest(system = "你是外卖顾问", messages = listOf(LlmMessage.User("附近有什么好吃的？")), maxTokens = maxTokens)

    // ---- request shape --------------------------------------------------------------------------------------------

    @Test
    fun `request goes to chat completions with bearer auth model tools and max_tokens`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TEXT_RESPONSE))

        client().complete(
            LlmRequest(
                system = "你是外卖顾问",
                messages = listOf(LlmMessage.User("附近有什么好吃的？")),
                tools = listOf(LIST_CANDIDATES),
                maxTokens = 4096,
            ),
        )

        val recorded = server.nextRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer $API_KEY", recorded.headers["Authorization"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))
        val body = recorded.bodyJson()
        assertEquals("deepseek-chat", body.str("model"))
        assertEquals(4096, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        val messages = body.arr("messages")
        assertEquals("system", messages.at(0).str("role"))
        assertEquals("你是外卖顾问", messages.at(0).str("content"))
        assertEquals("user", messages.at(1).str("role"))
        assertEquals("附近有什么好吃的？", messages.at(1).str("content"))
        val tool = body.arr("tools").at(0)
        assertEquals("function", tool.str("type"))
        assertEquals("list_candidates", tool.obj("function").str("name"))
        assertEquals("按条件筛选候选店铺", tool.obj("function").str("description"))
        assertEquals("object", tool.obj("function").obj("parameters").str("type"))
        assertEquals("integer", tool.obj("function").obj("parameters").obj("properties").obj("limit").str("type"))
        for (unexpected in listOf("temperature", "tool_choice", "stream", "top_p")) assertFalse(unexpected in body, unexpected)
    }

    @Test
    fun `base url handling strips trailing slashes and a pasted endpoint suffix`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(jsonResponse(TEXT_RESPONSE)) }

        client(baseUrl = server.url("/").toString()).complete(simpleRequest())
        client(baseUrl = server.url("/api/v3///").toString()).complete(simpleRequest())
        client(baseUrl = server.url("/v1/chat/completions").toString()).complete(simpleRequest())

        assertEquals("/chat/completions", server.nextRequest().url.encodedPath)
        assertEquals("/api/v3/chat/completions", server.nextRequest().url.encodedPath)
        assertEquals("/v1/chat/completions", server.nextRequest().url.encodedPath)
    }

    @Test
    fun `an invalid base url is rejected at construction`() {
        for (bad in listOf("", "api.deepseek.com/v1", "ftp://example.com/v1", "http://")) {
            assertFailsWith<IllegalArgumentException>("`$bad`") { client(baseUrl = bad) }
        }
    }

    @Test
    fun `tools are omitted when there are none`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TEXT_RESPONSE))

        client().complete(simpleRequest())

        assertFalse("tools" in server.nextRequest().bodyJson())
    }

    @Test
    fun `the request budget can only lower the configured cap`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TEXT_RESPONSE))
        server.enqueue(jsonResponse(TEXT_RESPONSE))

        client(maxTokens = 4000).complete(simpleRequest(maxTokens = 16000))
        client(maxTokens = 4000).complete(simpleRequest(maxTokens = 500))

        assertEquals("4000", server.nextRequest().bodyJson()["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("500", server.nextRequest().bodyJson()["max_tokens"]?.jsonPrimitive?.content)
    }

    // ---- responses ------------------------------------------------------------------------------------------------

    @Test
    fun `maps a text-only answer with usage`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TEXT_RESPONSE))

        val response = client().complete(simpleRequest())

        assertEquals("这是推荐结果。", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals(TokenUsage(12, 7), response.usage)
        assertNull(response.providerState)
    }

    @Test
    fun `maps tool calls whose arguments are a json string`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TOOL_CALLS_RESPONSE))

        val response = client().complete(simpleRequest())

        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals("", response.text)
        assertEquals(2, response.toolCalls.size)
        assertEquals("call_a", response.toolCalls[0].id)
        assertEquals("list_candidates", response.toolCalls[0].name)
        assertEquals(JsonPrimitive(5), response.toolCalls[0].arguments["limit"])
        assertEquals("call_b", response.toolCalls[1].id)
        assertEquals(JsonPrimitive("amap:1"), response.toolCalls[1].arguments["shop_id"])
    }

    @Test
    fun `tool round trip sends the assistant tool_calls with string arguments and one tool message per result`() =
        runBlocking<Unit> {
            server.enqueue(jsonResponse(TOOL_CALLS_RESPONSE))
            server.enqueue(jsonResponse(TEXT_RESPONSE))
            val client = client()
            val first = client.complete(simpleRequest())
            server.nextRequest()

            client.complete(
                LlmRequest(
                    system = "系统提示",
                    messages = listOf(
                        LlmMessage.User("帮我选一家"),
                        LlmMessage.Assistant(first.text, first.toolCalls, first.providerState),
                        LlmMessage.ToolResults(
                            listOf(
                                ToolResult("call_a", """{"total_in_pool":3}"""),
                                ToolResult("call_b", "错误：找不到店铺", isError = true),
                            ),
                        ),
                    ),
                    tools = listOf(LIST_CANDIDATES),
                ),
            )

            val messages = server.nextRequest().bodyJson().arr("messages")
            assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it.str("role") })
            val assistant = messages.at(2)
            assertEquals("", assistant.str("content"))
            val calls = assistant.arr("tool_calls")
            assertEquals(2, calls.size)
            assertEquals("call_a", calls.at(0).str("id"))
            assertEquals("function", calls.at(0).str("type"))
            assertEquals("list_candidates", calls.at(0).obj("function").str("name"))
            // arguments is a JSON *string*, not an object
            assertEquals("""{"limit":5}""", calls.at(0).obj("function").str("arguments"))
            assertEquals("""{"shop_id":"amap:1"}""", calls.at(1).obj("function").str("arguments"))
            assertEquals("call_a", messages.at(3).str("tool_call_id"))
            assertEquals("""{"total_in_pool":3}""", messages.at(3).str("content"))
            assertEquals("call_b", messages.at(4).str("tool_call_id"))
            assertEquals("错误：找不到店铺", messages.at(4).str("content"))
        }

    @Test
    fun `finish_reason length maps to MAX_TOKENS`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(chat(message = """{"role":"assistant","content":"被截断的回答"}""", finish = "length")))

        val response = client().complete(simpleRequest())

        assertEquals(StopReason.MAX_TOKENS, response.stopReason)
        assertEquals("被截断的回答", response.text)
    }

    @Test
    fun `finish_reason stop with tool calls is still a tool use`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                chat(
                    message = """{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"list_candidates","arguments":"{}"}}]}""",
                    finish = "stop",
                ),
            ),
        )

        assertEquals(StopReason.TOOL_USE, client().complete(simpleRequest()).stopReason)
    }

    @Test
    fun `content_filter is reported as a refusal`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(chat(message = """{"role":"assistant","content":""}""", finish = "content_filter")))

        val failure = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.REFUSAL, failure.kind)
    }

    @Test
    fun `malformed or empty arguments become an empty object and null content is tolerated`() = runBlocking<Unit> {
        val calls = listOf("", "{not json", "[1,2]", "\"just a string\"", "null")
            .mapIndexed { i, arguments ->
                val escaped = arguments.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"id":"c$i","type":"function","function":{"name":"list_candidates","arguments":"$escaped"}}"""
            }
        server.enqueue(
            jsonResponse(
                chat(
                    message = """{"role":"assistant","content":null,"tool_calls":[${calls.joinToString(",")}]}""",
                    finish = "tool_calls",
                ),
            ),
        )

        val response = client().complete(simpleRequest())

        assertEquals("", response.text)
        assertEquals(5, response.toolCalls.size)
        for (call in response.toolCalls) assertEquals(JsonObject(emptyMap()), call.arguments, call.id)
    }

    @Test
    fun `arguments that already are an object are accepted`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                chat(
                    message = """{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"list_candidates","arguments":{"limit":3}}}]}""",
                    finish = "tool_calls",
                ),
            ),
        )

        val call = client().complete(simpleRequest()).toolCalls.single()

        assertEquals(JsonPrimitive(3), call.arguments["limit"])
    }

    @Test
    fun `tool calls without id get generated unique ids and nameless ones are dropped`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                chat(
                    message = """{"role":"assistant","tool_calls":[
                        {"type":"function","function":{"name":"list_candidates","arguments":"{}"}},
                        {"id":"","type":"function","function":{"name":"get_shop_details","arguments":"{}"}},
                        {"id":"x","type":"function","function":{"name":"","arguments":"{}"}},
                        {"id":"y","type":"function"}]}""",
                    finish = "tool_calls",
                ),
            ),
        )

        val calls = client().complete(simpleRequest()).toolCalls

        assertEquals(listOf("list_candidates", "get_shop_details"), calls.map { it.name })
        assertEquals(2, calls.map { it.id }.toSet().size)
        assertTrue(calls.all { it.id.isNotBlank() })
    }

    @Test
    fun `unknown fields and array content are ignored or joined`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                """{"id":"x","object":"chat.completion","created":1,"model":"m","system_fingerprint":"fp",
                    "choices":[{"index":0,"logprobs":null,"finish_reason":"stop","message":{"role":"assistant",
                    "content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],
                    "refusal":null,"annotations":[]}}],
                    "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7,"prompt_tokens_details":{"cached_tokens":0}}}""",
            ),
        )

        val response = client().complete(simpleRequest())

        assertEquals("第一段\n第二段", response.text)
        assertEquals(TokenUsage(3, 4), response.usage)
    }

    @Test
    fun `reasoning_content of a tool turn is returned to the provider and not shown as text`() = runBlocking<Unit> {
        server.enqueue(
            jsonResponse(
                chat(
                    message = """{"role":"assistant","content":"","reasoning_content":"先看看候选店铺","tool_calls":[{"id":"c1","type":"function","function":{"name":"list_candidates","arguments":"{}"}}]}""",
                    finish = "tool_calls",
                ),
            ),
        )
        server.enqueue(jsonResponse(TEXT_RESPONSE))
        val client = client()
        val first = client.complete(simpleRequest())
        server.nextRequest()

        client.complete(
            LlmRequest(
                "s",
                listOf(
                    LlmMessage.User("q"),
                    LlmMessage.Assistant(first.text, first.toolCalls, first.providerState),
                    LlmMessage.ToolResults(listOf(ToolResult("c1", "{}"))),
                ),
            ),
        )

        assertEquals("", first.text)
        val assistant = server.nextRequest().bodyJson().arr("messages").at(2)
        assertEquals("先看看候选店铺", assistant.str("reasoning_content"))
    }

    @Test
    fun `images become image_url data uris while text-only user messages stay plain strings`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TEXT_RESPONSE))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        client().complete(
            LlmRequest(
                "s",
                listOf(
                    LlmMessage.User("纯文字"),
                    LlmMessage.Assistant("好的"),
                    LlmMessage.User(listOf(ContentPart.Text("第1张图片"), ContentPart.Image("image/png", png))),
                ),
            ),
        )

        val messages = server.nextRequest().bodyJson().arr("messages")
        assertEquals("纯文字", messages.at(1).str("content"))
        val content = messages.at(3).arr("content")
        assertEquals("text", content.at(0).str("type"))
        assertEquals("第1张图片", content.at(0).str("text"))
        assertEquals("image_url", content.at(1).str("type"))
        val url = content.at(1).obj("image_url").str("url").orEmpty()
        assertTrue(url.startsWith("data:image/png;base64,"), url)
        assertContentEquals(png, Base64.getDecoder().decode(url.removePrefix("data:image/png;base64,")))
    }

    @Test
    fun `empty assistant turns are not sent`() = runBlocking<Unit> {
        server.enqueue(jsonResponse(TEXT_RESPONSE))

        client().complete(
            LlmRequest("s", listOf(LlmMessage.User("q"), LlmMessage.Assistant(""), LlmMessage.User("请继续"))),
        )

        val roles = server.nextRequest().bodyJson().arr("messages").map { it.str("role") }
        assertEquals(listOf("system", "user", "user"), roles)
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    @Test
    fun `http statuses map to the documented kinds and keep the status and provider message`() = runBlocking<Unit> {
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
            server.enqueue(jsonResponse("""{"error":{"message":"服务商说明 $status","type":"x","code":"y"}}""", status))

            val failure = assertFailsWith<LlmException>("HTTP $status") { client().complete(simpleRequest()) }

            assertEquals(kind, failure.kind, "HTTP $status")
            assertEquals(status, failure.httpStatus, "HTTP $status")
            assertTrue("服务商说明 $status" in failure.message.orEmpty(), "HTTP $status: ${failure.message}")
            server.nextRequest()
        }
    }

    @Test
    fun `other error body shapes are understood and the key is never echoed`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"Incorrect API key provided: $API_KEY"}""", 401))
        server.enqueue(jsonResponse("""{"detail":"模型不存在"}""", 404))
        server.enqueue(jsonResponse("<html>502 Bad Gateway</html>", 502))

        val auth = assertFailsWith<LlmException> { client().complete(simpleRequest()) }
        val missing = assertFailsWith<LlmException> { client().complete(simpleRequest()) }
        val gateway = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.AUTH, auth.kind)
        assertFalse(API_KEY in auth.message.orEmpty())
        assertTrue("Incorrect API key provided" in auth.message.orEmpty())
        assertTrue("模型不存在" in missing.message.orEmpty())
        assertEquals(LlmException.Kind.SERVER, gateway.kind)
    }

    @Test
    fun `a 200 response that is not a completion is reported as unreadable or as the provider error`() = runBlocking<Unit> {
        server.enqueue(jsonResponse("<html>not the api</html>"))
        server.enqueue(jsonResponse("""{"error":{"message":"余额不足"}}"""))
        server.enqueue(jsonResponse("""{"choices":[]}"""))

        val html = assertFailsWith<LlmException> { client().complete(simpleRequest()) }
        val error = assertFailsWith<LlmException> { client().complete(simpleRequest()) }
        val empty = assertFailsWith<LlmException> { client().complete(simpleRequest()) }

        assertEquals(LlmException.Kind.OTHER, html.kind)
        assertEquals(LlmException.Kind.OTHER, error.kind)
        assertTrue("余额不足" in error.message.orEmpty())
        assertEquals(LlmException.Kind.OTHER, empty.kind)
    }

    @Test
    fun `an unreachable host is a NETWORK failure`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }

        val failure = assertFailsWith<LlmException> { client(baseUrl = "http://127.0.0.1:$closedPort/v1").complete(simpleRequest()) }

        assertEquals(LlmException.Kind.NETWORK, failure.kind)
        assertNull(failure.httpStatus)
    }

    // ---- cancellation ---------------------------------------------------------------------------------------------

    @Test
    fun `cancelling the coroutine aborts an in-flight call and propagates CancellationException`() = runBlocking<Unit> {
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
        server.nextRequest() // the request has reached the server, the call is now waiting for the response
        job.cancel()
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertIs<CancellationException>(thrown.get())
    }

    private companion object {
        const val API_KEY = "sk-test-0123456789abcdef"

        val LIST_CANDIDATES = ToolSpec(
            name = "list_candidates",
            description = "按条件筛选候选店铺",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") {}
            },
        )

        fun chat(message: String, finish: String, usage: String = """{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}"""): String =
            """{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"deepseek-chat",
                "choices":[{"index":0,"message":$message,"finish_reason":"$finish"}],"usage":$usage}"""

        val TEXT_RESPONSE = chat("""{"role":"assistant","content":"这是推荐结果。"}""", "stop")

        val TOOL_CALLS_RESPONSE = chat(
            message = """{"role":"assistant","content":null,"tool_calls":[
                {"id":"call_a","type":"function","function":{"name":"list_candidates","arguments":"{\"limit\":5}"}},
                {"id":"call_b","type":"function","function":{"name":"get_shop_details","arguments":"{\"shop_id\":\"amap:1\"}"}}]}""",
            finish = "tool_calls",
        )
    }
}
