package com.koubeiradar.app

import com.koubeiradar.app.meal.MealOutcome
import com.koubeiradar.app.meal.MealRecommendation
import com.koubeiradar.app.meal.NotificationContent
import com.koubeiradar.app.service.CachingRankerProvider
import com.koubeiradar.app.service.DEMO_CENTER
import com.koubeiradar.app.service.DefaultDataSources
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.app.settings.Secret
import com.koubeiradar.app.ui.home.HomeViewModel
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.data.ShopSourceException
import com.koubeiradar.data.repo.ShopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MealRecommendationTest {
    private val settings = FakeSettings(mealUser())
    private val pool = FakePool()
    private val rankers = ScriptedRankers()
    private val recommendation = MealRecommendation(settings, FakeDataSources(), pool, rankers, PlainTexts)

    private fun ranked(shop: Shop, score: Double, verdict: Verdict = Verdict.RECOMMENDED) = ScoredShop(shop, score, verdict, ScoreBreakdown())

    private suspend fun content(meal: Meal = Meal.LUNCH): NotificationContent =
        assertIs<MealOutcome.Notify>(recommendation.compose(meal)).content

    @Test
    fun `without a place it asks for one and loads nothing`() = runTest {
        settings.state.value = mealUser().copy(savedPlace = null)

        val content = content(Meal.DINNER)

        assertEquals(NotificationContent(Meal.DINNER, "DINNER|no-place", "set a place"), content)
        assertTrue(pool.refreshes.isEmpty())
    }

    @Test
    fun `the three best shops are listed in ranking order with their facts`() = runTest {
        val shops = listOf(
            shop("a", "晨光粥铺(科技园店)", rating = 4.8, price = 16.0, distance = 1300),
            shop("b", "老张面馆", rating = 4.6, price = 25.5, distance = 850),
            shop("c", "小满轻食", rating = 4.4, price = 32.0, distance = 2400),
            shop("d", "第四家", rating = 4.0),
        )
        rankers.result = listOf(ranked(shops[0], 86.4), ranked(shops[1], 79.5), ranked(shops[2], 71.0), ranked(shops[3], 60.0))

        val content = content()

        assertEquals("LUNCH|晨光粥铺(科技园店)|3", content.title)
        assertEquals("晨光粥铺(科技园店)+老张面馆+小满轻食", content.text)
        assertEquals(
            listOf(
                "晨光粥铺(科技园店) s86 r4.8 p¥16 1.3km",
                "老张面馆 s80 r4.6 p¥25.5 850m",
                "小满轻食 s71 r4.4 p¥32 2.4km",
            ),
            content.lines,
        )
    }

    @Test
    fun `unknown facts are left out of a line`() = runTest {
        rankers.result = listOf(ranked(shop("a", "无名小店", rating = null, price = null, distance = null), 55.0, Verdict.OKAY))

        val content = content()

        assertEquals(listOf("无名小店 s55"), content.lines)
        assertEquals("LUNCH|无名小店|1", content.title)
    }

    @Test
    fun `shops to avoid and shops without data are never recommended, however high they rank`() = runTest {
        val shops = ('a'..'f').map { shop(it.toString(), "店$it") }
        rankers.result = listOf(
            ranked(shops[0], 90.0, Verdict.AVOID),
            ranked(shops[1], 85.0, Verdict.RECOMMENDED),
            ranked(shops[2], 80.0, Verdict.INSUFFICIENT_DATA),
            ranked(shops[3], 75.0, Verdict.OKAY),
            ranked(shops[4], 70.0, Verdict.AVOID),
            ranked(shops[5], 65.0, Verdict.RECOMMENDED),
        )

        assertEquals("店b+店d+店f", content().text)
    }

    @Test
    fun `fewer than three good shops are listed as they are`() = runTest {
        rankers.result = listOf(ranked(shop("a"), 80.0), ranked(shop("b"), 70.0, Verdict.AVOID))

        val content = content()

        assertEquals(1, content.lines.size)
        assertEquals("LUNCH|店a|1", content.title)
    }

    @Test
    fun `nothing is posted when no shop is left`() = runTest {
        assertEquals(MealOutcome.NothingToRecommend, recommendation.compose(Meal.LUNCH), "no shops at all")

        rankers.result = listOf(ranked(shop("a"), 90.0, Verdict.AVOID), ranked(shop("b"), 0.0, Verdict.INSUFFICIENT_DATA))
        assertEquals(MealOutcome.NothingToRecommend, recommendation.compose(Meal.LUNCH), "only shops to avoid")
    }

    @Test
    fun `it ranks like the home screen does, with the default filter and the chosen preset, but with room for dropped shops`() = runTest {
        val criteria = Criteria(minRating = 4.3, maxDistanceMeters = 2000, limit = 3)
        settings.state.value = mealUser().copy(criteria = criteria, preset = ScoringPreset.VALUE_FIRST)
        rankers.result = listOf(ranked(shop("a"), 80.0))

        recommendation.compose(Meal.LUNCH)

        val (preset, used) = rankers.calls.single()
        assertEquals(ScoringPreset.VALUE_FIRST, preset)
        assertEquals(criteria.copy(limit = MealRecommendation.CANDIDATE_LIMIT), used)
    }

    @Test
    fun `shops are searched around the saved place, on the source the home screen would use`() = runTest {
        rankers.result = listOf(ranked(shop("a"), 80.0))

        recommendation.compose(Meal.LUNCH)
        val demo = pool.refreshes.single()
        assertEquals(somewhereInTown, demo.query.center)
        assertEquals(HomeViewModel.NEARBY_RADIUS_METERS, demo.query.radiusMeters)
        assertEquals(HomeViewModel.NEARBY_LIMIT, demo.query.limit)
        assertEquals("demo", demo.source.id)

        settings.state.value = mealUser().copy(amapKey = Secret("k"), savedPlace = SavedPlace("望京SOHO", DEMO_CENTER))
        recommendation.compose(Meal.LUNCH)
        val amap = pool.refreshes.last()
        assertEquals("amap", amap.source.id)
        assertEquals(DEMO_CENTER, amap.query.center)

        settings.state.value = settings.current.copy(useDemoData = true)
        recommendation.compose(Meal.LUNCH)
        assertEquals("demo", pool.refreshes.last().source.id, "the demo switch wins over the key")
    }

    @Test
    fun `only a connectivity failure is worth retrying`() = runTest {
        ShopSourceException.Kind.entries.forEach { kind ->
            pool.failure = ShopSourceException("x", kind)
            assertEquals(MealOutcome.SourceFailed(retryable = kind == ShopSourceException.Kind.NETWORK), recommendation.compose(Meal.LUNCH), "$kind")
        }
        pool.failure = IllegalStateException("broken source")
        assertEquals(MealOutcome.SourceFailed(retryable = false), recommendation.compose(Meal.LUNCH))
    }

    @Test
    fun `a cancelled run is cancelled, not reported as a failure`() = runTest {
        pool.failure = CancellationException("stopped")

        assertFailsWith<CancellationException> { recommendation.compose(Meal.LUNCH) }
    }

    @Test
    fun `with the real pipeline on the demo data it recommends three good shops`() = runTest {
        val real = MealRecommendation(settings, DefaultDataSources(OkHttpClient()), ShopRepository(), CachingRankerProvider(), PlainTexts)
        settings.state.value = mealUser().copy(savedPlace = SavedPlace("", DEMO_CENTER, fromDevice = true))

        val content = assertIs<MealOutcome.Notify>(real.compose(Meal.DINNER)).content

        assertEquals(3, content.lines.size)
        assertTrue(content.title.startsWith("DINNER|") && content.title.endsWith("|3"), content.title)
        assertTrue(content.lines.all { Regex("""^\S+ s\d+( r\d\.\d)?( p¥[\d.]+)?( [\d.]+k?m)?$""").matches(it) }, content.lines.toString())
        assertEquals(content.lines.map { it.substringBefore(' ') }, content.text.split("+"))
    }
}
