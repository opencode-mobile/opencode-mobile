package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Rule
import org.junit.Test

/** Search's chips sit in a row that scrolls sideways; their titles must still show (#929). */
class OpenCodeMenuChipTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun titlesShow_inARowThatScrollsSideways() {
        composeTestRule.setContent {
            OpenCodeTheme {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    OpenCodeMenuChip(
                        title = "acme-invoices",
                        onClick = {},
                        leading = OpenCodeMenuChipLeading.Letter('A', LetterAccent.Teal),
                        selected = true,
                    )
                    OpenCodeMenuChip(
                        title = "Include archived · 2",
                        onClick = {},
                        leading = OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Archive),
                        variant = OpenCodeMenuChipVariant.Toggle,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("acme-invoices", useUnmergedTree = true).assertIsDisplayed().assertWidthIsAtLeast(40.dp)
        composeTestRule.onNodeWithText("Include archived · 2", useUnmergedTree = true).assertIsDisplayed().assertWidthIsAtLeast(40.dp)
    }
}
