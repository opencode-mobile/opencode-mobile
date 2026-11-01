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
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderInset
import dev.opencode.mobile.ui.components.OpenCodeShellRow
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWorkPill
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionElapsed
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagent
import dev.opencode.mobile.ui.sessionchat.SessionChatSubagentStatus
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShellsCopy
import kotlinx.coroutines.delay

/**
 * Background work pill for Session Chat: "3 subagents" with a spinner, or the amber
 * "general needs input · 2 running" with a pulsing dot. Opens the background work panel.
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
 * The background work panel list: a Subagents section and a Shells section, each shown only
 * when it has rows, subagents first. Rows carry live timers. Tapping a subagent with a known
 * session opens it; tapping a shell opens its output.
 */
@Composable
fun SessionChatBackgroundWorkList(
    subagents: List<SessionChatSubagent>,
    shells: List<AgentShell>,
    onOpenSubagent: (SessionChatSubagent) -> Unit,
    onOpenShell: (AgentShell) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticking = subagents.any { it.isActive } || shells.isNotEmpty()
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
        if (subagents.isNotEmpty()) {
            OpenCodeSectionHeader(title = SUBAGENTS_SECTION_TITLE, inset = OpenCodeSectionHeaderInset.Flush)
            subagents.forEach { subagent ->
                OpenCodeChildSessionRow(
                    title = subagent.agentName,
                    subtitle = subagent.title,
                    elapsed = subagent.elapsed(nowMs),
                    status = subagent.status.rowStatus,
                    onClick = if (subagent.childSessionId == null) null else ({ onOpenSubagent(subagent) }),
                )
            }
        }
        if (shells.isNotEmpty()) {
            OpenCodeSectionHeader(title = SessionChatShellsCopy.SECTION_TITLE, inset = OpenCodeSectionHeaderInset.Flush)
            shells.forEach { shell ->
                OpenCodeShellRow(
                    command = shell.command,
                    description = shell.description,
                    elapsed = SessionChatChildSessionElapsed.format(shell.startedAtMs, nowMs),
                    onClick = { onOpenShell(shell) },
                )
            }
        }
    }
}

/** The background work panel: what the open session runs beside the conversation. */
@Composable
internal fun SessionChatBackgroundWorkSheet(
    visible: Boolean,
    subagents: List<SessionChatSubagent>,
    shells: List<AgentShell>,
    onOpenSubagent: (SessionChatSubagent) -> Unit,
    onOpenShell: (AgentShell) -> Unit,
    onDismiss: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_SUBAGENTS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )

    OpenCodeBottomSheet(
        visible = visible,
        title = BACKGROUND_WORK_TITLE,
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_SUBAGENTS,
                actionId = ProductAnalytics.Action.SUBAGENTS_DISMISS,
            )
            onDismiss()
        },
    ) {
        SessionChatBackgroundWorkList(
            subagents = subagents,
            shells = shells,
            onOpenSubagent = { subagent ->
                onDismiss()
                onOpenSubagent(subagent)
            },
            onOpenShell = { shell ->
                onDismiss()
                onOpenShell(shell)
            },
            modifier = Modifier.openCodeBottomSheetContentInset(),
        )
    }
}

internal const val BACKGROUND_WORK_TITLE = "Background work"
private const val SUBAGENTS_SECTION_TITLE = "Subagents"

internal val SessionChatSubagentStatus.rowStatus: OpenCodeChildSessionRowStatus
    get() =
        when (this) {
            SessionChatSubagentStatus.Running -> OpenCodeChildSessionRowStatus.Running
            SessionChatSubagentStatus.WaitingForInput -> OpenCodeChildSessionRowStatus.WaitingForInput
            SessionChatSubagentStatus.Done -> OpenCodeChildSessionRowStatus.Done
            SessionChatSubagentStatus.Failed -> OpenCodeChildSessionRowStatus.Failed
            SessionChatSubagentStatus.Stopped -> OpenCodeChildSessionRowStatus.Stopped
        }
