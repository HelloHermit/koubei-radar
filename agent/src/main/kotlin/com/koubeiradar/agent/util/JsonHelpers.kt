package com.koubeiradar.agent.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Compact output (no pretty printing); CJK text is not escaped, which keeps tool results cheap in tokens. */
internal val compactJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

internal fun JsonElement.toCompactString(): String = compactJson.encodeToString(JsonElement.serializer(), this)

/** Parses [text] leniently: anything that is not a JSON document yields `null`. */
internal fun parseJsonOrNull(text: String): JsonElement? =
    if (text.isBlank()) null else runCatching { compactJson.parseToJsonElement(text) }.getOrNull()

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

/** The text of a JSON string; numbers, booleans and `null` yield `null`. */
internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.isNullOrJsonNull(): Boolean = this == null || this is JsonNull

/**
 * A finite number from a JSON number or from a string that is entirely a plain number ("4.5"). Strings such as
 * "1000+" or "¥32" are not numbers and yield `null`.
 */
internal fun JsonElement?.finiteDoubleOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

/**
 * Best-effort repair of a JSON object that was cut off by the output limit: the longest prefix that ends right after a
 * completed `}` or `]` is closed with the brackets that are still open. The unfinished tail is dropped, nothing is invented.
 */
internal fun salvageTruncatedJsonObject(raw: String): JsonObject? {
    val open = StringBuilder()
    var inString = false
    var escaped = false
    val cuts = ArrayList<Pair<Int, String>>()
    for ((i, ch) in raw.withIndex()) {
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> open.append('}')
            '[' -> open.append(']')
            '}', ']' -> {
                if (open.isEmpty() || open.last() != ch) return null
                open.setLength(open.length - 1)
                if (open.isNotEmpty()) cuts += (i + 1) to open.reversed().toString()
            }
        }
    }
    for ((end, closers) in cuts.asReversed().take(8)) {
        parseJsonOrNull(raw.substring(0, end) + closers).objectOrNull()?.let { return it }
    }
    return null
}
