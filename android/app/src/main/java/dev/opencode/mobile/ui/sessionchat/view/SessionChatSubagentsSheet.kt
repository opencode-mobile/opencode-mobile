package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRow
import dev.opencode.mobile.ui.components.OpenCodeChildSessionRowStatus
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWorkPill
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagent
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus
import kotlinx.coroutines.delay

/**
 * Background work pill for Session Chat: "3 subagents" with a spinner, or the amber
 * "general needs input · 2 running" with a pulsing dot. Opens the Subagents panel.
 */
@Composable
fun SessionChatBackgroundWorkPill(
    pill: SessionChatBackgroundWorkPill,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    OpenCodeFloatingPill(
        layout = OpenCodeFloatingPillLayout.Counter(pill.label, waiting = pill.needsInput),
        state = if (pill.needsInput) OpenCodeFloatingPillState.NeedsAttention else OpenCodeFloatingPillState.Live,
        modifier = modifier,
        compact = compact,
        accessibilityLabel = pill.accessibilityLabel,
        onClick = onClick,
    )
}

/**
 * The Subagents panel list: each subagent with its status and a live timer. Tapping a
 * row with a known session opens it.
 */
@Composable
fun SessionChatSubagentsList(
    subagents: List<SessionChatSubagent>,
    onOpen: (SessionChatSubagent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticking = subagents.any { it.isActive }
    val nowMs by produceState(initialValue = System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        while (ticking) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        subagents.forEach { subagent ->
            OpenCodeChildSessionRow(
                title = subagent.agentName,
                subtitle = subagent.title,
                elapsed = subagent.elapsed(nowMs),
                status = subagent.status.rowStatus,
                onClick = if (subagent.childSessionId == null) null else ({ onOpen(subagent) }),
            )
        }
    }
}

@Composable
internal fun SessionChatSubagentsSheet(
    visible: Boolean,
    subagents: List<SessionChatSubagent>,
    onOpen: (SessionChatSubagent) -> Unit,
    onDismiss: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_SUBAGENTS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )

    OpenCodeBottomSheet(
        visible = visible,
        title = "Subagents",
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_SUBAGENTS,
                actionId = ProductAnalytics.Action.SUBAGENTS_DISMISS,
            )
            onDismiss()
        },
    ) {
        SessionChatSubagentsList(
            subagents = subagents,
            onOpen = { subagent ->
                onDismiss()
                onOpen(subagent)
            },
            modifier = Modifier.openCodeBottomSheetContentInset(),
        )
    }
}

internal val SessionChatSubagentStatus.rowStatus: OpenCodeChildSessionRowStatus
    get() =
        when (this) {
            SessionChatSubagentStatus.Running -> OpenCodeChildSessionRowStatus.Running
            SessionChatSubagentStatus.WaitingForInput -> OpenCodeChildSessionRowStatus.WaitingForInput
            SessionChatSubagentStatus.Done -> OpenCodeChildSessionRowStatus.Done
        }
