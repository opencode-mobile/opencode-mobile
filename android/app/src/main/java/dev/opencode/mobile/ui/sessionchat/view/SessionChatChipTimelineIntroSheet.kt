package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeToolChip
import dev.opencode.mobile.ui.components.OpenCodeToolChipVariant
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * One-time offer to turn on Chip timeline (OPE-270). Swipe, scrim, and back
 * count as Later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionChatChipTimelineIntroSheet(
    visible: Boolean,
    onEnable: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_CHIP_TIMELINE_INTRO,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )

    OpenCodeBottomSheet(
        visible = visible,
        title = "Try Chip timeline",
        onDismissRequest = onLater,
        contentMode = OpenCodeBottomSheetContentMode.ActionList,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpenCodeToolChip(variant = OpenCodeToolChipVariant.Explore("Explored · 6 reads · 3 greps"))
                OpenCodeToolChip(variant = OpenCodeToolChipVariant.File("SeedingSessionCache.kt", additions = 41, deletions = 9))
                OpenCodeToolChip(variant = OpenCodeToolChipVariant.Command("./gradlew test"))
            }
            Column {
                OpenCodeListItem(
                    title = "See the work at a glance",
                    subtitle = "Tool calls become chips under the sentence that explains them.",
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, null),
                )
                OpenCodeListItem(
                    title = "Less scrolling on long runs",
                    subtitle = "A run of 20 calls takes a few chips instead of a long list.",
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.CollapseContent, null),
                )
                OpenCodeListItem(
                    title = "Details on tap",
                    subtitle = "Tap a chip for its diffs, command output, or files.",
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, null),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Turn on Chip timeline"),
                    onClick = onEnable,
                    contrast = OpenCodeButtonContrast.Accent,
                    size = OpenCodeButtonSize.Large,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Later"),
                        onClick = onLater,
                        contrast = OpenCodeButtonContrast.Low,
                        size = OpenCodeButtonSize.Large,
                        modifier = Modifier.weight(1f),
                    )
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Don't show again"),
                        onClick = onNever,
                        contrast = OpenCodeButtonContrast.Low,
                        size = OpenCodeButtonSize.Large,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "You can change this anytime in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
