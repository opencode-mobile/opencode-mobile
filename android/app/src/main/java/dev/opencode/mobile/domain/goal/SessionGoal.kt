package dev.opencode.mobile.domain.goal

/**
 * A session's `/goal` (v2 goal plugin): after each turn a reviewer checks the work against
 * [objective] and sends the agent back until it is met, it needs the user, or [maxRounds] pass.
 * A goal belongs to one session; children and forks start without one. v1 has no goals.
 */
data class SessionGoal(
    val id: String,
    val objective: String,
    val status: SessionGoalStatus,
    /** Why the goal last changed: the reviewer's words, or what paused it. */
    val reason: String? = null,
    /** Continuations sent since the goal was set or last resumed. */
    val rounds: Int,
    val maxRounds: Int,
    /** Rounds in a row without progress; the server pauses at [SessionGoalLimits.MAX_STALLS]. */
    val stalls: Int? = null,
    val updatedAtEpochMs: Long,
) {
    /** How a paused goal reads; null unless [status] is [SessionGoalStatus.Paused]. */
    val pause: SessionGoalPause?
        get() = if (status == SessionGoalStatus.Paused) SessionGoalPause.classify(reason) else null
}

enum class SessionGoalStatus {
    Active,
    Paused,

    /** The reviewer found the objective met. */
    Complete,
}

/** A control the goal command takes: `/goal pause`, `/goal resume`, `/goal clear`. */
enum class SessionGoalAction(
    val commandText: String,
) {
    Pause("pause"),
    Resume("resume"),
    Clear("clear"),
}

/** Server limits from the goal plugin. */
object SessionGoalLimits {
    const val MAX_OBJECTIVE = 4_000
    const val MAX_STALLS = 3
    const val WAIT_LIMIT_MINUTES = 30

    /** The goal command's name, as `/goal`. */
    const val COMMAND = "goal"
}

/** Why a goal paused, read from the server's reason. */
enum class SessionGoalPauseKind {
    ByUser,
    Stopped,
    Failed,
    NoProgress,
    RoundLimit,
    ServerRestart,
    ReviewerFailed,
    BackgroundQuiet,

    /** The reviewer's own words (it needs the user, say): shown under a plain "Goal paused". */
    Other,
}

/**
 * A paused goal's [kind] and whether the reason line is hidden because the title already says
 * it all ("You paused the goal").
 */
data class SessionGoalPause(
    val kind: SessionGoalPauseKind,
    val hidesReason: Boolean,
) {
    /** A known server reason; anything else is the reviewer's own words. */
    val isKnown: Boolean get() = kind != SessionGoalPauseKind.Other

    companion object {
        // Reason strings the goal plugin writes (packages/core/src/plugin/goal.ts).
        private val RULES =
            listOf(
                Triple(Regex("^Paused by the user$"), SessionGoalPauseKind.ByUser, true),
                Triple(Regex("^Stopped by the user$"), SessionGoalPauseKind.Stopped, true),
                Triple(Regex("^The session failed: "), SessionGoalPauseKind.Failed, false),
                Triple(Regex("^Not making progress: "), SessionGoalPauseKind.NoProgress, false),
                Triple(Regex("^Reached the limit of \\d+ rounds$"), SessionGoalPauseKind.RoundLimit, true),
                Triple(Regex("^The server restarted while the goal was active$"), SessionGoalPauseKind.ServerRestart, true),
                Triple(Regex("^The goal reviewer"), SessionGoalPauseKind.ReviewerFailed, false),
                Triple(Regex("^Nothing ran for \\d+ minutes"), SessionGoalPauseKind.BackgroundQuiet, false),
            )

        fun classify(reason: String?): SessionGoalPause {
            val text = reason.orEmpty()
            val rule = RULES.firstOrNull { (pattern, _, _) -> pattern.containsMatchIn(text) }
            return if (rule != null) {
                SessionGoalPause(kind = rule.second, hidesReason = rule.third)
            } else {
                SessionGoalPause(kind = SessionGoalPauseKind.Other, hidesReason = text.isBlank())
            }
        }
    }
}

/** The goal plugin's kickoff message: the instruction text wraps the objective in a fixed block. */
object SessionGoalKickoff {
    /** `metadata.source` of goal messages (kickoff and continuations). */
    const val SOURCE = "goal"

    private val OBJECTIVE_BLOCK = Regex("<untrusted_objective>\\n?([\\s\\S]*?)\\n?</untrusted_objective>")

    /** The objective inside a kickoff's instruction [text], unescaped; null without the block. */
    fun objective(text: String?): String? {
        val match = OBJECTIVE_BLOCK.find(text ?: return null) ?: return null
        return unescape(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
    }

    // The plugin escapes &, < and > in the objective; & last so "&amp;lt;" stays "&lt;".
    private fun unescape(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}

/**
 * The objective of a `/goal <objective>` draft, or null when the draft is not a goal command
 * (or is a control: pause, resume, clear). Quotes around the objective are kept as typed;
 * the server strips a matching pair.
 */
object SessionGoalDraft {
    private const val PREFIX = "/" + SessionGoalLimits.COMMAND

    fun objective(text: String): String? {
        val trimmedStart = text.trimStart()
        if (!trimmedStart.startsWith(PREFIX)) return null
        val rest = trimmedStart.substring(PREFIX.length)
        if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
        val objective = rest.trim()
        if (SessionGoalAction.entries.any { it.commandText.equals(objective, ignoreCase = true) }) return null
        return objective
    }

    /** Whether the draft is only the command, waiting for an objective ("/goal "). */
    fun awaitsObjective(text: String): Boolean = objective(text)?.isEmpty() == true && text.trimStart().length > PREFIX.length

    /** Send stays off while the objective is empty or over the server's limit. */
    fun canSend(objective: String): Boolean = objective.isNotEmpty() && objective.length <= SessionGoalLimits.MAX_OBJECTIVE

    /** The count shows from this length on, so the limit is never a surprise. */
    const val COUNT_FROM = SessionGoalLimits.MAX_OBJECTIVE - 400

    fun showsCount(objective: String): Boolean = objective.length >= COUNT_FROM
}
