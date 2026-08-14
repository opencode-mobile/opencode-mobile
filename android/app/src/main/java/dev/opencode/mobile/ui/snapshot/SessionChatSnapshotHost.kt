package dev.opencode.mobile.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
fun SessionChatSnapshotHost(
    sessionTitle: String,
    messages: List<SnapshotEvidenceMessage>,
    composerState: OpenCodeSessionComposerState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        OpenCodeTopBar(
            title = sessionTitle,
            leading = OpenCodeTopBarLeading.Back(onClick = {}),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            messages.forEach { message ->
                SnapshotEvidenceRow(message = message)
            }
        }
        OpenCodeSessionComposer(
            state = composerState,
            onAction = {},
        )
    }
}

@Composable
fun SnapshotEvidenceAssistantBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.ifBlank { "…" },
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SnapshotEvidenceRow(message: SnapshotEvidenceMessage) {
    when (message.role) {
        SnapshotEvidenceRole.User ->
            OpenCodeOutgoingMessage(
                text = message.text,
                metadataLabel = message.metadataLabel.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )

        SnapshotEvidenceRole.Assistant ->
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                SnapshotEvidenceAssistantBubble(text = message.text)
            }
    }
}
