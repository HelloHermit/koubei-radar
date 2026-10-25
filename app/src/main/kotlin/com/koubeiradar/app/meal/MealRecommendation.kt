package com.koubeiradar.app.meal

import com.koubeiradar.app.service.DataSources
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.SettingsRepository
import com.koubeiradar.app.ui.format.Formatters
import com.koubeiradar.app.ui.home.HomeViewModel
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.repo.ShopPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Wording of the notifications; the Android implementation reads string resources. */
interface MealTexts {
    fun title(meal: Meal, firstShop: String, count: Int): String

    /** One line of the expanded notification; the facts that are unknown are `null` and left out. */
    fun shopLine(name: String, score: Int, rating: String?, price: String?, distance: String?): String

    fun summary(names: List<String>): String

    fun placeMissingTitle(meal: Meal): String

    fun placeMissingText(): String
}

/** A finished notification: [text] is the collapsed form, [lines] the expanded one (empty when there is nothing to list). */
data class NotificationContent(val meal: Meal, val title: String, val text: String, val lines: List<String> = emptyList())

sealed interface MealOutcome {
    data class Notify(val content: NotificationContent) : MealOutcome

    /** Nothing worth a notification: no shop passed the filters, or every one is a shop to avoid. */
    data object NothingToRecommend : MealOutcome

    /** Shops could not be loaded; [retryable] when trying again soon may help (connectivity). */
    data class SourceFailed(val retryable: Boolean) : MealOutcome
}

/**
 * Decides what the daily recommendation says: the best three shops around the last place the user set in the app, by
 * the same ranking, filter and data source as the home screen. Deterministic and on-device: no model is involved and
 * no location is read.
 */
class MealRecommendation(
    private val settings: SettingsRepository,
    private val dataSources: DataSources,
    private val pool: ShopPool,
    private val rankers: RankerProvider,
    private val texts: MealTexts,
) {
    @Throws(CancellationException::class)
    suspend fun compose(meal: Meal): MealOutcome {
        val current = settings.settings.first()
        val place = current.savedPlace
            ?: return MealOutcome.Notify(NotificationContent(meal, texts.placeMissingTitle(meal), texts.placeMissingText()))
        val ranked = try {
            val query = NearbyQuery(place.point, HomeViewModel.NEARBY_RADIUS_METERS, keyword = null, limit = HomeViewModel.NEARBY_LIMIT)
            pool.refreshNearby(dataSources.sourceFor(current), query)
            // Room beyond three: shops to avoid are dropped below and must not use up the places.
            rankers.forPreset(current.preset).select(pool.all(), current.criteria.copy(limit = CANDIDATE_LIMIT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ShopSourceException) {
            return MealOutcome.SourceFailed(retryable = e.kind == ShopSourceException.Kind.NETWORK)
        } catch (e: Exception) {
            // The source is another module's code: whatever it throws ends this run, never crashes it.
            return MealOutcome.SourceFailed(retryable = false)
        }
        val picks = ranked.filter { it.verdict != Verdict.AVOID && it.verdict != Verdict.INSUFFICIENT_DATA }.take(MAX_SHOPS)
        if (picks.isEmpty()) return MealOutcome.NothingToRecommend
        return MealOutcome.Notify(
            NotificationContent(
                meal = meal,
                title = texts.title(meal, picks.first().shop.name, picks.size),
                text = texts.summary(picks.map { it.shop.name }),
                lines = picks.map(::line),
            ),
        )
    }

    private fun line(scored: ScoredShop): String {
        val shop = scored.shop
        return texts.shopLine(
            name = shop.name,
            score = Formatters.score(scored.score),
            rating = shop.rating?.let(Formatters::rating),
            price = shop.avgPrice?.let(Formatters::price),
            distance = shop.distanceMeters?.let(Formatters::distance),
        )
    }

    companion object {
        const val MAX_SHOPS = 3
        const val CANDIDATE_LIMIT = 20
    }
}
