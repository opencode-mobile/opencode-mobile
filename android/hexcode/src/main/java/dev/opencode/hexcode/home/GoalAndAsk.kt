package dev.opencode.hexcode.home

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.components.HexAskLabels
import dev.opencode.hexcode.designsystem.components.HexAskMessage
import dev.opencode.hexcode.designsystem.components.HexAskState
import dev.opencode.hexcode.designsystem.components.HexButton
import dev.opencode.hexcode.designsystem.components.HexButtonSize
import dev.opencode.hexcode.designsystem.components.HexButtonStyle
import dev.opencode.hexcode.designsystem.components.HexDetailRow
import dev.opencode.hexcode.designsystem.components.HexDetailsSection
import dev.opencode.hexcode.designsystem.components.HexGoalBox
import dev.opencode.hexcode.designsystem.components.HexGoalBoxState
import dev.opencode.hexcode.designsystem.components.HexGoalLine
import dev.opencode.hexcode.designsystem.components.HexGoalLineState
import dev.opencode.hexcode.designsystem.components.HexMenu
import dev.opencode.hexcode.designsystem.components.HexMenuItem
import dev.opencode.hexcode.designsystem.components.HexMenuTone
import dev.opencode.hexcode.designsystem.components.HexSheet
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import dev.opencode.hexcode.designsystem.components.HexToastMessage
import dev.opencode.hexcode.designsystem.components.HexToastTone
import dev.opencode.hexcode.session.AskMessage
import dev.opencode.hexcode.session.AskReply
import dev.opencode.hexcode.session.AskedAnswer
import dev.opencode.hexcode.session.Goal
import dev.opencode.hexcode.session.GoalAction
import dev.opencode.hexcode.session.GoalLine
import dev.opencode.hexcode.session.GoalPhase
import dev.opencode.hexcode.session.SummaryLinks
import dev.opencode.hexcode.session.recentAge

/** The goal line under the top bar (app-plan.md, The goal › Line states). A tap opens the goal sheet. */
@Composable
internal fun GoalLineRow(
    line: GoalLine,
    goal: Goal?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (line) {
        GoalLine.Empty -> HexGoalLine(HexGoalLineState.Empty, stringResource(R.string.goal_line_empty), onClick, modifier)
        is GoalLine.Phase ->
            HexGoalLine(
                when (line.phase) {
                    GoalPhase.Starting, is GoalPhase.Round -> HexGoalLineState.Active
                    GoalPhase.Waiting -> HexGoalLineState.Waiting
                    GoalPhase.Paused -> HexGoalLineState.Paused
                    GoalPhase.Done -> HexGoalLineState.Done
                },
                goal?.shownObjective.orEmpty(),
                onClick,
                modifier,
                suffix =
                    when (val phase = line.phase) {
                        GoalPhase.Starting -> stringResource(R.string.goal_line_starting)
                        is GoalPhase.Round -> stringResource(R.string.goal_line_round, phase.round)
                        GoalPhase.Waiting -> stringResource(R.string.goal_line_waiting)
                        GoalPhase.Paused -> stringResource(R.string.goal_line_paused)
                        GoalPhase.Done -> stringResource(R.string.goal_line_done)
                    },
            )
    }
}

/**
 * The goal sheet (app-plan.md, The goal sheet): the goal box, the fine print, and the buttons for the goal's state. Say
 * the goal, Change, New goal and Again are held to dictate into the box, as Say why; Set or Replace sends it.
 */
