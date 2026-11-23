package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusAttentionMark
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase

/** Which bottom-left activity pill wins. Earlier entries come first. */
enum class SessionChatActivityPriority {
    NeedsInput,
    Error,
    AnswerReady,
    Running,
    Queued,
}

/**
 * One thing the bottom-left live activity slot can show. A new kind of live
 * work is a new subtype plus its [priority].
 */
sealed interface SessionChatActivityItem {
    val id: String
    val priority: SessionChatActivityPriority

    /** A collapsed permission panel; tapping brings it back. */
    data object ReviewPermission : SessionChatActivityItem {
        override val id = "review-permission"
        override val priority = SessionChatActivityPriority.NeedsInput
    }

    /** A collapsed question panel; tapping brings it back. */
    data object AnswerQuestion : SessionChatActivityItem {
        override val id = "answer-question"
        override val priority = SessionChatActivityPriority.NeedsInput
    }

    /**
     * Subagents of this session at work: "3 subagents", or "general needs input · 2 running"
     * while one waits. Tapping opens the waiting request, else the Subagents panel.
     */
    data class Subagents(
        val pill: SessionChatBackgroundWorkPill,
    ) : SessionChatActivityItem {
        override val id = "subagents"
        override val priority =
            if (pill.needsInput) SessionChatActivityPriority.NeedsInput else SessionChatActivityPriority.Running
    }

    /** The last turn failed. [messageId] is the timeline row to scroll to; [blockId] identifies the failure. */
    data class RequestFailed(
        val messageId: String,
        val blockId: String,
    ) : SessionChatActivityItem {
        override val id = "failed:$blockId"
        override val priority = SessionChatActivityPriority.Error
    }

    /** `/btw` side questions that failed; tapping asks them again. */
    data class SideQuestionsFailed(
        val count: Int,
    ) : SessionChatActivityItem {
        override val id = "btw-failed"
        override val priority = SessionChatActivityPriority.Error
    }

    /** `/btw` answers waiting to be read. */
    data class SideQuestionsReady(
        val count: Int,
    ) : SessionChatActivityItem {
        override val id = "btw-ready"
        override val priority = SessionChatActivityPriority.AnswerReady
    }

    /** `/btw` side questions still running. */
    data class SideQuestionsRunning(
        val count: Int,
    ) : SessionChatActivityItem {
        override val id = "btw-running"
        override val priority = SessionChatActivityPriority.Running
    }

    /** Prompts waiting to be sent after the current turn. */
    data class Queued(
        val count: Int,
    ) : SessionChatActivityItem {
        override val id = "queued"
        override val priority = SessionChatActivityPriority.Queued
    }
}

/**
 * What the bottom-left slot draws: one pill, a "+N" button, and the stack "+N" opens.
 * Away from the end it is one round pill ([collapsed]) that opens the whole stack.
 */
data class SessionChatActivitySlot(
    val primary: SessionChatActivityItem? = null,
    /** The primary pill shrinks to its round mark (keyboard up, unless it needs input). */
    val primaryCompact: Boolean = false,
    /** The rest, in priority order. Shown only while the stack is open. */
    val overflow: List<SessionChatActivityItem> = emptyList(),
    /** The "+N" button. Hidden with the keyboard up. */
    val showsOverflowButton: Boolean = false,
    /**
     * Away from the end: everything folds into one round pill in [primary]'s state, with a count
     * badge for more than one item. Tapping it opens the stack of [primary] plus [overflow].
     */
    val collapsed: Boolean = false,
) {
    /** How many items the round pill stands for. */
    val itemCount: Int get() = if (primary == null) 0 else 1 + overflow.size
}

/**
 * The one permanent turn pill that leads the bottom-left row in every timeline style. It is not
 * an activity item: it never counts toward "+N" and is never in the stack.
 */
