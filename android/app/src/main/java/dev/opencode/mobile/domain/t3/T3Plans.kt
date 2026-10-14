package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The desktop Tasks bar for the run that is still going.
 *
 * A `todo_list` whose run id matches the active run is shown until that run
 * leaves the working statuses. An idle thread publishes an empty list so the
 * pill hides, including when steps are still pending.
 */
object T3Plans {
    private val ACTIVE_STATUSES = setOf("preparing", "queued", "starting", "running", "waiting")

    fun visibleTodos(
        plans: List<T3Plan>,
        runs: List<T3Run>,
        activeRunId: String?,
        activityRunStatus: String?,
    ): List<SessionTodo> {
        val runId = activeRunId?.trim().orEmpty()
        if (runId.isEmpty()) return emptyList()
        val status =
            runs.firstOrNull { it.id == runId }?.status?.trim()?.takeIf { it.isNotEmpty() }
                ?: activityRunStatus?.trim()?.takeIf { it.isNotEmpty() }
        if (status != null && status.lowercase() !in ACTIVE_STATUSES) return emptyList()
        val plan = plans.lastOrNull { it.runId == runId && it.steps.isNotEmpty() } ?: return emptyList()
        return plan.steps.mapIndexed { index, step ->
            SessionTodo(
                position = index,
                content = step.text,
                status = todoStatus(step.status),
                priority = "medium",
            )
        }
    }

    /** `todo.updated` body the OpenCode live store already applies. */
    fun updatedEvent(
        sessionId: String,
        todos: List<SessionTodo>,
    ): OpencodeEvent =
        OpencodeEvent(
            type = "todo.updated",
            sessionId = sessionId,
            rawProperties =
                JsonObject(
                    mapOf(
                        "todos" to
                            JsonArray(
                                todos.map { todo ->
                                    JsonObject(
                                        mapOf(
                                            "content" to JsonPrimitive(todo.content),
                                            "status" to JsonPrimitive(wireStatus(todo.status)),
                                            "priority" to JsonPrimitive(todo.priority),
                                        ),
                                    )
                                },
                            ),
                    ),
                ),
        )

    private fun todoStatus(raw: String): SessionTodoStatus =
        when (raw.lowercase()) {
            "running", "in_progress", "in-progress", "inprogress" -> SessionTodoStatus.InProgress
            "completed", "complete", "done" -> SessionTodoStatus.Completed
            else -> SessionTodoStatus.Pending
        }

    private fun wireStatus(status: SessionTodoStatus): String =
        when (status) {
            SessionTodoStatus.InProgress -> "in_progress"
            SessionTodoStatus.Completed -> "completed"
            SessionTodoStatus.Cancelled -> "cancelled"
            SessionTodoStatus.Pending -> "pending"
        }
}
