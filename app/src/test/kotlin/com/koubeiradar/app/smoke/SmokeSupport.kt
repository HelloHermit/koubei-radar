package com.koubeiradar.app.smoke

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.IdlingPolicies
import com.koubeiradar.app.ui.components.MascotMood
import com.koubeiradar.app.ui.components.mascotTag
import com.koubeiradar.app.KoubeiApplication
import com.koubeiradar.app.MainActivity
import com.koubeiradar.app.R
import com.koubeiradar.core.model.Verdict
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared plumbing for the runtime smoke tests. They start the REAL [MainActivity] with the REAL [KoubeiApplication] and
 * [com.koubeiradar.app.AppContainer] (no fakes) on the JVM with Robolectric, and drive the real Compose UI.
 *
 * Concrete classes carry the Robolectric annotations (identical everywhere so the sandbox is shared):
 * `@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = SmokeTestBase.PHONE)`.
 */
abstract class SmokeTestBase {
    /**
     * Bounds a runaway test. JUnit's `Timeout` rule would run the test on another thread, which Robolectric cannot
     * do, so this interrupts the test thread (after dumping every thread) once [TEST_BUDGET_SECONDS] have passed.
     */
    @get:Rule(order = Int.MIN_VALUE)
    val budget = TestBudget(TEST_BUDGET_SECONDS)

    @get:Rule
    val compose = createEmptyComposeRule()

    protected var scenario: ActivityScenario<MainActivity>? = null

    private var intentBeforeNewIntent: Intent? = null

    protected val application: KoubeiApplication get() = ApplicationProvider.getApplicationContext()

    /** Robolectric grants every manifest permission by default; the first-run contract is "no permission granted". */
    @Before
    fun prepare() {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // "Waiting for the UI to become idle" gives up after IDLE_MS instead of the 60 s default.
        IdlingPolicies.setMasterPolicyTimeout(IDLE_MS, TimeUnit.MILLISECONDS)
        IdlingPolicies.setIdlingResourceTimeout(IDLE_MS, TimeUnit.MILLISECONDS)
    }

    @After
    fun closeActivity() {
        // MainActivity.onNewIntent calls setIntent(newIntent), the standard singleTop handling; with the changed intent
        // Robolectric's ActivityScenario can no longer finish the activity (close() hangs), so put the original back.
        val original = intentBeforeNewIntent
        if (original != null) scenario?.onActivity { it.intent = original }
        intentBeforeNewIntent = null
        diagnosing("closing the activity") { scenario?.close() }
        scenario = null
    }

    // ---- launching ----------------------------------------------------------------------------------------------

    /** Launches the real activity; [intent] defaults to the launcher intent. */
    protected fun launch(intent: Intent = Intent(application, MainActivity::class.java)): ActivityScenario<MainActivity> =
        ActivityScenario.launch<MainActivity>(intent).also { scenario = it }

    /** Launcher start, then waits until the home screen shows ranked shops. */
    protected fun launchAndAwaitHome() {
        launch()
        awaitHome()
    }

    protected fun awaitHome() {
        waitForText(string(R.string.app_name))
        waitForAnyText(VERDICT_LABELS)
    }

    /** An explicit-component `ACTION_SEND` intent, as the system would deliver a share to the exported activity. */
    protected fun sendIntent(mime: String, text: CharSequence? = null, stream: Uri? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            setClass(application, MainActivity::class.java)
            type = mime
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            if (stream != null) putExtra(Intent.EXTRA_STREAM, stream)
        }

    /** `MainActivity.onNewIntent` is protected; the system calls it for a share that arrives while the app is running. */
    protected fun deliverNewIntent(intent: Intent) {
        scenario!!.onActivity { activity ->
            if (intentBeforeNewIntent == null) intentBeforeNewIntent = activity.intent
            val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            method.isAccessible = true
            method.invoke(activity, intent)
        }
        settle()
    }

    protected fun recreateActivity() {
        diagnosing("recreating the activity") { scenario!!.recreate() }
        settle()
    }

    protected fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    /** Waits (bounded by [IDLE_MS]) until Compose is idle; if that takes suspiciously long, dumps what the UI thread is doing. */
    protected fun settle() = diagnosing("waiting for the UI to become idle") { compose.waitForIdle() }

