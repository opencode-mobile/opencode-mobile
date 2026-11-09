package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.goal.SessionGoalAction
import dev.opencode.mobile.domain.goal.SessionGoalLimits
import dev.opencode.mobile.domain.goal.SessionGoalStatus
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.OpenCodeTag
import dev.opencode.mobile.ui.components.OpenCodeTagSize
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatGoalActivity
import dev.opencode.mobile.ui.sessionchat.SessionChatGoalCopy
import dev.opencode.mobile.ui.sessionchat.SessionChatGoalUiState
import dev.opencode.mobile.ui.sessionchat.SessionChatGoalWait
import dev.opencode.mobile.ui.text.resolve

/** A last-known goal while reconnecting is drawn at this alpha. */
private const val STALE_GOAL_ALPHA = 0.5f

/** The goal's mark: concentric rings. */
internal val SessionChatGoalIcon: ImageVector get() = OpenCodeIcons.RadioButtonChecked

/** What the timeline's goal rows need from Session Chat (#1007). */
internal data class SessionChatGoalTimelineContext(
    val goal: SessionChatGoalUiState? = null,
    val onOpenSheet: () -> Unit = {},
    val onAction: (SessionGoalAction) -> Unit = {},
    val onNewGoal: () -> Unit = {},
)

internal val LocalSessionChatGoal = compositionLocalOf { SessionChatGoalTimelineContext() }

/**
 * The goal pill, first in the top-left slot. Working: spinner + "Goal". Waiting: goal mark.
 * Paused: amber "Goal paused". Met: green "Goal met". Tapping opens the goal sheet.
 */
@Composable
internal fun SessionChatGoalPill(
    state: SessionChatGoalUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = SessionChatGoalCopy.pillLabel(state.activity).resolve()
    val spinning = state.activity == SessionChatGoalActivity.Working && !state.isStale
    val (layout, pillState) =
        when (state.activity) {
            SessionChatGoalActivity.Working ->
                if (spinning) {
                    OpenCodeFloatingPillLayout.Counter(label) to OpenCodeFloatingPillState.Live
                } else {
                    OpenCodeFloatingPillLayout.IconLabel(SessionChatGoalIcon, label) to OpenCodeFloatingPillState.Ready
                }
            SessionChatGoalActivity.Waiting ->
                OpenCodeFloatingPillLayout.IconLabel(SessionChatGoalIcon, label) to OpenCodeFloatingPillState.Ready
            SessionChatGoalActivity.Paused ->
                OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.Pause, label) to OpenCodeFloatingPillState.NeedsAttention
            SessionChatGoalActivity.Met ->
                OpenCodeFloatingPillLayout.IconLabel(SessionChatGoalIcon, label) to OpenCodeFloatingPillState.Done
        }
    OpenCodeFloatingPill(
        layout = layout,
        state = pillState,
        accessibilityLabel = SessionChatGoalCopy.pillAccessibilityLabel(state).resolve(),
        onClick = onClick,
        modifier = modifier.alpha(if (state.isStale) STALE_GOAL_ALPHA else 1f),
    )
}

/**
 * The kickoff's "Goal set" row. The current goal's row opens the sheet; an earlier goal's
 * row is greyed with an "ended" tag and does nothing.
 */
@Composable
internal fun SessionChatGoalSetRow(notice: TimelineNotice.GoalSet) {
    val context = LocalSessionChatGoal.current
    val current = context.goal?.goal?.id == notice.goalId
    val label = stringResource(R.string.session_chat_goal_set)
    val ended = stringResource(R.string.session_chat_goal_ended)
    val value = SessionChatGoalCopy.goalSetValue(notice.description)
    val tone = if (current) openCodeColor("text-base") else openCodeColor("text-weaker")
    val style = MaterialTheme.typography.bodySmall
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(OpenCodeSmallShape)
                .then(if (current) Modifier.clickable(role = Role.Button, onClick = context.onOpenSheet) else Modifier)
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(label, value, ended.takeUnless { current }).joinToString(", ")
                },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = SessionChatGoalIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tone)
        Text(text = label, style = style.copy(fontWeight = FontWeight.SemiBold), color = tone, maxLines = 1)
        Text(
            text = value,
            modifier = Modifier.weight(1f, fill = false),
            style = style,
            color = if (current) openCodeColor("info") else tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!current) OpenCodeTag(title = ended, size = OpenCodeTagSize.Small)
    }
}

