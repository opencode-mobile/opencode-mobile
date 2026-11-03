package dev.opencode.mobile.ui.sessionchat.view

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R
import dev.opencode.mobile.ui.components.OpenCodeBottomFadeOverhang
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPill
import dev.opencode.mobile.ui.components.sessionTodosPillAccessibilityLabel
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionChatActivityItem
import dev.opencode.mobile.ui.sessionchat.SessionChatActivityPriority
import dev.opencode.mobile.ui.sessionchat.SessionChatActivitySlot
import dev.opencode.mobile.ui.sessionchat.SessionChatContextItem
import dev.opencode.mobile.ui.sessionchat.SessionChatNavigationPill
import dev.opencode.mobile.ui.text.ResourcesUiTextLookup
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.UiTextLookup
import dev.opencode.mobile.ui.text.resolve

/** Where the bottom pill row sits and what it reserves in the timeline. */
internal object SessionChatCornerSlotsLayout {
    val HorizontalPadding = 16.dp
    val Spacing = 8.dp

    /** Gap between the composer (or a shown snackbar) and the pill row. */
    val GapAboveComposer = 8.dp
    private val PillHeight = 36.dp

    /**
     * Bottom padding of the pill row. [overlayInset] is the composer clearance
     * plus the bottom fade overhang that the timeline's end pin keeps clear.
     */
    fun rowBottom(overlayInset: Dp): Dp = (overlayInset - OpenCodeBottomFadeOverhang).coerceAtLeast(0.dp) + GapAboveComposer

    /**
     * Extra timeline clearance while an activity pill shows, so it never covers the last message.
     * Just the pill height: the resting end-pin gap above the composer supplies the 8 dp between
     * the pill and the last row. Scroll-to-end and Syncing are overlays and reserve nothing.
     */
    fun reservedHeight(hasActivity: Boolean): Dp = if (hasActivity) PillHeight else 0.dp
}

private const val PillRevealMs = 260

/** Animation label for tooling; not shown to users. */
private const val PILL_MORPH_ANIMATION_LABEL = "sessionChatPillMorph"

/**
 * Enters and leaves a bottom-row pill by animating its width (grow from zero, shrink to zero)
 * on top of the fade and scale, so the pills beside it move in step. Ease-out, no overshoot.
 */
@Composable
internal fun SessionChatPillReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val size = tween<androidx.compose.ui.unit.IntSize>(PillRevealMs, easing = FastOutSlowInEasing)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandHorizontally(size, expandFrom = Alignment.Start) + fadeIn(tween(PillRevealMs)) + scaleIn(tween(PillRevealMs), initialScale = 0.8f),
        exit = shrinkHorizontally(size, shrinkTowards = Alignment.Start) + fadeOut(tween(PillRevealMs)) + scaleOut(tween(PillRevealMs), targetScale = 0.8f),
    ) {
        content()
    }
}

/**
 * Switches a pill between forms by animating its width, so the pills beside it move in step.
 * The forms cross-fade inside the changing box; the shadow is not clipped. [state] is everything
 * that decides what [content] draws, so the outgoing form keeps its own look while it fades.
 */
@Composable
internal fun <T> SessionChatPillMorph(
    state: T,
    modifier: Modifier = Modifier,
    content: @Composable (state: T) -> Unit,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(PillRevealMs)) togetherWith fadeOut(tween(PillRevealMs / 2)))
                .using(SizeTransform(clip = false) { _, _ -> tween(PillRevealMs, easing = FastOutSlowInEasing) })
        },
        label = PILL_MORPH_ANIMATION_LABEL,
    ) { form ->
        content(form)
    }
}

/** The shape the primary activity pill is drawn in; the morph animates between these. */
private data class ActivityPillForm(
    val collapsed: Boolean,
    val compact: Boolean,
)

/**
 * Bottom-left live activity slot: one pill, a "+N" button, and the stack "+N" springs up.
 * Away from the end it is one round pill with a count badge that toggles the whole stack.
 */
