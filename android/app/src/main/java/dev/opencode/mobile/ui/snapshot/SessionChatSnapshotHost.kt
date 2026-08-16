package dev.opencode.mobile.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeSessionComposer
import dev.opencode.mobile.ui.components.OpenCodeSessionComposerState
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.sessionchat.view.SessionChatChildSessionRow

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
                OpenCodeAssistantMessage(text = message.text)
            }

        SnapshotEvidenceRole.ChildSession ->
            message.childSession?.let { childSession ->
                SnapshotEvidenceChildSessionRow(mock = childSession)
            }
    }
}

@Composable
fun SnapshotEvidenceChildSessionRow(
    mock: SnapshotEvidenceChildSession,
    modifier: Modifier = Modifier,
) {
    SessionChatChildSessionRow(
        agentName = mock.agentName,
        isBackground = mock.isBackground,
        secondLine = mock.secondLine,
        timeStartEpochMs = mock.timeStartEpochMs,
        onClick = if (mock.showsChevron) ({}) else null,
        modifier = modifier.fillMaxWidth(),
    )
}
