package com.koubeiradar.app.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.koubeiradar.core.model.Platform
import com.koubeiradar.data.DeliveryApp
import com.koubeiradar.data.DeliveryApps

enum class OrderOutcome {
    /** The delivery app was started; the shop name is on the clipboard for the app's search box. */
    OPENED,

    /** The shop name was copied, but the delivery app is not installed. */
    NOT_INSTALLED,

    /** The app is installed but could not be started. */
    FAILED,
}

/** Hands a shop over to a delivery app: copy its name, then launch the app (no private deep links). */
interface DeliveryLauncher {
    fun isInstalled(app: DeliveryApp): Boolean

    fun order(shopName: String, app: DeliveryApp): OrderOutcome
}

/** Apps the shop is known to be orderable on come first; the original order is otherwise kept. */
fun orderedDeliveryApps(shopPlatforms: Set<Platform>, apps: List<DeliveryApp> = DeliveryApps.all): List<DeliveryApp> =
    apps.sortedBy { if (it.platform in shopPlatforms) 0 else 1 }

class AndroidDeliveryLauncher(context: Context) : DeliveryLauncher {
    private val appContext = context.applicationContext

    override fun isInstalled(app: DeliveryApp): Boolean = launchIntent(app) != null

    override fun order(shopName: String, app: DeliveryApp): OrderOutcome {
        copyToClipboard(shopName)
        val intent = launchIntent(app) ?: return OrderOutcome.NOT_INSTALLED
        return try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OrderOutcome.OPENED
        } catch (e: ActivityNotFoundException) {
            OrderOutcome.FAILED
        } catch (e: SecurityException) {
            OrderOutcome.FAILED
        }
    }

    private fun launchIntent(app: DeliveryApp): Intent? =
        app.packageNames.firstNotNullOfOrNull { appContext.packageManager.getLaunchIntentForPackage(it) }

    private fun copyToClipboard(text: String) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        } catch (e: SecurityException) {
            // Clipboard access can be denied (e.g. restricted profiles); launching the app is still useful.
        }
    }

    private companion object {
        const val CLIP_LABEL = "shop_name"
    }
}