@Composable
internal fun BoxScope.GoalSheetView(
    state: HomeUiState,
    vm: HomeViewModel,
    micReady: () -> Boolean,
) {
    val sheet = state.goalSheet
    // What shows stays while the sheet slides away.
    var shown by remember { mutableStateOf(GoalSheet()) }
    if (sheet != null) shown = sheet
    val goal = state.today.goal
    val edit = shown.edit
    HexSheet(
        visible = sheet != null,
        onDismiss = vm::closeGoal,
        title = stringResource(R.string.goal_sheet_title),
        trailing = HexSheetAction(stringResource(R.string.common_done), vm::closeGoal),
        footer = { GoalButtons(goal, shown, vm, micReady) },
    ) {
        if (edit != null) {
            HexGoalBox(
                when (edit.take) {
                    GoalTake.Listening -> HexGoalBoxState.Listening
                    GoalTake.Transcribing -> HexGoalBoxState.Transcribing
                    null -> HexGoalBoxState.Filled
                },
                when (edit.take) {
                    GoalTake.Listening -> stringResource(R.string.draft_listening)
                    GoalTake.Transcribing -> stringResource(R.string.needs_transcribing)
                    null -> edit.text.orEmpty()
                },
                label = stringResource(if (goal != null) R.string.goal_box_label_new else R.string.goal_box_label_today),
                status = stringResource(R.string.goal_box_on_phone),
            )
        } else if (goal != null) {
            HexGoalBox(
                HexGoalBoxState.Set,
                goal.shownObjective,
                label = stringResource(R.string.goal_box_label),
                status =
                    when (val phase = goal.phase(state.today.busySince != null)) {
                        GoalPhase.Starting -> stringResource(R.string.goal_box_active_starting)
                        is GoalPhase.Round -> stringResource(R.string.goal_box_active, phase.round, phase.of)
                        GoalPhase.Waiting -> stringResource(R.string.goal_box_active_waiting)
                        GoalPhase.Paused -> stringResource(R.string.goal_line_paused)
                        GoalPhase.Done -> stringResource(R.string.goal_line_done)
                    },
                reason = goal.shownReason,
            )
        } else {
            HexGoalBox(HexGoalBoxState.Empty, stringResource(R.string.goal_box_empty))
        }
        Text(
            stringResource(R.string.goal_fine),
            style = HexTheme.type.footnote,
            color = HexTheme.colors.text3,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * Where the goal sheet's hold-to-say button is, and whether it takes a hold, for the footer's gesture; and [toggle], a
 * screen reader's activate, which starts the take or finishes it.
 */
private class HoldTarget {
    var bounds by mutableStateOf<Rect?>(null)
    var enabled by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var toggle: () -> Unit = {}
}

/**
 * Clear on the left; then Pause or Resume and Change, or New goal; or, while saying one, Cancel, Again and Set. Say the
 * goal, Change, New goal and Again are held to dictate (app-plan.md, The goal sheet › Saying it), as Say why in M4.
 * The hold is watched by the footer, which stays while its buttons change under the finger (the take turns them into
 * Cancel, Again and Set), so the take ends only when the finger lifts.
 */
@Composable
private fun GoalButtons(
    goal: Goal?,
    sheet: GoalSheet,
    vm: HomeViewModel,
    micReady: () -> Boolean,
) {
    val edit = sheet.edit
    val loading = sheet.busy
    val target = remember { HoldTarget() }
    val view = LocalView.current
    val ready by rememberUpdatedState(micReady)
    target.listening = edit?.take == GoalTake.Listening
    target.toggle = {
        if (target.listening) {
            vm.sheetTakeEnd()
        } else if (ready()) {
            vm.goalTakeStart()
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val bounds = target.bounds ?: return@awaitEachGesture
                    if (!target.enabled || !bounds.contains(down.position + origin)) return@awaitEachGesture
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (!ready() || !vm.goalTakeStart()) return@awaitEachGesture
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.id == down.id && it.pressed })
                    vm.sheetTakeEnd()
                }
            },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (edit != null) {
                val taking = edit.take != null
                HexButton(
                    onClick = vm::cancelGoalEdit,
                    text = stringResource(R.string.common_cancel),
                    size = HexButtonSize.Regular,
                    enabled = !taking,
                )
                Spacer(Modifier.weight(1f))
                HoldToSay(stringResource(R.string.goal_again), target, enabled = !taking && loading == null)
                HexButton(
                    onClick = vm::setGoal,
                    text = stringResource(if (goal != null) R.string.goal_replace else R.string.goal_set),
                    style = HexButtonStyle.Primary,
                    size = HexButtonSize.Regular,
                    enabled = !taking && !edit.text.isNullOrBlank(),
                    loading = loading == GoalAction.Change,
                )
                return@Row
            }
            val actions = GoalAction.of(goal)
            if (GoalAction.Clear in actions) {
                HexButton(
                    onClick = vm::clearGoal,
                    text = stringResource(R.string.goal_clear),
                    style = HexButtonStyle.Danger,
                    size = HexButtonSize.Regular,
                    loading = loading == GoalAction.Clear,
                )
            }
            Spacer(Modifier.weight(1f))
            actions.filter { it != GoalAction.Clear }.forEach { action ->
                when (action) {
                    GoalAction.Pause ->
                        HexButton(
                            onClick = vm::pauseGoal,
                            text = stringResource(R.string.goal_pause),
                            size = HexButtonSize.Regular,
                            loading = loading == GoalAction.Pause,
                        )
                    GoalAction.Resume ->
                        HexButton(
                            onClick = vm::resumeGoal,
                            text = stringResource(R.string.goal_resume),
                            size = HexButtonSize.Regular,
                            loading = loading == GoalAction.Resume,
                        )
                    GoalAction.Say -> HoldToSay(stringResource(R.string.goal_say), target, primary = true)
                    GoalAction.Change -> HoldToSay(stringResource(R.string.goal_change), target, enabled = loading == null)
                    GoalAction.New -> HoldToSay(stringResource(R.string.goal_new), target, enabled = loading == null)
                    GoalAction.Clear -> Unit
                }
            }
        }
    }
}

