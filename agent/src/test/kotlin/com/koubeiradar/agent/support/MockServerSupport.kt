package com.koubeiradar.agent.support

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

/** The next request the server received; fails (instead of hanging) when none arrives. */
fun MockWebServer.nextRequest(): RecordedRequest =
    checkNotNull(takeRequest(10, TimeUnit.SECONDS)) { "the client did not send a request" }

fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(checkNotNull(body).utf8()).jsonObject

fun JsonElement.str(key: String): String? = (this as? JsonObject)?.get(key)?.jsonPrimitive?.content

fun JsonElement.obj(key: String): JsonObject = jsonObject.getValue(key).jsonObject

fun JsonElement.arr(key: String): JsonArray = jsonObject.getValue(key).jsonArray

fun JsonElement.at(index: Int): JsonObject = jsonArray[index].jsonObject
