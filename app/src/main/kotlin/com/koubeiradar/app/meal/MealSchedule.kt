package com.koubeiradar.app.meal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** What is queued for one meal: a run in progress, or one waiting for [triggerAt]. */
data class QueuedRun(val running: Boolean, val triggerAt: Instant?)

enum class SyncAction { KEEP, ENQUEUE, CANCEL }

object MealSchedule {
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /**
     * The first moment strictly after [now] at which the local wall-clock time is [time] (in [now]'s zone), on a weekday
     * only when [weekdaysOnly]. At exactly [time] the next trigger is the following day's. A [time] that does not exist
     * on a day with a clock change (the spring-forward gap) fires at the first valid moment after it.
     */
    fun nextTrigger(now: ZonedDateTime, time: LocalTime, weekdaysOnly: Boolean): ZonedDateTime {
        var date = now.toLocalDate()
        while (true) {
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now) && (!weekdaysOnly || date.dayOfWeek !in WEEKEND)) return candidate
            date = date.plusDays(1)
        }
    }

    /**
     * Whether the queued work for a meal has to change so that it fires at [expected] (`null` = the meal is off).
     * Work that is running, or due and about to run (or waiting out a retry), is never replaced: replacing it would
     * cancel the very run that is about to post the notification.
     */
    fun syncAction(queued: QueuedRun?, expected: Instant?, now: Instant): SyncAction = when {
        expected == null -> if (queued == null) SyncAction.KEEP else SyncAction.CANCEL
        queued == null -> SyncAction.ENQUEUE
        queued.running -> SyncAction.KEEP
        queued.triggerAt == expected -> SyncAction.KEEP
        queued.triggerAt?.isAfter(now) == false -> SyncAction.KEEP
        else -> SyncAction.ENQUEUE
    }
}
