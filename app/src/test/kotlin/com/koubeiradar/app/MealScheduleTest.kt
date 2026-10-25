package com.koubeiradar.app

import com.koubeiradar.app.meal.MealSchedule
import com.koubeiradar.app.meal.QueuedRun
import com.koubeiradar.app.meal.SyncAction
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.ui.format.Formatters
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealScheduleTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val eleven = LocalTime.of(11, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = shanghai) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    @Test
    fun `before the time the trigger is today`() {
        assertEquals(at(2026, 10, 26, 11, 0), MealSchedule.nextTrigger(at(2026, 10, 26, 8, 15), eleven, weekdaysOnly = false))
    }

    @Test
    fun `after the time the trigger is tomorrow`() {
        assertEquals(at(2026, 10, 27, 11, 0), MealSchedule.nextTrigger(at(2026, 10, 26, 11, 1), eleven, weekdaysOnly = false))
    }

    @Test
    fun `exactly at the time counts as passed because the trigger is strictly after now`() {
        val now = at(2026, 10, 26, 11, 0)
        assertEquals(at(2026, 10, 27, 11, 0), MealSchedule.nextTrigger(now, eleven, weekdaysOnly = false))
        assertEquals(now, MealSchedule.nextTrigger(now.minusNanos(1), eleven, weekdaysOnly = false))
        assertEquals(at(2026, 10, 27, 11, 0), MealSchedule.nextTrigger(now.plusNanos(1), eleven, weekdaysOnly = false))
    }

    @Test
    fun `midnight and the last minute of the day`() {
        assertEquals(at(2026, 10, 26, 0, 0).withHour(11), MealSchedule.nextTrigger(at(2026, 10, 26, 0, 0), eleven, false))
        assertEquals(at(2026, 10, 27, 0, 0), MealSchedule.nextTrigger(at(2026, 10, 26, 23, 59), LocalTime.MIDNIGHT, false))
        assertEquals(at(2026, 10, 26, 23, 59), MealSchedule.nextTrigger(at(2026, 10, 26, 23, 58), LocalTime.of(23, 59), false))
    }

    @Test
    fun `the day rolls over months and years`() {
        assertEquals(at(2026, 11, 1, 11, 0), MealSchedule.nextTrigger(at(2026, 10, 31, 12, 0), eleven, false))
        assertEquals(at(2027, 1, 1, 11, 0), MealSchedule.nextTrigger(at(2026, 12, 31, 12, 0), eleven, false))
        assertEquals(at(2028, 2, 29, 11, 0), MealSchedule.nextTrigger(at(2028, 2, 28, 12, 0), eleven, false), "leap day")
    }

    @Test
    fun `weekdays only skips the weekend`() {
        val friday = at(2026, 10, 30, 12, 0)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(at(2026, 11, 2, 11, 0), MealSchedule.nextTrigger(friday, eleven, weekdaysOnly = true), "Friday noon -> Monday")
        assertEquals(at(2026, 10, 31, 11, 0), MealSchedule.nextTrigger(friday, eleven, weekdaysOnly = false), "without the rule -> Saturday")
        assertEquals(at(2026, 10, 30, 11, 0), MealSchedule.nextTrigger(friday.withHour(10), eleven, weekdaysOnly = true), "Friday morning is still Friday")
        assertEquals(at(2026, 11, 2, 11, 0), MealSchedule.nextTrigger(at(2026, 10, 31, 8, 0), eleven, weekdaysOnly = true), "Saturday morning")
        assertEquals(at(2026, 10, 26, 11, 0), MealSchedule.nextTrigger(at(2026, 10, 25, 23, 59), eleven, weekdaysOnly = true), "Sunday night -> Monday")
        assertEquals(at(2026, 11, 3, 11, 0), MealSchedule.nextTrigger(at(2026, 11, 2, 11, 0), eleven, weekdaysOnly = true), "Monday exactly at the time -> Tuesday")
    }

    @Test
    fun `a weekday in another time zone is judged by the local calendar`() {
        // 2026-10-30 18:00 in New York is already Saturday 06:00 in Shanghai.
        val newYork = ZoneId.of("America/New_York")
        val friday = at(2026, 10, 30, 18, 0, newYork)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(at(2026, 11, 2, 11, 0, newYork), MealSchedule.nextTrigger(friday, eleven, weekdaysOnly = true))
        assertEquals(newYork, MealSchedule.nextTrigger(friday, eleven, true).zone)
    }

    @Test
    fun `a time that does not exist on the spring forward day fires just after the gap`() {
        val newYork = ZoneId.of("America/New_York")
        val trigger = MealSchedule.nextTrigger(at(2026, 3, 7, 12, 0, newYork), LocalTime.of(2, 30), false)
        assertEquals(at(2026, 3, 8, 3, 30, newYork), trigger)
        assertEquals(at(2026, 3, 9, 2, 30, newYork), MealSchedule.nextTrigger(trigger, LocalTime.of(2, 30), false))
    }

    @Test
    fun `a time that happens twice on the fall back day fires once, at the first occurrence`() {
        val newYork = ZoneId.of("America/New_York")
        val trigger = MealSchedule.nextTrigger(at(2026, 10, 31, 12, 0, newYork), LocalTime.of(1, 30), false)
        assertEquals(at(2026, 11, 1, 1, 30, newYork), trigger)
        assertEquals(ZoneOffset.ofHours(-4), trigger.offset, "the first 01:30, still daylight time")
        assertEquals(at(2026, 11, 2, 1, 30, newYork), MealSchedule.nextTrigger(trigger, LocalTime.of(1, 30), false), "not again an hour later")
    }

    @Test
    fun `zones with a half hour offset keep the wall clock time`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val trigger = MealSchedule.nextTrigger(at(2026, 10, 26, 12, 0, kolkata), LocalTime.of(17, 30), false)
        assertEquals(at(2026, 10, 26, 17, 30, kolkata), trigger)
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), trigger.toInstant())
    }

    @Test
    fun `every start time gives a trigger after now, on the requested time, within four days`() {
        for (day in 0 until 14) {
            for (minuteOfDay in 0 until 24 * 60 step 37) {
                val now = at(2026, 10, 25, 0, 0).plusDays(day.toLong()).plusMinutes(minuteOfDay.toLong())
                for (weekdaysOnly in listOf(false, true)) {
                    val trigger = MealSchedule.nextTrigger(now, LocalTime.of(17, 30), weekdaysOnly)
                    assertTrue(trigger.isAfter(now), "$now / $weekdaysOnly")
                    assertEquals(LocalTime.of(17, 30), trigger.toLocalTime())
                    assertTrue(trigger.toLocalDate() <= now.toLocalDate().plusDays(if (weekdaysOnly) 3 else 1), "$now / $weekdaysOnly -> $trigger")
                    if (weekdaysOnly) assertTrue(trigger.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                }
            }
        }
    }

    private val now = Instant.parse("2026-10-26T03:00:00Z")
    private val tomorrow = Instant.parse("2026-10-27T03:00:00Z")

    @Test
    fun `an off meal needs no work, or has its work cancelled`() {
        assertEquals(SyncAction.KEEP, MealSchedule.syncAction(null, null, now))
        assertEquals(SyncAction.CANCEL, MealSchedule.syncAction(QueuedRun(running = false, triggerAt = tomorrow), null, now))
        assertEquals(SyncAction.CANCEL, MealSchedule.syncAction(QueuedRun(running = true, triggerAt = now), null, now))
    }

    @Test
    fun `an on meal without work gets work`() {
        assertEquals(SyncAction.ENQUEUE, MealSchedule.syncAction(null, tomorrow, now))
    }

    @Test
    fun `work that is right, running or due is never replaced`() {
        assertEquals(SyncAction.KEEP, MealSchedule.syncAction(QueuedRun(false, tomorrow), tomorrow, now), "already the right time")
        assertEquals(SyncAction.KEEP, MealSchedule.syncAction(QueuedRun(true, now), tomorrow, now), "running: it queues its own successor")
        assertEquals(SyncAction.KEEP, MealSchedule.syncAction(QueuedRun(false, now), tomorrow, now), "due right now")
        assertEquals(SyncAction.KEEP, MealSchedule.syncAction(QueuedRun(false, now.minusSeconds(600)), tomorrow, now), "overdue or waiting out a retry")
    }

    @Test
    fun `work for another time is replaced`() {
        assertEquals(SyncAction.ENQUEUE, MealSchedule.syncAction(QueuedRun(false, tomorrow.plusSeconds(3600)), tomorrow, now), "time changed")
        assertEquals(SyncAction.ENQUEUE, MealSchedule.syncAction(QueuedRun(false, now.plusSeconds(60)), tomorrow, now), "time zone changed")
        assertEquals(SyncAction.ENQUEUE, MealSchedule.syncAction(QueuedRun(false, null), tomorrow, now), "no readable record of its time")
    }
}

