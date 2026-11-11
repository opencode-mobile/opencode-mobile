package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeMediumShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val FeatureRowIconTileSize = 40.dp
private val FeatureRowIconSize = 20.dp
private val FeatureRowSpacing = 14.dp

/** Fill opacity of the success color behind an [OpenCodeFeatureRowTone.Success] icon tile. */
private const val FEATURE_ROW_SUCCESS_FILL_ALPHA = 0.14f

/** Color treatment of an [OpenCodeFeatureRow]'s icon tile. */
enum class OpenCodeFeatureRowTone {
    /** Raised neutral tile, for something new or improved. */
    Neutral,

    /** Success-tinted tile, for a fix. */
    Success,
}

/**
 * One feature or change in a list such as release notes: a 40 dp rounded-square icon tile, a
 * semibold title, and an optional secondary [body] that wraps. Static, not tappable; TalkBack
 * reads title and body as one element and skips the decorative icon.
 */
@Composable
fun OpenCodeFeatureRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    body: String? = null,
    tone: OpenCodeFeatureRowTone = OpenCodeFeatureRowTone.Neutral,
) {
    val tileColor =
        when (tone) {
            OpenCodeFeatureRowTone.Neutral -> openCodeColor("surface-raised-base")
            OpenCodeFeatureRowTone.Success -> openCodeColor("success").copy(alpha = FEATURE_ROW_SUCCESS_FILL_ALPHA)
        }
    val iconColor =
        when (tone) {
            OpenCodeFeatureRowTone.Neutral -> openCodeColor("icon-strong-base")
            OpenCodeFeatureRowTone.Success -> openCodeColor("success")
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(FeatureRowSpacing),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .size(FeatureRowIconTileSize)
                    .background(tileColor, OpenCodeMediumShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(FeatureRowIconSize),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = openCodeColor("text-strong"),
            )
            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-weak"),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeFeatureRowPreview() {
    OpenCodeTheme(darkTheme = false) {
        Column {
            OpenCodeFeatureRow(
                title = "Start a session as a draft",
                body = "Write first, then pick the project and worktree.",
                icon = OpenCodeIcons.Edit,
            )
            OpenCodeFeatureRow(
                title = "Back gesture fix",
                body = "Going back no longer opens message actions.",
                icon = OpenCodeIcons.Check,
                tone = OpenCodeFeatureRowTone.Success,
            )
        }
    }
}
