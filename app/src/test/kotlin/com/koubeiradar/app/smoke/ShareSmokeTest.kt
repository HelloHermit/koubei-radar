package com.koubeiradar.app.smoke

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.ImportLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Everything that reaches the exported activity from another app: shared text, shared images, hostile extras. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)
class ShareSmokeTest : SmokeTestBase() {

    private val shopList = "1. 鸿运楼老字号烤鸭(总店) 4.7分 月售4200 人均86\n2. 晨光粥铺(科技园店) 4.8分 月售160 人均16\n3. 巷尾一碗面 4.6分 人均18"

    @Test
    fun sharedTextOpensTheImportScreenWithTheTextPrefilled() {
        launch(sendIntent("text/plain", text = shopList))

        waitForText(string(R.string.import_howto_title))
        waitForText(shopList)
        waitForText(string(R.string.import_text_counter, shopList.length, ImportLimits.MAX_TEXT_CHARS))
        compose.onAllNodes(hasText("导入") and hasClickAction())[0].assertIsSelected()
        assertFalse("the home list is not what the user sees", shows(string(R.string.app_name)))
        assertNull("the share was consumed by the import screen", application.container.shareInbox.pending.value)
        screenshot("import-shared")
    }

    @Test
    fun sharedTextBeyondTheDocumentedCapIsCappedNotCrashing() {
        val huge = "鸿运楼老字号烤鸭 4.7分 人均86 月售4200\n".repeat(3_000)
        assertTrue(huge.length > ImportLimits.MAX_TEXT_CHARS * 2)

        launch(sendIntent("text/plain", text = huge))

        waitForText(string(R.string.import_howto_title))
        waitForText(string(R.string.import_text_counter, ImportLimits.MAX_TEXT_CHARS, ImportLimits.MAX_TEXT_CHARS))
        assertEquals("the edit field holds exactly the cap", ImportLimits.MAX_TEXT_CHARS, allTexts().maxOf { it.length })
        assertTrue(shows(string(R.string.import_action_recognize)))
    }

    @Test
    fun shareArrivingWhileTheAppIsRunningOpensTheImportScreen() {
        launchAndAwaitHome()

        deliverNewIntent(sendIntent("text/plain", text = shopList))

        waitForText(string(R.string.import_howto_title))
        waitForText(shopList)
        assertNull(application.container.shareInbox.pending.value)
    }

    @Test
    fun shareReplacesWhatWasAlreadyTypedOnTheImportScreen() {
        launchAndAwaitHome()
        clickTab("导入")
        waitForText(string(R.string.import_howto_title))
        compose.onNode(hasSetTextAction()).performTextInput("OLD DRAFT")
        compose.waitForIdle()
        waitForText("OLD DRAFT")
        clickTab("推荐")
        awaitHome()

        deliverNewIntent(sendIntent("text/plain", text = shopList))

        waitForText(string(R.string.import_howto_title))
        waitForText(shopList)
        assertFalse(shows("OLD DRAFT"))
    }

    @Test
    fun theSharedTextIsNotAppliedAgainAfterARecreate() {
        launch(sendIntent("text/plain", text = shopList))
        waitForText(shopList)
        compose.onNode(hasSetTextAction()).performTextInput("EDITED ")
        compose.waitForIdle()
        waitFor("the edit") { allTexts().any { it.contains("EDITED ") } }

        recreateActivity()

        waitForText(string(R.string.import_howto_title))
        waitFor("the edited text to survive") { allTexts().any { it.contains("EDITED ") && it.contains("鸿运楼老字号烤鸭") } }
    }

    @Test
    fun hostileExtrasAreIgnoredAndTheAppOpensNormally() {
        val intent = sendIntent("text/plain").apply {
            putExtra(Intent.EXTRA_TEXT, 12345) // not a CharSequence
            putExtra(Intent.EXTRA_STREAM, "this is not a Uri")
        }
        launch(intent)

        awaitHome()
        assertFalse(shows(string(R.string.import_howto_title)))
    }

    @Test
    fun aFileUriIsRejectedAndOnlyContentUrisAreAccepted() {
        launch(sendIntent("image/png", stream = Uri.parse("file:///data/data/com.koubeiradar.app/files/imported_shops.json")))
        awaitHome()
        assertFalse("a file:// share must not even open the import screen", shows(string(R.string.import_howto_title)))
    }

    @Test
    fun manySharedImagesAreCappedAtTheImageLimit() {
        val uris = ArrayList((1..9).map { Uri.parse("content://share.example/image/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setClass(application, com.koubeiradar.app.MainActivity::class.java)
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        launch(intent)

        waitForText(string(R.string.import_howto_title))
        waitForText(string(R.string.import_images_selected, ImportLimits.MAX_IMAGES, ImportLimits.MAX_IMAGES))
    }

    @Test
    fun aSharedReadableImageShowsAThumbnail() {
        val uri = Uri.parse("content://share.example/image/readable")
        val png = tinyPng()
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(png) }

        launch(sendIntent("image/png", stream = uri))

        waitForText(string(R.string.import_howto_title))
        waitForText(string(R.string.import_images_selected, 1, ImportLimits.MAX_IMAGES))
        waitFor("the thumbnail") { compose.onAllNodesWithContentDescription(string(R.string.cd_screenshot, 1)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A provider in another app may throw whatever it likes; Binder re-throws IllegalArgumentException & co. in our process. */
    @Test
    fun aSharedImageFromAProviderThatThrowsDoesNotCrashTheImportScreen() {
        Robolectric.setupContentProvider(HostileImageProvider::class.java, "hostile.example")

        launch(sendIntent("image/png", stream = Uri.parse("content://hostile.example/image/1")))

        waitForText(string(R.string.import_howto_title))
        waitForText(string(R.string.import_images_selected, 1, ImportLimits.MAX_IMAGES))
        // Give the thumbnail loader (a background coroutine) time to hit the provider and fail.
        repeat(20) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
        assertTrue("the screen is still alive", shows(string(R.string.import_action_recognize)))
    }

    private fun tinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 90, 30)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

/** Stands in for a sloppy or hostile app that granted us a `content://` URI and then misbehaves when it is opened. */
class HostileImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        throw IllegalArgumentException("Unknown URI $uri")

    override fun getType(uri: Uri): String? = throw IllegalArgumentException("Unknown URI $uri")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = throw IllegalArgumentException("Unknown URI $uri")
}
