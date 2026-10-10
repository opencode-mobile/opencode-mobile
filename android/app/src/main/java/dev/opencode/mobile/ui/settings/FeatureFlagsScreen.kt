package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.featureflags.FeatureFlag
import dev.opencode.mobile.domain.featureflags.FeatureFlagResolution
import dev.opencode.mobile.domain.featureflags.FeatureFlagSource
import dev.opencode.mobile.domain.featureflags.FeatureFlags
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

data class FeatureFlagRow(
    val flag: FeatureFlag,
    val resolution: FeatureFlagResolution,
) {
    val subtitle: String
        get() {
            val state = if (resolution.value) "On" else "Off"
            val source =
                when (resolution.source) {
                    FeatureFlagSource.Default -> "bundled default"
                    FeatureFlagSource.Remote -> "remote"
                    FeatureFlagSource.Override -> "device override"
                }
            return "$state · $source · ${flag.key}"
        }
}

/** Developer-tools screen listing every [FeatureFlag] with an override switch (OPE-269). */
@Composable
fun FeatureFlagsRoute(
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
) {
    val rows = FeatureFlag.entries.map { FeatureFlagRow(it, FeatureFlags.resolution(it)) }
    FeatureFlagsScreen(
        rows = rows,
        onNavigateBack = onNavigateBack,
        detailControl = detailControl,
        onOverrideChange = { flag, value -> FeatureFlags.setOverride(flag, value) },
        onClearOverrides = { FeatureFlags.clearOverrides() },
    )
}

@Composable
fun FeatureFlagsScreen(
    rows: List<FeatureFlagRow>,
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    onOverrideChange: (FeatureFlag, Boolean) -> Unit,
    onClearOverrides: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = "Clear overrides",
                        icon = null,
                        onClick = onClearOverrides,
                    ),
                ),
        ) {
            OpenCodeTopBar(
                title = "Feature Flags",
                leading = openCodeDetailLeading(detailControl, onNavigateBack),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text =
                        "Switching a flag sets a device override that wins over the remote value. " +
                            "Remote values are fetched on launch and applied at the next launch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-base"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OpenCodeSectionHeader(title = "Flags")
                rows.forEach { row ->
                    OpenCodeListItem(
                        title = row.flag.title,
                        subtitle = row.subtitle,
                        style = OpenCodeListItemStyle.Card,
                        startDecorator = StartDecorator.Icon(OpenCodeIcons.Settings, row.flag.title),
                        accessory =
                            OpenCodeListItemAccessory.Toggle(
                                checked = row.resolution.value,
                                onCheckedChange = { onOverrideChange(row.flag, it) },
                            ),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureFlagsScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        FeatureFlagsScreen(
            rows =
                listOf(
                    FeatureFlagRow(
                        FeatureFlag.SessionChatChipTimeline,
                        FeatureFlagResolution(value = false, source = FeatureFlagSource.Override),
                    ),
                ),
            onNavigateBack = {},
            onOverrideChange = { _, _ -> },
        )
    }
}
