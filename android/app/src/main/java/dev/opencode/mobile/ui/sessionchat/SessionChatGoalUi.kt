package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.goal.SessionGoal
import dev.opencode.mobile.domain.goal.SessionGoalAction
import dev.opencode.mobile.domain.goal.SessionGoalLimits
import dev.opencode.mobile.domain.goal.SessionGoalPause
import dev.opencode.mobile.domain.goal.SessionGoalPauseKind
import dev.opencode.mobile.domain.goal.SessionGoalStatus
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.sessionchat.region.SessionChatGoalRegionState
import dev.opencode.mobile.ui.text.UiText

/** What a goal is doing now, as the pill, the sheet's tag and the status row show it. */
enum class SessionChatGoalActivity {
    /** Active and the session works (or the reviewer checks): the pill spins. */
    Working,

    /** Active while the session waits on the user or on background work: no spinner. */
    Waiting,
    Paused,
    Met,
}

/** What an active goal waits on. */
sealed interface SessionChatGoalWait {
    data object Permission : SessionChatGoalWait

    data object Question : SessionChatGoalWait

    /** A background shell of the session; the reviewer answered "wait" for it. */
    data class Shell(
        val command: String,
    ) : SessionChatGoalWait

    data object Subagent : SessionChatGoalWait
}

/** Session Chat's goal: pill, sheet and status row (#1007). Null when the session has none. */
data class SessionChatGoalUiState(
    val goal: SessionGoal,
    val activity: SessionChatGoalActivity,
    val wait: SessionChatGoalWait? = null,
    /** The action in flight; its button shows progress. */
    val pending: SessionGoalAction? = null,
    /** Reconnecting: the last known goal, dimmed, with its actions off. */
    val isStale: Boolean = false,
    val sheetOpen: Boolean = false,
    val objectiveExpanded: Boolean = false,
    /** Pause, Resume, Clear and New goal… can be used now. */
    val actionsEnabled: Boolean = true,
) {
    val pause: SessionGoalPause? get() = goal.pause

    /** A paused or met goal gets its status row at the end of the timeline. */
    val hasStatusRow: Boolean get() = goal.status != SessionGoalStatus.Active

    companion object {
        /**
         * The goal Session Chat shows, or null: no goal, or a subagent session (a goal belongs to
         * one session; children start without one).
         */
        fun resolve(
            region: SessionChatGoalRegionState,
            isChildSession: Boolean,
            isSessionBusy: Boolean,
            hasPermission: Boolean,
            hasQuestion: Boolean,
            runningShellCommand: String?,
            hasRunningSubagent: Boolean,
            canMutate: Boolean,
        ): SessionChatGoalUiState? {
            val goal = region.goal ?: return null
            if (isChildSession) return null
            val wait =
                when {
                    goal.status != SessionGoalStatus.Active -> null
                    hasPermission -> SessionChatGoalWait.Permission
                    hasQuestion -> SessionChatGoalWait.Question
                    isSessionBusy -> null
                    runningShellCommand != null -> SessionChatGoalWait.Shell(runningShellCommand)
                    hasRunningSubagent -> SessionChatGoalWait.Subagent
                    else -> null
                }
            val activity =
                when (goal.status) {
                    SessionGoalStatus.Paused -> SessionChatGoalActivity.Paused
                    SessionGoalStatus.Complete -> SessionChatGoalActivity.Met
                    SessionGoalStatus.Active -> if (wait != null) SessionChatGoalActivity.Waiting else SessionChatGoalActivity.Working
                }
            return SessionChatGoalUiState(
                goal = goal,
                activity = activity,
                wait = wait,
                pending = region.pending,
                isStale = region.isStale,
                sheetOpen = region.sheetOpen,
                objectiveExpanded = region.objectiveExpanded,
                actionsEnabled = !region.isStale && region.pending == null && canMutate,
            )
        }
    }
}

/** Goal copy shared by the pill, sheet, timeline rows and composer. */
object SessionChatGoalCopy {
    /** Over this many characters the sheet clamps the objective to five lines with "Show all". */
    const val OBJECTIVE_CLAMP_CHARACTERS = 260
    const val OBJECTIVE_CLAMP_LINES = 5