/** A `mic` button held to dictate the goal; it only draws and says where it is: the footer takes the hold. */
@Composable
private fun HoldToSay(
    text: String,
    target: HoldTarget,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    target.enabled = enabled
    val hint = stringResource(R.string.a11y_say_hint)
    // The footer takes the finger's hold; a screen reader activates the button instead: start, then finish
    // (app-plan.md, Accessibility › Hold-to-dictate options). While a take records it stays usable to finish it.
    Box(
        Modifier.clearAndSetSemantics {
            role = Role.Button
            contentDescription = text
            stateDescription = hint
            if (enabled || target.listening) {
                onClick {
                    target.toggle()
                    true
                }
            }
        },
    ) {
        HexButton(
            onClick = {},
            text = text,
            icon = HexIcons.mic,
            style = if (primary) HexButtonStyle.Primary else HexButtonStyle.Secondary,
            size = HexButtonSize.Regular,
            enabled = enabled,
            modifier = Modifier.onGloballyPositioned { target.bounds = it.boundsInRoot() },
        )
    }
}

/** The draft's ⋯ (app-plan.md, Set as goal from a draft): it opens and closes the menu ([DraftMenu]). */
@Composable
internal fun DraftMore(vm: HomeViewModel) {
    HexButton(onClick = vm::toggleDraftMenu, icon = HexIcons.more, contentDescription = stringResource(R.string.draft_more))
}

/**
 * The draft's menu (`.dmenu`): a `HexMenu` 8 dp above the draft's action row, at its right edge, with Ask on the side
 * and Set as (or Replace) today's goal, disabled while any chunk is still on its way. Put it in a box round the row.
 */
@Composable
internal fun DraftMenu(
    state: HomeUiState,
    vm: HomeViewModel,
) {
    if (!state.draftMenu) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = AboveEnd(gap),
        onDismissRequest = vm::closeDraftMenu,
        properties = PopupProperties(focusable = true),
    ) {
        val ready = state.draftReady
        HexMenu(
            listOf(
                HexMenuItem(
                    HexIcons.ask,
                    stringResource(R.string.draft_menu_ask),
                    stringResource(R.string.draft_menu_ask_sub),
                    HexMenuTone.Default,
                    enabled = ready,
                    onClick = vm::askFromDraft,
                ),
                HexMenuItem(
                    HexIcons.target,
                    stringResource(if (state.today.goal != null) R.string.draft_menu_goal_replace else R.string.draft_menu_goal),
                    stringResource(R.string.draft_menu_goal_sub),
                    HexMenuTone.Green,
                    enabled = ready,
                    onClick = vm::goalFromDraft,
                ),
            ),
        )
    }
}