internal data class SessionChatTurnStatusSlot(
    val phase: OpenCodeTurnStatusPillPhase,
    /** Folded to its round mark. Attention and Failed never fold. */
    val compact: Boolean,
    /** Fading out. The timeline already gives its row back, so the list glides down. */
    val leaving: Boolean,
    /** What a tap acts on; null when the pill is a plain status. */
    val item: SessionChatActivityItem? = null,
) {
    val reservesRow: Boolean get() = !leaving

    /** Only the Working family and Done may fold. */
    val canFold: Boolean get() = canFold(phase)

    val isClosable: Boolean get() = item is SessionChatActivityItem.RequestFailed

    companion object {
        fun canFold(phase: OpenCodeTurnStatusPillPhase): Boolean =
            phase is OpenCodeTurnStatusPillPhase.Working || phase == OpenCodeTurnStatusPillPhase.Done
    }
}

/** Measured pixel widths of the bottom pill row; 0 for a pill that is not shown. */
internal data class SessionChatPillRowWidths(
    val available: Int,
    val turn: Int,
    val turnCompact: Int,
    val secondary: Int,
    val secondaryCompact: Int,
    val overflow: Int,
    val trailing: Int,
    val spacing: Int,
)

/** Which pills the row has to fold so everything fits. */
internal data class SessionChatPillRowFold(
    val turnCompact: Boolean = false,
    val secondaryCompact: Boolean = false,
    /** Set when a turn pill that cannot fold still does not fit: its label truncates to this width. */
    val turnMaxWidth: Int? = null,
)

/** A top-left context pill. */
sealed interface SessionChatContextItem {
    /** The session's `/goal` (#1007); always first. */
    data class Goal(
        val goal: SessionChatGoalUiState,
    ) : SessionChatContextItem

    data class Todos(
        val completed: Int,
        val total: Int,
    ) : SessionChatContextItem

    data class Branch(
        val pill: SessionBranchPillUiState,
    ) : SessionChatContextItem
}

/** The bottom-right navigation pill: "↓ Scroll to end", or "↓ N new" once messages arrive below. */
data class SessionChatNavigationPill(
    val newCount: Int,
)

/** Pure rules for the three Session Chat corner slots. */
object SessionChatPillSlots {
    const val MAX_CONTEXT_PILLS = 3

    /**
     * Bottom left, beside the turn pill: highest priority first, the rest behind "+N". Requests
     * and failures belong to the turn pill and never appear here. With the keyboard up the pill
     * goes compact and "+N" hides. Away from the end (the scroll-to-end pill shows, keyboard
     * down) everything folds into one round pill ([SessionChatActivitySlot.collapsed]).
     */
    fun activity(
        items: List<SessionChatActivityItem>,
        keyboardVisible: Boolean,
        awayFromEnd: Boolean = false,
    ): SessionChatActivitySlot {
        // sortedBy is stable: equal priorities keep the caller's order.
        val sorted = secondaryItems(items).sortedBy { it.priority }
        val primary = sorted.firstOrNull() ?: return SessionChatActivitySlot()
        val overflow = sorted.drop(1)
        if (collapsesActivity(awayFromEnd, keyboardVisible)) {
            return SessionChatActivitySlot(primary = primary, primaryCompact = true, overflow = overflow, collapsed = true)
        }
        return SessionChatActivitySlot(
            primary = primary,
            primaryCompact = keyboardVisible,
            overflow = if (keyboardVisible) emptyList() else overflow,
            showsOverflowButton = !keyboardVisible && overflow.isNotEmpty(),
        )
    }

    /**
     * What stays a separate pill. Requests and failures merge into the turn pill; a subagent
     * that needs input gives its blocker to the turn pill and leaves only its running count.
     */
    fun secondaryItems(items: List<SessionChatActivityItem>): List<SessionChatActivityItem> =
        items.mapNotNull { item ->
            when (item) {
                SessionChatActivityItem.ReviewPermission,
                SessionChatActivityItem.AnswerQuestion,
                is SessionChatActivityItem.RequestFailed,
                -> null
                is SessionChatActivityItem.Subagents ->
                    if (item.pill.needsInput) item.pill.stillRunning?.let(SessionChatActivityItem::Subagents) else item
                else -> item
            }
        }

