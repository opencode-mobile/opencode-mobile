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
    OpenCodeChildSessionRow(
        title = agentName,
        caption = if (isBackground) "background" else null,
        subtitle = secondLine,
        elapsed = rememberElapsedLabel(timeStartEpochMs),
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Live `m:ss` since [startEpochMs], ticking once a second; null without a
 * start. Shared by in-flight child-session rows and tool chips.
 */
@Composable
internal fun rememberElapsedLabel(startEpochMs: Long?): String? {
    val nowEpochMs by produceState(
        initialValue = System.currentTimeMillis(),
        startEpochMs,
    ) {
        if (startEpochMs == null) return@produceState
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return SessionChatChildSessionElapsed.format(startEpochMs, nowEpochMs)
}
