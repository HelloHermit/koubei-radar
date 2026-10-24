package com.koubeiradar.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Small builders for the JSON Schema of tool parameters. They stick to the common subset (type, description, enum,
 * items, required) that Claude and the OpenAI-style providers all accept.
 */
internal object ToolSchemas {
    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }

    fun number(description: String): JsonObject = scalar("number", description)

    fun integer(description: String): JsonObject = scalar("integer", description)

    fun boolean(description: String): JsonObject = scalar("boolean", description)

    fun stringArray(description: String): JsonObject = array(description, buildJsonObject { put("type", "string") })

    fun array(description: String, items: JsonElement): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    /** `properties` keeps its insertion order (use `linkedMapOf`/`mapOf`), which keeps the serialised tool list stable. */
    fun objectSchema(
        properties: Map<String, JsonElement>,
        required: List<String> = emptyList(),
        description: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        description?.let { put("description", it) }
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    private fun scalar(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }
}
