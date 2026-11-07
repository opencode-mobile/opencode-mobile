package dev.opencode.hexcode.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexDetailRow
import dev.opencode.hexcode.designsystem.components.HexDetailsSection
import dev.opencode.hexcode.designsystem.components.HexDot
import dev.opencode.hexcode.designsystem.components.HexDotTone
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexSheetSize
import dev.opencode.hexcode.designsystem.components.HexThumbnail
import dev.opencode.hexcode.designsystem.components.HexThumbnailSize
import dev.opencode.hexcode.session.DetailsBody
import dev.opencode.hexcode.session.DetailsSection
import dev.opencode.hexcode.session.DetailsSections
import dev.opencode.hexcode.session.PendingItem
import dev.opencode.hexcode.session.RecentOutcome
import dev.opencode.hexcode.session.RecentStep
import dev.opencode.hexcode.session.RunningNow
import dev.opencode.hexcode.session.RunningRow
import dev.opencode.hexcode.session.TodoItem
import dev.opencode.hexcode.session.elapsedClock
import dev.opencode.hexcode.session.recentAge

/**
 * Details (app-plan.md, Needs you and control › Details): Needs you, Waiting to send, Running now, the to-do list and
 * Recent, in that order, each left out when empty; "Nothing running right now." when all are. It updates live while
 * open. Asked on the side lists the closed Ask answers.
 */
@Composable
internal fun BoxScope.DetailsSheet(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val today = state.today
    val now = rememberNow(1_000)
    val running = RunningNow.of(today.runningChildren, today.children, today.shells, now)
    val sections =
        DetailsSections.of(
            pending = today.pending.size,
            waiting = today.inbox.size,
            asked = today.asked.size,
            running = running.total,
            todos = today.todos.size,
            recent = state.recent.size,
        )
    val body = DetailsSections.body(sections, state.detailsReading)
    // What shows in the sheet's first frame is just there; what arrives after (also after the spinner) fades in.
    val opening = rememberOpening(state.detailsOpen)
    // Opens at half the screen whatever has loaded, so it never jumps (app-plan.md, Details › Size and first load).
    HexSheet(
        visible = state.detailsOpen,
        onDismiss = vm::closeDetails,
        title = stringResource(R.string.details_title),
        trailing = HexSheetAction(stringResource(R.string.common_done), vm::closeDetails),
        size = HexSheetSize.Medium,
        loading = body == DetailsBody.Loading,
    ) {
        if (body == DetailsBody.Empty) {
            FadeInLate(opening) {
                Text(
                    stringResource(R.string.details_empty),
                    style = HexTheme.type.footnote,
                    color = HexTheme.colors.text3,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                )
            }
        }
        sections.forEach { section ->
            key(section) {
                FadeInLate(opening) {
                    when (section) {
                        DetailsSection.NeedsYou -> NeedsYou(today.pending, vm)
                        DetailsSection.Waiting -> Waiting(state, vm)
                        DetailsSection.AskedOnTheSide -> AskedSection(today.asked, vm)
                        DetailsSection.Running -> Running(running, now)
                        DetailsSection.Todo -> Todos(today.todos)
                        DetailsSection.Recent -> Recent(state.recent, now)
                    }
                }
            }
        }
    }
}

/**
 * True in the first frame after [key] changes (the sheet opening) or this first composes, false after: what composes
 * then was there from the start.
 */
@Composable
private fun rememberOpening(key: Any = Unit): () -> Boolean {
    val first = remember(key) { booleanArrayOf(true) }
    LaunchedEffect(key) { first[0] = false }
    return { first[0] }
}

/**
 * [content], faded in over 0.2 s when it arrives after the sheet opened ([opening] false when it first composes);
 * instant with Reduce Motion, and when it was there from the start.
 */
@Composable
private fun FadeInLate(
    opening: () -> Boolean,
    content: @Composable () -> Unit,
) {
    val reduced = HexMotion.reduced
    val fade = HexTheme.motion.fade<Float>()
    val alpha = remember { Animatable(if (opening() || reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (alpha.value < 1f) alpha.animateTo(1f, fade) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

@Composable
private fun NeedsYou(
    pending: List<PendingItem>,
    vm: HomeViewModel,
) {
    HexDetailsSection(stringResource(R.string.details_needs)) {
        pending.forEachIndexed { index, item ->
            HexDetailRow(
                AnnotatedString(
                    stringResource(
                        if (item.kind == PendingItem.Kind.Approval) R.string.details_needs_approval else R.string.details_needs_questions,
                    ),
                ),
                leading = { Icon(HexIcons.alert, null, tint = HexTheme.colors.amber, modifier = Modifier.size(HexTheme.size.icon)) },
                detail = item.text.ifBlank { null },
                chevron = true,
                divider = index > 0,
                onClick = { vm.openNeeds(item.id) },
            )
        }
    }
}

/** Every message waiting, oldest first: its text, its images, queue or steer, and Cancel with a spinner. */
@Composable
private fun Waiting(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    val inbox = state.today.inbox
    HexDetailsSection(stringResource(R.string.details_waiting), count = inbox.size.toString()) {
        inbox.forEachIndexed { index, item ->
            val own = state.queuedImages[item.id].orEmpty()
            val images = item.images
            HexDetailRow(
                AnnotatedString(
                    item.text.ifEmpty {
                        if (images >
                            0
                        ) {
                            pluralStringResource(R.plurals.home_waiting_images, images, images)
                        } else {
                            ""
                        }
                    },
                ),
                leading = { Icon(HexIcons.send, null, tint = HexTheme.colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall)) },
                titleLines = 2,
                detail = stringResource(if (item.delivery == "steer") R.string.details_waiting_steer else R.string.details_waiting_queue),
                below =
                    if (own.isNotEmpty()) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                own.take(Draft.MAX_IMAGES).forEach { image ->
                                    HexThumbnail(rememberThumbnail(image.file, HexThumbnailSize.Small.side), size = HexThumbnailSize.Small)
                                }
                            }
                        }
                    } else {
                        null
                    },
                action = {
                    HexButton(
                        onClick = { vm.cancelQueued(item.id) },
                        text = stringResource(R.string.home_waiting_cancel),
                        loading = item.id in state.cancelling,
                    )
                },
                divider = index > 0,
            )
        }
    }
}

