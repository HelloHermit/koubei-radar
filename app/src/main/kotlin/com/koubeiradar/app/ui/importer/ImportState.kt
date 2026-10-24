package com.koubeiradar.app.ui.importer

import com.koubeiradar.agent.importer.ImportResult
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.core.model.Shop

sealed interface ImportError {
    data object NotConfigured : ImportError

    data class Llm(val kind: LlmException.Kind) : ImportError

    /** Screenshots were selected but none of them could be read (and there is no text to fall back on). */
    data object NoReadableImages : ImportError

    data object Unexpected : ImportError
}

sealed interface ImportPhase {
    /** Choosing screenshots / typing text. */
    data object Editing : ImportPhase

    data object Extracting : ImportPhase

    /**
     * What the model found. [notes] are the extractor's remarks (Chinese text), [notices] are the app's own
     * (skipped screenshots). [shops] may be empty: "nothing recognised" is a result, not an error.
     */
    data class Preview(val shops: List<Shop>, val notes: List<String>, val notices: List<UiText>) : ImportPhase

    data class Failed(val error: ImportError) : ImportPhase
}

data class ImportUiState(
    /** `null` until the settings are read. */
    val configured: Boolean? = null,
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    /** OpenAI-compatible provider without a vision model: screenshots may be rejected by the chat model. */
    val visionModelMissing: Boolean = false,
    val text: String = "",
    /** `content://` URIs of the selected screenshots. */
    val images: List<String> = emptyList(),
    /** Set when more screenshots were offered than the limit allows. */
    val imagesCapped: Boolean = false,
    val phase: ImportPhase = ImportPhase.Editing,
) {
    val canExtract: Boolean
        get() = phase !is ImportPhase.Extracting && (text.isNotBlank() || images.isNotEmpty())
}

sealed interface ImportEvent {
    /** [count] shops were added to the candidate pool. */
    data class Merged(val count: Int) : ImportEvent
}

/** Combines what the extractor found in screenshots and in text; a shop seen twice keeps its more complete record. */
fun mergeImportResults(results: List<ImportResult>): ImportResult {
    val byId = LinkedHashMap<String, Shop>()
    for (shop in results.flatMap { it.shops }) {
        val known = byId[shop.id]
        if (known == null || shop.knownFieldCount() > known.knownFieldCount()) byId[shop.id] = shop
    }
    return ImportResult(byId.values.toList(), results.flatMap { it.notes })
}

private fun Shop.knownFieldCount(): Int = listOfNotNull(
    category, rating, ratingCount, monthlySales, avgPrice, distanceMeters, deliveryMinutes, deliveryFee, minOrder,
    address, location, openNow, phone,
).size + tags.size + reviews.size + platforms.size
