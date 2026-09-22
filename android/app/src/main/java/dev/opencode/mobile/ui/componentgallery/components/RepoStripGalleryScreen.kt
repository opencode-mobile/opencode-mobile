package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeRepoStrip
import dev.opencode.mobile.ui.components.OpenCodeRepoStripFile
import dev.opencode.mobile.ui.components.OpenCodeRepoStripFileStatus

private enum class RepoStripSample(
    val label: String,
    val branch: String,
    val defaultBranch: String?,
    val files: List<OpenCodeRepoStripFile>,
) {
    Dirty(
        label = "Dirty",
        branch = "fix/opencode-2-health-probe",
        defaultBranch = "main",
        files =
            listOf(
                OpenCodeRepoStripFile(
                    "android/app/src/main/java/dev/opencode/mobile/data/remote/HealthApi.kt",
                    OpenCodeRepoStripFileStatus.Modified,
                    42,
                    7,
                ),
                OpenCodeRepoStripFile(
                    "ios/OpenCode Mobile/OpenCode Mobile/Data/Remote/HealthAPIClient.swift",
                    OpenCodeRepoStripFileStatus.Modified,
                    38,
                    5,
                ),
                OpenCodeRepoStripFile(
                    "android/app/src/test/java/dev/opencode/mobile/HealthProbeTest.kt",
                    OpenCodeRepoStripFileStatus.Added,
                    61,
                    0,
                ),
                OpenCodeRepoStripFile("docs/features/health-probe.md", OpenCodeRepoStripFileStatus.Deleted, 0, 19),
            ),
    ),
    Clean(label = "Clean", branch = "feature/ope-260-branch-badge", defaultBranch = "main", files = emptyList()),
    DefaultBranch(
        label = "On main",
        branch = "main",
        defaultBranch = "main",
        files = listOf(OpenCodeRepoStripFile("README.md", OpenCodeRepoStripFileStatus.Modified, 3, 1)),
    ),
}

@Composable
fun RepoStripGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableStateOf(RepoStripSample.Dirty) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Tap the strip to collapse or expand. A clean tree shows the branch row only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepoStripSample.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.label,
                    selected = sample == option,
                    onClick = { sample = option },
                )
            }
        }
        OpenCodeRepoStrip(
            branch = sample.branch,
            defaultBranch = sample.defaultBranch,
            files = sample.files,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onOpenChanges = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
