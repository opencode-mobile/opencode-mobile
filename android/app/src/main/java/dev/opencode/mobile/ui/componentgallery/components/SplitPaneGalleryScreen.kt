package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneState
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun SplitPaneGalleryScreen(modifier: Modifier = Modifier) {
    // Local state so the demo never touches the persisted Home or Settings geometry.
    var state by remember { mutableStateOf(OpenCodeSplitPaneState()) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(420.dp)
                .border(1.dp, openCodeColor("border-weak-base")),
    ) {
        OpenCodeSplitPane(
            state = state,
            onStateChange = { state = it },
            leading = {
                Column(modifier = Modifier.fillMaxSize()) {
                    OpenCodeTopBar(title = "Sessions")
                    OpenCodeEmptyState(
                        title = "Leading pane",
                        message = "Drag the handle to resize. Release below 220dp to close; tap to toggle. Changes and Terminals use the same handle, with a 500dp column floor.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            trailing = { control ->
                Column(modifier = Modifier.fillMaxSize()) {
                    OpenCodeTopBar(
                        title = "Detail",
                        leading = openCodeDetailLeading(control) {},
                    )
                    OpenCodeEmptyState(
                        title = "Trailing pane",
                        message = "The sidebar button shows or hides the leading pane.",
                        icon = OpenCodeIcons.Sidebar,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }
}
