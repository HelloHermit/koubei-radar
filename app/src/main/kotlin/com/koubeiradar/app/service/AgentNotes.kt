package com.koubeiradar.app.service

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Situational facts the agent cannot know by itself. The text goes to the model, not to the screen. */
object AgentNotes {
    enum class MealPeriod(val label: String) {
        BREAKFAST("早餐时间"),
        LUNCH("午餐时间"),
        AFTERNOON_TEA("下午茶时间"),
        DINNER("晚餐时间"),
        LATE_NIGHT("夜宵时间"),
    }

    private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    fun mealPeriod(time: LocalTime): MealPeriod = when {
        time >= LocalTime.of(5, 0) && time < LocalTime.of(10, 30) -> MealPeriod.BREAKFAST
        time >= LocalTime.of(10, 30) && time < LocalTime.of(14, 0) -> MealPeriod.LUNCH
        time >= LocalTime.of(14, 0) && time < LocalTime.of(17, 0) -> MealPeriod.AFTERNOON_TEA
        time >= LocalTime.of(17, 0) && time < LocalTime.of(20, 30) -> MealPeriod.DINNER
        else -> MealPeriod.LATE_NIGHT
    }

    /** `现在是 12:10（午餐时间）` */
    fun forNow(now: LocalDateTime): String =
        "现在是 ${now.format(CLOCK_FORMAT)}（${mealPeriod(now.toLocalTime()).label}）"
}
