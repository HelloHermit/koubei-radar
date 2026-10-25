package com.koubeiradar.agent.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.Timeout
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.SseException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.koubeiradar.agent.util.awaitBlocking
import com.koubeiradar.agent.util.objectOrNull
import com.koubeiradar.agent.util.stringOrNull
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * Claude via the official Anthropic Java SDK (`com.anthropic:anthropic-java`, non-beta `client.messages()`).
 * Spec: `docs/DESIGN.md` §5.5. Key rules from the claude-api skill: default model `claude-opus-5-5`,
 * no `temperature`/`top_p`/`top_k`, no `thinking` param (adaptive is the default), never force `tool_choice`,
 * replay the previous assistant content (including signed thinking blocks) unchanged, return all tool results
 * of one assistant turn in a single user message, `is_error` for failed tools.
 *
 * The call streams (`createStreaming`, the final [Message] is rebuilt by the SDK's [MessageAccumulator]) for one reason:
 * cancelling the coroutine closes the response stream, which drops the connection so the server stops generating (and billing).
 * What can and cannot be aborted is described on [StreamingCall].
 *
 * The output budget of a call is `min(request.maxTokens, maxTokens)`: [maxTokens] is the configured cap (thinking counts
 * towards it, so keep it >= 16000), a request may only ask for less.
 *
 * @param baseUrl optional gateway/proxy root, without the `/v1` the SDK appends itself (a trailing `/v1` is tolerated).
 * @param effort `low | medium | high | xhigh | max`, sent as `output_config.effort`; `null`/blank leaves the model default.
 * @param maxRetries SDK-level retries for 429/5xx/connection errors of the request itself; a stream that fails after it
 *   started is never retried (the model would regenerate, and bill, everything).
 * @param timeoutSeconds upper bound for one complete call.
 */
class AnthropicLlmClient(
    private val apiKey: String,
    private val model: String = LlmConfig.DEFAULT_CLAUDE_MODEL,
    private val baseUrl: String? = null,
    private val maxTokens: Int = 16000,
    private val effort: String? = null,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : LlmClient {

    private val outputConfig: OutputConfig?

    init {
        require(apiKey.isNotBlank()) { "请填写 Claude 的 API Key。" }
        requireHeaderSafeKey(apiKey)
        require(model.isNotBlank()) { "请填写 Claude 模型名称。" }
        require(maxTokens > 0) { "maxTokens 必须大于 0。" }
        outputConfig = parseEffort(effort)?.let { OutputConfig.builder().effort(it).build() }
    }

    private val sdk: AnthropicClient by lazy {
        val readTimeout = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            // SDK 3.x would otherwise send the key as `Authorization: Bearer`; it leaves a request that already carries
            // `X-Api-Key` alone. The classic header is understood by the API and by every Anthropic-compatible gateway.
            .putHeader("X-Api-Key", apiKey)
            .apply { normalizeBaseUrl(baseUrl)?.let { baseUrl(it) } }
            .maxRetries(maxRetries.coerceAtLeast(0))
            .timeout(
                Timeout.builder()
                    .connect(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .write(Duration.ofSeconds(WRITE_TIMEOUT_SECONDS))
                    .read(readTimeout)
                    .request(readTimeout)
                    .build(),
            )
            .build()
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        try {
            val params = buildParams(request)
            // `timeoutSeconds` bounds the WHOLE call: the SDK applies it per attempt, so with retries it would otherwise be multiplied.
            val call = StreamingCall()
            val message: Message = try {
                withTimeout(timeoutSeconds.coerceAtLeast(1) * 1000) {
                    awaitBlocking(onCancel = call::abandon) { call.read(sdk, params) }
                }
            } catch (e: TimeoutCancellationException) {
                throw LlmErrors.network(PROVIDER_NAME, e)
            }
            return AnthropicMapping.toLlmResponse(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            // The SDK wraps the interrupt of a cancelled call in its own exception types; cancellation wins over mapping.
            currentCoroutineContext().ensureActive()
            throw mapException(e)
        }
    }

    private fun buildParams(request: LlmRequest): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(minOf(request.maxTokens, maxTokens).coerceAtLeast(1).toLong())
            .messages(AnthropicMapping.toMessageParams(request.messages))
        if (request.system.isNotBlank()) builder.system(request.system)
        request.tools.forEach { builder.addTool(AnthropicMapping.toTool(it)) }
        outputConfig?.let { builder.outputConfig(it) }
        return builder.build()
    }

    private fun mapException(e: Exception): LlmException = when (e) {
        // An `error` event of a stream that already started: the HTTP status is 200, so classify by the error type.
        is SseException -> LlmErrors.fromStreamError(PROVIDER_NAME, errorFieldOf(e, "type"), errorFieldOf(e, "message"), apiKey, e)

        is AnthropicServiceException ->
            LlmErrors.fromStatus(PROVIDER_NAME, e.statusCode(), errorFieldOf(e, "message"), apiKey, e)

        is AnthropicIoException, is IOException -> LlmErrors.network(PROVIDER_NAME, e)
        is AnthropicInvalidDataException -> LlmErrors.unreadableResponse(PROVIDER_NAME, e)
        else -> LlmErrors.unexpected(PROVIDER_NAME, e)
    }

    /** `{"type":"error","error":{"type":"...","message":"..."}}` -> the `type` or `message` inside `error`. */
    private fun errorFieldOf(e: AnthropicServiceException, field: String): String? =
        runCatching { AnthropicMapping.toKotlinx(e.body()) }.getOrNull()
            .objectOrNull()?.get("error").objectOrNull()?.get(field).stringOrNull()

    /**
     * One streaming request. [read] runs on a worker thread; [abandon] (cancellation) may be called from any thread.
     *
     * Abortable: once the response headers have arrived, closing the [StreamResponse] closes the connection (OkHttp may first
     * try to drain the current chunk, for 100 ms at most), so the server sees the hang-up and stops generating.
     *
     * Not abortable: everything before the headers (connect, TLS, writing the request, waiting for the first response
     * bytes, SDK retry back-off). [awaitBlocking] interrupts the worker, but a blocking socket read ignores that and the SDK
     * hands out no handle to cancel the request. The caller still resumes at once; the stray request ends at the connect/read
     * timeout or, if the server answers first, at the moment its headers arrive, because [attach] then closes the stream
     * immediately instead of reading it. The server may already have begun generating by then.
     */
    private class StreamingCall {
        private var response: StreamResponse<RawMessageStreamEvent>? = null
        private var abandoned = false

        fun read(sdk: AnthropicClient, params: MessageCreateParams): Message {
            val accumulator = MessageAccumulator.create()
            var received = 0
            var finished = false
            // No `eager_input_streaming`: our tool inputs are small, and it would switch off API-side validation of the input JSON.
            sdk.messages().createStreaming(params).use { opened ->
                if (!attach(opened)) throw CancellationException("cancelled before the response started")
                val events = opened.stream().iterator()
                while (events.hasNext()) {
                    val event = events.next()
                    received++
                    accumulator.accumulate(event)
                    if (event.isMessageStop()) finished = true
                }
            }
            // A dropped connection can also look like an orderly end of the stream; without `message_stop` the answer is partial.
            // No event at all means the 200 response was not an event stream (wrong Base URL, gateway ignoring `stream`).
            if (!finished) {
                throw if (received == 0) AnthropicInvalidDataException("the response is not an event stream")
                else IOException("the response stream ended before message_stop")
            }
            return accumulator.message()
        }

        /** Marks the call cancelled and closes the stream, if open, off the cancelling thread (closing can block briefly). */
        fun abandon() {
            val open = synchronized(this) {
                abandoned = true
                response.also { response = null }
            } ?: return
            runCatching { Dispatchers.IO.asExecutor().execute { runCatching { open.close() } } }
                .onFailure { runCatching { open.close() } }
        }

        @Synchronized
        private fun attach(opened: StreamResponse<RawMessageStreamEvent>): Boolean {
            if (abandoned) {
                runCatching { opened.close() }
                return false
            }
            response = opened
            return true
        }
    }

    private companion object {
        const val PROVIDER_NAME = "Claude"
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_TIMEOUT_SECONDS = 300L
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 60L

        fun parseEffort(value: String?): OutputConfig.Effort? {
            val name = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when (name) {
                "low" -> OutputConfig.Effort.LOW
                "medium" -> OutputConfig.Effort.MEDIUM
                "high" -> OutputConfig.Effort.HIGH
                "xhigh" -> OutputConfig.Effort.XHIGH
                "max" -> OutputConfig.Effort.MAX
                else -> throw IllegalArgumentException("不支持的 effort「$value」，可选值：low、medium、high、xhigh、max。")
            }
        }

        fun normalizeBaseUrl(value: String?): String? =
            value?.trim()?.trimEnd('/')?.removeSuffix("/v1")?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    }
}
