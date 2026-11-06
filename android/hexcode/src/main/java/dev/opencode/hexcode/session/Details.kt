package dev.opencode.hexcode.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One of Recent's steps (app-plan.md, Details › Recent): an edit, a command or a subagent start. */
data class RecentStep(
    val kind: Kind,
    /** The file path, the command, or the subagent's task. */
    val title: String,
    val outcome: RecentOutcome,
    /** When the step finished (else when it started), for its age. */
    val at: Long,
) {
    enum class Kind { Edit, Command, Subagent }
}

/** What Recent's second line says about a step. */
sealed interface RecentOutcome {
    /** "Edited · +2 −0". */
    data class Edited(
        val additions: Int,
        val deletions: Int,
    ) : RecentOutcome

    /** "Created · +12": a new file. */
    data class Created(
        val additions: Int,
    ) : RecentOutcome

    /** "3 files · +18 −3". */
    data class Files(
        val count: Int,
        val additions: Int,
        val deletions: Int,
    ) : RecentOutcome

    /** A command that exited 0. */
    data object Done : RecentOutcome

    /** "Failed · exit 1". */
    data class Failed(
        val exit: Int,
    ) : RecentOutcome

    /** Interrupted (Stop): `aborted` "…interrupted". */
    data object Stopped : RecentOutcome

    /** Denied: `permission.rejected`, or `aborted` "…declined…". */
    data object Denied : RecentOutcome

    /** An error that says nothing more: no second line. */
    data object Unknown : RecentOutcome

    /** "Subagent · general". */
    data class Subagent(
        val agent: String?,
    ) : RecentOutcome
}

/**
 * Recent from the session's tool parts (`GET /api/session/{id}/message?type=assistant&order=desc&limit=20`, shapes
 * as OpenCode v2.2.5 sends them, m4-proposal.md §1.4): edits, commands and subagent starts, newest first, at most
 * [LIMIT]. Every other tool (`read`, `grep`, `glob`, `question`, `todowrite`, …) is left out, and so are parts still
 * running. An edit that didn't complete changed nothing, so it's left out too.
 */
object RecentSteps {
    const val LIMIT = 3

    private val EDITS = setOf("edit", "write", "patch")
    private val COMMANDS = setOf("shell", "bash")

    fun from(messages: List<JsonObject>): List<RecentStep> {
        val steps = mutableListOf<RecentStep>()
        for (message in messages) {
            val parts = message["content"] as? JsonArray ?: continue
            // Within a message, a later part is newer.
            for (element in parts.reversed()) {
                val part = element as? JsonObject ?: continue
                if (part.string("type") != "tool") continue
                step(part)?.let { steps += it }
            }
        }
        return steps.sortedByDescending { it.at }.take(LIMIT)
    }

