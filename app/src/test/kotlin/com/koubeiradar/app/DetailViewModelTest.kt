package com.koubeiradar.app

import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.ui.detail.DetailUiState
import com.koubeiradar.app.ui.detail.DetailViewModel
import com.koubeiradar.core.model.Criteria
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val settings = FakeSettings()
    private val store = FakeShopStore()
    private val rankers = FakeRankers()

    private fun TestScope.viewModel(id: String) = DetailViewModel(id, settings, store, rankers, StandardTestDispatcher(testScheduler))

    @Test
    fun `the shop is scored within the whole pool, whatever the filter`() = runTest {
        store.pool.value = listOf(shop("a", rating = 3.0), shop("b", rating = 4.9))
        settings.state.value = AppSettings(criteria = Criteria(minRating = 4.5))
        val viewModel = viewModel("a")
        assertEquals(DetailUiState.Loading, viewModel.uiState.value)
        advanceUntilIdle()

        val found = assertIs<DetailUiState.Found>(viewModel.uiState.value)
        assertEquals("a", found.scored.shop.id)
        assertEquals(Criteria(limit = 2), rankers.calls.last().second, "every shop is scored and the user's filter does not apply")
    }

    @Test
    fun `an unknown shop is reported as not found, and appears once the pool has it`() = runTest {
        val viewModel = viewModel("late")
        advanceUntilIdle()
        assertEquals(DetailUiState.NotFound, viewModel.uiState.value)

        store.pool.value = listOf(shop("late"))
        advanceUntilIdle()
        assertEquals("late", assertIs<DetailUiState.Found>(viewModel.uiState.value).scored.shop.id)
    }

    @Test
    fun `a new scoring preset re-scores`() = runTest {
        store.pool.value = listOf(shop("a"))
        val viewModel = viewModel("a")
        advanceUntilIdle()
        assertEquals(ScoringPreset.REPUTATION_FIRST, rankers.calls.last().first)

        settings.state.value = AppSettings(preset = ScoringPreset.FAST_FIRST)
        advanceUntilIdle()

        assertEquals(ScoringPreset.FAST_FIRST, rankers.calls.last().first)
        assertIs<DetailUiState.Found>(viewModel.uiState.value)
    }
}