/** Subagents first, then long-running agent shells; at most three rows, then "+N more". */
@Composable
private fun Running(
    running: RunningNow,
    now: Long,
) {
    HexDetailsSection(stringResource(R.string.details_running), count = running.total.toString()) {
        running.rows.forEachIndexed { index, row ->
            when (row) {
                is RunningRow.Subagent ->
                    HexDetailRow(
                        AnnotatedString(row.title),
                        leading = { HexDot(HexDotTone.Green) },
                        detail = stringResource(R.string.details_running_subagent, row.agent ?: ""),
                        time = row.since?.let { elapsedClock(it, now) },
                        divider = index > 0,
                    )
                is RunningRow.Shell ->
                    HexDetailRow(
                        AnnotatedString(row.command),
                        leading = { HexDot(HexDotTone.Neutral) },
                        code = true,
                        detail = stringResource(R.string.details_running_shell),
                        time = elapsedClock(row.since, now),
                        divider = index > 0,
                    )
            }
        }
        if (running.more > 0) {
            HexDetailRow(AnnotatedString(stringResource(R.string.details_more, running.more)), done = true, divider = true)
        }
    }
}

/** The agent's whole list, cancelled items left out: done, in progress and pending, formatted as on the card. */
@Composable
private fun Todos(todos: List<TodoItem>) {
    val colors = HexTheme.colors
    val done = todos.count { it.status == TodoItem.Status.Completed }
    HexDetailsSection(
        stringResource(R.string.details_todo),
        suffix = stringResource(R.string.details_todo_suffix),
        count = "$done/${todos.size}",
    ) {
        todos.forEachIndexed { index, item ->
            val (icon, tint) =
                when (item.status) {
                    TodoItem.Status.Completed -> HexIcons.todoDone to colors.text2
                    TodoItem.Status.InProgress -> HexIcons.todoDoing to colors.solid
                    TodoItem.Status.Pending -> HexIcons.todoPending to colors.text3
                }
            HexDetailRow(
                summaryText(item.content),
                leading = { Icon(icon, null, tint = tint, modifier = Modifier.size(HexTheme.size.icon)) },
                titleLines = Int.MAX_VALUE,
                done = item.status == TodoItem.Status.Completed,
                divider = index > 0,
            )
        }
    }
}

/** The latest edits, commands and subagent starts, newest first, with their age. */
@Composable
private fun Recent(
    steps: List<RecentStep>,
    now: Long,
) {
    val opening = rememberOpening()
    HexDetailsSection(stringResource(R.string.details_recent)) {
        steps.forEachIndexed { index, step ->
            key(step) { FadeInLate(opening) { RecentRow(step, index, now) } }
        }
    }
}

@Composable
private fun RecentRow(
    step: RecentStep,
    index: Int,
    now: Long,
) {
    val icon =
        when (step.kind) {
            RecentStep.Kind.Edit -> HexIcons.pencil
            RecentStep.Kind.Command -> HexIcons.term
            RecentStep.Kind.Subagent -> HexIcons.spark
        }
    HexDetailRow(
        AnnotatedString(step.title),
        leading = { Icon(icon, null, tint = HexTheme.colors.text3, modifier = Modifier.size(HexTheme.size.iconSmall)) },
        code = step.kind != RecentStep.Kind.Subagent,
        detail = recentLine(step.outcome),
        time = recentAge(step.at, now) ?: stringResource(R.string.home_summary_updated_now),
        divider = index > 0,
    )
}

@Composable
private fun recentLine(outcome: RecentOutcome): String? =
    when (outcome) {
        is RecentOutcome.Edited -> stringResource(R.string.details_recent_edited, outcome.additions, outcome.deletions)
        is RecentOutcome.Created -> stringResource(R.string.details_recent_created, outcome.additions)
        is RecentOutcome.Files -> stringResource(R.string.details_recent_files, outcome.count, outcome.additions, outcome.deletions)
        RecentOutcome.Done -> stringResource(R.string.details_recent_done)
        is RecentOutcome.Failed -> stringResource(R.string.details_recent_failed, outcome.exit)
        RecentOutcome.Stopped -> stringResource(R.string.details_recent_stopped)
        RecentOutcome.Denied -> stringResource(R.string.details_recent_denied)
        RecentOutcome.Unknown -> null
        is RecentOutcome.Subagent -> stringResource(R.string.details_running_subagent, outcome.agent ?: "")
    }