class MealSettingsModelTest {
    @Test
    fun `everything is off by default, at lunch and dinner time`() {
        val meals = MealNotificationSettings()
        assertEquals(false, meals.lunchEnabled)
        assertEquals(false, meals.dinnerEnabled)
        assertEquals(false, meals.weekdaysOnly)
        assertEquals(false, meals.anyEnabled)
        assertEquals(LocalTime.of(11, 0), meals.lunchTime)
        assertEquals(LocalTime.of(17, 30), meals.dinnerTime)
    }

    @Test
    fun `a meal is changed on its own`() {
        val meals = MealNotificationSettings().withEnabled(Meal.DINNER, true).withTime(Meal.LUNCH, LocalTime.of(12, 5))
        assertEquals(false, meals.isEnabled(Meal.LUNCH))
        assertEquals(true, meals.isEnabled(Meal.DINNER))
        assertEquals(LocalTime.of(12, 5), meals.timeOf(Meal.LUNCH))
        assertEquals(LocalTime.of(17, 30), meals.timeOf(Meal.DINNER))
        assertEquals(true, meals.anyEnabled)
    }

    @Test
    fun `times are kept in whole minutes`() {
        assertEquals(LocalTime.of(12, 5), MealNotificationSettings().withTime(Meal.LUNCH, LocalTime.of(12, 5, 59, 999)).lunchTime)
    }

    @Test
    fun `stored minutes outside a day are rejected, the others round trip`() {
        assertNull(MealNotificationSettings.timeOfMinute(-1))
        assertNull(MealNotificationSettings.timeOfMinute(24 * 60))
        assertEquals(LocalTime.MIDNIGHT, MealNotificationSettings.timeOfMinute(0))
        assertEquals(LocalTime.of(23, 59), MealNotificationSettings.timeOfMinute(24 * 60 - 1))
        assertEquals(17 * 60 + 30, MealNotificationSettings.minuteOfDay(LocalTime.of(17, 30)))
        assertEquals(LocalTime.of(7, 5), MealNotificationSettings.timeOfMinute(MealNotificationSettings.minuteOfDay(LocalTime.of(7, 5))))
    }

    @Test
    fun `times are shown on the 24 hour clock with two digits`() {
        assertEquals("07:05", Formatters.time(LocalTime.of(7, 5)))
        assertEquals("00:00", Formatters.time(LocalTime.MIDNIGHT))
        assertEquals("23:59", Formatters.time(LocalTime.of(23, 59, 30)))
    }
}