@Composable
internal fun SessionChatActivitySlotView(
    slot: SessionChatActivitySlot,
    stackOpen: Boolean,
    onStackOpenChange: (Boolean) -> Unit,
    onClick: (SessionChatActivityItem) -> Unit,
    onClose: (SessionChatActivityItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stackAvailable = slot.showsOverflowButton || slot.collapsed
    LaunchedEffect(stackAvailable, slot.collapsed) { onStackOpenChange(false) }
    val primary = slot.primary ?: return
    // Collapsed, the stack lists everything with the primary nearest the circle. Otherwise it
    // lists the overflow with the next most urgent nearest the primary pill.
    val stackItems = if (slot.collapsed) listOf(primary) + slot.overflow else slot.overflow
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SessionChatCornerSlotsLayout.Spacing),
    ) {
        AnimatedVisibility(
            visible = stackOpen && stackAvailable,
            enter =
                fadeIn() +
                    expandVertically(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                        expandFrom = Alignment.Bottom,
                    ),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SessionChatCornerSlotsLayout.Spacing)) {
                // Lowest priority on top, so the most urgent sits nearest the bottom row.
                stackItems.asReversed().forEach { item ->
                    SessionChatActivityPill(
                        item = item,
                        compact = false,
                        onClick = {
                            onStackOpenChange(false)
                            onClick(item)
                        },
                        onClose = { onClose(item) },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SessionChatCornerSlotsLayout.Spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionChatPillMorph(
                state = ActivityPillForm(collapsed = slot.collapsed, compact = slot.collapsed || slot.primaryCompact),
            ) { form ->
                if (form.collapsed) {
                    SessionChatActivityPill(
                        item = primary,
                        compact = form.compact,
                        badgeCount = slot.itemCount,
                        accessibilityLabel = SessionChatActivityPillCopy.collapsedAccessibilityLabel(primary, slot.itemCount, stackOpen).resolve(),
                        onClick = { onStackOpenChange(!stackOpen) },
                        onClose = {},
                    )
                } else {
                    SessionChatActivityPill(
                        item = primary,
                        compact = form.compact,
                        onClick = {
                            onStackOpenChange(false)
                            onClick(primary)
                        },
                        onClose = { onClose(primary) },
                    )
                }
            }
            SessionChatPillReveal(visible = slot.showsOverflowButton) {
                val more = slot.overflow.size
                OpenCodeFloatingPill(
                    layout = OpenCodeFloatingPillLayout.Label("+${more.coerceAtLeast(1)}"),
                    state = OpenCodeFloatingPillState.Ready,
                    accessibilityLabel =
                        stringResource(
                            if (stackOpen) R.string.session_chat_activity_hide_more else R.string.session_chat_activity_show_more,
                            more,
                        ),
                    onClick = { onStackOpenChange(!stackOpen) },
                )
            }
        }
    }
}

@Composable
private fun SessionChatActivityPill(
    item: SessionChatActivityItem,
    compact: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    badgeCount: Int = 0,
    accessibilityLabel: String = SessionChatActivityPillCopy.accessibilityLabel(item).resolve(),
) {
    OpenCodeFloatingPill(
        layout = SessionChatActivityPillCopy.layout(item, ResourcesUiTextLookup(LocalResources.current)),
        state = SessionChatActivityPillCopy.state(item),
        compact = compact,
        badgeCount = badgeCount,
        accessibilityLabel = accessibilityLabel,
        // The collapsed circle always toggles the stack, even when its primary is not tappable.
        onClick = onClick.takeIf { badgeCount > 0 || SessionChatActivityPillCopy.isClickable(item) },
        onClose = onClose.takeIf { SessionChatActivityPillCopy.isClosable(item) },
    )
}

/**
 * How each activity item looks, reads and behaves on the shared floating pill. Text is [UiText];
 * the view resolves it ([layout] takes the lookup because the pill layout holds strings).
 */
