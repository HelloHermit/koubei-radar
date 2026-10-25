package com.koubeiradar.app.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.koubeiradar.app.KoubeiApplication
import com.koubeiradar.app.meal.MealSchedule
import com.koubeiradar.app.meal.MealScheduler
import com.koubeiradar.app.meal.QueuedRun
import com.koubeiradar.app.meal.SyncAction
import com.koubeiradar.app.settings.Meal
import com.koubeiradar.app.settings.MealNotificationSettings
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal object MealWork {
    const val KEY_MEAL = "meal"
    private const val TRIGGER_TAG = "trigger:"

    fun uniqueName(meal: Meal): String = "meal_" + meal.name.lowercase()

    fun triggerTag(at: Instant): String = TRIGGER_TAG + at.toEpochMilli()

    /** When the run was due, as recorded on the work request; `null` when there is no (readable) record. */
    fun triggerOf(tags: Set<String>): Instant? =
        tags.firstNotNullOfOrNull { tag -> tag.removePrefix(TRIGGER_TAG).takeIf { it != tag }?.toLongOrNull() }?.let(Instant::ofEpochMilli)
}

/**
 * One unique, delayed, one-time request per meal. WorkManager (not exact alarms) keeps them across reboots; the system
 * may run them a little late under Doze, which is why the UI says "around". Each run queues the next day's.
 */
class WorkManagerMealScheduler(context: Context, private val clock: Clock) : MealScheduler {
    private val appContext = context.applicationContext
    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    override suspend fun sync(meals: MealNotificationSettings) {
        val now = clock.instant()
        for (meal in Meal.entries) {
            val trigger = nextTrigger(meal, meals)
            when (MealSchedule.syncAction(queuedRun(meal), trigger?.toInstant(), now)) {
                SyncAction.KEEP -> Unit
                SyncAction.CANCEL -> workManager.cancelUniqueWork(MealWork.uniqueName(meal))
                SyncAction.ENQUEUE -> trigger?.let { enqueue(meal, it) }
            }
        }
    }

    override fun scheduleNext(meal: Meal, meals: MealNotificationSettings) {
        val trigger = nextTrigger(meal, meals)
        if (trigger == null) workManager.cancelUniqueWork(MealWork.uniqueName(meal)) else enqueue(meal, trigger)
    }

    private fun nextTrigger(meal: Meal, meals: MealNotificationSettings): ZonedDateTime? =
        if (meals.isEnabled(meal)) MealSchedule.nextTrigger(ZonedDateTime.now(clock), meals.timeOf(meal), meals.weekdaysOnly) else null

    private suspend fun queuedRun(meal: Meal): QueuedRun? =
        workManager.getWorkInfosForUniqueWorkFlow(MealWork.uniqueName(meal)).first()
            .firstOrNull { !it.state.isFinished }
            ?.let { QueuedRun(running = it.state == WorkInfo.State.RUNNING, triggerAt = MealWork.triggerOf(it.tags)) }

    private fun enqueue(meal: Meal, trigger: ZonedDateTime) {
        val request = OneTimeWorkRequestBuilder<MealWorker>()
            .setInitialDelay(Duration.between(clock.instant(), trigger.toInstant()))
            .setInputData(workDataOf(MealWork.KEY_MEAL to meal.name))
            .addTag(MealWork.triggerTag(trigger.toInstant()))
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(MealWork.uniqueName(meal), ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val RETRY_BACKOFF_MINUTES = 5L
    }
}

/** Thin wrapper: everything it decides is in [com.koubeiradar.app.meal.MealDelivery]. */
class MealWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val meal = inputData.getString(MealWork.KEY_MEAL)?.let { name -> Meal.entries.firstOrNull { it.name == name } }
            ?: return Result.failure()
        val container = (applicationContext as? KoubeiApplication)?.container ?: return Result.failure()
        val retry = container.mealDelivery.runScheduled(meal, runAttemptCount, MealWork.triggerOf(tags))
        return if (retry) Result.retry() else Result.success()
    }
}