/**
 * A paused or met goal's row at the end of the timeline. Paused: the pause title and reason
 * with Resume and Clear. Met: "Goal met · <evidence>" with New goal… and Clear.
 */
@Composable
internal fun SessionChatGoalStatusRow() {
    val context = LocalSessionChatGoal.current
    val state = context.goal?.takeIf { it.hasStatusRow } ?: return
    val goal = state.goal
    val met = goal.status == SessionGoalStatus.Complete
    val title = if (met) stringResource(R.string.session_chat_goal_met) else SessionChatGoalCopy.pauseTitle(goal).resolve()
    val reason = if (met) goal.reason else SessionChatGoalCopy.pauseReason(goal)
    val accent = if (met) openCodeColor("success") else openCodeColor("warning")
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (state.isStale) STALE_GOAL_ALPHA else 1f),
        shape = OpenCodeSmallShape,
        color = openCodeColor("surface-raised-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (met) OpenCodeIcons.Check else OpenCodeIcons.Pause,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    tint = accent,
                )
                val weak = openCodeColor("text-weak")
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                            if (!reason.isNullOrBlank()) {
                                withStyle(SpanStyle(color = weak)) { append(" · $reason") }
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-strong"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (met) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.CompactText(stringResource(R.string.session_chat_goal_new)),
                        onClick = context.onNewGoal,
                        enabled = state.actionsEnabled,
                        contrast = OpenCodeButtonContrast.High,
                    )
                } else {
                    GoalActionButton(state, SessionGoalAction.Resume, OpenCodeButtonContrast.High, context.onAction)
                }
                GoalActionButton(state, SessionGoalAction.Clear, OpenCodeButtonContrast.Low, context.onAction)
            }
        }
    }
}

@Composable
private fun GoalActionButton(
    state: SessionChatGoalUiState,
    action: SessionGoalAction,
    contrast: OpenCodeButtonContrast,
    onAction: (SessionGoalAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeButton(
        variant = OpenCodeButtonVariant.CompactText(SessionChatGoalCopy.actionLabel(action, pending = state.pending == action).resolve()),
        onClick = { onAction(action) },
        modifier = modifier,
        enabled = state.actionsEnabled,
        contrast = contrast,
    )
}

/** The goal sheet: objective, progress, the reviewer's last note, and the goal's actions. */
@Composable
internal fun SessionChatGoalSheet(
    state: SessionChatGoalUiState?,
    onDismiss: () -> Unit,
    onAction: (SessionGoalAction) -> Unit,
    onNewGoal: () -> Unit,
    onToggleObjective: () -> Unit,
) {
    // Keeps the last goal while the sheet animates out.
    var shown by remember { mutableStateOf(state) }
    if (state != null) shown = state
    val goalState = shown ?: return
    val goal = goalState.goal
    val title = stringResource(R.string.session_chat_goal_sheet_title)
    OpenCodeBottomSheet(
        visible = state?.sheetOpen == true,
        title = title,
        onDismissRequest = onDismiss,
        titleContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = openCodeColor("text-strong"), maxLines = 1)
                OpenCodeTag(title = SessionChatGoalCopy.tag(goalState.activity).resolve(), size = OpenCodeTagSize.Small)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().openCodeBottomSheetContentInset().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (goalState.isStale) {
                Text(
                    text = stringResource(R.string.session_chat_goal_reconnecting),
                    style = MaterialTheme.typography.labelMedium,
                    color = openCodeColor("text-weak"),
                )
            }
            val long = goal.objective.length > SessionChatGoalCopy.OBJECTIVE_CLAMP_CHARACTERS
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = goal.objective,
                    style = MaterialTheme.typography.bodyLarge,
                    color = openCodeColor("text-strong"),
                    maxLines = if (long && !goalState.objectiveExpanded) SessionChatGoalCopy.OBJECTIVE_CLAMP_LINES else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                if (long) {
                    OpenCodeButton(
                        variant =
                            OpenCodeButtonVariant.Link(
                                stringResource(
                                    if (goalState.objectiveExpanded) {
                                        R.string.session_chat_goal_show_less
                                    } else {
                                        R.string.session_chat_goal_show_all
                                    },
                                ),
                            ),
                        onClick = onToggleObjective,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (goal.status == SessionGoalStatus.Active) {
                    LinearProgressIndicator(
                        progress = { SessionChatGoalCopy.progressFraction(goal) },
                        modifier = Modifier.fillMaxWidth(),
                        color = openCodeColor("interactive"),
                        trackColor = openCodeColor("border-weak-base"),
                        drawStopIndicator = {},
                    )
                }
                Text(
                    text = SessionChatGoalCopy.progress(goal).resolve(),
                    style = MaterialTheme.typography.labelMedium,
                    color = openCodeColor("text-weak"),
                )
                SessionChatGoalCopy.stalls(goal)?.let { stalls ->
                    Text(text = stalls.resolve(), style = MaterialTheme.typography.labelMedium, color = openCodeColor("warning"))
                }
            }
            GoalSheetNote(goalState)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Same quiet style as the status row's Clear; the main action stays the strong one.
                GoalActionButton(goalState, SessionGoalAction.Clear, OpenCodeButtonContrast.Low, onAction)
                Spacer(modifier = Modifier.weight(1f))
                when (goal.status) {
                    SessionGoalStatus.Active ->
                        GoalActionButton(goalState, SessionGoalAction.Pause, OpenCodeButtonContrast.High, onAction)
                    SessionGoalStatus.Paused ->
                        GoalActionButton(goalState, SessionGoalAction.Resume, OpenCodeButtonContrast.High, onAction)
                    SessionGoalStatus.Complete ->
                        OpenCodeButton(
                            variant = OpenCodeButtonVariant.CompactText(stringResource(R.string.session_chat_goal_new)),
                            onClick = onNewGoal,
                            enabled = goalState.actionsEnabled,
                            contrast = OpenCodeButtonContrast.High,
                        )
                }
            }
            Text(
                text = SessionChatGoalCopy.footnote(goal).resolve(),
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-weak"),
            )
        }
    }
}

