package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode

@Composable
fun UnifiedDiffGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableIntStateOf(0) }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val labels = listOf("Modified", "Added", "Deleted", "Unavailable")
    val files =
        listOf(
            FileDiff(
                path = "Sources/ChangesScreen.kt",
                patch =
                    "@@ -10,3 +10,3 @@\n context\n-old value\n+" +
                        "val newValue = buildAnIntentionallyLongLineThatRequiresHorizontalScrollingToReviewSafely()",
                additions = 1,
                deletions = 1,
                status = FileDiffStatus.Modified,
            ),
            FileDiff("Sources/NewFile.kt", "@@ -0,0 +1,2 @@\n+val value = 1\n+println(value)", 2, 0, FileDiffStatus.Added),
            FileDiff("Sources/OldFile.kt", "@@ -1,2 +0,0 @@\n-val value = 1\n-println(value)", 0, 2, FileDiffStatus.Deleted),
            null,
        )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Interactive", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                OpenCodeChoiceBadge(label = label, selected = sample == index, onClick = { sample = index })
            }
        }
        OpenCodeChoiceBadge(label = "Reviewed", selected = reviewed, onClick = { reviewed = !reviewed })
        OpenCodeChoiceBadge(label = "Expanded context", selected = expanded, onClick = { expanded = !expanded })
        files[sample]?.let {
            OpenCodeUnifiedDiff(
                file = it,
                contextMode = if (expanded) UnifiedDiffContextMode.Expanded else UnifiedDiffContextMode.Collapsed,
                reviewed = reviewed,
                selectedHunkIndex = 0,
            )
        } ?: Text(
            "A text preview is unavailable for assets/logo.png.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
