package com.koubeiradar.data.baidu

import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import com.koubeiradar.data.amap.fetch
import com.koubeiradar.data.amap.text
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * What Baidu place search and geocoding share: an authenticated GET, the `status` / `message` envelope and its mapping onto
 * [ShopSourceException]. The AK only ever travels in the request URL; no message or cause leaving this class contains it.
 * Spec: `docs/DESIGN.md` §3.4.
 */
internal class BaiduApi(
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
) {
    /**
     * Returns the JSON envelope of a successful (`status == 0`) reply to `GET {baseUrl}/{path}?ak=..&output=json&{params}`.
     * A failure for which [tolerate] (status, message) holds is returned as the envelope too, for callers that treat it as "no result".
     */
    suspend fun get(
        path: String,
        params: List<Pair<String, String>>,
        tolerate: (status: Int, message: String?) -> Boolean = { _, _ -> false },
    ): JsonObject {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw ShopSourceException("百度地图接口地址无效", Kind.OTHER)
        url.addPathSegments(path).addQueryParameter("ak", apiKey).addQueryParameter("output", "json")
        for ((name, value) in params) url.addQueryParameter(name, value)

        val reply = try {
            httpClient.fetch(Request.Builder().url(url.build()).build())
        } catch (e: IOException) {
            val cause = scrub(e)
            throw ShopSourceException("网络请求失败：${cause.message ?: e.javaClass.simpleName}", Kind.NETWORK, cause)
        }
        if (reply.code !in 200..299) {
            throw ShopSourceException("百度地图接口返回 HTTP ${reply.code}", Kind.NETWORK)
        }
        return envelope(reply.body, tolerate)
    }

    private fun envelope(body: String, tolerate: (Int, String?) -> Boolean): JsonObject {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw ShopSourceException("百度地图响应不是有效的 JSON", Kind.PARSE, scrub(e))
        }
        val envelope = root as? JsonObject ?: throw ShopSourceException("百度地图响应格式异常", Kind.PARSE)
        val status = envelope.text("status")?.toIntOrNull() ?: throw ShopSourceException("百度地图响应缺少有效的 status 字段", Kind.PARSE)
        if (status == 0) return envelope
        // The geocoding endpoint names the text `msg`, the place endpoint `message`.
        val message = envelope.text("message") ?: envelope.text("msg")
        if (tolerate(status, message)) return envelope
        throw apiError(status, message)
    }

    private fun apiError(status: Int, message: String?): ShopSourceException {
        val kind = kindOf(status)
        val reason = when {
            status == STATUS_IP_NOT_ALLOWED -> "百度地图 AK 设置了 IP 白名单，本机 IP 不在其中"
            status == STATUS_SN_MISMATCH -> "百度地图 AK 开启了 SN 校验，本应用不支持签名"
            kind == Kind.AUTH -> "百度地图 AK 无效或无权限"
            kind == Kind.QUOTA -> "百度地图调用额度或并发超限"
            else -> "百度地图接口返回错误"
        }
        return ShopSourceException("$reason：${message?.let(::redact) ?: "未知错误"}（status=$status）", kind)
    }

    private fun redact(text: String): String = if (apiKey.isEmpty()) text else text.replace(apiKey, REDACTED)

    /** Library exceptions can quote the request URL; hand on a copy without the AK if this one does. */
    private fun scrub(error: Throwable): Throwable {
        val message = error.message ?: return error
        if (apiKey.isEmpty() || apiKey !in message) return error
        return IOException(redact(message)).also { it.stackTrace = error.stackTrace }
    }

    internal companion object {
        private const val REDACTED = "***"
        private const val STATUS_IP_NOT_ALLOWED = 210
        private const val STATUS_SN_MISMATCH = 211

        /**
         * Baidu's service status table (docs `webapi/appendix`): 3 / 5 / 9 / 101 / 102 and the whole 2xx range
         * (AK, app, service or user not usable) are authentication; 4, 302, 401 and the 3xx range are quota or concurrency.
         */
        fun kindOf(status: Int): Kind = when (status) {
            3, 5, 9, 101, 102 -> Kind.AUTH
            4, 401 -> Kind.QUOTA
            in 200..299 -> Kind.AUTH
            in 300..399 -> Kind.QUOTA
            else -> Kind.OTHER
        }
    }
}
