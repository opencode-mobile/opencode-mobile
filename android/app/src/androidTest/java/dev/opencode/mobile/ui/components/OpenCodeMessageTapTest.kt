package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Message actions open on a deliberate tap only, and a code block tap names its block. */
class OpenCodeMessageTapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val taps = mutableListOf<OpenCodeMessageTapTarget>()

    private val markdown =
        """
        Here is the fix for the rounding bug in the formatter.

        ```ts
        export function formatMoney(cents: number, currency: string): string { return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(cents / 100) }
        ```

        Run the tests after applying it.
        """.trimIndent()

    private fun setMessages(count: Int = 1) {
        val document = OpenCodeMarkdownParser.parse(markdown)
        rule.setContent {
            OpenCodeTheme {
                val listState = rememberLazyListState()
                val probe = remember(listState) { { listState.isScrollInProgress } }
                CompositionLocalProvider(LocalOpenCodeUserScrollInProgress provides probe) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("list")) {
                        items(count) { index ->
                            OpenCodeAssistantMessage(
                                text = markdown,
                                renderedMarkdown = document,
                                modifier = Modifier.testTag("message-$index"),
                                onTextTap = { taps += it },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun tapOnTextOpensActionsForText() {
        setMessages()
        rule.onNodeWithText("Run the tests after applying it.").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(OpenCodeMessageTapTarget.Text), taps)
    }

    @Test
    fun tapOnCodeBlockOpensActionsForThatBlock() {
        setMessages()
        rule.onNodeWithText("export function", substring = true).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, taps.size)
        val target = taps.single() as OpenCodeMessageTapTarget.CodeBlock
        assertEquals(true, target.code.startsWith("export function formatMoney"))
    }

    @Test
    fun horizontalSwipeInCodeBlockIsNotTap() {
        setMessages()
        rule.onNodeWithText("export function", substring = true).performTouchInput { swipeLeft(durationMillis = 200) }
        rule.waitForIdle()
        assertEquals(emptyList<OpenCodeMessageTapTarget>(), taps)
    }

    @Test
    fun tapThatStopsFlingIsNotTap() {
        setMessages(count = 30)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 60) }
        // Let the fling run a few frames, then touch the text it is moving under.
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("list").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(48)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(emptyList<OpenCodeMessageTapTarget>(), taps)

        // Once the list rests, the same touch is a tap again.
        rule.onNodeWithTag("list").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, taps.size)
    }
}
