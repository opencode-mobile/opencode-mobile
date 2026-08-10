package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val SyncingPillShape = RoundedCornerShape(percent = 50)
private val SyncingPillPaddingHorizontal = 14.dp
private val SyncingPillPaddingVertical = 8.dp
private val SyncingPillMinHeight = 36.dp
private val SyncingPillSpinnerSize = 14.dp
private val SyncingPillIconSpacing = 6.dp
private val SyncingPillShadowElevation = 4.dp

/** Non-interactive floating status pill for quiet cache→network sync. */
@Composable
fun OpenCodeSyncingPill(
    modifier: Modifier = Modifier,
    label: String = "Syncing…",
) {
    val surfaceRaised = openCodeColor("surface-raised-base")
    val borderColor = openCodeColor("border-weaker-base")
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier =
            modifier
                .defaultMinSize(minHeight = SyncingPillMinHeight)
                .semantics { contentDescription = label },
        shape = SyncingPillShape,
        color = surfaceRaised.copy(alpha = 0.95f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp,
        shadowElevation = SyncingPillShadowElevation,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = SyncingPillPaddingHorizontal,
                    vertical = SyncingPillPaddingVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(SyncingPillIconSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(SyncingPillSpinnerSize),
                strokeWidth = 2.dp,
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.25f),
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSyncingPillPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeSyncingPill()
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeSyncingPillDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeSyncingPill()
    }
}