/** Places a pop-up [gap] above its anchor, its right edge on the anchor's, kept on screen. */
private class AboveEnd(
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** The answer as the card draws text: backticks boxed, other Markdown without its symbols. */
@Composable
private fun askText(answer: String): AnnotatedString =
    summaryText(
        answer
            .lines()
            .joinToString("\n") { line -> SummaryLinks.pull(line.trimStart('#', '>', ' ')).first },
    )

/** "now" under 45 s, then "3m", as ↻. */
@Composable
private fun askAge(
    at: Long,
    now: Long,
): String = recentAge(at, now) ?: stringResource(R.string.home_summary_updated_now)

/** The Ask message (app-plan.md, The Ask message): asking, folded, open, failed or empty. */
@Composable
internal fun AskMessageView(
    ask: AskMessage,
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow(15_000)
    val age = askAge(ask.askedAt, now)
    val context = LocalContext.current
    val state =
        when (ask.reply) {
            AskReply.Asking -> HexAskState.Asking
            is AskReply.Answered -> if (ask.open) HexAskState.Open else HexAskState.Folded
            AskReply.Empty -> HexAskState.Empty
            AskReply.Failed -> HexAskState.Failed
        }
    HexAskMessage(
        state = state,
        question = ask.question,
        age = age,
        labels =
            HexAskLabels(
                title = stringResource(R.string.ask_title, age),
                status =
                    stringResource(
                        when (ask.reply) {
                            AskReply.Empty -> R.string.ask_empty
                            AskReply.Failed -> R.string.ask_failed
                            else -> R.string.ask_reading
                        },
                    ),
                statusDetail =
                    when (ask.reply) {
                        AskReply.Empty -> stringResource(R.string.ask_empty_sub)
                        AskReply.Failed -> stringResource(R.string.ask_failed_sub)
                        else -> null
                    },
                onlyYou = stringResource(R.string.ask_only_you),
                copy = stringResource(R.string.ask_copy),
                retry = stringResource(R.string.ask_try_again),
                fold = stringResource(R.string.ask_fold),
                close = stringResource(R.string.ask_close),
            ),
        modifier = modifier,
        answer = ask.answer?.let { askText(it) },
        onOpen = vm::openAsk,
        onFold = vm::foldAsk,
        onClose = vm::closeAsk,
        onCopy = {
            val answer = ask.answer ?: return@HexAskMessage
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Answer", answer))
            // Android 13 and later confirm a copy themselves.
            vm.askCopied(confirm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        },
        onRetry = vm::retryAsk,
    )
}

/** Details' Asked on the side: every closed answer, newest first; a tap shows it as the open Ask message. */
@Composable
internal fun AskedSection(
    asked: List<AskedAnswer>,
    vm: HomeViewModel,
) {
    HexDetailsSection(
        stringResource(R.string.details_asked),
        suffix = stringResource(R.string.details_asked_suffix),
        count = asked.size.toString(),
    ) {
        asked.forEachIndexed { index, row ->
            HexDetailRow(
                AnnotatedString(row.question),
                leading = { Icon(HexIcons.ask, null, tint = HexTheme.colors.text3, modifier = Modifier.size(HexTheme.size.icon)) },
                detail = SummaryLinks.pull(row.answer).first.replace('\n', ' '),
                chevron = true,
                divider = index > 0,
                onClick = { vm.showAsked(index) },
            )
        }
    }
}

/** The goal's notices (app-plan.md, The goal sheet and Set as goal from a draft). */
internal class GoalNoticeTexts(
    private val texts: Map<HomeNotice, HexToastMessage>,
) {
    fun of(notice: HomeNotice?): HexToastMessage? = texts[notice]
}

@Composable
internal fun goalNoticeTexts(): GoalNoticeTexts {
    val set = stringResource(R.string.goal_notice_set)
    val replaced = stringResource(R.string.goal_notice_replaced)
    val idle = stringResource(R.string.goal_notice_set_idle)
    val busy = stringResource(R.string.goal_notice_set_busy)
    val paused = stringResource(R.string.goal_notice_paused)
    val pausedSub = stringResource(R.string.goal_notice_paused_sub)
    val resumed = stringResource(R.string.goal_notice_resumed)
    val resumedSub = stringResource(R.string.goal_notice_resumed_sub)
    val cleared = stringResource(R.string.goal_notice_cleared)
    val clearedBusy = stringResource(R.string.goal_notice_cleared_busy)
    val stale = stringResource(R.string.goal_notice_stale)
    val failed = stringResource(R.string.goal_notice_failed)
    val failedSub = stringResource(R.string.goal_notice_failed_sub)
    val word = stringResource(R.string.goal_refused_word)
    val wordSub = stringResource(R.string.goal_refused_word_sub)
    val long = stringResource(R.string.goal_refused_long)
    val longSub = stringResource(R.string.goal_refused_long_sub)
    return remember(set, replaced, idle, busy) {
        GoalNoticeTexts(
            mapOf(
                HomeNotice.GoalSetIdle to HexToastMessage(set, HexToastTone.Steer, idle, durationMs = 3_000),
                HomeNotice.GoalSetBusy to HexToastMessage(set, HexToastTone.Steer, busy, durationMs = 3_000),
                HomeNotice.GoalReplacedIdle to HexToastMessage(replaced, HexToastTone.Steer, idle, durationMs = 3_000),
                HomeNotice.GoalReplacedBusy to HexToastMessage(replaced, HexToastTone.Steer, busy, durationMs = 3_000),
                HomeNotice.GoalPaused to HexToastMessage(paused, HexToastTone.Info, pausedSub),
                HomeNotice.GoalResumed to HexToastMessage(resumed, HexToastTone.Steer, resumedSub),
                HomeNotice.GoalCleared to HexToastMessage(cleared, HexToastTone.Info),
                HomeNotice.GoalClearedBusy to HexToastMessage(cleared, HexToastTone.Info, clearedBusy),
                HomeNotice.GoalStale to HexToastMessage(stale, HexToastTone.Info),
                HomeNotice.GoalFailed to HexToastMessage(failed, HexToastTone.Stop, failedSub),
                HomeNotice.GoalRefusedWord to HexToastMessage(word, HexToastTone.Info, wordSub),
                HomeNotice.GoalRefusedLong to HexToastMessage(long, HexToastTone.Info, longSub),
            ),
        )
    }
}
