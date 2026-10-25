package com.koubeiradar.app.smoke

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.MainActivity
import com.koubeiradar.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 设置 > 每日推荐 in the real app. The system permission dialog and the real clock/Doze are not covered (see README). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class MealSettingsSmokeTest : SmokeTestBase() {

    @Test
    fun theSectionShowsTheTimesAndTheControls() {
        launchAndAwaitHome()
        openSettings()

        scrollTo(string(R.string.settings_meals_test_summary))
        assertTrue(shows(string(R.string.settings_section_meals)))
        assertTrue(shows(string(R.string.settings_meals_subtitle, "午饭", "11:00", "晚饭", "17:30")))
        assertTrue(shows(string(R.string.settings_meals_lunch)))
        assertTrue(shows(string(R.string.settings_meals_dinner)))
        assertTrue(shows("11:00") && shows("17:30"))
        assertTrue(shows(string(R.string.settings_meals_weekdays)))
        assertTrue(shows(string(R.string.settings_meals_test)))
        assertTrue(showsContaining("大约在设定时间前后"))
        assertTrue("no place has been set yet", shows(string(R.string.settings_meals_place_none)))
        assertFalse("nothing is on, so there is nothing to warn about", shows(string(R.string.settings_meals_blocked)))
        screenshot("settings-meals")
    }

    @Test
    fun turningLunchOnStoresItAndLeavesTheRestAlone() {
        launchAndAwaitHome()
        openSettings()
        scrollTo(string(R.string.settings_meals_test_summary))

        // The switch sits above the row scrolled to; a click at an off-screen node's coordinates would silently miss.
        compose.onNodeWithContentDescription(string(R.string.settings_meals_lunch)).performScrollTo().performClick()
        settle()

        waitFor("lunch to be stored as on") { meals().lunchEnabled }
        assertFalse(meals().dinnerEnabled)
        assertFalse(meals().weekdaysOnly)
        assertTrue(shows(string(R.string.settings_meals_subtitle, "午饭", "11:00", "晚饭", "17:30")))
    }

    @Test
    fun theTimeOfAMealIsPickedInADialog() {
        launchAndAwaitHome()
        openSettings()
        scrollTo(string(R.string.settings_meals_test_summary))

        clickText("11:00")
        waitForText(string(R.string.settings_meals_time_dialog_title, "午饭"))
        clickText(string(R.string.action_cancel))
        waitForGone(string(R.string.settings_meals_time_dialog_title, "午饭"))
        assertEquals("cancelling changes nothing", "11:00", meals().lunchTime.toString())
    }

    @Test
    fun theTestButtonPostsTheHintToSetAPlaceWhenThereIsNone() {
        launchAndAwaitHome()
        openSettings()

        clickText(string(R.string.settings_meals_test))
        waitForText(string(R.string.settings_meals_msg_sent))

        val notification = shadowOf(application.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(string(R.string.meal_notification_no_place), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().endsWith("推荐"))
    }

    @Test
    fun tappingTheNotificationShowsTheHomeTabEvenFromSettings() {
        launchAndAwaitHome()
        openSettings()

        deliverNewIntent(Intent(application, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_HOME))

        awaitHome()
        assertFalse(shows(string(R.string.settings_section_data)))
    }

    private fun meals() = runBlocking(Dispatchers.Default) { application.container.settings.settings.first().meals }

    private fun scrollTo(text: String) {
        compose.onNodeWithText(text).performScrollTo()
        settle()
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription(string(R.string.home_action_settings)).performClick()
        waitForText(string(R.string.settings_section_data))
    }
}