    private const val DESCRIPTION_PREFIX = "Goal: "

    fun pillLabel(activity: SessionChatGoalActivity): UiText =
        when (activity) {
            SessionChatGoalActivity.Working, SessionChatGoalActivity.Waiting -> UiText.resource(R.string.session_chat_goal_pill)
            SessionChatGoalActivity.Paused -> UiText.resource(R.string.session_chat_goal_pill_paused)
            SessionChatGoalActivity.Met -> UiText.resource(R.string.session_chat_goal_pill_met)
        }

    fun pillAccessibilityLabel(state: SessionChatGoalUiState): UiText {
        val goal = state.goal
        return when (state.activity) {
            SessionChatGoalActivity.Working, SessionChatGoalActivity.Waiting ->
                UiText.resource(R.string.session_chat_goal_pill_accessibility_active, goal.rounds, goal.maxRounds)
            SessionChatGoalActivity.Paused -> UiText.resource(R.string.session_chat_goal_pill_accessibility_paused, pauseTitle(goal))
            SessionChatGoalActivity.Met -> UiText.resource(R.string.session_chat_goal_pill_met)
        }
    }

    fun tag(activity: SessionChatGoalActivity): UiText =
        UiText.resource(
            when (activity) {
                SessionChatGoalActivity.Working -> R.string.session_chat_goal_tag_working
                SessionChatGoalActivity.Waiting -> R.string.session_chat_goal_tag_waiting
                SessionChatGoalActivity.Paused -> R.string.session_chat_goal_tag_paused
                SessionChatGoalActivity.Met -> R.string.session_chat_goal_tag_met
            },
        )

    /** "Goal paused" for the reviewer's own words; a fixed title for a known server reason. */
    fun pauseTitle(goal: SessionGoal): UiText {
        val pause = goal.pause ?: SessionGoalPause.classify(goal.reason)
        return when (pause.kind) {
            SessionGoalPauseKind.ByUser -> UiText.resource(R.string.session_chat_goal_paused_by_user)
            SessionGoalPauseKind.Stopped -> UiText.resource(R.string.session_chat_goal_paused_stopped)
            SessionGoalPauseKind.Failed -> UiText.resource(R.string.session_chat_goal_paused_failed)
            SessionGoalPauseKind.NoProgress -> UiText.resource(R.string.session_chat_goal_paused_no_progress, SessionGoalLimits.MAX_STALLS)
            SessionGoalPauseKind.RoundLimit -> UiText.resource(R.string.session_chat_goal_paused_round_limit, goal.maxRounds)
            SessionGoalPauseKind.ServerRestart -> UiText.resource(R.string.session_chat_goal_paused_restart)
            SessionGoalPauseKind.ReviewerFailed -> UiText.resource(R.string.session_chat_goal_paused_reviewer)
            SessionGoalPauseKind.BackgroundQuiet -> UiText.resource(R.string.session_chat_goal_paused_quiet)
            SessionGoalPauseKind.Other -> UiText.resource(R.string.session_chat_goal_paused)
        }
    }

    /** The reason line under a pause title; null when the title says it all. */
    fun pauseReason(goal: SessionGoal): String? {
        val pause = goal.pause ?: return null
        return goal.reason?.takeUnless { pause.hidesReason }?.takeIf { it.isNotBlank() }
    }

    /** "Round 3 of 50", "First turn · round 0 of 50", "Paused in round 3 of 50", "Met in round 4". */
    fun progress(goal: SessionGoal): UiText =
        when (goal.status) {
            SessionGoalStatus.Active ->
                if (goal.rounds == 0) {
                    UiText.resource(R.string.session_chat_goal_first_turn, goal.maxRounds)
                } else {
                    UiText.resource(R.string.session_chat_goal_round, goal.rounds, goal.maxRounds)
                }
            SessionGoalStatus.Paused -> UiText.resource(R.string.session_chat_goal_paused_in_round, goal.rounds, goal.maxRounds)
            SessionGoalStatus.Complete -> UiText.resource(R.string.session_chat_goal_met_in_round, goal.rounds)
        }

