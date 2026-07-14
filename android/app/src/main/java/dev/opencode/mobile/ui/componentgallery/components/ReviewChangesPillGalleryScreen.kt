package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeReviewChangesPill
import dev.opencode.mobile.ui.components.OpenCodeReviewChangesPillVariant

@Composable
fun ReviewChangesPillGalleryScreen(modifier: Modifier = Modifier) {
    var enabled by rememberSaveable { mutableStateOf(true) }
    var summary by rememberSaveable { mutableStateOf(ReviewChangesPillGallerySummary.Mixed) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Review mixed, additions-only, deletions-only, zero-line, singular, plural, and disabled states.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge("Mixed", summary == ReviewChangesPillGallerySummary.Mixed, onClick = { summary = ReviewChangesPillGallerySummary.Mixed })
            OpenCodeChoiceBadge("+ only", summary == ReviewChangesPillGallerySummary.AdditionsOnly, onClick = { summary = ReviewChangesPillGallerySummary.AdditionsOnly })
            OpenCodeChoiceBadge("− only", summary == ReviewChangesPillGallerySummary.DeletionsOnly, onClick = { summary = ReviewChangesPillGallerySummary.DeletionsOnly })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge("Zero lines", summary == ReviewChangesPillGallerySummary.ZeroLine, onClick = { summary = ReviewChangesPillGallerySummary.ZeroLine })
            OpenCodeChoiceBadge("1 file", summary == ReviewChangesPillGallerySummary.Singular, onClick = { summary = ReviewChangesPillGallerySummary.Singular })
            OpenCodeChoiceBadge("Enabled", enabled, onClick = { enabled = !enabled })
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OpenCodeReviewChangesPill(
                variant = OpenCodeReviewChangesPillVariant.LatestTurn,
                changedFileCount = summary.changedFileCount,
                additions = summary.additions,
                deletions = summary.deletions,
                enabled = enabled,
                onClick = {},
            )
        }
    }
}

private enum class ReviewChangesPillGallerySummary(
    val changedFileCount: Int,
    val additions: Int,
    val deletions: Int,
) {
    Mixed(5, 12, 3),
    AdditionsOnly(5, 12, 0),
    DeletionsOnly(5, 0, 3),
    ZeroLine(5, 0, 0),
    Singular(1, 1, 1),
}
