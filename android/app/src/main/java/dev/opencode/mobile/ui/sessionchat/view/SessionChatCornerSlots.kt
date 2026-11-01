package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
     * The navigation pill only shows away from the end, so it reserves nothing.
     */
    fun reservedHeight(hasActivity: Boolean): Dp = if (hasActivity) PillHeight + GapAboveComposer else 0.dp
}

/** Bottom-left live activity slot: one pill, a "+N" button, and the stack "+N" springs up. */
@Composable
internal fun SessionChatActivitySlotView(
    slot: SessionChatActivitySlot,
    stackOpen: Boolean,
    onStackOpenChange: (Boolean) -> Unit,
    onClick: (SessionChatActivityItem) -> Unit,
    onClose: (SessionChatActivityItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(slot.showsOverflowButton) {
        if (!slot.showsOverflowButton) onStackOpenChange(false)
    }
    val primary = slot.primary ?: return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SessionChatCornerSlotsLayout.Spacing),
    ) {
        AnimatedVisibility(
            visible = stackOpen && slot.showsOverflowButton,
            enter =
                fadeIn() +
                    expandVertically(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                        expandFrom = Alignment.Bottom,
                    ),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SessionChatCornerSlotsLayout.Spacing)) {
                // Lowest priority on top, so the next most urgent sits nearest the pill.
                slot.overflow.asReversed().forEach { item ->
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
            SessionChatActivityPill(
                item = primary,
                compact = slot.primaryCompact,
                onClick = {
                    onStackOpenChange(false)
                    onClick(primary)
                },
                onClose = { onClose(primary) },
            )
            if (slot.showsOverflowButton) {
                val more = slot.overflow.size
                OpenCodeFloatingPill(
                    layout = OpenCodeFloatingPillLayout.Label("+$more"),
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
) {
    OpenCodeFloatingPill(
        layout = SessionChatActivityPillCopy.layout(item),
        state = SessionChatActivityPillCopy.state(item),
        compact = compact,
        accessibilityLabel = SessionChatActivityPillCopy.accessibilityLabel(item),
        onClick = onClick.takeIf { SessionChatActivityPillCopy.isClickable(item) },
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
            is SessionChatActivityItem.Syncing -> OpenCodeFloatingPillLayout.Counter(item.label)
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
            is SessionChatActivityItem.Syncing,
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
                "${item.pill.accessibilityLabel}. ${if (item.pill.needsInput) "Open request" else "Open subagents"}"
            is SessionChatActivityItem.RequestFailed -> "Request failed. Show in chat"
            is SessionChatActivityItem.SideQuestionsFailed ->
                if (item.count > 1) "${item.count} side questions failed. Retry" else "Side question failed. Retry"
            is SessionChatActivityItem.SideQuestionsReady ->
                if (item.count > 1) "${item.count} side question answers ready. Open" else "Side question answer ready. Open"
            is SessionChatActivityItem.SideQuestionsRunning ->
                if (item.count > 1) "${item.count} side questions running" else "Side question running"
            is SessionChatActivityItem.Syncing -> item.label
            is SessionChatActivityItem.Queued ->
                if (item.count == 1) "1 queued prompt. Open Up next" else "${item.count} queued prompts. Open Up next"
            is SessionChatActivityItem.Finished -> "${item.label}, Done"
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

/** Bottom-right navigation: a round ↓, or "↓ N new". */
@Composable
internal fun SessionChatNavigationSlotView(
    pill: SessionChatNavigationPill,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pill.newCount > 0) {
        OpenCodeFloatingPill(
            layout = OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.ArrowDownward, "${pill.newCount} new"),
            state = OpenCodeFloatingPillState.Ready,
            accessibilityLabel =
                if (pill.newCount == 1) "Scroll to end, 1 new message" else "Scroll to end, ${pill.newCount} new messages",
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        OpenCodeFloatingPill(
            layout = OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll to end"),
            state = OpenCodeFloatingPillState.Ready,
            accessibilityLabel = "Scroll to end",
            onClick = onClick,
            modifier = modifier,
        )
    }
}
