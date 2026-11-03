package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OpenCodeOverflowMenuLayoutTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dividerKeepsThePanelAtItsContentWidth() {
        composeTestRule.setContent {
            OpenCodeTheme(darkTheme = false) {
                OpenCodeTopBarOverflowHost(
                    expanded = true,
                    onDismissRequest = {},
                    entries =
                        listOf(
                            OpenCodeOverflowMenuEntry(
                                title = "Archived sessions",
                                icon = OpenCodeIcons.Archive,
                                subtitle = "4 in All projects",
                                onClick = {},
                                testTag = ARCHIVED_TAG,
                            ),
                            OpenCodeOverflowMenuEntry(
                                title = "Settings",
                                icon = OpenCodeIcons.Settings,
                                onClick = {},
                                testTag = SETTINGS_TAG,
                                dividerAbove = true,
                            ),
                        ),
                ) {
                    OpenCodeTopBar(title = "Sessions")
                }
            }
        }

        val archived = composeTestRule.onNodeWithTag(ARCHIVED_TAG).getUnclippedBoundsInRoot()
        val settings = composeTestRule.onNodeWithTag(SETTINGS_TAG).getUnclippedBoundsInRoot()
        val metrics = composeTestRule.activity.resources.displayMetrics
        val windowWidth = metrics.widthPixels / metrics.density
        assertTrue(
            "Menu row is ${archived.width} wide in a $windowWidth dp window",
            archived.width.value < windowWidth - 64f,
        )
        // The tag sits inside the row padding, so the 200 dp minimum reads a little narrower.
        assertTrue(archived.width >= 170.dp)
        // Every row spans the panel, so the whole row takes the tap.
        assertEquals(archived.width.value, settings.width.value, 0.5f)
    }

    private companion object {
        const val ARCHIVED_TAG = "overflowArchived"
        const val SETTINGS_TAG = "overflowSettings"
    }
}
