package dev.opencode.mobile.ui.components

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.mobile.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Behavior of the shared bottom sheet that the pure height math cannot cover: one composition
 * of the body, height choice and latch, keyboard policy, every dismiss path, and modal
 * accessibility.
 */
class OpenCodeBottomSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class BodyProbe {
        var compositions = 0
        var active = 0
        var maxActive = 0
    }

    private class FakeKeyboard : SoftwareKeyboardController {
        var hides = 0

        override fun show() = Unit

        override fun hide() {
            hides += 1
        }
    }

    @Composable
    private fun ProbedBody(
        probe: BodyProbe,
        rows: Int,
        selectedRow: Int = -1,
    ) {
        DisposableEffect(probe) {
            probe.compositions += 1
            probe.active += 1
            probe.maxActive = maxOf(probe.maxActive, probe.active)
            onDispose { probe.active -= 1 }
        }
        repeat(rows) { index ->
            OpenCodeBottomSheetEntry(title = "Row $index", selected = index == selectedRow, onClick = {})
        }
    }

    private fun windowHeight(): Dp = rule.onNode(isDialog()).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun sheetTop(title: String): Dp = rule.onNodeWithText(title).getUnclippedBoundsInRoot().top

    @Test
    fun body_isComposedOnce_inEveryHeightAndKeyboardMode() {
        data class Mode(
            val height: OpenCodeBottomSheetHeight,
            val keyboard: OpenCodeBottomSheetKeyboard,
            val rows: Int,
        )
        val modes =
            OpenCodeBottomSheetKeyboard.entries.flatMap { keyboard ->
                listOf(
                    Mode(OpenCodeBottomSheetHeight.FitContent, keyboard, rows = 2),
                    Mode(OpenCodeBottomSheetHeight.FitContent, keyboard, rows = 30),
                    Mode(OpenCodeBottomSheetHeight.Half, keyboard, rows = 30),
                )
            }
        var index by mutableIntStateOf(0)
        val probes = modes.map { BodyProbe() }
        rule.setContent {
            OpenCodeTheme {
                val mode = modes[index]
                key(index) {
                    OpenCodeBottomSheet(
                        visible = true,
                        title = "Sheet $index",
                        onDismissRequest = {},
                        height = mode.height,
                        keyboard = mode.keyboard,
                    ) {
                        ProbedBody(probes[index], mode.rows)
                    }
                }
            }
        }
        modes.indices.forEach { modeIndex ->
            rule.runOnIdle { index = modeIndex }
            rule.waitForIdle()
            rule.onNodeWithText("Sheet $modeIndex").assertExists()
            val probe = probes[modeIndex]
            assertEquals("compositions for ${modes[modeIndex]}", 1, probe.compositions)
            assertEquals("live instances for ${modes[modeIndex]}", 1, probe.maxActive)
        }
    }

    @Test
    fun fitContent_wrapsShortBody_switchesToHalfForLongBody_andLatches() {
        var rows by mutableIntStateOf(2)
        rule.setContent {
            OpenCodeTheme {
                OpenCodeBottomSheet(visible = true, title = "Fit", onDismissRequest = {}) {
                    ProbedBody(BodyProbe(), rows)
                }
            }
        }
        rule.waitForIdle()
        val window = windowHeight()
        val compactTop = sheetTop("Fit")
        assertTrue("short body stays compact", compactTop > window / 2 + 48.dp)

        // A compact sheet follows its body down as well as up.
        rule.runOnIdle { rows = 4 }
        rule.waitForIdle()
        val tallerTop = sheetTop("Fit")
        assertTrue("taller body raises the sheet", tallerTop < compactTop)
        rule.runOnIdle { rows = 2 }
        rule.waitForIdle()
        assertEquals(compactTop.value, sheetTop("Fit").value, 1f)

        rule.runOnIdle { rows = 30 }
        rule.waitForIdle()
        val halfTop = sheetTop("Fit")
        assertTrue("long body rests at half height", halfTop < window / 2 + 24.dp)

        // Once at half height, a body that shrinks again keeps the sheet there.
        rule.runOnIdle { rows = 2 }
        rule.waitForIdle()
        assertEquals(halfTop.value, sheetTop("Fit").value, 1f)
    }

    @Test
    fun half_restsAtHalfHeightForAShortBody() {
        rule.setContent {
            OpenCodeTheme {
                OpenCodeBottomSheet(
                    visible = true,
                    title = "Half",
                    onDismissRequest = {},
                    height = OpenCodeBottomSheetHeight.Half,
                ) {
                    ProbedBody(BodyProbe(), rows = 1)
                }
            }
        }
        rule.waitForIdle()
        assertTrue(sheetTop("Half") < windowHeight() / 2 + 24.dp)
    }

    @Test
    fun keyboardPolicy_onlyDismissHidesTheKeyboard() {
        var keyboard by mutableStateOf(OpenCodeBottomSheetKeyboard.Keep)
        var visible by mutableStateOf(false)
        val fake = FakeKeyboard()
        rule.setContent {
            OpenCodeTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides fake) {
                    OpenCodeBottomSheet(visible = visible, title = "Keys", onDismissRequest = {}, keyboard = keyboard) {
                        ProbedBody(BodyProbe(), rows = 2)
                    }
                }
            }
        }
        rule.runOnIdle { visible = true }
        rule.waitForIdle()
        assertEquals("Keep leaves the keyboard", 0, fake.hides)

        rule.runOnIdle { keyboard = OpenCodeBottomSheetKeyboard.Expand }
        rule.waitForIdle()
        assertEquals("Expand leaves the keyboard", 0, fake.hides)

        rule.runOnIdle { keyboard = OpenCodeBottomSheetKeyboard.Dismiss }
        rule.waitForIdle()
        assertEquals("switching to Dismiss hides the keyboard", 1, fake.hides)
    }

    private fun showDismissible(onDismiss: () -> Unit): () -> Boolean {
        var visible by mutableStateOf(true)
        rule.setContent {
            OpenCodeTheme {
                OpenCodeBottomSheet(
                    visible = visible,
                    title = "Dismiss me",
                    onDismissRequest = {
                        onDismiss()
                        visible = false
                    },
                ) {
                    ProbedBody(BodyProbe(), rows = 3)
                }
            }
        }
        rule.waitForIdle()
        return { visible }
    }

    private fun assertSheetLeaves() {
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Dismiss me").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun close_dismissesOnce_andTheSheetLeavesAfterItsExit() {
        var dismissals = 0
        showDismissible { dismissals += 1 }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
        assertSheetLeaves()
    }

    @Test
    fun scrim_dismisses_butTapsOnTheSheetDoNot() {
        var dismissals = 0
        showDismissible { dismissals += 1 }
        rule.onNodeWithText("Dismiss me").performClick()
        rule.runOnIdle { assertEquals("a tap on the sheet stays on the sheet", 0, dismissals) }

        rule.onNode(isDialog()).performTouchInput { click(Offset(centerX, height * 0.15f)) }
        rule.runOnIdle { assertEquals(1, dismissals) }
        assertSheetLeaves()
    }

    @Test
    fun back_dismisses() {
        var dismissals = 0
        showDismissible { dismissals += 1 }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(1, dismissals) }
        assertSheetLeaves()
    }

    @Test
    fun hardwareEscape_dismisses() {
        var dismissals = 0
        showDismissible { dismissals += 1 }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        rule.runOnIdle { assertEquals(1, dismissals) }
        assertSheetLeaves()
    }

    @Test
    fun headerDrag_dismissesPastTheThreshold_andSettlesBackBelowIt() {
        var dismissals = 0
        showDismissible { dismissals += 1 }
        val restingTop = sheetTop("Dismiss me")

        // A short, slow drag springs back.
        rule.onNodeWithText("Dismiss me").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 40.dp.toPx()), durationMillis = 600)
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, dismissals) }
        assertEquals(restingTop.value, sheetTop("Dismiss me").value, 1f)

        rule.onNodeWithText("Dismiss me").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 160.dp.toPx()), durationMillis = 600)
        }
        rule.runOnIdle { assertEquals(1, dismissals) }
        assertSheetLeaves()
    }

    @Test
    fun modalAccessibility_dialogHeadingButtonsAndSelection() {
        rule.setContent {
            OpenCodeTheme {
                OpenCodeBottomSheet(visible = true, title = "Pick one", onDismissRequest = {}) {
                    ProbedBody(BodyProbe(), rows = 3, selectedRow = 1)
                }
            }
        }
        rule.waitForIdle()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("Pick one").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithContentDescription("Close").assert(hasClickAction())

        val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        rule.onNodeWithText("Row 0").assert(isButton).assertIsNotSelected()
        rule.onNodeWithText("Row 1").assert(isButton).assertIsSelected()

        // Only the rows and Close take clicks: the scrim and the sheet surface are not
        // accessibility targets.
        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(4, clickable.size)
    }
}
