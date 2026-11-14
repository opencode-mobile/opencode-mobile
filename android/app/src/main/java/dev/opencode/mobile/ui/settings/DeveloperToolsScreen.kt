package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.preferences.PerformanceOverlayPreferences
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Settings > Developer Tools: the gallery, playground, flags, and diagnostics overlays. */
@Composable
fun DeveloperToolsScreen(
    onNavigateBack: () -> Unit,
    onComponentGalleryClick: () -> Unit,
    onSessionChatPlaygroundClick: () -> Unit,
    onFeatureFlagsClick: () -> Unit,
    modifier: Modifier = Modifier,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    /** Lab builds only; null hides the Highlighting Lab row. */
    onHighlightingLabClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = DeveloperToolsCopy.TITLE,
            leading = openCodeDetailLeading(detailControl, onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeListItem(
                    title = DeveloperToolsCopy.COMPONENT_GALLERY,
                    subtitle = DeveloperToolsCopy.COMPONENT_GALLERY_SUBTITLE,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, DeveloperToolsCopy.COMPONENT_GALLERY),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onComponentGalleryClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = DeveloperToolsCopy.PLAYGROUND,
                    subtitle = DeveloperToolsCopy.PLAYGROUND_SUBTITLE,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, DeveloperToolsCopy.PLAYGROUND),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onSessionChatPlaygroundClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                if (onHighlightingLabClick != null) {
                    OpenCodeListItem(
                        title = DeveloperToolsCopy.HIGHLIGHTING_LAB,
                        subtitle = DeveloperToolsCopy.HIGHLIGHTING_LAB_SUBTITLE,
                        style = OpenCodeListItemStyle.Card,
                        startDecorator = StartDecorator.Icon(OpenCodeIcons.Code, DeveloperToolsCopy.HIGHLIGHTING_LAB),
                        accessory = OpenCodeListItemAccessory.Disclosure,
                        onClick = onHighlightingLabClick,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                OpenCodeListItem(
                    title = DeveloperToolsCopy.FEATURE_FLAGS,
                    subtitle = DeveloperToolsCopy.FEATURE_FLAGS_SUBTITLE,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Settings, DeveloperToolsCopy.FEATURE_FLAGS),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = onFeatureFlagsClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = DeveloperToolsCopy.PERFORMANCE_OVERLAY,
                    subtitle = DeveloperToolsCopy.PERFORMANCE_OVERLAY_SUBTITLE,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Settings, DeveloperToolsCopy.PERFORMANCE_OVERLAY),
                    accessory =
                        OpenCodeListItemAccessory.Toggle(
                            checked = PerformanceOverlayPreferences.enabled,
                            onCheckedChange = PerformanceOverlayPreferences::updateEnabled,
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Developer Tools rows are developer-only and stay English. */
internal object DeveloperToolsCopy {
    const val TITLE = "Developer Tools"
    const val SETTINGS_SUBTITLE = "Gallery, playground, and flags"
    const val COMPONENT_GALLERY = "Component Gallery"
    const val COMPONENT_GALLERY_SUBTITLE = "Design-system foundations and reusable components"
    const val PLAYGROUND = "Session Chat Playground"
    const val PLAYGROUND_SUBTITLE = "The real chat over fake scenarios, no server"
    const val HIGHLIGHTING_LAB = "Highlighting Lab"
    const val HIGHLIGHTING_LAB_SUBTITLE = "Data sets and on-device benchmarks"
    const val FEATURE_FLAGS = "Feature Flags"
    const val FEATURE_FLAGS_SUBTITLE = "Remote flags with device overrides"
    const val PERFORMANCE_OVERLAY = "Performance Overlay"
    const val PERFORMANCE_OVERLAY_SUBTITLE = "Live FPS and frame-time spikes"
}

@Preview(showBackground = true)
@Composable
private fun DeveloperToolsScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        DeveloperToolsScreen(
            onNavigateBack = {},
            onComponentGalleryClick = {},
            onSessionChatPlaygroundClick = {},
            onFeatureFlagsClick = {},
        )
    }
}