internal object SessionChatActivityPillCopy {
    fun layout(
        item: SessionChatActivityItem,
        text: UiTextLookup,
    ): OpenCodeFloatingPillLayout =
        when (item) {
            SessionChatActivityItem.ReviewPermission ->
                OpenCodeFloatingPillLayout.IconLabel(
                    OpenCodeIcons.ArrowUpward,
                    UiText.resource(R.string.session_chat_activity_review_permission).resolve(text),
                )
            SessionChatActivityItem.AnswerQuestion ->
                OpenCodeFloatingPillLayout.IconLabel(
                    OpenCodeIcons.ArrowUpward,
                    UiText.resource(R.string.session_chat_activity_answer_question).resolve(text),
                )
            is SessionChatActivityItem.Subagents ->
                OpenCodeFloatingPillLayout.Counter(item.pill.labelText.resolve(text), waiting = item.pill.needsInput)
            is SessionChatActivityItem.RequestFailed ->
                OpenCodeFloatingPillLayout.Closable(UiText.resource(R.string.session_chat_activity_request_failed).resolve(text))
            is SessionChatActivityItem.SideQuestionsFailed ->
                OpenCodeFloatingPillLayout.Closable(
                    countedText(item.count, R.string.session_chat_activity_btw_failed, R.string.session_chat_activity_btw_failed_count)
                        .resolve(text),
                )
            is SessionChatActivityItem.SideQuestionsReady ->
                OpenCodeFloatingPillLayout.IconLabel(
                    OpenCodeIcons.Check,
                    countedText(item.count, R.string.session_chat_activity_btw_ready, R.string.session_chat_activity_btw_ready_count)
                        .resolve(text),
                )
            // The `/btw` command and the count; no words to translate.
            is SessionChatActivityItem.SideQuestionsRunning ->
                OpenCodeFloatingPillLayout.Counter(if (item.count > 1) "/btw ×${item.count}" else "/btw")
            is SessionChatActivityItem.Queued ->
                OpenCodeFloatingPillLayout.Label(UiText.plural(R.plurals.session_chat_activity_queued, item.count).resolve(text))
            is SessionChatActivityItem.Finished -> OpenCodeFloatingPillLayout.Label(item.label)
        }

    fun state(item: SessionChatActivityItem): OpenCodeFloatingPillState =
        when (item.priority) {
            SessionChatActivityPriority.NeedsInput -> OpenCodeFloatingPillState.NeedsAttention
            SessionChatActivityPriority.Error -> OpenCodeFloatingPillState.Error
            SessionChatActivityPriority.AnswerReady, SessionChatActivityPriority.Queued -> OpenCodeFloatingPillState.Ready
            SessionChatActivityPriority.Running -> OpenCodeFloatingPillState.Live
            SessionChatActivityPriority.Finished -> OpenCodeFloatingPillState.Done
        }

    fun isClickable(item: SessionChatActivityItem): Boolean =
        when (item) {
            is SessionChatActivityItem.SideQuestionsRunning,
            is SessionChatActivityItem.Finished,
            -> false
            else -> true
        }

    fun isClosable(item: SessionChatActivityItem): Boolean =
        item is SessionChatActivityItem.RequestFailed || item is SessionChatActivityItem.SideQuestionsFailed

    /** What VoiceOver/TalkBack reads: the item's state, then what a tap does. */
    fun accessibilityLabel(item: SessionChatActivityItem): UiText {
        val statusText = status(item)
        val tapAction = action(item) ?: return statusText
        return UiText.resource(R.string.session_chat_activity_status_action, statusText, UiText.resource(tapAction))
    }

    /** The round pill that stands for [count] items: its primary's state, then that a tap shows or hides them. */
    fun collapsedAccessibilityLabel(
        primary: SessionChatActivityItem,
        count: Int,
        stackOpen: Boolean,
    ): UiText {
        val toggle =
            if (stackOpen) {
                UiText.plural(R.plurals.session_chat_activity_collapsed_hide_count, count)
            } else {
                UiText.plural(R.plurals.session_chat_activity_collapsed_show_count, count)
            }
        return UiText.resource(R.string.session_chat_activity_status_action, status(primary), toggle)
    }

    /** The item's state, as read aloud. */
    private fun status(item: SessionChatActivityItem): UiText =
        when (item) {
            SessionChatActivityItem.ReviewPermission -> UiText.resource(R.string.session_chat_activity_review_permission_request)
            SessionChatActivityItem.AnswerQuestion -> UiText.resource(R.string.session_chat_activity_answer_question)
            is SessionChatActivityItem.Subagents -> item.pill.accessibilityText
            is SessionChatActivityItem.RequestFailed -> UiText.resource(R.string.session_chat_activity_request_failed)
            is SessionChatActivityItem.SideQuestionsFailed ->
                countedPlural(
                    item.count,
                    R.string.session_chat_activity_side_question_failed,
                    R.plurals.session_chat_activity_side_questions_failed,
                )
            is SessionChatActivityItem.SideQuestionsReady ->
                countedPlural(
                    item.count,
                    R.string.session_chat_activity_side_question_ready,
                    R.plurals.session_chat_activity_side_questions_ready,
                )
            is SessionChatActivityItem.SideQuestionsRunning ->
                countedPlural(
                    item.count,
                    R.string.session_chat_activity_side_question_running,
                    R.plurals.session_chat_activity_side_questions_running,
                )
            is SessionChatActivityItem.Queued -> UiText.plural(R.plurals.session_chat_activity_queued_prompts, item.count)
            is SessionChatActivityItem.Finished ->
                UiText.resource(R.string.session_chat_activity_finished, UiText.verbatim(item.label))
        }

