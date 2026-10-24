package com.koubeiradar.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koubeiradar.agent.importer.ImageInput
import com.koubeiradar.agent.importer.ImportResult
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.ImageLoadResult
import com.koubeiradar.app.platform.ImageLoader
import com.koubeiradar.app.platform.ImportLimits
import com.koubeiradar.app.platform.ShareInbox
import com.koubeiradar.app.platform.SharePayload
import com.koubeiradar.app.service.ImportService
import com.koubeiradar.app.service.LlmNotConfiguredException
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.store.ShopStore
import com.koubeiradar.app.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screenshot / text import: collect inputs (picked, pasted or shared by another app), let the model extract shops,
 * preview them, then merge them into the candidate pool. Everything that enters is treated as untrusted and capped.
 */
class ImportViewModel(
    settings: SettingsRepository,
    private val importService: ImportService,
    private val imageLoader: ImageLoader,
    private val store: ShopStore,
    private val shareInbox: ShareInbox,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _state.asStateFlow()

    private val eventChannel = Channel<ImportEvent>(Channel.BUFFERED)
    val events: Flow<ImportEvent> = eventChannel.receiveAsFlow()

    private var extractJob: Job? = null

    init {
        viewModelScope.launch {
            combine(settings.settings, importService.isConfigured) { current, configured ->
                val visionMissing = current.provider == LlmProvider.OPENAI_COMPATIBLE && current.openAi.visionModel.isBlank()
                Triple(configured, current.provider, visionMissing)
            }.collect { (configured, provider, visionMissing) ->
                _state.update { it.copy(configured = configured, provider = provider, visionModelMissing = visionMissing) }
            }
        }
        viewModelScope.launch {
            shareInbox.pending.filterNotNull().collect { payload ->
                applyShare(payload)
                shareInbox.consume(payload)
            }
        }
    }

    fun onTextChanged(text: String) {
        if (_state.value.phase is ImportPhase.Extracting) return
        _state.update { it.copy(text = ImportLimits.capText(text), phase = ImportPhase.Editing) }
    }

    /** Adds picked screenshots to the selection, up to [ImportLimits.MAX_IMAGES]. */
    fun addImages(refs: List<String>) {
        if (_state.value.phase is ImportPhase.Extracting) return
        _state.update { state ->
            val acceptable = ImportLimits.acceptableImages(state.images + refs)
            state.copy(
                images = acceptable.take(ImportLimits.MAX_IMAGES),
                imagesCapped = acceptable.size > ImportLimits.MAX_IMAGES,
                phase = ImportPhase.Editing,
            )
        }
    }

    fun removeImage(ref: String) {
        if (_state.value.phase is ImportPhase.Extracting) return
        _state.update { it.copy(images = it.images - ref, imagesCapped = false, phase = ImportPhase.Editing) }
    }

    /** Back from a preview or an error to the inputs, which are kept. */
    fun backToEditing() {
        if (_state.value.phase !is ImportPhase.Extracting) _state.update { it.copy(phase = ImportPhase.Editing) }
    }

    fun extract() {
        val snapshot = _state.value
        if (!snapshot.canExtract) return
        extractJob?.cancel()
        _state.update { it.copy(phase = ImportPhase.Extracting) }
        extractJob = viewModelScope.launch {
            val phase = try {
                recognise(snapshot.text, snapshot.images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmNotConfiguredException) {
                ImportPhase.Failed(ImportError.NotConfigured)
            } catch (e: LlmException) {
                ImportPhase.Failed(ImportError.Llm(e.kind))
            } catch (e: Exception) {
                // The extractor is another module's code: whatever it throws becomes an error state, never a crash.
                ImportPhase.Failed(ImportError.Unexpected)
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    fun cancel() {
        extractJob?.cancel()
        _state.update { if (it.phase is ImportPhase.Extracting) it.copy(phase = ImportPhase.Editing) else it }
    }

    /** "加入候选池": hands the previewed shops to the pool and clears the form. */
    fun commit() {
        val preview = _state.value.phase as? ImportPhase.Preview ?: return
        if (preview.shops.isEmpty()) return
        store.addImported(preview.shops)
        eventChannel.trySend(ImportEvent.Merged(preview.shops.size))
        _state.update { it.copy(text = "", images = emptyList(), imagesCapped = false, phase = ImportPhase.Editing) }
    }

    private suspend fun recognise(text: String, imageRefs: List<String>): ImportPhase {
        val images = ArrayList<ImageInput>()
        var tooLarge = 0
        var unreadable = 0
        for (ref in imageRefs) {
            when (val loaded = imageLoader.load(ref)) {
                is ImageLoadResult.Loaded -> images += loaded.image
                ImageLoadResult.TooLarge -> tooLarge++
                ImageLoadResult.Unreadable -> unreadable++
            }
        }
        if (images.isEmpty() && text.isBlank()) return ImportPhase.Failed(ImportError.NoReadableImages)

        val results = ArrayList<ImportResult>()
        if (images.isNotEmpty()) results += importService.fromImages(images)
        if (text.isNotBlank()) results += importService.fromText(text)
        val merged = mergeImportResults(results)
        val notices = buildList {
            if (tooLarge > 0) add(UiText(R.string.import_notice_too_large, tooLarge))
            if (unreadable > 0) add(UiText(R.string.import_notice_unreadable, unreadable))
        }
        return ImportPhase.Preview(merged.shops, merged.notes, notices)
    }

    /** A share replaces the form: it starts a new import. */
    private fun applyShare(payload: SharePayload) {
        extractJob?.cancel()
        _state.update {
            it.copy(
                text = payload.text.orEmpty(),
                images = payload.imageRefs,
                imagesCapped = false,
                phase = ImportPhase.Editing,
            )
        }
    }
}