    /**
     * The turn pill's state, highest priority first: failed, a collapsed request, an open
     * request, a subagent's request, then the active label or Done. Null with no turn to show.
     */
    internal fun turnStatus(
        label: String?,
        fading: Boolean,
        isAwaitingUser: Boolean,
        items: List<SessionChatActivityItem>,
        keyboardVisible: Boolean,
        awayFromEnd: Boolean,
        text: UiTextLookup,
    ): SessionChatTurnStatusSlot? {
        fun attention(
            label: UiText,
            mark: OpenCodeTurnStatusAttentionMark,
        ) = OpenCodeTurnStatusPillPhase.Attention(label.resolve(text), mark)
        val failure = items.firstOrNull { it is SessionChatActivityItem.RequestFailed }
        val blockedSubagent =
            items.firstNotNullOfOrNull { item -> (item as? SessionChatActivityItem.Subagents)?.pill?.takeIf { it.needsInput } }
        val (phase, item) =
            when {
                failure != null ->
                    OpenCodeTurnStatusPillPhase.Failed(UiText.resource(R.string.session_chat_activity_request_failed).resolve(text)) to failure
                SessionChatActivityItem.ReviewPermission in items ->
                    attention(UiText.resource(R.string.session_chat_activity_review_permission), OpenCodeTurnStatusAttentionMark.Reopen) to
                        SessionChatActivityItem.ReviewPermission
                SessionChatActivityItem.AnswerQuestion in items ->
                    attention(UiText.resource(R.string.session_chat_activity_answer_question), OpenCodeTurnStatusAttentionMark.Reopen) to
                        SessionChatActivityItem.AnswerQuestion
                isAwaitingUser ->
                    attention(UiText.resource(R.string.timeline_activity_waiting_for_you), OpenCodeTurnStatusAttentionMark.PulsingDot) to null
                blockedSubagent != null ->
                    attention(blockedSubagent.blocker.labelText, OpenCodeTurnStatusAttentionMark.PulsingDot) to
                        SessionChatActivityItem.Subagents(blockedSubagent.blocker)
                label == null -> return null
                WorkingTurnIndicator.showsIdleMark(label) -> OpenCodeTurnStatusPillPhase.Done to null
                else -> OpenCodeTurnStatusPillPhase.Working(label) to null
            }
        val canFold = SessionChatTurnStatusSlot.canFold(phase)
        return SessionChatTurnStatusSlot(
            phase = phase,
            compact = canFold && (awayFromEnd || keyboardVisible),
            leaving = canFold && fading,
            item = item,
        )
    }

    /**
     * Folds the row to fit its measured widths. A Working or Done pill folds first; Attention
     * and Failed never fold, so the secondary pill gives way instead. A secondary pill that fits
     * never folds the turn pill. With [turnMergesWhenCompact] the folded turn pill takes no room.
     */
    internal fun pillRowFold(
        widths: SessionChatPillRowWidths,
        turnCanFold: Boolean,
        turnMergesWhenCompact: Boolean = false,
    ): SessionChatPillRowFold {
        fun total(
            turn: Int,
            secondary: Int,
        ): Int {
            val shown = listOf(turn, secondary, widths.overflow, widths.trailing).filter { it > 0 }
            return shown.sum() + widths.spacing * (shown.size - 1).coerceAtLeast(0)
        }

        fun fits(
            turn: Int,
            secondary: Int,
        ): Boolean = total(turn, secondary) <= widths.available
        if (fits(widths.turn, widths.secondary)) return SessionChatPillRowFold()
        if (!turnCanFold) {
            val excess = total(widths.turn, minOf(widths.secondary, widths.secondaryCompact)) - widths.available
            return SessionChatPillRowFold(
                secondaryCompact = widths.secondary > 0,
                turnMaxWidth = if (excess > 0 && widths.turn > 0) maxOf(widths.turnCompact, widths.turn - excess) else null,
            )
        }
        val foldedTurn = if (turnMergesWhenCompact) 0 else widths.turnCompact
        if (fits(foldedTurn, widths.secondary)) return SessionChatPillRowFold(turnCompact = true)
        return SessionChatPillRowFold(turnCompact = true, secondaryCompact = widths.secondary > 0)
    }

