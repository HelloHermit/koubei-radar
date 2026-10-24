package com.koubeiradar.data.amap

import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.ShopSourceException.Kind
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What POI search and geocoding share: an authenticated GET, the `status` / `infocode` envelope and its mapping onto
 * [ShopSourceException]. The key only ever travels in the request URL; no message or cause leaving this class contains it.
 */
internal class AmapApi(
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
) {
    /** Returns the JSON envelope of a successful (`status == "1"`) reply to `GET {baseUrl}/{path}?key=..&{params}`. */
    suspend fun get(path: String, params: List<Pair<String, String>>): JsonObject {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw ShopSourceException("高德接口地址无效", Kind.OTHER)
        url.addPathSegments(path).addQueryParameter("key", apiKey)
        for ((name, value) in params) url.addQueryParameter(name, value)

        val reply = try {
            httpClient.fetch(Request.Builder().url(url.build()).build())
        } catch (e: IOException) {
            val cause = scrub(e)
            throw ShopSourceException("网络请求失败：${cause.message ?: e.javaClass.simpleName}", Kind.NETWORK, cause)
        }
        if (reply.code !in 200..299) {
            throw ShopSourceException("高德接口返回 HTTP ${reply.code}", Kind.NETWORK)
        }
        return envelope(reply.body)
    }

    private fun envelope(body: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw ShopSourceException("高德响应不是有效的 JSON", Kind.PARSE, scrub(e))
        }
        val envelope = root as? JsonObject ?: throw ShopSourceException("高德响应格式异常", Kind.PARSE)
        val status = envelope.text("status") ?: throw ShopSourceException("高德响应缺少 status 字段", Kind.PARSE)
        if (status != "1") throw apiError(infocode = envelope.text("infocode"), info = envelope.text("info"))
        return envelope
    }

    private fun apiError(infocode: String?, info: String?): ShopSourceException {
        val kind = when (infocode) {
            in AUTH_INFOCODES -> Kind.AUTH
            in QUOTA_INFOCODES -> Kind.QUOTA
            else -> Kind.OTHER
        }
        val reason = when (kind) {
            Kind.AUTH -> "高德 Key 无效或无权限"
            Kind.QUOTA -> "高德调用额度或并发超限"
            else -> "高德接口返回错误"
        }
        return ShopSourceException("$reason：${info?.let(::redact) ?: "未知错误"}（infocode=${infocode?.let(::redact) ?: "无"}）", kind)
    }

    private fun redact(text: String): String = if (apiKey.isEmpty()) text else text.replace(apiKey, REDACTED)

    /** Library exceptions can quote the request URL; hand on a copy without the key if this one does. */
    private fun scrub(error: Throwable): Throwable {
        val message = error.message ?: return error
        if (apiKey.isEmpty() || apiKey !in message) return error
        return IOException(redact(message)).also { it.stackTrace = error.stackTrace }
    }

    private companion object {
        const val REDACTED = "***"

        val AUTH_INFOCODES = setOf("10001", "10005", "10006", "10007", "10008", "10009", "10012", "10013", "10026")
        val QUOTA_INFOCODES = setOf("10003", "10004", "10010", "10014", "10019", "10020", "10021", "10022", "10044")
    }
}

private class RawReply(val code: Int, val body: String)

/** Runs [request] on OkHttp's own threads; cancelling the calling coroutine cancels the HTTP call. */
private suspend fun OkHttpClient.fetch(request: Request): RawReply = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val reply = try {
                response.use { RawReply(it.code, it.body.string()) }
            } catch (e: IOException) {
                continuation.resumeWithException(e)
                return
            }
            continuation.resume(reply)
        }
    })
}
