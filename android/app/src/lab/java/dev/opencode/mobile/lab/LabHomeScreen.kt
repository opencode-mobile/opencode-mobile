package dev.opencode.mobile.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.BuildConfig
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
internal fun LabHomeScreen(
    onNavigateBack: () -> Unit,
    onDataSets: () -> Unit,
    onBenchmark: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Highlighting Lab",
            subtitle = "${BuildConfig.BUILD_TYPE} build",
            leading = OpenCodeTopBarLeading.Back(onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(Modifier.weight(1f)) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (BuildConfig.DEBUG) {
                    OpenCodeInlineNotice(
                        message = "Debug build: timings are slow and uneven. Use the benchmark build for numbers.",
                        variant = OpenCodeInlineNoticeVariant.Banner,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                OpenCodeListItem(
                    title = "Data sets",
                    subtitle = "Browse by language, size, and kind; view highlighted; paste text",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.List, "Data sets"),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onDataSets,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OpenCodeListItem(
                    title = "Benchmark",
                    subtitle = "Quick or full tier, or picked data sets, on picked engines",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, "Benchmark"),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onBenchmark,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