    /** One tool part as a step; null when Recent leaves it out. */
    fun step(part: JsonObject): RecentStep? {
        val name = part.string("name") ?: return null
        val state = part["state"] as? JsonObject ?: return null
        val status = state.string("status")
        if (status != "completed" && status != "error") return null
        val input = state["input"] as? JsonObject
        val metadata = state["metadata"] as? JsonObject
        val time = part["time"] as? JsonObject
        val at = time?.long("completed") ?: time?.long("ran") ?: time?.long("created") ?: 0L
        return when {
            name in EDITS -> {
                if (status != "completed") return null
                val files =
                    (metadata?.get("files") as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        .orEmpty()
                val first =
                    files.firstOrNull()?.let { it.string("file") ?: it.string("path") }
                        ?: input?.string("path")
                        ?: input?.string("filePath")
                        ?: return null
                val additions = files.sumOf { it.int("additions") }
                val deletions = files.sumOf { it.int("deletions") }
                val outcome =
                    when {
                        files.size > 1 -> RecentOutcome.Files(files.size, additions, deletions)
                        files.singleOrNull()?.string("status") == "added" -> RecentOutcome.Created(additions)
                        else -> RecentOutcome.Edited(additions, deletions)
                    }
                RecentStep(RecentStep.Kind.Edit, first, outcome, at)
            }
            name in COMMANDS -> {
                val command = input?.string("command")?.ifBlank { null } ?: return null
                RecentStep(RecentStep.Kind.Command, command, commandOutcome(status, state, metadata), at)
            }
            name == "subagent" || name == "task" -> {
                val description = input?.string("description")?.ifBlank { null } ?: return null
                val agent = input?.string("agent") ?: input?.string("subagent_type")
                RecentStep(RecentStep.Kind.Subagent, description, RecentOutcome.Subagent(agent), at)
            }
            else -> null
        }
    }

    private fun commandOutcome(
        status: String?,
        state: JsonObject,
        metadata: JsonObject?,
    ): RecentOutcome {
        val exit = (metadata?.get("exit") as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        if (status == "completed") return if (exit == null || exit == 0) RecentOutcome.Done else RecentOutcome.Failed(exit)
        val error = state["error"] as? JsonObject
        val type = error?.string("type")
        val message = error?.string("message").orEmpty().lowercase()
        return when {
            type == "permission.rejected" -> RecentOutcome.Denied
            type == "aborted" && "declined" in message -> RecentOutcome.Denied
            type == "aborted" && "interrupted" in message -> RecentOutcome.Stopped
            exit != null && exit != 0 -> RecentOutcome.Failed(exit)
            type == "aborted" -> RecentOutcome.Stopped
            else -> RecentOutcome.Unknown
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}

/** A Running now row: a subagent (its task and agent) or an agent shell (its command), and since when. */
sealed interface RunningRow {
    val since: Long?

    data class Subagent(
        val id: String,
        val title: String,
        val agent: String?,
        override val since: Long?,
    ) : RunningRow

    data class Shell(
        val id: String,
        val command: String,
        override val since: Long,
    ) : RunningRow
}

/**
 * Running now (app-plan.md, Details › Running now): subagents first, then agent shells running for [SHELL_SHOWN_MS] or
 * longer; at most [ROWS] rows, the rest counted in [more]. [total] matches the "N running ·" on the Details link.
 */
data class RunningNow(
    val rows: List<RunningRow>,
    val more: Int,
) {
    val total: Int get() = rows.size + more

    companion object {
        const val ROWS = 3

        fun of(
            runningChildren: List<String>,
            children: Map<String, ChildInfo>,
            shells: List<ShellRun>,
            now: Long,
        ): RunningNow {
            val subagents =
                runningChildren.map { id ->
                    val info = children[id]
                    RunningRow.Subagent(id, info?.title?.ifBlank { null } ?: id, info?.agent, info?.busySince)
                }
            val longShells =
                shells
                    .filter { now - it.startedAt >= SHELL_SHOWN_MS }
                    .sortedBy { it.startedAt }
                    .map { RunningRow.Shell(it.id, it.command, it.startedAt) }
            val all = subagents + longShells
            return RunningNow(all.take(ROWS), (all.size - ROWS).coerceAtLeast(0))
        }
    }
}

/** Details' sections, in their order (app-plan.md, Details › Order). */
enum class DetailsSection { NeedsYou, Waiting, AskedOnTheSide, Running, Todo, Recent }

object DetailsSections {
    /** The sections with something in them, in order; empty means "Nothing running right now." */
    fun of(
        pending: Int,
        waiting: Int,
        asked: Int,
        running: Int,
        todos: Int,
        recent: Int,
    ): List<DetailsSection> =
        listOfNotNull(
            DetailsSection.NeedsYou.takeIf { pending > 0 },
            DetailsSection.Waiting.takeIf { waiting > 0 },
            DetailsSection.AskedOnTheSide.takeIf { asked > 0 },
            DetailsSection.Running.takeIf { running > 0 },
            DetailsSection.Todo.takeIf { todos > 0 },
            DetailsSection.Recent.takeIf { recent > 0 },
        )
}

/** "now" under a minute, then whole minutes ("2m"), then whole hours ("3h"): Recent's age on the right. */
fun recentAge(
    at: Long,
    now: Long,
): String? {
    val seconds = (now - at).coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> null
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h"
    }
}

/** "0:07", "4:12": Running now's elapsed time. */
fun elapsedClock(
    since: Long,
    now: Long,
): String {
    val seconds = ((now - since).coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
