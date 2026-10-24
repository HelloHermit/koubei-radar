package com.koubeiradar.app

import com.koubeiradar.agent.importer.ImageInput
import com.koubeiradar.agent.importer.ImportResult
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.agent.llm.LlmProvider
import com.koubeiradar.app.platform.ImportLimits
import com.koubeiradar.app.platform.ShareInbox
import com.koubeiradar.app.platform.SharePayload
import com.koubeiradar.app.service.ImportService
import com.koubeiradar.app.service.LlmNotConfiguredException
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.OpenAiSettings
import com.koubeiradar.app.ui.UiText
import com.koubeiradar.app.ui.importer.ImportError
import com.koubeiradar.app.ui.importer.ImportEvent
import com.koubeiradar.app.ui.importer.ImportPhase
import com.koubeiradar.app.ui.importer.ImportViewModel
import com.koubeiradar.core.model.Shop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettings()
    private val service = FakeImportService()
    private val loader = FakeImageLoader()
    private val store = FakeShopStore()
    private val inbox = ShareInbox()

    private fun TestScope.viewModel() = ImportViewModel(settings, service, loader, store, inbox)

    private fun imported(id: String, rating: Double? = 4.7) = Shop(id = "import:$id", name = "店$id", source = "import", rating = rating, monthlySales = 1000)

    @Test
    fun `select, recognise, preview, merge`() = runTest {
        service.imageResult = ImportResult(listOf(imported("a"), imported("b")), listOf("第 2 张图片有一家店信息不完整"))
        val viewModel = viewModel()
        val merged = mutableListOf<ImportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { merged += it } }
        advanceUntilIdle()

        viewModel.addImages(listOf("content://media/1", "content://media/2"))
        assertTrue(viewModel.uiState.value.canExtract)
        viewModel.extract()
        assertEquals(ImportPhase.Extracting, viewModel.uiState.value.phase)
        advanceUntilIdle()

        assertEquals(2, service.imageBatches.single().size)
        assertTrue(service.texts.isEmpty())
        val preview = assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase)
        assertEquals(listOf("import:a", "import:b"), preview.shops.map { it.id })
        assertEquals(listOf("第 2 张图片有一家店信息不完整"), preview.notes)
        assertTrue(store.imported.isEmpty(), "nothing is merged before the user confirms")

        viewModel.commit()
        advanceUntilIdle()

        assertEquals(listOf("import:a", "import:b"), store.imported.map { it.id })
        assertEquals(listOf<ImportEvent>(ImportEvent.Merged(2)), merged)
        val state = viewModel.uiState.value
        assertEquals(ImportPhase.Editing, state.phase)
        assertTrue(state.images.isEmpty() && state.text.isEmpty())
    }

    @Test
    fun `pasted text goes to the text extractor only`() = runTest {
        service.textResult = ImportResult(listOf(imported("t")), emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onTextChanged("老张川菜 4.8分 月售1000+")
        viewModel.extract()
        advanceUntilIdle()

        assertEquals(listOf("老张川菜 4.8分 月售1000+"), service.texts)
        assertTrue(service.imageBatches.isEmpty())
        assertEquals(listOf("import:t"), assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase).shops.map { it.id })
    }

    @Test
    fun `screenshots and text are combined`() = runTest {
        service.imageResult = ImportResult(listOf(imported("a", rating = null)), listOf("图"))
        service.textResult = ImportResult(listOf(imported("a", rating = 4.9), imported("c")), listOf("文"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.addImages(listOf("content://media/1"))
        viewModel.onTextChanged("文字")

        viewModel.extract()
        advanceUntilIdle()

        val preview = assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase)
        assertEquals(listOf("import:a", "import:c"), preview.shops.map { it.id })
        assertEquals(4.9, preview.shops.first().rating, "the fuller record wins")
        assertEquals(listOf("图", "文"), preview.notes)
    }

    @Test
    fun `inputs are capped and only content URIs are accepted`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.addImages((1..9).map { "content://media/$it" } + "file:///data/data/x")
        viewModel.onTextChanged("字".repeat(ImportLimits.MAX_TEXT_CHARS + 500))

        val state = viewModel.uiState.value
        assertEquals(ImportLimits.MAX_IMAGES, state.images.size)
        assertTrue(state.imagesCapped)
        assertTrue(state.images.all { it.startsWith("content://") })
        assertEquals(ImportLimits.MAX_TEXT_CHARS, state.text.length)

        viewModel.removeImage(state.images.first())
        assertEquals(ImportLimits.MAX_IMAGES - 1, viewModel.uiState.value.images.size)
        assertFalse(viewModel.uiState.value.imagesCapped)
    }

    @Test
    fun `nothing to recognise means no request`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canExtract)
        viewModel.onTextChanged("   ")
        viewModel.extract()
        advanceUntilIdle()
        assertTrue(service.texts.isEmpty() && service.imageBatches.isEmpty())
        assertEquals(ImportPhase.Editing, viewModel.uiState.value.phase)
    }

    @Test
    fun `provider failures are mapped and the inputs are kept for a retry`() = runTest {
        service.failure = LlmException("bad key", LlmException.Kind.AUTH, 401)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.addImages(listOf("content://media/1"))

        viewModel.extract()
        advanceUntilIdle()

        assertEquals(ImportPhase.Failed(ImportError.Llm(LlmException.Kind.AUTH)), viewModel.uiState.value.phase)
        assertEquals(listOf("content://media/1"), viewModel.uiState.value.images)

        service.failure = null
        service.imageResult = ImportResult(listOf(imported("a")), emptyList())
        viewModel.extract()
        advanceUntilIdle()
        assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase)
    }

    @Test
    fun `a missing configuration and unexpected errors are contained`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onTextChanged("x")

        service.failure = LlmNotConfiguredException()
        viewModel.extract()
        advanceUntilIdle()
        assertEquals(ImportPhase.Failed(ImportError.NotConfigured), viewModel.uiState.value.phase)

        service.failure = IllegalStateException("bug")
        viewModel.extract()
        advanceUntilIdle()
        assertEquals(ImportPhase.Failed(ImportError.Unexpected), viewModel.uiState.value.phase)
    }

    @Test
    fun `unreadable and oversized screenshots are skipped with a notice`() = runTest {
        service.imageResult = ImportResult(listOf(imported("a")), emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.addImages(listOf("content://media/ok", "content://media/big", "content://media/bad", "content://media/bad2"))

        viewModel.extract()
        advanceUntilIdle()

        assertEquals(1, service.imageBatches.single().size)
        val preview = assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase)
        assertEquals(listOf(UiText(R.string.import_notice_too_large, 1), UiText(R.string.import_notice_unreadable, 2)), preview.notices)
    }

    @Test
    fun `when no screenshot can be read and there is no text the model is not called`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.addImages(listOf("content://media/bad"))

        viewModel.extract()
        advanceUntilIdle()

        assertEquals(ImportPhase.Failed(ImportError.NoReadableImages), viewModel.uiState.value.phase)
        assertTrue(service.imageBatches.isEmpty())
    }

    @Test
    fun `finding nothing is a result, not an error, and cannot be merged`() = runTest {
        service.textResult = ImportResult(emptyList(), listOf("没有找到店铺信息"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onTextChanged("你好")

        viewModel.extract()
        advanceUntilIdle()
        val preview = assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase)
        assertTrue(preview.shops.isEmpty())

        viewModel.commit()
        advanceUntilIdle()
        assertTrue(store.imported.isEmpty())
        assertIs<ImportPhase.Preview>(viewModel.uiState.value.phase)
    }

    @Test
    fun `cancel returns to editing and ignores a late result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        service.imageResult = ImportResult(listOf(imported("a")), emptyList())
        val slow = object : ImportService by service {
            override suspend fun fromImages(images: List<ImageInput>): ImportResult {
                gate.await()
                return service.fromImages(images)
            }
        }
        val viewModel = ImportViewModel(settings, slow, loader, store, inbox)
        advanceUntilIdle()
        viewModel.addImages(listOf("content://media/1"))
        viewModel.extract()
        advanceUntilIdle()
        assertEquals(ImportPhase.Extracting, viewModel.uiState.value.phase)

        viewModel.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ImportPhase.Editing, viewModel.uiState.value.phase)
        assertEquals(listOf("content://media/1"), viewModel.uiState.value.images)
    }

    @Test
    fun `a share from another app prefills the form and is consumed`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.addImages(listOf("content://media/old"))

        val payload = SharePayload("分享来的文字", listOf("content://media/shared1", "content://media/shared2"))
        inbox.offer(payload)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("分享来的文字", state.text)
        assertEquals(payload.imageRefs, state.images)
        assertEquals(ImportPhase.Editing, state.phase)
        assertNull(inbox.pending.value)
    }

    @Test
    fun `the screen knows whether recognition can work`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.configured)
        assertFalse(viewModel.uiState.value.visionModelMissing)

        service.configured.value = false
        settings.state.value = AppSettings(provider = LlmProvider.OPENAI_COMPATIBLE, openAi = OpenAiSettings(visionModel = ""))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.configured)
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, state.provider)
        assertTrue(state.visionModelMissing)
    }
}
