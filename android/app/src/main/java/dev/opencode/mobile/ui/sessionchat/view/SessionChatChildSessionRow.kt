package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRow
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed
import kotlinx.coroutines.delay

@Composable
fun SessionChatChildSessionRow(
    agentName: String,
    isBackground: Boolean,
    secondLine: String?,
    timeStartEpochMs: Long?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val nowEpochMs by produceState(
        initialValue = System.currentTimeMillis(),
        timeStartEpochMs,
    ) {
        if (timeStartEpochMs == null) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    OpenCodeChildSessionRow(
        title = agentName,
        caption = if (isBackground) "background" else null,
        subtitle = secondLine,
        elapsed = SessionChatChildSessionElapsed.format(timeStartEpochMs, nowEpochMs),
        onClick = onClick,
        modifier = modifier,
    )
}