    /** The meter's fill, 0..1, with a sliver at round 0 so it reads as a meter. */
    fun progressFraction(goal: SessionGoal): Float = (goal.rounds.toFloat() / goal.maxRounds.coerceAtLeast(1)).coerceIn(0.01f, 1f)

    /** "No progress for 2 rounds; pauses after 3", from one stalled round on; null otherwise. */
    fun stalls(goal: SessionGoal): UiText? {
        val stalls = goal.stalls ?: return null
        if (goal.status != SessionGoalStatus.Active || stalls < 1) return null
        return UiText.plural(R.plurals.session_chat_goal_stalls, stalls, stalls, SessionGoalLimits.MAX_STALLS)
    }

    fun footnote(goal: SessionGoal): UiText =
        when (goal.status) {
            SessionGoalStatus.Active -> UiText.resource(R.string.session_chat_goal_footnote_active, goal.maxRounds)
            SessionGoalStatus.Paused -> UiText.resource(R.string.session_chat_goal_footnote_paused, goal.maxRounds)
            SessionGoalStatus.Complete -> UiText.resource(R.string.session_chat_goal_footnote_met)
        }

    /** The Goal set row's text: the kickoff description without its "Goal: " lead. */
    fun goalSetValue(description: String): String = description.removePrefix(DESCRIPTION_PREFIX).trim().ifEmpty { description }

    /** The `/goal` command row's subtitle while a goal exists; null keeps the server's description. */
    fun commandSubtitle(goal: SessionGoal?): UiText? =
        when (goal?.status) {
            null -> null
            SessionGoalStatus.Active -> UiText.resource(R.string.session_chat_goal_command_replaces)
            SessionGoalStatus.Paused -> UiText.resource(R.string.session_chat_goal_command_replaces_paused)
            SessionGoalStatus.Complete -> UiText.resource(R.string.session_chat_goal_command_replaces_met)
        }

    /** The composer asks for a reply while the reviewer paused the goal in its own words. */
    fun resumesAfterReply(state: SessionChatGoalUiState?): Boolean = state?.pause?.isKnown == false

    fun actionLabel(
        action: SessionGoalAction,
        pending: Boolean,
    ): UiText =
        UiText.resource(
            when (action) {
                SessionGoalAction.Pause -> if (pending) R.string.session_chat_goal_pausing else R.string.session_chat_goal_pause
                SessionGoalAction.Resume -> if (pending) R.string.session_chat_goal_resuming else R.string.session_chat_goal_resume
                SessionGoalAction.Clear -> if (pending) R.string.session_chat_goal_clearing else R.string.session_chat_goal_clear
            },
        )
}

/** The goal's status row in the timeline: built from the goal state, not a message. */
object SessionChatGoalTimeline {
    private const val STATUS_ROW_PREFIX = "goal-status:"

    fun statusRowId(goalId: String): String = STATUS_ROW_PREFIX + goalId

    fun isStatusRow(message: SessionChatMessageItem): Boolean = message.id.startsWith(STATUS_ROW_PREFIX)

    /**
     * [messages] with a paused or met goal's status row placed by its `updatedAt`: after every
     * message sent by then. Messages without a send time count as earlier.
     */
    fun withStatusRow(
        messages: List<SessionChatMessageItem>,
        goal: SessionChatGoalUiState?,
    ): List<SessionChatMessageItem> {
        if (goal == null || !goal.hasStatusRow) return messages
        val updatedAt = goal.goal.updatedAtEpochMs
        var index = messages.size
        while (index > 0) {
            val sentAt = messages[index - 1].sentAtEpochMs ?: break
            if (sentAt <= updatedAt) break
            index -= 1
        }
        val id = statusRowId(goal.goal.id)
        val row =
            SessionChatMessageItem(
                id = id,
                role = MessageRole.Notice,
                text = "",
                metadataLabel = "",
                blocks = listOf(SessionChatTimelineBlock.GoalStatus(blockID = id)),
                sentAtEpochMs = updatedAt,
            )
        return messages.subList(0, index) + row + messages.subList(index, messages.size)
    }
}
