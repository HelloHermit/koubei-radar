package com.koubeiradar.app.smoke

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.koubeiradar.app.KoubeiApplication
import com.koubeiradar.app.MainActivity
import com.koubeiradar.app.meal.NotificationContent
import com.koubeiradar.app.platform.AndroidMealNotifier
import com.koubeiradar.app.platform.ResourceMealTexts
import com.koubeiradar.app.platform.WorkManagerMealScheduler
import com.koubeiradar.app.service.DEMO_CENTER
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.settings.SavedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The Android side of the daily recommendation, on the JVM: wording from the real string resources, the notification and
 * its channel, the WorkManager requests, and the worker running them end to end against the demo data.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = InMemoryKeyApplication::class)
class MealPlatformTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as KoubeiApplication).container
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    @Before
    fun prepare() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // The container creates the channel from a background coroutine at start; wait for it so nothing races with the tests.
        await("the notification channel") { manager.getNotificationChannel(AndroidMealNotifier.CHANNEL_ID) != null }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun lunchWork(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("meal_lunch").get()

    private fun pendingLunch(): WorkInfo? = lunchWork().firstOrNull { it.state == WorkInfo.State.ENQUEUED }

    private fun triggerOf(info: WorkInfo): Instant =
        Instant.ofEpochMilli(info.tags.first { it.startsWith("trigger:") }.removePrefix("trigger:").toLong())

    private fun updateSettings(transform: (com.koubeiradar.app.settings.AppSettings) -> com.koubeiradar.app.settings.AppSettings) =
        runBlocking(Dispatchers.Default) { container.settings.update(transform) }

    // ---- wording ------------------------------------------------------------------------------------------------

    @Test
    fun `the notification reads like the examples`() {
        val texts = ResourceMealTexts(context)

        assertEquals("午饭推荐：晨光粥铺(科技园店) 等 3 家", texts.title(Meal.LUNCH, "晨光粥铺(科技园店)", 3))
        assertEquals("晚饭推荐：老张面馆", texts.title(Meal.DINNER, "老张面馆", 1))
        assertEquals("晨光粥铺(科技园店)：86分 · ★4.8 · 人均¥16 · 1.3km", texts.shopLine("晨光粥铺(科技园店)", 86, "4.8", "¥16", "1.3km"))
        assertEquals("无名小店：55分", texts.shopLine("无名小店", 55, null, null, null))
        assertEquals("店甲、店乙", texts.summary(listOf("店甲", "店乙")))
        assertEquals("午饭推荐", texts.placeMissingTitle(Meal.LUNCH))
        assertEquals("打开口碑雷达设置位置，就能收到每日推荐", texts.placeMissingText())
    }

    // ---- notification -------------------------------------------------------------------------------------------

    @Test
    fun `the channel is called 每日推荐 and has the default importance`() {
        val notifier = AndroidMealNotifier(context)
        notifier.ensureChannel()
        notifier.ensureChannel()

        val channel = manager.getNotificationChannel(AndroidMealNotifier.CHANNEL_ID)
        assertEquals("meal_recommendations", channel.id)
        assertEquals("每日推荐", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `a recommendation is shown with its list and opens the home tab when tapped`() {
        val notifier = AndroidMealNotifier(context)
        notifier.ensureChannel()

        assertTrue(notifier.post(NotificationContent(Meal.LUNCH, "午饭推荐：甲 等 2 家", "甲、乙", listOf("甲：80分", "乙：70分"))))

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("午饭推荐：甲 等 2 家", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("甲、乙", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("甲：80分", "乙：70分"), notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() })
        assertEquals(AndroidMealNotifier.CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.ACTION_OPEN_HOME, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `today's recommendation replaces yesterday's, and the meals have a slot each`() {
        val notifier = AndroidMealNotifier(context)
        notifier.ensureChannel()

        notifier.post(NotificationContent(Meal.LUNCH, "昨天", "x"))
        notifier.post(NotificationContent(Meal.LUNCH, "今天", "x"))
        notifier.post(NotificationContent(Meal.DINNER, "晚上", "x"))

        val titles = shadowOf(manager).allNotifications.map { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }
        assertEquals(setOf("今天", "晚上"), titles.toSet())
        assertEquals(2, titles.size)
    }

    @Test
    fun `nothing is posted, and the caller is told, when notifications are off`() {
        val notifier = AndroidMealNotifier(context)
        notifier.ensureChannel()

        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(notifier.post(NotificationContent(Meal.LUNCH, "t", "x")))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `nothing is posted, and the caller is told, when the user silenced the channel`() {
        manager.deleteNotificationChannel(AndroidMealNotifier.CHANNEL_ID)
        manager.createNotificationChannel(NotificationChannel(AndroidMealNotifier.CHANNEL_ID, "每日推荐", NotificationManager.IMPORTANCE_NONE))

        assertFalse(AndroidMealNotifier(context).post(NotificationContent(Meal.LUNCH, "t", "x")))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    // ---- work requests ------------------------------------------------------------------------------------------

    @Test
    fun `turning a meal on queues one run at the next set time, changing the time replaces it, turning it off cancels it`() {
        assertTrue(lunchWork().none { !it.state.isFinished })

        updateSettings { it.copy(meals = MealNotificationSettings(lunchEnabled = true)) }
        await("the lunch run to be queued") { pendingLunch() != null }
        val first = checkNotNull(pendingLunch())
        val now = Instant.now()
        val trigger = ZonedDateTime.ofInstant(triggerOf(first), ZoneId.systemDefault())
        assertTrue(trigger.toInstant().isAfter(now.minusSeconds(5)) && trigger.toInstant().isBefore(now.plusSeconds(24 * 3600 + 5)))
        assertEquals(LocalTime.of(11, 0), trigger.toLocalTime())
        assertEquals("only lunch is on", emptyList<WorkInfo>(), workManager.getWorkInfosForUniqueWork("meal_dinner").get().filter { !it.state.isFinished })

        // Starting the app again with the same settings leaves the queued run alone.
        runBlocking(Dispatchers.Default) { WorkManagerMealScheduler(context, Clock.systemDefaultZone()).sync(MealNotificationSettings(lunchEnabled = true)) }
        assertEquals(first.id, pendingLunch()?.id)

        updateSettings { it.copy(meals = it.meals.withTime(Meal.LUNCH, LocalTime.of(12, 30))) }
        await("the lunch run to be replaced") { pendingLunch()?.id.let { it != null && it != first.id } }
        assertEquals(LocalTime.of(12, 30), ZonedDateTime.ofInstant(triggerOf(checkNotNull(pendingLunch())), ZoneId.systemDefault()).toLocalTime())

        updateSettings { it.copy(meals = it.meals.withEnabled(Meal.LUNCH, false)) }
        await("the lunch run to be cancelled") { pendingLunch() == null }
    }

    @Test
    fun `a due run posts the recommendation from the saved place and queues the next day's run`() {
        updateSettings {
            it.copy(
                savedPlace = SavedPlace("", DEMO_CENTER, fromDevice = true),
                meals = MealNotificationSettings(lunchEnabled = true),
            )
        }
        await("the lunch run to be queued") { pendingLunch() != null }
        val due = checkNotNull(pendingLunch())

        checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setInitialDelayMet(due.id)

        // CoroutineWorker runs on Dispatchers.Default, not on the executor the test driver controls.
        await("the recommendation") { shadowOf(manager).allNotifications.isNotEmpty() }
        val notification = shadowOf(manager).allNotifications.single()
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("午饭推荐："))
        assertEquals(3, notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.size)
        await("tomorrow's run to be queued") { pendingLunch()?.id.let { it != null && it != due.id } }
        assertNotEquals(due.id, pendingLunch()?.id)
    }
}
