package com.koubeiradar.agent.importer

import com.koubeiradar.agent.llm.ContentPart
import com.koubeiradar.agent.llm.LlmClient
import com.koubeiradar.agent.llm.LlmMessage
import com.koubeiradar.agent.llm.LlmRequest
import com.koubeiradar.agent.llm.StopReason
import com.koubeiradar.agent.llm.ToolCall
import com.koubeiradar.agent.llm.ToolResult
import com.koubeiradar.agent.util.arrayOrNull
import com.koubeiradar.agent.util.stringOrNull
import com.koubeiradar.agent.util.stripInvisible
import com.koubeiradar.agent.util.toCleanText
import com.koubeiradar.agent.util.truncate
import com.koubeiradar.core.model.Shop

class ImageInput(val mediaType: String, val bytes: ByteArray)

data class ImportResult(
    /** Shops with `source = "import"` and stable ids `import:<hash of the normalised name>`. */
    val shops: List<Shop>,
    /** Short Chinese remarks for the user, e.g. "第 2 张图片中有 1 家店信息不完整，已跳过". */
    val notes: List<String>,
)

/**
 * Turns screenshots of a delivery app's shop list / shop page, or pasted text (including the textual output of an
 * external GUI agent such as Open-AutoGLM), into structured [Shop]s. This is the "agent instead of scraping" path:
 * the user browses the delivery app themselves, we only read what they hand us. Spec: `docs/DESIGN.md` §6.
 *
 * CONTRACT (frozen): never invents values - anything not visible/stated stays `null`; returns an empty list plus a
 * note (not an exception) when nothing usable was found; throws `LlmException` on provider failures.
 */
class ShopImportExtractor(private val llm: LlmClient) {
    /** At most [MAX_IMAGES] images are read; extra ones are dropped with a note. */
    suspend fun fromImages(images: List<ImageInput>, hint: String? = null): ImportResult {
        val notes = ArrayList<String>()
        val usable = images.filter { it.bytes.isNotEmpty() }
        if (usable.size < images.size) notes += "有 ${images.size - usable.size} 张图片是空文件，已忽略。"
        if (usable.isEmpty()) return ImportResult(emptyList(), notes + "没有可识别的图片，请先选择截图。")
        val used = usable.take(MAX_IMAGES)
        if (usable.size > used.size) notes += "一次最多识别 $MAX_IMAGES 张图片，已忽略多出的 ${usable.size - used.size} 张。"

        val parts = ArrayList<ContentPart>()
        parts += ContentPart.Text(ImportPrompts.imagesIntro(used.size, hint))
        used.forEachIndexed { index, image ->
            parts += ContentPart.Text("第${index + 1}张图片")
            parts += ContentPart.Image(image.mediaType, image.bytes)
        }
        return extract(parts, notes)
    }

    /** Pasted text (or the text output of a GUI agent); anything beyond [MAX_TEXT_CHARS] characters is cut off with a note. */
    suspend fun fromText(text: String, hint: String? = null): ImportResult {
        val notes = ArrayList<String>()
        val cleaned = text.stripInvisible(keepNewlines = true).trim()
        if (cleaned.isEmpty()) return ImportResult(emptyList(), listOf("没有可识别的文字内容，请先粘贴店铺信息。"))
        val capped = cleaned.truncate(MAX_TEXT_CHARS, ellipsis = "")
        if (capped.length < cleaned.length) notes += "文字超过 $MAX_TEXT_CHARS 字，只识别了前 $MAX_TEXT_CHARS 字。"
        // The fence tag must stay unforgeable by the pasted text itself.
        val fenced = capped.replace(FENCE_TAG, "＜${ImportPrompts.TEXT_FENCE_TAG}＞")
        val parts = listOf(
            ContentPart.Text(ImportPrompts.textIntro(hint)),
            ContentPart.Text(ImportPrompts.fencedText(fenced)),
        )
        return extract(parts, notes)
    }

    /** One call, and one retry (with a nudge) when the model answered without calling `report_shops`. */
    private suspend fun extract(userParts: List<ContentPart>, notes: MutableList<String>): ImportResult {
        val request = LlmRequest(
            system = ImportPrompts.SYSTEM,
            messages = listOf(LlmMessage.User(userParts)),
            tools = listOf(ImportPrompts.REPORT_SHOPS),
            maxTokens = MODEL_MAX_TOKENS,
        )
        var response = llm.complete(request)
        var reports = response.toolCalls.filter { it.name == ImportPrompts.REPORT_TOOL }
        if (reports.isEmpty()) {
            val followUp = buildList {
                addAll(request.messages)
                add(LlmMessage.Assistant(response.text, response.toolCalls, response.providerState))
                if (response.toolCalls.isNotEmpty()) {
                    add(LlmMessage.ToolResults(response.toolCalls.map { ToolResult(it.id, ImportPrompts.UNKNOWN_TOOL_RESULT, isError = true) }))
                }
                add(LlmMessage.User(ImportPrompts.RETRY_NUDGE))
            }
            response = llm.complete(request.copy(messages = followUp))
            reports = response.toolCalls.filter { it.name == ImportPrompts.REPORT_TOOL }
        }
        if (reports.isEmpty()) {
            notes += "模型没有返回可识别的结果，请换一张更清晰的截图，或检查文字内容后再试。"
            return ImportResult(emptyList(), notes)
        }
        if (response.stopReason == StopReason.MAX_TOKENS) notes += "模型的输出被长度上限截断，识别结果可能不完整；可以分批导入更少的截图。"
        return convert(reports, notes)
    }

    private fun convert(reports: List<ToolCall>, notes: MutableList<String>): ImportResult {
        val stats = ImportSanitizer.Stats()
        val modelNotes = ArrayList<String>()
        val converted = ArrayList<Shop>()
        for (report in reports) {
            converted += ImportSanitizer.convert(report.arguments["shops"].arrayOrNull().orEmpty(), stats)
            report.arguments["notes"].arrayOrNull().orEmpty()
                .mapNotNullTo(modelNotes) { it.stringOrNull()?.toCleanText()?.take(MAX_NOTE_CHARS)?.takeIf { note -> note.isNotEmpty() } }
        }
        var shops = ImportSanitizer.mergeDuplicates(converted, stats)
        if (shops.size > MAX_SHOPS) {
            shops = shops.take(MAX_SHOPS)
            notes += "识别到的店铺超过 $MAX_SHOPS 家，只保留了前 $MAX_SHOPS 家。"
        }
        if (stats.nameless > 0) notes += "有 ${stats.nameless} 条记录没有可用的店名，已跳过。"
        if (stats.invalidNumbers > 0) notes += "有 ${stats.invalidNumbers} 个数值看不清或超出合理范围，已留空。"
        if (stats.merged > 0) notes += "${stats.merged} 条重复出现的店铺已合并。"
        if (shops.isEmpty()) notes += "没有识别到店铺信息。"
        notes += modelNotes.distinct().take(MAX_MODEL_NOTES)
        return ImportResult(shops, notes)
    }

    companion object {
        const val MAX_IMAGES = 6
        const val MAX_TEXT_CHARS = 20_000

        private const val MODEL_MAX_TOKENS = 16000
        private const val MAX_SHOPS = 100
        private const val MAX_MODEL_NOTES = 8
        private const val MAX_NOTE_CHARS = 200
        private val FENCE_TAG = Regex("<\\s*/?\\s*${ImportPrompts.TEXT_FENCE_TAG}\\s*>", RegexOption.IGNORE_CASE)
    }
}
