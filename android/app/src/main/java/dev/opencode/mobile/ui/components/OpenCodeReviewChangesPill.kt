package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ReviewChangesPillShape = RoundedCornerShape(percent = 50)

fun reviewChangesPillLabel(changedFileCount: Int): String =
    "Review $changedFileCount changed ${if (changedFileCount == 1) "file" else "files"}"

enum class OpenCodeReviewChangesPillVariant {
    LatestTurn,
}

enum class ReviewChangesPillPreviewCase(
    val changedFileCount: Int,
    val enabled: Boolean,
    val darkTheme: Boolean,
) {
    SingularLight(1, true, false),
    SingularDark(1, true, true),
    PluralLight(4, true, false),
    PluralDark(4, true, true),
    DisabledLight(4, false, false),
    DisabledDark(4, false, true),
}

@Composable
private fun ReviewChangesPillPreview(case: ReviewChangesPillPreviewCase) {
    OpenCodeTheme(darkTheme = case.darkTheme) {
        OpenCodeReviewChangesPill(
            variant = OpenCodeReviewChangesPillVariant.LatestTurn,
            changedFileCount = case.changedFileCount,
            enabled = case.enabled,
            onClick = {},
        )
    }
}

@Composable
fun OpenCodeReviewChangesPill(
    variant: OpenCodeReviewChangesPillVariant,
    changedFileCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(changedFileCount > 0) { "Review Changes Pill requires a positive file count" }
    val label =
        when (variant) {
            OpenCodeReviewChangesPillVariant.LatestTurn -> reviewChangesPillLabel(changedFileCount)
        }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val contentColor =
        if (enabled) openCodeColor("text-strong") else MaterialTheme.colorScheme.onSurfaceVariant
    val iconColor =
        if (enabled) openCodeColor("icon-strong-base") else MaterialTheme.colorScheme.onSurfaceVariant
    val containerColor =
        if (enabled && isPressed) openCodeColor("surface-raised-base-hover") else openCodeColor("surface-raised-base")

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .defaultMinSize(minHeight = 36.dp)
                .semantics { contentDescription = label },
        shape = ReviewChangesPillShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        shadowElevation = if (enabled) 4.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = iconColor,
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillSingularLightPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.SingularLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillSingularDarkPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.SingularDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillPluralLightPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.PluralLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillPluralDarkPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.PluralDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillDisabledLightPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.DisabledLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillDisabledDarkPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.DisabledDark)
}
