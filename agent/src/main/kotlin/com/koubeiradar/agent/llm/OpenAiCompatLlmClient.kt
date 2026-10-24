package com.koubeiradar.agent.llm

import com.koubeiradar.agent.util.parseJsonOrNull
import com.koubeiradar.agent.util.toCompactString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Any OpenAI-style `POST {baseUrl}/chat/completions` endpoint with function calling (DeepSeek, Qwen, GLM, Kimi,
 * Doubao, Ollama ...). Plain OkHttp + kotlinx.serialization, no SDK. Spec: `docs/DESIGN.md` §5.6.
 *
 * The output budget of a call is `min(request.maxTokens, maxTokens)`. Providers cap `max_tokens` differently
 * (commonly 4095/8192), so [maxTokens] should be the cap of the configured model.
 *
 * @param timeoutSeconds upper bound for one complete call; the (shared) [httpClient] is only adapted if its own timeouts are shorter.
 */
class OpenAiCompatLlmClient(
    private val apiKey: String,
    private val model: String,
    /** Without trailing `/chat/completions`, e.g. `https://api.deepseek.com/v1`. */
    private val baseUrl: String,
    private val maxTokens: Int = 4096,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : LlmClient {

    private val endpoint: String
    private val http: OkHttpClient
    private val callIds = AtomicInteger()

    init {
        require(apiKey.isNotBlank()) { "请填写 API Key。" }
        requireHeaderSafeKey(apiKey)
        require(model.isNotBlank()) { "请填写模型名称。" }
        require(maxTokens > 0) { "maxTokens 必须大于 0。" }
        endpoint = chatCompletionsUrl(baseUrl)
        val timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds.coerceAtLeast(1))
        http = httpClient.newBuilder()
            .readTimeout(maxOf(httpClient.readTimeoutMillis.toLong(), timeoutMillis), TimeUnit.MILLISECONDS)
            .callTimeout(maxOf(httpClient.callTimeoutMillis.toLong(), timeoutMillis), TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        try {
            val payload = OpenAiWire.buildRequest(model, minOf(request.maxTokens, maxTokens).coerceAtLeast(1), request)
            val httpRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(payload.toCompactString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val raw = execute(httpRequest)
            if (raw.code !in 200..299) {
                val message = OpenAiWire.errorMessage(parseJsonOrNull(raw.body))
                throw LlmErrors.fromStatus(PROVIDER_NAME, raw.code, message, apiKey)
            }
            val root = parseJsonOrNull(raw.body) ?: throw LlmErrors.unreadableResponse(PROVIDER_NAME)
            return OpenAiWire.parseResponse(PROVIDER_NAME, root) { "call_${callIds.incrementAndGet()}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmErrors.network(PROVIDER_NAME, e)
        } catch (e: Exception) {
            throw LlmErrors.unexpected(PROVIDER_NAME, e)
        }
    }

    private class RawResponse(val code: Int, val body: String)

    /**
     * Runs the call asynchronously and reads the whole body inside the OkHttp callback, so cancelling the coroutine
     * (`Call.cancel()`) aborts the connection immediately, also while waiting for the first byte.
     */
    private suspend fun execute(request: Request): RawResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use { RawResponse(it.code, readBounded(it)) })
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun readBounded(response: Response): String {
        val source = response.body.source()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("response body too large")
        return source.readUtf8()
    }

    private companion object {
        const val PROVIDER_NAME = "模型服务"
        const val DEFAULT_TIMEOUT_SECONDS = 180L
        const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun chatCompletionsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
            require(base.toHttpUrlOrNull() != null) { "Base URL 必须是以 http:// 或 https:// 开头的有效地址。" }
            return "$base/chat/completions"
        }
    }
}
