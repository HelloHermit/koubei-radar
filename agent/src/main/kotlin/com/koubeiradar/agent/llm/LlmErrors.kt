package com.koubeiradar.agent.llm

import com.koubeiradar.agent.llm.LlmException.Kind
import com.koubeiradar.agent.util.collapseWhitespace
import com.koubeiradar.agent.util.stripInvisible
import com.koubeiradar.agent.util.truncate

/** Error mapping shared by both clients. User-facing messages are Chinese and never contain the API key. */
internal object LlmErrors {
    private const val MAX_PROVIDER_MESSAGE = 300

    fun kindForStatus(status: Int): Kind = when (status) {
        401, 403 -> Kind.AUTH
        429 -> Kind.RATE_LIMIT
        400, 404, 413, 422 -> Kind.BAD_REQUEST
        in 500..599 -> Kind.SERVER
        else -> Kind.OTHER
    }

    /** [providerMessage] is the `error.message` text of the response, if the body had one. */
    fun fromStatus(
        providerName: String,
        status: Int,
        providerMessage: String?,
        apiKey: String,
        cause: Throwable? = null,
    ): LlmException {
        val kind = kindForStatus(status)
        return LlmException(withDetail(headline(providerName, kind, "（HTTP $status）"), providerMessage, apiKey), kind, status, cause)
    }

    /** An `error` event inside a response stream that had already started (HTTP 200); [errorType] is the API's `error.type`. */
    fun fromStreamError(
        providerName: String,
        errorType: String?,
        providerMessage: String?,
        apiKey: String,
        cause: Throwable? = null,
    ): LlmException {
        val kind = when (errorType) {
            "overloaded_error", "api_error" -> Kind.SERVER
            "rate_limit_error" -> Kind.RATE_LIMIT
            "authentication_error", "permission_error" -> Kind.AUTH
            "invalid_request_error", "not_found_error", "request_too_large" -> Kind.BAD_REQUEST
            else -> Kind.OTHER
        }
        return LlmException(withDetail(headline(providerName, kind, "（生成中途）"), providerMessage, apiKey), kind, null, cause)
    }

    private fun headline(providerName: String, kind: Kind, where: String): String = when (kind) {
        Kind.AUTH -> "$providerName 认证失败$where：API Key 无效、已过期或无权使用该模型。"
        Kind.RATE_LIMIT -> "$providerName 请求过于频繁或额度已用完$where，请稍后再试。"
        Kind.BAD_REQUEST -> "$providerName 拒绝了这次请求$where，请检查模型名称、Base URL 和请求内容。"
        Kind.SERVER -> "$providerName 服务暂时不可用$where，请稍后再试。"
        else -> "$providerName 调用失败$where。"
    }

    private fun withDetail(headline: String, providerMessage: String?, apiKey: String): String {
        val detail = providerMessage?.let { cleanProviderMessage(it, apiKey) }?.takeIf { it.isNotEmpty() }
        return if (detail == null) headline else "$headline 服务端返回：$detail"
    }

    fun network(providerName: String, cause: Throwable): LlmException =
        LlmException("无法连接 $providerName：网络不通、请求超时或地址有误，请检查网络与 Base URL 后重试。", Kind.NETWORK, null, cause)

    fun unreadableResponse(providerName: String, cause: Throwable? = null): LlmException =
        LlmException("$providerName 返回的内容无法识别，请检查 Base URL 是否正确（应指向接口根地址）。", Kind.OTHER, null, cause)

    fun unexpected(providerName: String, cause: Throwable): LlmException =
        LlmException("调用 $providerName 时出现未知错误。", Kind.OTHER, null, cause)

    /** Collapses whitespace, masks the key if the provider echoed it back, and bounds the length. */
    fun cleanProviderMessage(message: String, apiKey: String): String {
        var text = message.stripInvisible().collapseWhitespace()
        if (apiKey.isNotBlank()) text = text.replace(apiKey, "***")
        return text.truncate(MAX_PROVIDER_MESSAGE)
    }
}