    /** What a tap does, read after the state; null when the pill does nothing. */
    @StringRes
    private fun action(item: SessionChatActivityItem): Int? =
        when (item) {
            SessionChatActivityItem.ReviewPermission,
            SessionChatActivityItem.AnswerQuestion,
            is SessionChatActivityItem.SideQuestionsRunning,
            is SessionChatActivityItem.Finished,
            -> null
            is SessionChatActivityItem.Subagents ->
                if (item.pill.needsInput) {
                    R.string.session_chat_activity_action_open_request
                } else {
                    R.string.session_chat_activity_action_open_background_work
                }
            is SessionChatActivityItem.RequestFailed -> R.string.session_chat_activity_action_show_in_chat
            is SessionChatActivityItem.SideQuestionsFailed -> R.string.session_chat_activity_action_retry
            is SessionChatActivityItem.SideQuestionsReady -> R.string.session_chat_activity_action_open
            is SessionChatActivityItem.Queued -> R.string.session_chat_activity_action_open_up_next
        }

    /** [one] for a single item, [many] with the count otherwise. */
    private fun countedText(
        count: Int,
        @StringRes one: Int,
        @StringRes many: Int,
    ): UiText = if (count > 1) UiText.resource(many, count) else UiText.resource(one)

    /** [one] for a single item (its text has no count), the [many] plural otherwise. */
    private fun countedPlural(
        count: Int,
        @StringRes one: Int,
        @PluralsRes many: Int,
    ): UiText = if (count > 1) UiText.plural(many, count) else UiText.resource(one)
}

/** Top-left context pills: todos progress and the branch pill. */
@Composable
internal fun SessionChatContextSlotView(
    items: List<SessionChatContextItem>,
    todosActivePreview: String?,
    onTodosClick: () -> Unit,
    onBranchClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBranchCopied: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SessionChatCornerSlotsLayout.Spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            when (item) {
                is SessionChatContextItem.Todos ->
                    OpenCodeFloatingPill(
                        layout = OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.List, "${item.completed}/${item.total}"),
                        state = OpenCodeFloatingPillState.Ready,
                        accessibilityLabel = sessionTodosPillAccessibilityLabel(item.completed, item.total, todosActivePreview),
                        onClick = onTodosClick,
                    )
                is SessionChatContextItem.Branch ->
                    OpenCodeSessionBranchPill(
                        summary = item.pill.summary,
                        onViewAllChanges = onBranchClick,
                        onBranchNameCopied = onBranchCopied,
                    )
            }
        }
    }
}

/** Bottom-right navigation: always labelled, "↓ Scroll to end" or "↓ N new". */
@Composable
internal fun SessionChatNavigationSlotView(
    pill: SessionChatNavigationPill,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeFloatingPill(
        layout = OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.ArrowDownward, SessionChatNavigationCopy.label(pill).resolve()),
        state = OpenCodeFloatingPillState.Ready,
        accessibilityLabel = SessionChatNavigationCopy.accessibilityLabel(pill).resolve(),
        onClick = onClick,
        modifier = modifier,
    )
}

/** What the scroll-to-end pill says. */
internal object SessionChatNavigationCopy {
    fun label(pill: SessionChatNavigationPill): UiText =
        if (pill.newCount > 0) {
            UiText.plural(R.plurals.session_chat_navigation_new_count, pill.newCount)
        } else {
            UiText.resource(R.string.session_chat_navigation_scroll_to_end)
        }

    fun accessibilityLabel(pill: SessionChatNavigationPill): UiText =
        if (pill.newCount > 0) {
            UiText.plural(R.plurals.session_chat_navigation_scroll_to_end_new_messages, pill.newCount)
        } else {
            UiText.resource(R.string.session_chat_navigation_scroll_to_end)
        }
}

/**
 * Bottom-right Syncing status, left of scroll-to-end. A normal pill at the end of the chat,
 * a dot-only circle when [compact]. Never reserves timeline space.
 */
@Composable
internal fun SessionChatSyncingSlotView(
    label: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    SessionChatPillMorph(state = compact, modifier = modifier) { compactNow ->
        OpenCodeFloatingPill(
            layout = OpenCodeFloatingPillLayout.Counter(label),
            state = OpenCodeFloatingPillState.Live,
            compact = compactNow,
            accessibilityLabel = label,
        )
    }
}