    /**
     * A folded Working pill is a bare spinner, so beside a running activity pill it would only
     * repeat that pill's spinner. The activity pill stays because it is the one that opens.
     */
    internal fun turnMergesIntoActivity(
        turnStatus: SessionChatTurnStatusSlot?,
        turnCompact: Boolean,
        primary: SessionChatActivityItem?,
    ): Boolean =
        turnCompact &&
            turnStatus?.phase is OpenCodeTurnStatusPillPhase.Working &&
            primary?.priority == SessionChatActivityPriority.Running

    /** Away from the end with the keyboard down, the left side folds into one round pill. */
    fun collapsesActivity(
        awayFromEnd: Boolean,
        keyboardVisible: Boolean,
    ): Boolean = awayFromEnd && !keyboardVisible

    /**
     * Syncing is a normal pill at the end of the chat, and a dot-only circle beside the
     * scroll-to-end pill or with the keyboard up. It never reserves timeline space.
     */
    fun syncingCompact(
        awayFromEnd: Boolean,
        keyboardVisible: Boolean,
    ): Boolean = awayFromEnd || keyboardVisible

    /** Top left: the goal in the corner, then the branch, then todos, at most three. */
    fun context(
        todosCompleted: Int?,
        todosTotal: Int?,
        branch: SessionBranchPillUiState?,
        goal: SessionChatGoalUiState? = null,
    ): List<SessionChatContextItem> {
        val items = mutableListOf<SessionChatContextItem>()
        if (goal != null) {
            items += SessionChatContextItem.Goal(goal)
        }
        if (branch != null && branch.branch.isNotEmpty()) {
            items += SessionChatContextItem.Branch(branch)
        }
        if (todosTotal != null && todosTotal > 0) {
            items += SessionChatContextItem.Todos((todosCompleted ?: 0).coerceIn(0, todosTotal), todosTotal)
        }
        return items.take(MAX_CONTEXT_PILLS)
    }

    /**
     * The keyboard hides the top-left pills. Their measured inset stays, so the timeline's
     * top padding does not change while the keyboard is up.
     */
    fun showsContext(keyboardVisible: Boolean): Boolean = !keyboardVisible

    /**
     * Whether the timeline reserves the activity pill row. At the end of the chat it follows
     * the pill; away from the end it keeps [reserved], so a pill coming or going
     * never moves the rows the reader is looking at. Syncing and scroll-to-end are bottom-right
     * overlays and never reserve.
     */
    fun reservesActivityRow(
        reserved: Boolean,
        hasActivity: Boolean,
        pinnedToEnd: Boolean,
    ): Boolean = if (pinnedToEnd) hasActivity else reserved

    /** Bottom right scroll-to-end: empty at the end of the chat and with the keyboard up. */
    fun navigation(
        awayFromEnd: Boolean,
        newCount: Int,
        keyboardVisible: Boolean,
    ): SessionChatNavigationPill? = if (!awayFromEnd || keyboardVisible) null else SessionChatNavigationPill(newCount.coerceAtLeast(0))

    /**
     * Rows that arrived after [baselineLastId], the newest row when the reader left the end.
     * Older history loaded above does not count.
     */
    fun newMessageCount(
        rowIds: List<String>,
        baselineLastId: String?,
    ): Int {
        val index = baselineLastId?.let(rowIds::lastIndexOf) ?: return 0
        return if (index < 0) 0 else rowIds.size - index - 1
    }

    /**
     * The failure that ended the last turn, if any. A user row after it, a
     * neutral mark (a denied permission) or a live retry means no pill.
     */
    fun latestFailure(
        messages: List<SessionChatMessageItem>,
        isRetrying: Boolean,
    ): SessionChatActivityItem.RequestFailed? {
        if (isRetrying) return null
        for (message in messages.asReversed()) {
            if (message.role == MessageRole.User) return null
            val failure =
                message.blocks.lastOrNull { it is SessionChatTimelineBlock.FailureLine }
                    as? SessionChatTimelineBlock.FailureLine ?: continue
            return if (failure.isNeutral) null else SessionChatActivityItem.RequestFailed(message.id, failure.blockID)
        }
        return null
    }
}
