package dev.opencode.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ActionBarVerticalPadding = 12.dp
private val ActionBarTargetSpacing = 10.dp
private val ActionBarTargetIconSize = 22.dp
private val ActionBarDividerThickness = 1.dp

/** What the bar's action applies to, shown above the button. */
data class OpenCodeActionBarTarget(
    val title: String,
    /** Usually the full path; truncates at the start so the end stays readable. */
    val subtitle: String,
    /** Small tag after the title, such as "git". */
    val tag: String? = null,
    val icon: ImageVector = OpenCodeIcons.Folder,
)

/**
 * Bottom action bar pinned under a page's content: an optional [target] row over one
 * full-width button. The fill extends under the navigation bar, and the content rides the
 * keyboard, so place it last in an edge-to-edge screen. [compact] hides the target row (for
 * example while the keyboard is up); the button always stays. When [enabled] is false,
 * [actionTitle] is the reason.
 */
@Composable
fun OpenCodeActionBar(
    actionTitle: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    target: OpenCodeActionBarTarget? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(openCodeColor("background-base")),
    ) {
        HorizontalDivider(thickness = ActionBarDividerThickness, color = openCodeColor("border-weaker-base"))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
                    ).padding(horizontal = OpenCodeLayout.ScreenGutter, vertical = ActionBarVerticalPadding),
        ) {
            AnimatedVisibility(
                visible = target != null && !compact,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                target?.let { ActionBarTargetRow(target = it, modifier = Modifier.padding(bottom = ActionBarTargetSpacing)) }
            }
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText(actionTitle),
                onClick = onAction,
                enabled = enabled,
                contrast = OpenCodeButtonContrast.High,
                size = OpenCodeButtonSize.Large,
            )
        }
    }
}

@Composable
private fun ActionBarTargetRow(
    target: OpenCodeActionBarTarget,
    modifier: Modifier = Modifier,
) {
    val label = listOfNotNull(target.title, target.tag?.takeIf { it.isNotBlank() }, target.subtitle).joinToString(", ")
    Row(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ActionBarTargetSpacing),
    ) {
        Icon(
            imageVector = target.icon,
            contentDescription = null,
            tint = openCodeColor("text-weak"),
            modifier = Modifier.size(ActionBarTargetIconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = openCodeColor("text-strong"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                target.tag?.takeIf { it.isNotBlank() }?.let { tag ->
                    OpenCodeTag(title = tag, size = OpenCodeTagSize.Small)
                }
            }
            Text(
                text = target.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionBarPreview() {
    OpenCodeTheme(darkTheme = true) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OpenCodeActionBar(
                actionTitle = "Add this folder",
                onAction = {},
                target =
                    OpenCodeActionBarTarget(
                        title = "opencode-mobile",
                        subtitle = "/Users/me/Projects/opencode-mobile",
                        tag = "git",
                    ),
            )
            OpenCodeActionBar(
                actionTitle = "Already on Home",
                onAction = {},
                enabled = false,
                target = OpenCodeActionBarTarget(title = "notes", subtitle = "/Users/me/notes"),
            )
            OpenCodeActionBar(actionTitle = "Add this folder", onAction = {}, compact = true)
        }
    }
}
