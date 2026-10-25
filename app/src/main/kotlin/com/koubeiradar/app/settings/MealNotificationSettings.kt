package com.koubeiradar.app.settings

import java.time.LocalTime

enum class Meal { LUNCH, DINNER }

/** Daily recommendation notifications. Times are whole minutes, local to the device; everything is off by default. */
data class MealNotificationSettings(
    val lunchEnabled: Boolean = false,
    val lunchTime: LocalTime = DEFAULT_LUNCH_TIME,
    val dinnerEnabled: Boolean = false,
    val dinnerTime: LocalTime = DEFAULT_DINNER_TIME,
    val weekdaysOnly: Boolean = false,
) {
    val anyEnabled: Boolean get() = lunchEnabled || dinnerEnabled

    fun isEnabled(meal: Meal): Boolean = when (meal) {
        Meal.LUNCH -> lunchEnabled
        Meal.DINNER -> dinnerEnabled
    }

    fun timeOf(meal: Meal): LocalTime = when (meal) {
        Meal.LUNCH -> lunchTime
        Meal.DINNER -> dinnerTime
    }

    fun withEnabled(meal: Meal, enabled: Boolean): MealNotificationSettings = when (meal) {
        Meal.LUNCH -> copy(lunchEnabled = enabled)
        Meal.DINNER -> copy(dinnerEnabled = enabled)
    }

    /** Seconds are dropped: the settings, the picker and the stored value all work in whole minutes. */
    fun withTime(meal: Meal, time: LocalTime): MealNotificationSettings {
        val minutes = time.withSecond(0).withNano(0)
        return when (meal) {
            Meal.LUNCH -> copy(lunchTime = minutes)
            Meal.DINNER -> copy(dinnerTime = minutes)
        }
    }

    companion object {
        val DEFAULT_LUNCH_TIME: LocalTime = LocalTime.of(11, 0)
        val DEFAULT_DINNER_TIME: LocalTime = LocalTime.of(17, 30)

        private const val MINUTES_PER_DAY = 24 * 60

        fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

        /** `null` for anything outside a day, so a damaged stored value falls back to the default. */
        fun timeOfMinute(minute: Int): LocalTime? =
            if (minute in 0 until MINUTES_PER_DAY) LocalTime.of(minute / 60, minute % 60) else null
    }
}
