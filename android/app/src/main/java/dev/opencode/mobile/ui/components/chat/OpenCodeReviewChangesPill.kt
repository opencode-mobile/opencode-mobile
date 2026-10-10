package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val ReviewChangesPillShape = RoundedCornerShape(percent = 50)

private fun reviewChangesPillFileLabel(changedFileCount: Int): String =
    "$changedFileCount ${if (changedFileCount == 1) "file" else "files"}"

fun reviewChangesPillSummary(
    changedFileCount: Int,
    additions: Int,
    deletions: Int,
): String = reviewChangesPillSummaryContent(changedFileCount, additions, deletions).visibleLabel

fun reviewChangesPillAccessibilityLabel(
    changedFileCount: Int,
    additions: Int,
    deletions: Int,
): String = reviewChangesPillSummaryContent(changedFileCount, additions, deletions).accessibilityLabel

private fun reviewChangesPillSummaryContent(
    changedFileCount: Int,
    additions: Int,
    deletions: Int,
) = ReviewChangesPillSummary(
    fileLabel = reviewChangesPillFileLabel(changedFileCount),
    additionLabel = additions.takeIf { it > 0 }?.let { "+$it" },
    deletionLabel = deletions.takeIf { it > 0 }?.let { "−$it" },
    additionAccessibilityLabel = additions.takeIf { it > 0 }?.let { "$it ${if (it == 1) "addition" else "additions"}" },
    deletionAccessibilityLabel = deletions.takeIf { it > 0 }?.let { "$it ${if (it == 1) "deletion" else "deletions"}" },
)

private data class ReviewChangesPillSummary(
    val fileLabel: String,
    val additionLabel: String?,
    val deletionLabel: String?,
    val additionAccessibilityLabel: String?,
    val deletionAccessibilityLabel: String?,
) {
    val visibleLabel: String
        get() = listOfNotNull(fileLabel, additionLabel, deletionLabel).joinToString("  ")

    val accessibilityLabel: String
        get() =
            listOfNotNull("Review changes", fileLabel, additionAccessibilityLabel, deletionAccessibilityLabel)
                .joinToString(", ")
}

enum class OpenCodeReviewChangesPillVariant {
    LatestTurn,
}

enum class ReviewChangesPillPreviewCase(
    val changedFileCount: Int,
    val additions: Int,
    val deletions: Int,
    val enabled: Boolean,
    val darkTheme: Boolean,
) {
    MixedLight(4, 12, 3, true, false),
    MixedDark(4, 12, 3, true, true),
    AdditionsOnlyLight(4, 12, 0, true, false),
    DeletionsOnlyDark(4, 0, 3, true, true),
    ZeroLineLight(4, 0, 0, true, false),
    SingularDark(1, 1, 1, true, true),
    DisabledLight(4, 12, 3, false, false),
    DisabledDark(4, 0, 0, false, true),
}

@Composable
private fun ReviewChangesPillPreview(case: ReviewChangesPillPreviewCase) {
    OpenCodeTheme(darkTheme = case.darkTheme) {
        OpenCodeReviewChangesPill(
            variant = OpenCodeReviewChangesPillVariant.LatestTurn,
            changedFileCount = case.changedFileCount,
            additions = case.additions,
            deletions = case.deletions,
            enabled = case.enabled,
            onClick = {},
        )
    }
}

@Composable
fun OpenCodeReviewChangesPill(
    variant: OpenCodeReviewChangesPillVariant,
    changedFileCount: Int,
    additions: Int,
    deletions: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(changedFileCount > 0) { "Review Changes Pill requires a positive file count" }
    require(additions >= 0) { "Review Changes Pill requires nonnegative additions" }
    require(deletions >= 0) { "Review Changes Pill requires nonnegative deletions" }
    val summary =
        when (variant) {
            OpenCodeReviewChangesPillVariant.LatestTurn ->
                reviewChangesPillSummaryContent(changedFileCount, additions, deletions)
        }
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor =
        if (enabled) openCodeColor("text-strong") else MaterialTheme.colorScheme.onSurfaceVariant
    val counts =
        openCodeDiffStatAnnotatedString(
            additions = additions.takeIf { it > 0 },
            deletions = deletions.takeIf { it > 0 },
            enabled = enabled,
            separator = OpenCodeDiffStatSeparator.Wide,
        )
    val surfaceRaised = openCodeColor("surface-raised-base")

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .semantics { contentDescription = summary.accessibilityLabel },
        shape = ReviewChangesPillShape,
        color = surfaceRaised.copy(alpha = if (enabled) 0.95f else 0.7f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        tonalElevation = 2.dp,
        shadowElevation = if (enabled) 4.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier =
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    buildAnnotatedString {
                        append(summary.fileLabel)
                        if (counts.isNotEmpty()) {
                            append("  ")
                            append(counts)
                        }
                    },
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillMixedLightPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.MixedLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillMixedDarkPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.MixedDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillAdditionsOnlyLightPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.AdditionsOnlyLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillDeletionsOnlyDarkPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.DeletionsOnlyDark)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillZeroLineLightPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.ZeroLineLight)
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeReviewChangesPillSingularDarkPreview() {
    ReviewChangesPillPreview(ReviewChangesPillPreviewCase.SingularDark)
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
