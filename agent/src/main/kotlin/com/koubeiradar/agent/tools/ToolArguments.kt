package com.koubeiradar.agent.tools

import com.koubeiradar.agent.util.finiteDoubleOrNull
import com.koubeiradar.agent.util.isNullOrJsonNull
import com.koubeiradar.agent.util.toSafeLine
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient reader for model-supplied tool arguments. Models stringify numbers and booleans or wrap a single value in
 * nothing, so these are tolerated; anything that cannot be understood is collected in [errors] (Chinese, addressed to
 * the model) instead of throwing, so the tool can answer with one message that lists everything to fix.
 */
internal class ArgReader(private val args: JsonObject) {
    private val problems = ArrayList<String>()

    val errors: List<String> get() = problems

    fun problem(message: String) {
        problems += message
    }

    /** A finite number, optionally restricted to `[min, max]`; absent or `null` gives `null`. */
    fun number(name: String, min: Double? = null, max: Double? = null): Double? {
        val element = args[name]
        if (element.isNullOrJsonNull()) return null
        val value = element.finiteDoubleOrNull()
        if (value == null) {
            problems += "参数 $name 必须是数字（收到：${element.toString().toSafeLine(40)}）"
            return null
        }
        if (min != null && value < min) {
            problems += "参数 $name 不能小于 ${plain(min)}（收到：${plain(value)}）"
            return null
        }
        if (max != null && value > max) {
            problems += "参数 $name 不能大于 ${plain(max)}（收到：${plain(value)}）"
            return null
        }
        return value
    }

    fun int(name: String, min: Int? = null, max: Int? = null): Int? =
        number(name, min?.toDouble(), max?.toDouble())?.roundToInt()

    /** Like [int] but an out-of-range value is clamped instead of rejected (for paging-style limits). */
    fun clampedInt(name: String, min: Int, max: Int): Int? {
        val value = number(name) ?: return null
        return value.roundToInt().coerceIn(min, max)
    }

    fun boolean(name: String): Boolean? {
        val element = args[name]
        if (element.isNullOrJsonNull()) return null
        val text = (element as? JsonPrimitive)?.content?.trim()?.lowercase()
        return when (text) {
            "true" -> true
            "false" -> false
            else -> {
                problems += "参数 $name 必须是 true 或 false（收到：${element.toString().toSafeLine(40)}）"
                null
            }
        }
    }

    fun string(name: String, maxLength: Int = 500): String? {
        val element = args[name]
        if (element.isNullOrJsonNull()) return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || (!primitive.isString && primitive.content.isEmpty())) {
            problems += "参数 $name 必须是字符串"
            return null
        }
        return primitive.content.trim().take(maxLength)
    }

    /** One of [allowed] (case-insensitive); anything else is reported with the allowed values. */
    fun choice(name: String, allowed: List<String>): String? {
        val text = string(name, 40)?.lowercase() ?: return null
        if (text in allowed) return text
        problems += "参数 $name 只能是 ${allowed.joinToString("、")} 之一（收到：${text.toSafeLine(40)}）"
        return null
    }

    /**
     * A list of short strings. A single string is accepted as a one-element list and a comma separated one is split;
     * blanks and duplicates are dropped, the list and each entry are length-limited.
     */
    fun stringList(name: String, maxItems: Int = 10, maxLength: Int = 20): List<String>? {
        val element = args[name]
        if (element.isNullOrJsonNull()) return null
        val raw = when (element) {
            is JsonArray -> element.map { item -> (item as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty() }
            is JsonPrimitive -> element.content.split(SEPARATORS)
            else -> {
                problems += "参数 $name 必须是字符串数组"
                return null
            }
        }
        return raw.map { it.trim().take(maxLength) }.filter { it.isNotEmpty() }.distinct().take(maxItems)
    }

    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private companion object {
        val SEPARATORS = Regex("[,，、;；]")
    }
}