/** The sheet's note: the reviewer's last word, what the goal waits on, why it paused, or the evidence. */
@Composable
private fun GoalSheetNote(state: SessionChatGoalUiState) {
    val goal = state.goal
    val (heading, body, tone) =
        when (goal.status) {
            SessionGoalStatus.Active ->
                when (val wait = state.wait) {
                    SessionChatGoalWait.Permission ->
                        Triple(
                            stringResource(R.string.session_chat_goal_waiting_on_you),
                            stringResource(R.string.session_chat_goal_waiting_permission),
                            openCodeColor("warning"),
                        )
                    SessionChatGoalWait.Question ->
                        Triple(
                            stringResource(R.string.session_chat_goal_waiting_on_you),
                            stringResource(R.string.session_chat_goal_waiting_question),
                            openCodeColor("warning"),
                        )
                    is SessionChatGoalWait.Shell ->
                        Triple(
                            stringResource(R.string.session_chat_goal_waiting),
                            stringResource(R.string.session_chat_goal_waiting_shell, wait.command, SessionGoalLimits.WAIT_LIMIT_MINUTES),
                            openCodeColor("text-weak"),
                        )
                    SessionChatGoalWait.Subagent ->
                        Triple(
                            stringResource(R.string.session_chat_goal_waiting),
                            stringResource(R.string.session_chat_goal_waiting_subagent, SessionGoalLimits.WAIT_LIMIT_MINUTES),
                            openCodeColor("text-weak"),
                        )
                    null ->
                        if (goal.reason != null) {
                            Triple(
                                stringResource(R.string.session_chat_goal_reviewer_last_round),
                                stringResource(R.string.session_chat_goal_not_met_yet, goal.reason),
                                openCodeColor("text-weak"),
                            )
                        } else {
                            Triple(
                                stringResource(R.string.session_chat_goal_reviewer),
                                stringResource(R.string.session_chat_goal_reviewer_pending),
                                openCodeColor("text-weak"),
                            )
                        }
                }
            SessionGoalStatus.Paused ->
                Triple(SessionChatGoalCopy.pauseTitle(goal).resolve(), SessionChatGoalCopy.pauseReason(goal), openCodeColor("warning"))
            SessionGoalStatus.Complete ->
                Triple(stringResource(R.string.session_chat_goal_evidence), goal.reason, openCodeColor("success"))
        }
    GoalNote(heading = heading, body = body, headingColor = tone)
}

@Composable
private fun GoalNote(
    heading: String,
    body: String?,
    headingColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OpenCodeSmallShape,
        color = openCodeColor("surface-raised-base"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = heading, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = headingColor)
            if (!body.isNullOrBlank()) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium, color = openCodeColor("text-base"))
            }
        }
    }
}
