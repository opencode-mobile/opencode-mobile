package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChatActivityState
import dev.opencode.mobile.ui.components.OpenCodeReasoningRow

@Composable
fun ReasoningRowGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenCodeReasoningRow(state = OpenCodeChatActivityState.Working)
        OpenCodeReasoningRow(
            state = OpenCodeChatActivityState.Expanded,
            durationLabel = "1.2s",
            onClick = {},
        )
        OpenCodeReasoningRow(state = OpenCodeChatActivityState.Expanded, onClick = {})
        OpenCodeReasoningRow(state = OpenCodeChatActivityState.Idle)
        OpenCodeReasoningRow(state = OpenCodeChatActivityState.Collapsed)
    }
}
