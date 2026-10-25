package com.koubeiradar.app

import com.koubeiradar.app.meal.MealNotifier
import com.koubeiradar.app.meal.MealScheduler
import com.koubeiradar.app.meal.MealTestSender
import com.koubeiradar.app.meal.MealTexts
import com.koubeiradar.app.meal.NotificationContent
import com.koubeiradar.app.meal.TestOutcome
import com.koubeiradar.app.platform.NotificationPermission
import com.koubeiradar.app.service.RankerProvider
import com.koubeiradar.app.settings.AppSettings
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.settings.SavedPlace
import com.koubeiradar.app.settings.ScoringPreset
import com.koubeiradar.core.model.Criteria
import com.koubeiradar.core.model.GeoPoint
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Shop
import com.koubeiradar.core.selection.ShopRanker
import com.koubeiradar.data.NearbyQuery
import com.koubeiradar.data.ShopSource
import com.koubeiradar.data.repo.ShopPool
import kotlinx.coroutines.CompletableDeferred

/** Words that make the decisions visible without Android resources. */
object PlainTexts : MealTexts {
    override fun title(meal: Meal, firstShop: String, count: Int) = "${meal.name}|$firstShop|$count"

    override fun shopLine(name: String, score: Int, rating: String?, price: String?, distance: String?) =
        listOfNotNull(name, "s$score", rating?.let { "r$it" }, price?.let { "p$it" }, distance).joinToString(" ")

    override fun summary(names: List<String>) = names.joinToString("+")

    override fun placeMissingTitle(meal: Meal) = "${meal.name}|no-place"

    override fun placeMissingText() = "set a place"
}

class FakePool(var shops: List<Shop> = emptyList(), var failure: Exception? = null) : ShopPool {
    class Refresh(val source: ShopSource, val query: NearbyQuery)

    val refreshes = mutableListOf<Refresh>()

    override fun all(): List<Shop> = shops

    override fun find(id: String): Shop? = shops.firstOrNull { it.id == id }

    override suspend fun refreshNearby(source: ShopSource, query: NearbyQuery, force: Boolean): List<Shop> {
        refreshes += Refresh(source, query)
        failure?.let { throw it }
        return shops
    }
}

/** Always answers with [result], whatever the shops and the filter; records what it was asked. */
class ScriptedRankers(var result: List<ScoredShop> = emptyList()) : RankerProvider {
    val calls = mutableListOf<Pair<ScoringPreset, Criteria>>()

    override fun forPreset(preset: ScoringPreset): ShopRanker = object : ShopRanker {
        override fun select(shops: List<Shop>, criteria: Criteria): List<ScoredShop> {
            calls += preset to criteria
            return result
        }
    }
}

class FakeNotifier(var accepts: Boolean = true, var failure: RuntimeException? = null) : MealNotifier {
    val posted = mutableListOf<NotificationContent>()

    override fun post(content: NotificationContent): Boolean {
        failure?.let { throw it }
        posted += content
        return accepts
    }
}

class FakeScheduler : MealScheduler {
    val synced = mutableListOf<MealNotificationSettings>()
    val next = mutableListOf<Pair<Meal, MealNotificationSettings>>()

    override suspend fun sync(meals: MealNotificationSettings) {
        synced += meals
    }

    override fun scheduleNext(meal: Meal, meals: MealNotificationSettings) {
        next += meal to meals
    }
}

class FakeNotificationPermission(var granted: Boolean = true) : NotificationPermission {
    override fun isGranted() = granted
}

class FakeMealTests(var outcome: TestOutcome = TestOutcome.SENT) : MealTestSender {
    var calls = 0
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun sendTest(): TestOutcome {
        calls++
        gate?.await()
        failure?.let { throw it }
        return outcome
    }
}

val somewhereInTown = GeoPoint(30.1, 120.2)

/** Settings of a user who has a place and both meals on, on the offline demo data. */
fun mealUser(meals: MealNotificationSettings = MealNotificationSettings(lunchEnabled = true, dinnerEnabled = true)) =
    AppSettings(savedPlace = SavedPlace("", somewhereInTown, fromDevice = true), meals = meals)
