package com.koubeiradar.app.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.koubeiradar.app.MainActivity
import com.koubeiradar.app.R
import com.koubeiradar.app.meal.MealNotifier
import com.koubeiradar.app.meal.MealTexts
import com.koubeiradar.app.meal.NotificationContent
import com.koubeiradar.app.settings.Meal

/** The wording of the daily recommendation, from string resources. */
class ResourceMealTexts(context: Context) : MealTexts {
    private val resources = context.applicationContext.resources

    private fun name(meal: Meal): String = resources.getString(
        when (meal) {
            Meal.LUNCH -> R.string.meal_lunch
            Meal.DINNER -> R.string.meal_dinner
        },
    )

    override fun title(meal: Meal, firstShop: String, count: Int): String =
        if (count <= 1) {
            resources.getString(R.string.meal_notification_title_one, name(meal), firstShop)
        } else {
            resources.getString(R.string.meal_notification_title_many, name(meal), firstShop, count)
        }

    override fun shopLine(name: String, score: Int, rating: String?, price: String?, distance: String?): String {
        val facts = listOfNotNull(
            resources.getString(R.string.meal_fact_score, score),
            rating?.let { resources.getString(R.string.meal_fact_rating, it) },
            price?.let { resources.getString(R.string.meal_fact_price, it) },
            distance,
        ).joinToString(resources.getString(R.string.meal_fact_separator))
        return resources.getString(R.string.meal_notification_line, name, facts)
    }

    override fun summary(names: List<String>): String = names.joinToString(resources.getString(R.string.list_separator))

    override fun placeMissingTitle(meal: Meal): String = resources.getString(R.string.meal_notification_title_plain, name(meal))

    override fun placeMissingText(): String = resources.getString(R.string.meal_notification_no_place)
}

/** Posts the recommendation on its own channel; the user can silence the channel in the system settings. */
class AndroidMealNotifier(context: Context) : MealNotifier {
    private val appContext = context.applicationContext

    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(appContext.getString(R.string.meal_channel_name))
            .setDescription(appContext.getString(R.string.meal_channel_description))
            .build()
        NotificationManagerCompat.from(appContext).createNotificationChannel(channel)
    }

    override fun post(content: NotificationContent): Boolean {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return false
        if (manager.getNotificationChannelCompat(CHANNEL_ID)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) return false
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(openHome())
            .setAutoCancel(true)
        if (content.lines.isNotEmpty()) {
            builder.setStyle(NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) })
        }
        return try {
            manager.notify(NOTIFICATION_ID_BASE + content.meal.ordinal, builder.build())
            true
        } catch (e: SecurityException) {
            // The permission was withdrawn between the check above and the call.
            false
        }
    }

    private fun openHome(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ID = "meal_recommendations"

        /** One slot per meal: today's recommendation replaces yesterday's instead of piling up. */
        const val NOTIFICATION_ID_BASE = 4100
    }
}