    /** Runs [block]; a daemon thread prints the test thread's stack if it is still inside after a few seconds. */
    protected fun <T> diagnosing(what: String, afterMillis: Long = 4_000, block: () -> T): T {
        val testThread = Thread.currentThread()
        val finished = AtomicBoolean(false)
        val sampler = Thread {
            try {
                Thread.sleep(afterMillis)
                var samples = 0
                while (!finished.get() && samples++ < 3) {
                    System.err.println("STILL BUSY after $afterMillis ms ($what); test thread stack:")
                    testThread.stackTrace.take(45).forEach { System.err.println("    at $it") }
                    Thread.sleep(700)
                }
            } catch (e: InterruptedException) {
                // finished
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            return block()
        } finally {
            finished.set(true)
            sampler.interrupt()
        }
    }

    // ---- reading the screen -------------------------------------------------------------------------------------

    protected fun string(@StringRes id: Int, vararg args: Any): String = application.getString(id, *args)

    protected fun shows(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Whether the mascot with this mood is on screen (it has no text, only a test tag). */
    protected fun showsMascot(mood: MascotMood): Boolean =
        compose.onAllNodesWithTag(mascotTag(mood), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** [shows] for text that is only a part of a node's text (e.g. a title and a body merged into one multi-line text). */
    protected fun showsContaining(fragment: String): Boolean =
        compose.onAllNodesWithText(fragment, substring = true).fetchSemanticsNodes().isNotEmpty()

    protected fun isSelectedText(text: String): Boolean =
        compose.onAllNodes(hasText(text) and isSelected()).fetchSemanticsNodes().isNotEmpty()

    /** Every text (and edit-field content) in the merged semantics tree of every window. */
    protected fun allTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            node.children.forEach(::walk)
        }
        try {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
        } catch (e: Throwable) {
            out += "<semantics unavailable: $e>"
        }
        return out
    }

    private fun findNode(predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        fun search(node: SemanticsNode): SemanticsNode? {
            if (predicate(node)) return node
            node.children.forEach { child -> search(child)?.let { return it } }
            return null
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach { root -> search(root)?.let { return it } }
        return null
    }

    /** Name of the first shop card on screen: the first clickable node whose text carries a verdict label. */
    protected fun firstShopName(): String {
        val card = findNode { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text)?.let { texts -> texts.size > 2 && texts.any { it.text in VERDICT_LABELS } } == true
        } ?: throw AssertionError("No shop card on screen. Screen text: ${allTexts().joinToString(" | ")}")
        return card.config[SemanticsProperties.Text].first().text
    }

    /** The N of "为你找到 N 家 · 按口碑排序", or null when that line is not on screen. */
    protected fun resultCount(): Int? =
        allTexts().firstNotNullOfOrNull { Regex("""为你找到 (\d+) 家""").find(it)?.groupValues?.get(1)?.toInt() }

    protected fun clipboardText(): String? =
        application.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    // ---- waiting ------------------------------------------------------------------------------------------------

    protected fun waitFor(what: String, timeoutMillis: Long = WAIT_MS, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Timed out after $timeoutMillis ms waiting for $what. Screen text: ${allTexts().joinToString(" | ")}", e)
        }
    }

    protected fun waitForText(text: String, timeoutMillis: Long = WAIT_MS) = waitFor("text \"$text\"", timeoutMillis) { shows(text) }

    protected fun waitForAnyText(texts: Collection<String>, timeoutMillis: Long = WAIT_MS) =
        waitFor("any of $texts", timeoutMillis) { texts.any(::shows) }

    protected fun waitForGone(text: String, timeoutMillis: Long = WAIT_MS) = waitFor("text \"$text\" to go away", timeoutMillis) { !shows(text) }

    protected fun waitForTextContaining(fragment: String, timeoutMillis: Long = WAIT_MS) =
        waitFor("text containing \"$fragment\"", timeoutMillis) { showsContaining(fragment) }

    /** Selection of a segmented button / chip follows a DataStore write, which completes off the UI thread. */
    protected fun waitForSelected(text: String, timeoutMillis: Long = WAIT_MS) =
        waitFor("\"$text\" to be selected", timeoutMillis) { isSelectedText(text) }

    // ---- acting -------------------------------------------------------------------------------------------------

    /** Scrolls the node into view first: a click at the coordinates of an off-screen node would silently miss. */
    protected fun clickText(text: String, index: Int = 0) {
        val node = compose.onAllNodesWithText(text)[index]
        scrollIntoView(node)
        node.performClick()
        settle()
    }

    private fun scrollIntoView(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (e: AssertionError) {
            // Not inside a scrollable (a dialog button, a bar item): it is where it is.
        } catch (e: RuntimeException) {
            // Same.
        }
    }

