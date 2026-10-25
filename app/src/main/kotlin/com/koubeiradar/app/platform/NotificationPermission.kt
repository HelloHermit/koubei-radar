package com.koubeiradar.app.platform

import android.content.Context
import androidx.core.app.NotificationManagerCompat

interface NotificationPermission {
    /** Whether the app may post notifications at all: the Android 13+ permission, and the user's app-level switch. */
    fun isGranted(): Boolean
}

class AndroidNotificationPermission(context: Context) : NotificationPermission {
    private val appContext = context.applicationContext

    override fun isGranted(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
}
