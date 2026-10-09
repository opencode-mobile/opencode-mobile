package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeDiffStat
import dev.opencode.mobile.ui.components.OpenCodeDiffStatSeparator
import dev.opencode.mobile.ui.components.OpenCodeDiffStatStyle

@Composable
fun DiffStatGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DiffStatSample("Mixed") {
            OpenCodeDiffStat(additions = 12, deletions = 3)
        }
        DiffStatSample("Additions only") {
            OpenCodeDiffStat(additions = 12)
        }
        DiffStatSample("Deletions only") {
            OpenCodeDiffStat(deletions = 3)
        }
        DiffStatSample("Zeros shown") {
            OpenCodeDiffStat(additions = 0, deletions = 0, separator = OpenCodeDiffStatSeparator.Split)
        }
        DiffStatSample("Disabled") {
            OpenCodeDiffStat(
                additions = 12,
                deletions = 3,
                enabled = false,
                style = OpenCodeDiffStatStyle.Button,
            )
        }
        DiffStatSample("File row") {
            OpenCodeDiffStat(
                additions = 8,
                deletions = 2,
                style = OpenCodeDiffStatStyle.Label,
                separator = OpenCodeDiffStatSeparator.Split,
            )
        }
    }
}

@Composable
private fun DiffStatSample(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
