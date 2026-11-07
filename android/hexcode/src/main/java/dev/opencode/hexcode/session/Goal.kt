package dev.opencode.hexcode.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** OpenCode's `/goal` status. There is no "blocked": a blocked goal is paused with the reviewer's reason. */
enum class GoalStatus {
    Active,
    Paused,
    Complete,
    ;

    companion object {
        /** `active`, `paused` or `complete`; anything else counts as paused, so nothing claims it's running. */
        fun of(wire: String): GoalStatus =
            when (wire) {
                "active" -> Active
                "complete" -> Complete
                else -> Paused
            }
    }
}

/**
 * Today's goal as the server keeps it (m5-proposal.md §1.1): its [objective], [status], why it last changed ([reason],
 * absent on a new goal), and the server's [rounds]: continuations sent since it was set or last resumed (0 after
 * setting, 1 after resuming), up to [maxRounds].
 */
data class Goal(
    val id: String,
    val objective: String,
    val status: GoalStatus,
    val reason: String? = null,
    val rounds: Int = 0,
    val maxRounds: Int = MAX_ROUNDS,
) {
    /** The reason the sheet shows: only for a paused or finished goal, as the server gives it. */
    val shownReason: String? get() = reason?.trim()?.ifEmpty { null }?.takeIf { status != GoalStatus.Active }

    /** Where it stands while the session is [busy] or idle (app-plan.md, The goal › Line states). */
    fun phase(busy: Boolean): GoalPhase =
        when (status) {
            GoalStatus.Paused -> GoalPhase.Paused
            GoalStatus.Complete -> GoalPhase.Done
            GoalStatus.Active ->
                when {
                    // Active while idle: the reviewer waits on a background shell.
                    !busy -> GoalPhase.Waiting
                    rounds == 0 -> GoalPhase.Starting
                    else -> GoalPhase.Round(rounds, maxRounds)
                }
        }

    companion object {
        /** OpenCode's limit per set or resume (`MAX_ROUNDS`). */
        const val MAX_ROUNDS = 50

        /** The `goal` of `rpc.opencode.goal.changed`, or the get RPC's `output`; null when it has no id. */
        fun read(json: JsonObject): Goal? {
            fun string(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

            fun int(key: String) = (json[key] as? JsonPrimitive)?.intOrNull
            val id = string("id") ?: return null
            return Goal(
                id = id,
                objective = string("objective").orEmpty(),
                status = GoalStatus.of(string("status").orEmpty()),
                reason = string("reason"),
                rounds = int("rounds") ?: 0,
                maxRounds = int("maxRounds") ?: MAX_ROUNDS,
            )
        }
    }
}

/** A goal's state on the line and in the sheet's box. */
sealed interface GoalPhase {
    /** Active and busy, before the first review (`rounds` 0): "starting", "active · starting". */
    data object Starting : GoalPhase

    /** Active and busy: "round 3", "active · round 3 of 50". */
    data class Round(
        val round: Int,
        val of: Int,
    ) : GoalPhase

    /** Active while the session is idle: "waiting", "active · waiting". */
    data object Waiting : GoalPhase

    /** Paused for any reason, the round limit included. */
    data object Paused : GoalPhase

    /** Complete; kept for the rest of the day. */
    data object Done : GoalPhase
}

/** The goal line under the top bar (`.goalline`). */
sealed interface GoalLine {
    /** No goal while idle: "Set today's goal", all `text3`. */
    data object Empty : GoalLine

    data class Phase(
        val phase: GoalPhase,
    ) : GoalLine

    companion object {
        /** The line for [goal] while the session is [busy]; null for none (no goal while busy). */
        fun of(
            goal: Goal?,
            busy: Boolean,
        ): GoalLine? =
            when {
                goal != null -> Phase(goal.phase(busy))
                busy -> null
                else -> Empty
            }
    }
}

/** The goal sheet's buttons (app-plan.md, The goal sheet › Buttons). */
enum class GoalAction {
    /** "Say the goal": no goal yet. */
    Say,
    Clear,
    Pause,
    Resume,

    /** Change: a new take for an active or paused goal. */
    Change,

    /** "New goal": a new take after a finished one. */
    New,
    ;

    companion object {
        /** Left to right: Clear first (left), then the rest (right). */
        fun of(goal: Goal?): List<GoalAction> =
            when (goal?.status) {
                null -> listOf(Say)
                GoalStatus.Active -> listOf(Clear, Pause, Change)
                GoalStatus.Paused -> listOf(Clear, Resume, Change)
                GoalStatus.Complete -> listOf(Clear, New)
            }
    }
}

/** What `/goal` is sent: `{"name": "goal", "text": …}`. Pause, resume and clear are the same command. */
sealed interface GoalCommand {
    val text: String

    /** Sets a new goal, replacing any one there is. */
    data class Set(
        override val text: String,
    ) : GoalCommand

    data object Pause : GoalCommand {
        override val text = "pause"
    }

    data object Resume : GoalCommand {
        override val text = "resume"
    }

    data object Clear : GoalCommand {
        override val text = "clear"
    }
}

/** Why a goal's text isn't sent. */
enum class GoalRefusal {
    /** "Say a longer goal": the server would read it as `pause`, `resume` or `clear`. */
    Word,

    /** "That's too long for a goal": over [GoalText.MAX_CHARACTERS]. */
    TooLong,
}

/** The rules for a goal's text (app-plan.md, Set as goal from a draft › Refused). */
object GoalText {
    /** OpenCode's limit on an objective, in UTF-16 units, as it counts them. */
    const val MAX_CHARACTERS = 4_000

    private val commands = setOf("pause", "resume", "clear")

    /**
     * Why [text] can't be a goal: a lone `pause`, `resume` or `clear` in any case and with any punctuation around it,
     * which the server matches as a command; or more than [MAX_CHARACTERS]. Null when it can be sent.
     */
    fun refusal(text: String): GoalRefusal? {
        val word = text.lowercase().trim { !it.isLetterOrDigit() }
        return when {
            word in commands -> GoalRefusal.Word
            text.trim().length > MAX_CHARACTERS -> GoalRefusal.TooLong
            else -> null
        }
    }
}
