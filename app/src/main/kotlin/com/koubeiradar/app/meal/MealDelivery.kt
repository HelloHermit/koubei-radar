package com.koubeiradar.app.meal

import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import com.koubeiradar.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/** Posts a notification. `false` when the system will not show it (notifications or the channel are switched off). */
interface MealNotifier {
    fun post(content: NotificationContent): Boolean
}

/** Keeps one pending run per enabled meal. */
interface MealScheduler {
    /** Brings the queued runs in line with [meals] without disturbing a run that is about to happen. */
    suspend fun sync(meals: MealNotificationSettings)

    /** Queues the next run of [meal] unconditionally (or none when it is off); called when a run is over. */
    fun scheduleNext(meal: Meal, meals: MealNotificationSettings)
}

enum class TestOutcome { SENT, NOTHING_TO_RECOMMEND, NOTIFICATIONS_BLOCKED, FAILED }

fun interface MealTestSender {
    suspend fun sendTest(): TestOutcome
}

/** What a scheduled run does, and the "send one now" button, on top of [MealRecommendation]. */
class MealDelivery(
    private val recommendation: MealRecommendation,
    private val notifier: MealNotifier,
    private val scheduler: MealScheduler,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : MealTestSender {

    /**
     * One run of the scheduled work for [meal]; [attempt] counts retries from 0, [scheduledAt] is when the run was due.
     * Returns whether it should be retried; otherwise the next day's run has been queued, also when posting failed. A run
     * that was cancelled queues nothing: whoever cancelled it (a settings change, WorkManager) owns what happens next.
     */
    @Throws(CancellationException::class)
    suspend fun runScheduled(meal: Meal, attempt: Int, scheduledAt: Instant?): Boolean {
        val retry = try {
            attempt(meal, attempt, scheduledAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scheduleNext(meal)
            throw e
        }
        if (!retry) scheduleNext(meal)
        return retry
    }

    override suspend fun sendTest(): TestOutcome {
        val meal = if (LocalTime.now(clock) < DINNER_FROM) Meal.LUNCH else Meal.DINNER
        return when (val outcome = recommendation.compose(meal)) {
            is MealOutcome.Notify -> if (notifier.post(outcome.content)) TestOutcome.SENT else TestOutcome.NOTIFICATIONS_BLOCKED
            MealOutcome.NothingToRecommend -> TestOutcome.NOTHING_TO_RECOMMEND
            is MealOutcome.SourceFailed -> TestOutcome.FAILED
        }
    }

    private suspend fun attempt(meal: Meal, attempt: Int, scheduledAt: Instant?): Boolean {
        val enabled = settings.settings.first().meals.isEnabled(meal)
        // A run that WorkManager only got to hours late (device off, long Doze) would announce a meal that is over.
        val stale = scheduledAt != null && Duration.between(scheduledAt, clock.instant()) > MAX_LATENESS
        if (!enabled || stale) return false
        return when (val outcome = recommendation.compose(meal)) {
            is MealOutcome.Notify -> {
                notifier.post(outcome.content)
                false
            }

            MealOutcome.NothingToRecommend -> false
            is MealOutcome.SourceFailed -> outcome.retryable && attempt < MAX_RETRIES
        }
    }

    private suspend fun scheduleNext(meal: Meal) {
        scheduler.scheduleNext(meal, settings.settings.first().meals)
    }

    companion object {
        /** After the first failed run: two more tries, then give up for the day. */
        const val MAX_RETRIES = 2
        val MAX_LATENESS: Duration = Duration.ofMinutes(90)

        /** The test notification is for the meal that is next at this time of day. */
        private val DINNER_FROM: LocalTime = LocalTime.of(15, 0)
    }
}
