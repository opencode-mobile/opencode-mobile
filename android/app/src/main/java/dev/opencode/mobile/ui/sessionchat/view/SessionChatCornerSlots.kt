package dev.opencode.mobile.ui.sessionchat.view

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Switches a pill between full and round by animating its width, so the pills beside it move in
 * step. The two forms cross-fade inside the changing box; the shadow is not clipped.
 */
@Composable
internal fun SessionChatPillMorph(
    compact: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (compact: Boolean) -> Unit,
) {
    AnimatedContent(
        targetState = compact,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(PillRevealMs)) togetherWith fadeOut(tween(PillRevealMs / 2)))
                .using(SizeTransform(clip = false) { _, _ -> tween(PillRevealMs, easing = FastOutSlowInEasing) })
        },
        label = "sessionChatPillMorph",
    ) { isCompact ->
        content(isCompact)
    }
}

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
            SessionChatPillMorph(compact = slot.collapsed || slot.primaryCompact) { compactNow ->
                if (slot.collapsed) {
                    SessionChatActivityPill(
                        item = primary,
                        compact = compactNow,
                        badgeCount = slot.itemCount,
                        accessibilityLabel = SessionChatActivityPillCopy.collapsedAccessibilityLabel(primary, slot.itemCount, stackOpen),
                        onClick = { onStackOpenChange(!stackOpen) },
                        onClose = {},
                    )
                } else {
                    SessionChatActivityPill(
                        item = primary,
                        compact = compactNow,
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
                    accessibilityLabel = if (stackOpen) "Hide $more more" else "Show $more more",
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
    accessibilityLabel: String = SessionChatActivityPillCopy.accessibilityLabel(item),
) {
    OpenCodeFloatingPill(
        layout = SessionChatActivityPillCopy.layout(item),
        state = SessionChatActivityPillCopy.state(item),
        compact = compact,
        badgeCount = badgeCount,
        accessibilityLabel = accessibilityLabel,
        // The collapsed circle always toggles the stack, even when its primary is not tappable.
        onClick = onClick.takeIf { badgeCount > 0 || SessionChatActivityPillCopy.isClickable(item) },
        onClose = onClose.takeIf { SessionChatActivityPillCopy.isClosable(item) },
    )
}

/** How each activity item looks, reads and behaves on the shared floating pill. */
internal object SessionChatActivityPillCopy {
    fun layout(item: SessionChatActivityItem): OpenCodeFloatingPillLayout =
        when (item) {
            SessionChatActivityItem.ReviewPermission ->
                OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.ArrowUpward, "Review permission")
            SessionChatActivityItem.AnswerQuestion ->
                OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.ArrowUpward, "Answer question")
            is SessionChatActivityItem.Subagents ->
                OpenCodeFloatingPillLayout.Counter(item.pill.label, waiting = item.pill.needsInput)
            is SessionChatActivityItem.RequestFailed -> OpenCodeFloatingPillLayout.Closable("Request failed")
            is SessionChatActivityItem.SideQuestionsFailed ->
                OpenCodeFloatingPillLayout.Closable("/btw failed${countSuffix(item.count)} · Retry")
            is SessionChatActivityItem.SideQuestionsReady ->
                OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.Check, "/btw answer ready${countSuffix(item.count)}")
            is SessionChatActivityItem.SideQuestionsRunning ->
                OpenCodeFloatingPillLayout.Counter("/btw${countSuffix(item.count)}")
            is SessionChatActivityItem.Queued -> OpenCodeFloatingPillLayout.Label("${item.count} queued")
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

    fun accessibilityLabel(item: SessionChatActivityItem): String =
        when (item) {
            SessionChatActivityItem.ReviewPermission -> "Review permission request"
            SessionChatActivityItem.AnswerQuestion -> "Answer question"
            is SessionChatActivityItem.Subagents ->
                "${item.pill.accessibilityLabel}. ${if (item.pill.needsInput) "Open request" else "Open background work"}"
            is SessionChatActivityItem.RequestFailed -> "Request failed. Show in chat"
            is SessionChatActivityItem.SideQuestionsFailed ->
                if (item.count > 1) "${item.count} side questions failed. Retry" else "Side question failed. Retry"
            is SessionChatActivityItem.SideQuestionsReady ->
                if (item.count > 1) "${item.count} side question answers ready. Open" else "Side question answer ready. Open"
            is SessionChatActivityItem.SideQuestionsRunning ->
                if (item.count > 1) "${item.count} side questions running" else "Side question running"
            is SessionChatActivityItem.Queued ->
                if (item.count == 1) "1 queued prompt. Open Up next" else "${item.count} queued prompts. Open Up next"
            is SessionChatActivityItem.Finished -> "${item.label}, Done"
        }

    /** The round pill that stands for [count] items: its primary's state plus what a tap does. */
    fun collapsedAccessibilityLabel(
        primary: SessionChatActivityItem,
        count: Int,
        stackOpen: Boolean,
    ): String {
        val lead = accessibilityLabel(primary).substringBefore(". ")
        val more = if (count > 1) ", $count activities" else ""
        return "$lead$more. ${if (stackOpen) "Hide" else "Show"} activity"
    }

    private fun countSuffix(count: Int): String = if (count > 1) " ×$count" else ""
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
        layout = OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.ArrowDownward, SessionChatNavigationCopy.label(pill)),
        state = OpenCodeFloatingPillState.Ready,
        accessibilityLabel = SessionChatNavigationCopy.accessibilityLabel(pill),
        onClick = onClick,
        modifier = modifier,
    )
}

/** What the scroll-to-end pill says. */
internal object SessionChatNavigationCopy {
    fun label(pill: SessionChatNavigationPill): String = if (pill.newCount > 0) "${pill.newCount} new" else "Scroll to end"

    fun accessibilityLabel(pill: SessionChatNavigationPill): String =
        when (pill.newCount) {
            0 -> "Scroll to end"
            1 -> "Scroll to end, 1 new message"
            else -> "Scroll to end, ${pill.newCount} new messages"
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
    SessionChatPillMorph(compact = compact, modifier = modifier) { compactNow ->
        OpenCodeFloatingPill(
            layout = OpenCodeFloatingPillLayout.Counter(label),
            state = OpenCodeFloatingPillState.Live,
            compact = compactNow,
            accessibilityLabel = label,
        )
    }
}
