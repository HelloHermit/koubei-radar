package com.koubeiradar.agent.llm

import com.koubeiradar.agent.support.bodyJson
import com.koubeiradar.agent.support.jsonResponse
import com.koubeiradar.agent.support.nextRequest
import com.koubeiradar.agent.support.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class LlmClientsTest {
    private val key = "sk-secret-key-123456"

    @Test
    fun `anthropic provider builds the Claude client`() {
        val client = LlmClients.create(LlmConfig(LlmProvider.ANTHROPIC, key, "claude-sonnet-5-5", effort = "high"))

        assertIs<AnthropicLlmClient>(client)
    }

    @Test
    fun `openai compatible provider builds the compat client`() {
        val client = LlmClients.create(
            LlmConfig(LlmProvider.OPENAI_COMPATIBLE, key, "deepseek-chat", baseUrl = "https://api.deepseek.com/v1"),
        )

        assertIs<OpenAiCompatLlmClient>(client)
    }

    @Test
    fun `a blank key is rejected with a Chinese message that does not contain the key`() {
        for (blank in listOf("", "   ")) {
            for (provider in LlmProvider.entries) {
                val failure = assertFailsWith<IllegalArgumentException> {
                    LlmClients.create(LlmConfig(provider, blank, "m", baseUrl = "https://example.com/v1"))
                }
                assertTrue("API Key" in failure.message.orEmpty(), failure.message)
            }
        }
    }

    @Test
    fun `openai compatible needs a base url`() {
        for (missing in listOf(null, "", "  ")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                LlmClients.create(LlmConfig(LlmProvider.OPENAI_COMPATIBLE, key, "m", baseUrl = missing))
            }
            assertTrue("Base URL" in failure.message.orEmpty(), failure.message)
            assertFalse(key in failure.message.orEmpty())
        }
    }

    @Test
    fun `a base url that is not http or https is rejected for both providers`() {
        for (bad in listOf("api.deepseek.com/v1", "ftp://example.com", "file:///etc/passwd", "javascript:alert(1)")) {
            for (provider in LlmProvider.entries) {
                val failure = assertFailsWith<IllegalArgumentException>("$provider `$bad`") {
                    LlmClients.create(LlmConfig(provider, key, "m", baseUrl = bad))
                }
                assertTrue("Base URL" in failure.message.orEmpty(), failure.message)
                assertFalse(key in failure.message.orEmpty())
            }
        }
    }

    @Test
    fun `anthropic accepts a missing or blank base url and a blank model falls back to the default`() {
        for (url in listOf(null, "", " ")) {
            assertIs<AnthropicLlmClient>(LlmClients.create(LlmConfig(LlmProvider.ANTHROPIC, key, " ", baseUrl = url)))
        }
    }

    @Test
    fun `openai compatible needs a model name`() {
        assertFailsWith<IllegalArgumentException> {
            LlmClients.create(LlmConfig(LlmProvider.OPENAI_COMPATIBLE, key, " ", baseUrl = "https://example.com/v1"))
        }
    }

    @Test
    fun `max_tokens for openai compatible providers is capped, the shared http client and trimmed key are used`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    jsonResponse("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""),
                )
                val client = LlmClients.create(
                    LlmConfig(
                        LlmProvider.OPENAI_COMPATIBLE,
                        "  $key\n",
                        "deepseek-chat",
                        baseUrl = server.url("/v1").toString(),
                        maxTokens = 16000,
                    ),
                    OkHttpClient(),
                )

                client.complete(LlmRequest("s", listOf(LlmMessage.User("q")), maxTokens = 32000))

                val request = server.nextRequest()
                assertEquals("Bearer $key", request.headers["Authorization"])
                assertEquals("4095", request.bodyJson()["max_tokens"]?.jsonPrimitive?.content)
                assertEquals("deepseek-chat", request.bodyJson().str("model"))
            }
        }

    @Test
    fun `a vision config swaps the model only`() {
        val config = LlmConfig(LlmProvider.OPENAI_COMPATIBLE, key, "qwen-plus", baseUrl = "https://example.com/v1", visionModel = "qwen-vl-max")

        assertEquals("qwen-vl-max", config.forVision().model)
        assertIs<OpenAiCompatLlmClient>(LlmClients.create(config.forVision()))
    }
}
