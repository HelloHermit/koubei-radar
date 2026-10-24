package com.koubeiradar.agent.llm

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

enum class LlmProvider(val label: String) {
    /** Claude through the official Anthropic SDK. */
    ANTHROPIC("Claude (Anthropic 官方)"),

    /** Any `/chat/completions` style endpoint: DeepSeek, Qwen (DashScope), GLM, Kimi, Doubao, local Ollama ... */
    OPENAI_COMPATIBLE("OpenAI 兼容接口 (DeepSeek/通义/智谱/Kimi…)"),
}

data class LlmConfig(
    val provider: LlmProvider,
    val apiKey: String,
    /** Model used for text + tool calling. */
    val model: String,
    /** Anthropic: optional gateway/base URL override. OpenAI-compatible: REQUIRED, e.g. `https://api.deepseek.com/v1`. */
    val baseUrl: String? = null,
    /** Separate model for screenshot import when the chat model has no vision (OpenAI-compatible providers). */
    val visionModel: String? = null,
    /** Max output tokens per call. For Claude with adaptive thinking keep this >= 16000. */
    val maxTokens: Int = 16000,
    /** Claude only: "low" | "medium" | "high" | "xhigh" | "max". `null` = omit (model default). */
    val effort: String? = null,
) {
    /** Config to use for image understanding (swaps in [visionModel] if set). */
    /** Never prints the API key (config objects end up in logs and crash reports). */
    override fun toString(): String =
        "LlmConfig(provider=$provider, model=$model, baseUrl=$baseUrl, visionModel=$visionModel, maxTokens=$maxTokens, effort=$effort, apiKey=***)"

    fun forVision(): LlmConfig = if (visionModel.isNullOrBlank()) this else copy(model = visionModel)

    companion object {
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
    }
}

/** UI conveniences for OpenAI-compatible providers. Model ids are suggestions - providers rename models often. */
data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val suggestedModel: String,
    val suggestedVisionModel: String?,
)

object ProviderPresets {
    val openAiCompatible: List<ProviderPreset> = listOf(
        ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", null),
        ProviderPreset("通义千问 (DashScope)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen-vl-max"),
        ProviderPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "glm-4v-plus"),
        ProviderPreset("Moonshot Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-32k", "moonshot-v1-32k-vision-preview"),
    )
}

object LlmClients {
    /**
     * `max_tokens` sent to OpenAI-compatible providers is capped here: [LlmConfig.maxTokens] defaults to the value
     * Claude needs for adaptive thinking (16000), but DeepSeek, Qwen and GLM reject anything above 8192 / 4095.
     */
    private const val OPENAI_COMPATIBLE_MAX_TOKENS_CAP = 4095

    /** Builds the client for [config]. Throws [IllegalArgumentException] for an unusable config (blank key / base URL). */
    fun create(config: LlmConfig, http: OkHttpClient = OkHttpClient()): LlmClient {
        val apiKey = config.apiKey.trim()
        require(apiKey.isNotEmpty()) { "请先填写 API Key。" }
        requireHeaderSafeKey(apiKey)
        val baseUrl = config.baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        require(baseUrl == null || baseUrl.toHttpUrlOrNull() != null) { "Base URL 必须是以 http:// 或 https:// 开头的有效地址。" }
        val model = config.model.trim()
        return when (config.provider) {
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(
                apiKey = apiKey,
                model = model.ifEmpty { LlmConfig.DEFAULT_CLAUDE_MODEL },
                baseUrl = baseUrl,
                maxTokens = config.maxTokens,
                effort = config.effort,
            )

            LlmProvider.OPENAI_COMPATIBLE -> {
                requireNotNull(baseUrl) { "请填写 Base URL，例如 https://api.deepseek.com/v1。" }
                require(model.isNotEmpty()) { "请填写模型名称。" }
                OpenAiCompatLlmClient(
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl,
                    maxTokens = config.maxTokens.coerceAtMost(OPENAI_COMPATIBLE_MAX_TOKENS_CAP),
                    httpClient = http,
                )
            }
        }
    }
}

/** HTTP header values are printable ASCII; anything else (a pasted full-width character, a space) fails deep inside the HTTP stack. */
internal fun requireHeaderSafeKey(apiKey: String) =
    require(apiKey.all { it.code in 0x21..0x7E }) { "API Key 含有不能用于请求头的字符（空格、中文或全角符号），请重新复制。" }
