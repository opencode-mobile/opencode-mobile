package dev.opencode.mobile.ui.componentgallery.capture

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.ThemeManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Renders every [ComponentCaptureRegistry] state on the device in Porcelain light and dark and saves
 * one PNG per state per appearance, cropped to its [ComponentCaptureTile], plus `captures.json`.
 * `scripts/component-gallery/capture-android.sh` runs this and pulls the files.
 *
 * Argument: `captureComponents`, comma-separated component ids (default: all). Output goes to the app's
 * external files folder under `component-captures`.
 */
@RunWith(AndroidJUnit4::class)
class ComponentCaptureTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    // getExternalFilesDir creates the app's folder, which adb can then pull from.
    private val outDir = File(instrumentation.targetContext.getExternalFilesDir(null), "component-captures")

    @Test
    fun captureComponentStates() {
        val only =
            arguments
                .getString(ARG_COMPONENTS)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
        val components = ComponentCaptureRegistry.components.filter { only == null || it.id in only }
        assertTrue("No capture components match $only", components.isNotEmpty())
        outDir.deleteRecursively()
        outDir.mkdirs()

        instrumentation.runOnMainSync { ThemeManager.applyPreset(THEME_ID) }
        assertTrue("Theme $THEME_ID did not load", ThemeManager.loadError == null)

        // Overlays are captured from a screenshot of every window; keep the system bars out of it.
        composeTestRule.runOnUiThread {
            val window = composeTestRule.activity.window
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        val firstComponent = components.first()
        var current by mutableStateOf(Triple(firstComponent, firstComponent.states.first(), false))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            val (component, state, dark) = current
            OpenCodeTheme(darkTheme = dark) {
                val placement =
                    when (state.overlay(component)) {
                        CaptureOverlay.Center -> Alignment.Center
                        CaptureOverlay.Bottom -> Alignment.BottomCenter
                        CaptureOverlay.Top, null -> Alignment.TopCenter
                    }
                Box(Modifier.fillMaxSize(), contentAlignment = placement) {
                    // A fresh composition per tile, so no state remembered by one tile leaks into the next.
                    key(component.id, state.id, dark) {
                        ComponentCaptureTile(component = component, state = state)
                    }
                }
            }
        }

        val density = composeTestRule.activity.resources.displayMetrics.density
        val captures = mutableListOf<JsonObject>()
        for (dark in listOf(false, true)) {
            val appearance = if (dark) "dark" else "light"
            for (component in components) {
                for (state in component.states) {
                    composeTestRule.runOnIdle {
                        // Each tile measures its own content height on first layout.
                        CapturePaging.page = 0
                        current = Triple(component, state, dark)
                    }
                    settle()
                    val node = composeTestRule.onNodeWithTag(componentCaptureTag(component.id, state.id))
                    val pressTarget = state.press?.let { press -> press(press, node) }
                    val bitmap = if (state.overlay(component) != null) captureScreen(node) else captureWithRetry(node)
                    pressTarget?.performTouchInput { cancel() }
                    captures += save(component, state, state.id, appearance, bitmap, density)
                    val pageHeight = state.pageHeight ?: continue
                    val pagePx = pageHeight.value * density
                    val pages = ceil(CapturePaging.contentHeightPx / pagePx).toInt()
                    for (page in 1 until pages) {
                        composeTestRule.runOnIdle { CapturePaging.page = page }
                        settle()
                        val pageId = CapturePaging.pageId(state.id, page)
                        captures += save(component, state, pageId, appearance, captureWithRetry(node), density)
                    }
                }
            }
        }

        val manifest =
            buildJsonObject {
                put("platform", "android")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("scale", density)
                put("theme", THEME_ID)
                putJsonArray("captures") { captures.forEach { add(it) } }
                putJsonArray("skipped") {
                    components.forEach { component ->
                        component.skipped.forEach { skip ->
                            addJsonObject {
                                put("component", component.id)
                                put("state", skip.id)
                                put("reason", skip.reason)
                            }
                        }
                    }
                }
            }
        File(outDir, "captures.json").writeText(Json.encodeToString(JsonObject.serializer(), manifest))
    }

    /** Writes one tile's PNG and returns its manifest entry. */
    private fun save(
        component: ComponentCaptureComponent,
        state: ComponentCaptureState,
        stateId: String,
        appearance: String,
        bitmap: Bitmap,
        density: Float,
    ): JsonObject {
        val relative = "${component.id}/$stateId--$appearance.png"
        val file = File(outDir, relative).apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return buildJsonObject {
            put("component", component.id)
            put("state", stateId)
            put("appearance", appearance)
            put("file", relative)
            put("widthPx", bitmap.width)
            put("heightPx", bitmap.height)
            put("widthPt", (bitmap.width / density * 10).roundToInt() / 10.0)
            put("heightPt", (bitmap.height / density * 10).roundToInt() / 10.0)
            state.note?.let { put("note", it) }
        }
    }

    /**
     * Runs the state's gesture on the content's [CAPTURE_PRESS_TAG] node, or the tile. Returns the node still held
     * down, to release after the capture.
     */
    private fun press(
        press: CapturePress,
        tile: SemanticsNodeInteraction,
    ): SemanticsNodeInteraction? {
        val tagged = composeTestRule.onAllNodesWithTag(CAPTURE_PRESS_TAG, useUnmergedTree = true)
        val target = if (tagged.fetchSemanticsNodes().isEmpty()) tile else tagged.onFirst()
        target.performTouchInput {
            val x =
                press.fromStart?.toPx()
                    ?: press.fromEnd?.let { width - it.toPx() }
                    ?: centerX
            down(Offset(x, centerY))
        }
        when (press.gesture) {
            CaptureGesture.Hold -> Unit
            CaptureGesture.Tap -> {
                target.performTouchInput { up() }
                settle()
                return null
            }
            CaptureGesture.LongPressHold -> composeTestRule.mainClock.advanceTimeBy(LONG_PRESS_MS)
            CaptureGesture.DragStartHold ->
                target.performTouchInput {
                    repeat(SWIPE_STEPS) {
                        moveBy(Offset(-DRAG_DISTANCE.toPx() / SWIPE_STEPS, 0f))
                        composeTestRule.mainClock.advanceTimeBy(16)
                    }
                }
            CaptureGesture.ScrollBack -> {
                target.performTouchInput {
                    repeat(SWIPE_STEPS) {
                        moveBy(Offset(0f, SCROLL_DISTANCE.toPx() / SWIPE_STEPS))
                        composeTestRule.mainClock.advanceTimeBy(16)
                    }
                    up()
                }
                settle()
                return null
            }
            CaptureGesture.SwipeOpen -> {
                target.performTouchInput {
                    repeat(SWIPE_STEPS) {
                        moveBy(Offset(-SWIPE_DISTANCE.toPx() / SWIPE_STEPS, 0f))
                        composeTestRule.mainClock.advanceTimeBy(16)
                    }
                    up()
                }
                settle()
                return null
            }
        }
        settle()
        return target
    }

    /** Crops a screenshot of every window (the overlay's own included) to the tile. */
    private fun captureScreen(tile: SemanticsNodeInteraction): Bitmap {
        composeTestRule.waitForIdle()
        val bounds = tile.fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.window.decorView
                .getLocationOnScreen(origin)
        }
        val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot failed" }
        val left = origin[0] + bounds.left.roundToInt()
        val top = origin[1] + bounds.top.roundToInt()
        return Bitmap.createBitmap(screen, left, top, bounds.width.roundToInt(), bounds.height.roundToInt())
    }

    /** PixelCopy can time out while a freshly booted emulator's GPU warms up; retry a few times. */
    private fun captureWithRetry(node: SemanticsNodeInteraction): Bitmap {
        var failure: Throwable? = null
        repeat(CAPTURE_ATTEMPTS) {
            try {
                return node.captureToImage().asAndroidBitmap()
            } catch (error: AssertionError) {
                failure = error
            } catch (error: RuntimeException) {
                // PixelCopyException: the copy timed out or the surface was not ready yet.
                failure = error
            }
            settle()
        }
        throw checkNotNull(failure)
    }

    /** Advances the paused clock a fixed amount so every capture shows the same frame of its animations. */
    private fun settle() {
        repeat(SETTLE_STEPS) {
            composeTestRule.mainClock.advanceTimeBy(SETTLE_STEP_MS)
            composeTestRule.waitForIdle()
        }
    }

    private companion object {
        const val ARG_COMPONENTS = "captureComponents"
        const val THEME_ID = "porcelain"
        const val CAPTURE_ATTEMPTS = 5
        const val LONG_PRESS_MS = 800L
        const val SWIPE_STEPS = 10
        val SWIPE_DISTANCE = 160.dp
        val DRAG_DISTANCE = 80.dp
        val SCROLL_DISTANCE = 320.dp
        const val SETTLE_STEPS = 4
        const val SETTLE_STEP_MS = 500L
    }
}
