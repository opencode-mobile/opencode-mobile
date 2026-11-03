package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeFilterTab
import dev.opencode.mobile.ui.components.OpenCodeFilterTabs
import dev.opencode.mobile.ui.components.OpenCodeFilterTabsStyle
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderInset
import dev.opencode.mobile.ui.components.OpenCodeShellRow
import dev.opencode.mobile.ui.components.OpenCodeTaskRow
import dev.opencode.mobile.ui.components.OpenCodeTaskRowState
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWork
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWorkPill
import dev.opencode.mobile.ui.sessionchat.SessionChatBackgroundWorkTab
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
 * The background work panel list: a Running / Done segmented control over the rows (#966).
 * Running holds waiting and running subagents, then running shells; Done holds finished,
 * failed and stopped subagents, newest first. Rows carry live timers. Tapping a subagent
 * with a known session opens it; tapping a shell opens its output.
 */
@Composable
fun SessionChatBackgroundWorkList(
    subagents: List<SessionChatSubagent>,
    shells: List<AgentShell>,
    onOpenSubagent: (SessionChatSubagent) -> Unit,
    onOpenShell: (AgentShell) -> Unit,
    modifier: Modifier = Modifier,
    tab: SessionChatBackgroundWorkTab = SessionChatBackgroundWorkTab.Running,
    onTabSelected: (SessionChatBackgroundWorkTab) -> Unit = {},
    doneUnseen: Boolean = false,
) {
    val tabs = SessionChatBackgroundWork.tabs(subagents)
    val ticking = tabs.running.isNotEmpty() || shells.isNotEmpty()
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
        OpenCodeFilterTabs(
            tabs =
                listOf(
                    OpenCodeFilterTab(id = RUNNING_TAB_ID, title = RUNNING_TAB_TITLE, count = tabs.running.size + shells.size),
                    OpenCodeFilterTab(id = DONE_TAB_ID, title = DONE_TAB_TITLE, count = tabs.done.size, unseen = doneUnseen),
                ),
            selectedTabId = if (tab == SessionChatBackgroundWorkTab.Done) DONE_TAB_ID else RUNNING_TAB_ID,
            onTabSelected = { id ->
                onTabSelected(if (id == DONE_TAB_ID) SessionChatBackgroundWorkTab.Done else SessionChatBackgroundWorkTab.Running)
            },
            style = OpenCodeFilterTabsStyle.Segmented,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        when (tab) {
            SessionChatBackgroundWorkTab.Running -> {
                if (tabs.running.isEmpty() && shells.isEmpty()) {
                    EmptyTabText(RUNNING_EMPTY_TEXT)
                }
                val sectioned = tabs.running.isNotEmpty() && shells.isNotEmpty()
                if (sectioned) {
                    OpenCodeSectionHeader(title = SUBAGENTS_SECTION_TITLE, inset = OpenCodeSectionHeaderInset.Flush)
                }
                tabs.running.forEach { subagent -> SubagentRow(subagent, nowMs, onOpenSubagent) }
                if (shells.isNotEmpty()) {
                    if (sectioned) {
                        OpenCodeSectionHeader(title = SessionChatShellsCopy.SECTION_TITLE, inset = OpenCodeSectionHeaderInset.Flush)
                    }
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
            SessionChatBackgroundWorkTab.Done -> {
                if (tabs.done.isEmpty()) EmptyTabText(DONE_EMPTY_TEXT)
                tabs.done.forEach { subagent -> SubagentRow(subagent, nowMs, onOpenSubagent) }
            }
        }
    }
}

@Composable
private fun SubagentRow(
    subagent: SessionChatSubagent,
    nowMs: Long,
    onOpenSubagent: (SessionChatSubagent) -> Unit,
) {
    val text = SessionChatBackgroundWork.rowText(subagent, nowMs)
    OpenCodeTaskRow(
        title = text.title,
        state = subagent.status.taskRowState,
        tag = text.tag,
        detail = text.detail,
        detailCaption = text.detailCaption,
        time = text.time,
        onClick = if (subagent.childSessionId == null) null else ({ onOpenSubagent(subagent) }),
    )
}

@Composable
private fun EmptyTabText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = openCodeColor("text-weak"),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * The background work panel: what the open session runs beside the conversation. Opens on
 * Running; reports while Done is on screen so the view model can clear its dot.
 */
@Composable
internal fun SessionChatBackgroundWorkSheet(
    visible: Boolean,
    subagents: List<SessionChatSubagent>,
    shells: List<AgentShell>,
    onOpenSubagent: (SessionChatSubagent) -> Unit,
    onOpenShell: (AgentShell) -> Unit,
    onDismiss: () -> Unit,
    doneUnseen: Boolean = false,
    onDoneVisibleChange: (Boolean) -> Unit = {},
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_SUBAGENTS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )
    var tab by remember(visible) { mutableStateOf(SessionChatBackgroundWorkTab.Running) }
    val doneVisible = visible && tab == SessionChatBackgroundWorkTab.Done
    LaunchedEffect(doneVisible) { onDoneVisibleChange(doneVisible) }

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
            tab = tab,
            onTabSelected = { tab = it },
            doneUnseen = doneUnseen,
        )
    }
}

internal const val BACKGROUND_WORK_TITLE = "Background work"
private const val SUBAGENTS_SECTION_TITLE = "Subagents"
private const val RUNNING_TAB_ID = "running"
private const val DONE_TAB_ID = "done"
internal const val RUNNING_TAB_TITLE = "Running"
internal const val DONE_TAB_TITLE = "Done"
internal const val RUNNING_EMPTY_TEXT = "Nothing running. Finished work is under Done."
internal const val DONE_EMPTY_TEXT = "Nothing finished yet."

internal val SessionChatSubagentStatus.taskRowState: OpenCodeTaskRowState
    get() =
        when (this) {
            SessionChatSubagentStatus.Running -> OpenCodeTaskRowState.Running
            SessionChatSubagentStatus.WaitingForInput -> OpenCodeTaskRowState.WaitingForInput
            SessionChatSubagentStatus.Done -> OpenCodeTaskRowState.Done
            SessionChatSubagentStatus.Failed -> OpenCodeTaskRowState.Failed
            SessionChatSubagentStatus.Stopped -> OpenCodeTaskRowState.Stopped
        }
