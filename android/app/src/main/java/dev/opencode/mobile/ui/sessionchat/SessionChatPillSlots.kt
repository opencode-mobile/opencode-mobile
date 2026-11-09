package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole

/** Which bottom-left activity pill wins. Earlier entries come first. */
enum class SessionChatActivityPriority {
    NeedsInput,
    Error,
    AnswerReady,
    Running,
    Queued,

    /** Fades after [SessionChatPillSlots.FINISHED_FADE_MS], so beside other work it only shows in the stack. */
    Finished,
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

    /** Work that just finished, shown until it fades. */
    data class Finished(
        val key: String,
        val label: String,
        val finishedAtEpochMs: Long,
    ) : SessionChatActivityItem {
        override val id = "finished:$key"
        override val priority = SessionChatActivityPriority.Finished
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
    const val FINISHED_FADE_MS = 3_000L
    const val MAX_CONTEXT_PILLS = 3

    /**
     * Bottom left: highest priority first, the rest behind "+N". Finished items
     * older than the fade drop out. With the keyboard up the pill goes compact
     * unless it needs input, and "+N" hides. Away from the end (the scroll-to-end pill shows,
     * keyboard down) everything folds into one round pill ([SessionChatActivitySlot.collapsed]).
     */
    fun activity(
        items: List<SessionChatActivityItem>,
        keyboardVisible: Boolean,
        nowEpochMs: Long,
        awayFromEnd: Boolean = false,
    ): SessionChatActivitySlot {
        // sortedBy is stable: equal priorities keep the caller's order.
        val sorted = items.filterNot { isFaded(it, nowEpochMs) }.sortedBy { it.priority }
        val primary = sorted.firstOrNull() ?: return SessionChatActivitySlot()
        val overflow = sorted.drop(1)
        if (collapsesActivity(awayFromEnd, keyboardVisible)) {
            return SessionChatActivitySlot(primary = primary, primaryCompact = true, overflow = overflow, collapsed = true)
        }
        return SessionChatActivitySlot(
            primary = primary,
            primaryCompact = keyboardVisible && primary.priority != SessionChatActivityPriority.NeedsInput,
            overflow = if (keyboardVisible) emptyList() else overflow,
            showsOverflowButton = !keyboardVisible && overflow.isNotEmpty(),
        )
    }

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

    fun isFaded(
        item: SessionChatActivityItem,
        nowEpochMs: Long,
    ): Boolean = item is SessionChatActivityItem.Finished && nowEpochMs - item.finishedAtEpochMs >= FINISHED_FADE_MS

    /** When the next finished item fades, so the host can resolve the slot again then. */
    fun nextFadeEpochMs(
        items: List<SessionChatActivityItem>,
        nowEpochMs: Long,
    ): Long? =
        items
            .filterIsInstance<SessionChatActivityItem.Finished>()
            .map { it.finishedAtEpochMs + FINISHED_FADE_MS }
            .filter { it > nowEpochMs }
            .minOrNull()

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