    /** The bottom-navigation item (the only clickable node carrying the label, unlike a screen title with the same words). */
    protected fun clickTab(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction())[0].performClick()
        settle()
    }

    // ---- screenshots --------------------------------------------------------------------------------------------

    /**
     * Saves everything on screen as a PNG: the activity window plus any dialog / bottom-sheet windows on top of it,
     * composited in window order. Fails when the image is blank, so an empty frame can never pass as a screenshot.
     */
    protected fun screenshot(name: String): File {
        settle()
        val roots = compose.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        check(count > 0) { "No compose root to capture for $name" }
        val base = roots[0].captureToImage().asAndroidBitmap()
        val composed = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        canvas.drawBitmap(base, 0f, 0f, null)
        for (index in 1 until count) {
            val layer = try {
                roots[index].captureToImage().asAndroidBitmap()
            } catch (e: Throwable) {
                println("SCREENSHOT $name: window $index could not be captured separately: $e")
                continue
            }
            val position = roots[index].fetchSemanticsNode().positionOnScreen
            canvas.drawBitmap(layer, position.x, position.y, null)
        }
        assertNotBlank(name, composed)
        SCREENSHOT_DIR.mkdirs()
        val file = File(SCREENSHOT_DIR, "$name.png")
        file.outputStream().use { composed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT $name: ${file.absolutePath} (${composed.width}x${composed.height}, ${file.length()} bytes, $count window(s))")
        return file
    }

    /**
     * Looks at the pixels of the (first) node showing [text]: some colour that takes up a noticeable part of it must
     * contrast with the dominant one by at least 3:1, i.e. the label can really be read (white-on-white fails).
     */
    protected fun assertLabelIsReadable(text: String, index: Int = 0) {
        val bitmap = compose.onAllNodesWithText(text)[index].captureToImage().asAndroidBitmap()
        // Only the middle of the node: its corners can show the colour of whatever surrounds a rounded button.
        val left = bitmap.width / 5
        val top = bitmap.height / 4
        val width = bitmap.width * 3 / 5
        val height = bitmap.height / 2
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val counts = HashMap<Int, Int>()
        for (pixel in pixels) counts.merge(pixel, 1, Int::plus)
        val background = counts.maxBy { it.value }.key
        val ink = counts.filter { it.value >= pixels.size * MIN_INK_SHARE }.keys
        val best = ink.maxOf { contrastRatio(background, it) }
        check(best >= MIN_LABEL_CONTRAST) { "\"$text\" cannot be read: best contrast against its background is ${"%.1f".format(best)}:1" }
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel(Color.red(argb)) + 0.7152 * channel(Color.green(argb)) + 0.0722 * channel(Color.blue(argb))
    }

    private fun assertNotBlank(name: String, bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val counts = HashMap<Int, Int>()
        var sampled = 0
        for (y in 0 until height step SAMPLE_STEP) {
            for (x in 0 until width step SAMPLE_STEP) {
                counts.merge(pixels[y * width + x], 1, Int::plus)
                sampled++
            }
        }
        val dominant = counts.values.max().toDouble() / sampled
        check(counts.size >= MIN_DISTINCT_COLOURS && dominant < MAX_DOMINANT_SHARE) {
            "Screenshot $name looks blank: ${counts.size} distinct colours, the most common one covers ${(dominant * 100).toInt()}%"
        }
    }

    companion object {
        /** Phone-sized window: 411 x 891 dp at xxhdpi (1233 x 2673 px). */
        const val PHONE = "w411dp-h891dp-xxhdpi"

        /** Upper bound for every wait for a screen change. */
        const val WAIT_MS = 15_000L

        /** Upper bound for Compose's own "wait until idle". */
        const val IDLE_MS = 15_000L

        /** Upper bound for a whole test. */
        const val TEST_BUDGET_SECONDS = 60L

        private const val SAMPLE_STEP = 5
        private const val MIN_DISTINCT_COLOURS = 12
        private const val MAX_DOMINANT_SHARE = 0.97
        private const val MIN_INK_SHARE = 0.003
        private const val MIN_LABEL_CONTRAST = 3.0

        val VERDICT_LABELS: List<String> = Verdict.entries.map { it.label }

        /** Gradle runs unit tests with the module directory as working directory, so this is `app/build/screenshots`. */
        val SCREENSHOT_DIR: File = File("build/screenshots").absoluteFile
    }
}

/** Interrupts the thread that started the test when it overruns its budget; see [SmokeTestBase.budget]. */
class TestBudget(private val seconds: Long) : TestWatcher() {
    private var timer: Timer? = null

    override fun starting(description: Description) {
        val testThread = Thread.currentThread()
        timer = Timer("smoke-test-budget", true).also { timer ->
            timer.schedule(
                object : TimerTask() {
                    override fun run() {
                        System.err.println("SMOKE TEST BUDGET OF $seconds s EXCEEDED: ${description.displayName}. Thread dump:")
                        Thread.getAllStackTraces().forEach { (thread, stack) ->
                            System.err.println("--- ${thread.name} (${thread.state})")
                            stack.take(40).forEach { System.err.println("    at $it") }
                        }
                        testThread.interrupt()
                    }
                },
                seconds * 1_000,
            )
        }
    }

    override fun finished(description: Description) {
        timer?.cancel()
        timer = null
        Thread.interrupted() // never leak the interrupt into the next test
    }
}
