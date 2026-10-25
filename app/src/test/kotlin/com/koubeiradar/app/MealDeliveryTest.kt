package com.koubeiradar.app

import com.koubeiradar.app.meal.MealDelivery
import com.koubeiradar.app.meal.MealRecommendation
import com.koubeiradar.app.meal.TestOutcome
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.core.model.ScoreBreakdown
import com.koubeiradar.core.model.ScoredShop
import com.koubeiradar.core.model.Verdict
import com.koubeiradar.data.ShopSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MealDeliveryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val noon = Instant.parse("2026-10-26T04:00:00Z") // 12:00 in Shanghai
    private val settings = FakeSettings(mealUser())
    private val pool = FakePool()
    private val rankers = ScriptedRankers()
    private val notifier = FakeNotifier()
    private val scheduler = FakeScheduler()

    private fun delivery(now: Instant = noon) = MealDelivery(
        MealRecommendation(settings, FakeDataSources(), pool, rankers, PlainTexts),
        notifier,
        scheduler,
        settings,
        Clock.fixed(now, zone),
    )

    private fun withShops() {
        val shop = shop("a", "晨光粥铺")
        pool.shops = listOf(shop)
        rankers.result = listOf(ScoredShop(shop, 86.0, Verdict.RECOMMENDED, ScoreBreakdown()))
    }

    @Test
    fun `a scheduled run posts the recommendation and queues the next day's run`() = runTest {
        withShops()

        val retry = delivery().runScheduled(Meal.LUNCH, attempt = 0, scheduledAt = noon)

        assertFalse(retry)
        assertEquals(listOf("LUNCH|晨光粥铺|1"), notifier.posted.map { it.title })
        assertEquals(listOf(Meal.LUNCH to settings.current.meals), scheduler.next)
    }

    @Test
    fun `a meal that was switched off since posts nothing and is handed back to the scheduler to cancel`() = runTest {
        withShops()
        settings.state.value = mealUser(MealNotificationSettings(lunchEnabled = false, dinnerEnabled = true))

        assertFalse(delivery().runScheduled(Meal.LUNCH, 0, noon))

        assertTrue(notifier.posted.isEmpty())
        assertEquals(listOf(Meal.LUNCH to settings.current.meals), scheduler.next, "the scheduler sees the meal off and cancels")
        assertTrue(pool.refreshes.isEmpty())
    }

    @Test
    fun `a connectivity failure is retried twice, then given up for the day`() = runTest {
        pool.failure = ShopSourceException("offline", ShopSourceException.Kind.NETWORK)
        val delivery = delivery()

        assertTrue(delivery.runScheduled(Meal.LUNCH, 0, noon))
        assertTrue(delivery.runScheduled(Meal.LUNCH, 1, noon))
        assertTrue(scheduler.next.isEmpty(), "while retrying, tomorrow is not queued yet")

        assertFalse(delivery.runScheduled(Meal.LUNCH, MealDelivery.MAX_RETRIES, noon))
        assertEquals(1, scheduler.next.size, "after the last try tomorrow's run is queued")
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a failure that retrying cannot fix gives up at once but still queues tomorrow`() = runTest {
        pool.failure = ShopSourceException("bad key", ShopSourceException.Kind.AUTH)

        assertFalse(delivery().runScheduled(Meal.LUNCH, 0, noon))

        assertEquals(1, scheduler.next.size)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `with nothing to recommend it stays quiet and queues tomorrow`() = runTest {
        assertFalse(delivery().runScheduled(Meal.LUNCH, 0, noon))

        assertTrue(notifier.posted.isEmpty())
        assertEquals(1, scheduler.next.size)
    }

    @Test
    fun `a notification the system refuses does not stop the chain`() = runTest {
        withShops()
        notifier.accepts = false

        assertFalse(delivery().runScheduled(Meal.LUNCH, 0, noon))

        assertEquals(1, scheduler.next.size)
    }

    @Test
    fun `a crash while posting still queues tomorrow, then surfaces`() = runTest {
        withShops()
        notifier.failure = IllegalStateException("boom")

        assertFailsWith<IllegalStateException> { delivery().runScheduled(Meal.LUNCH, 0, noon) }

        assertEquals(1, scheduler.next.size)
    }

    @Test
    fun `a cancelled run queues nothing because whoever cancelled it owns the schedule`() = runTest {
        pool.failure = CancellationException("replaced")

        assertFailsWith<CancellationException> { delivery().runScheduled(Meal.LUNCH, 0, noon) }

        assertTrue(scheduler.next.isEmpty())
    }

    @Test
    fun `a run that the system only got to hours late is skipped, a slightly late one is not`() = runTest {
        withShops()
        val delivery = delivery()

        assertFalse(delivery.runScheduled(Meal.LUNCH, 0, scheduledAt = noon.minus(MealDelivery.MAX_LATENESS).minusSeconds(1)))
        assertTrue(notifier.posted.isEmpty(), "too late")
        assertEquals(1, scheduler.next.size, "but tomorrow is queued")

        delivery.runScheduled(Meal.LUNCH, 0, scheduledAt = noon.minus(MealDelivery.MAX_LATENESS))
        assertEquals(1, notifier.posted.size, "exactly at the limit still counts")

        delivery.runScheduled(Meal.LUNCH, 0, scheduledAt = null)
        assertEquals(2, notifier.posted.size, "no record of the due time: assume on time")
    }

    @Test
    fun `the test notification goes through the same decisions, whatever the meal switches say`() = runTest {
        settings.state.value = mealUser(MealNotificationSettings())
        withShops()

        assertEquals(TestOutcome.SENT, delivery().sendTest())

        assertEquals(listOf("LUNCH|晨光粥铺|1"), notifier.posted.map { it.title })
        assertTrue(scheduler.next.isEmpty() && scheduler.synced.isEmpty(), "a test never touches the schedule")
    }

    @Test
    fun `the test notification is for the meal that is next at this time of day`() = runTest {
        withShops()
        listOf("2026-10-26T00:00:00Z" to Meal.LUNCH, "2026-10-26T06:59:00Z" to Meal.LUNCH, "2026-10-26T07:00:00Z" to Meal.DINNER, "2026-10-26T15:30:00Z" to Meal.DINNER)
            .forEach { (time, meal) ->
                notifier.posted.clear()
                delivery(Instant.parse(time)).sendTest()
                assertEquals(meal, notifier.posted.single().meal, time)
            }
    }

    @Test
    fun `the test notification says what went wrong`() = runTest {
        assertEquals(TestOutcome.NOTHING_TO_RECOMMEND, delivery().sendTest())

        withShops()
        notifier.accepts = false
        assertEquals(TestOutcome.NOTIFICATIONS_BLOCKED, delivery().sendTest())

        pool.failure = ShopSourceException("offline", ShopSourceException.Kind.NETWORK)
        assertEquals(TestOutcome.FAILED, delivery().sendTest())
    }

    @Test
    fun `without a place the test notification is the hint to set one`() = runTest {
        settings.state.value = mealUser().copy(savedPlace = null)

        assertEquals(TestOutcome.SENT, delivery().sendTest())

        assertEquals("set a place", notifier.posted.single().text)
    }
}
