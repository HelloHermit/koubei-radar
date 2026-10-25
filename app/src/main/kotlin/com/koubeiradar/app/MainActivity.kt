package com.koubeiradar.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.koubeiradar.app.platform.ShareIntake
import com.koubeiradar.app.ui.KoubeiApp
import com.koubeiradar.app.ui.theme.KoubeiTheme

/** The only activity (and the only exported component): hosts the Compose UI and receives shares from other apps. */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as KoubeiApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation the original intent is delivered again; only a fresh launch carries a new share.
        if (savedInstanceState == null) offerShare(intent)
        setContent {
            KoubeiTheme {
                KoubeiApp(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_HOME) container.homeRequests.request()
        offerShare(intent)
    }

    private fun offerShare(intent: Intent?) {
        ShareIntake.parse(intent)?.let(container.shareInbox::offer)
    }

    companion object {
        /** Sent by the daily recommendation notification: show the home tab. */
        const val ACTION_OPEN_HOME = "com.koubeiradar.app.action.OPEN_HOME"
    }
}
