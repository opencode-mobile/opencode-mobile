package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The events the artifact feature reads from the same stream as today's session (app-plan.md, Artifacts (M8) › The
 * setup session): the setup session's run, tools, approvals and replies, any session's tool calls (for the one-time
 * hint), and config changes. Today's own state never sees them.
 */
sealed interface ArtifactEvent {
    val sessionId: String?

    data class RunStarted(
        override val sessionId: String,
    ) : ArtifactEvent

    /** `session.execution.succeeded`, `.failed` (with [error]) or `.interrupted`. */
    data class RunEnded(
        override val sessionId: String,
        val error: RunError? = null,
        val interrupted: Boolean = false,
    ) : ArtifactEvent

    /** `session.tool.input.started`: a tool's name, before its input. */
    data class ToolNamed(
        override val sessionId: String,
        val id: String,
        val name: String,
    ) : ArtifactEvent

    /** `session.tool.called`: a tool's input; [path] for the file tools. */
    data class ToolCalled(
        override val sessionId: String,
        val id: String,
        val path: String?,
    ) : ArtifactEvent

    /** `session.tool.success` or `.failed`. */
    data class ToolEnded(
        override val sessionId: String,
        val id: String,
        val failed: Boolean,
    ) : ArtifactEvent

    data class Approval(
        val item: PendingItem,
    ) : ArtifactEvent {
        override val sessionId: String get() = item.sessionId
    }

    data class ApprovalReplied(
        override val sessionId: String,
        val id: String,
    ) : ArtifactEvent

    /** `session.text.ended`: one of the agent's replies, whole. */
    data class Reply(
        override val sessionId: String,
        val text: String,
    ) : ArtifactEvent

    /** `config.updated`: a config file changed somewhere. */
    data object ConfigUpdated : ArtifactEvent {
        override val sessionId: String? get() = null
    }

    /** A failed run's error (`Session.StructuredError`). */
    data class RunError(
        val type: String,
        val message: String,
        val status: Int? = null,
    ) {
        /** OpenCode's free-usage limit (`429 provider.free-limit`). */
        val freeLimit: Boolean get() = status == 429 || "free-limit" in type || "free_limit" in type
    }

    companion object {
        /** The extra types the stream asks for, besides [SessionEvent.TYPES]. */
        val TYPES =
            listOf(
                "session.tool.input.started",
                "session.tool.called",
                "session.text.ended",
                "config.updated",
            )

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(line: String): ArtifactEvent? {
            val envelope = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
            val type = envelope.str("type") ?: return null
            val data = envelope["data"] as? JsonObject ?: JsonObject(emptyMap())
            if (type == "config.updated") return ConfigUpdated
            val session = data.str("sessionID")
            return when (type) {
                "permission.asked", "permission.replied" ->
                    when (val event = SessionEvent.decode(line)) {
                        is SessionEvent.PermissionAsked -> Approval(event.item)
                        is SessionEvent.PermissionReplied -> ApprovalReplied(event.sessionId, event.id)
                        else -> null
                    }
                else -> session?.let { decode(type, it, data) }
            }
        }

        private fun decode(
            type: String,
            session: String,
            data: JsonObject,
        ): ArtifactEvent? =
            when (type) {
                "session.execution.started" -> RunStarted(session)
                "session.execution.succeeded" -> RunEnded(session)
                "session.execution.interrupted" -> RunEnded(session, interrupted = true)
                "session.execution.failed" -> {
                    val error = data["error"] as? JsonObject
                    RunEnded(
                        session,
                        RunError(
                            error?.str("type").orEmpty(),
                            error?.str("message").orEmpty(),
                            (error?.get("status") as? JsonPrimitive)?.intOrNull,
                        ),
                    )
                }
                "session.tool.input.started" -> {
                    val id = data.str("id")
                    val name = data.str("name")
                    if (id != null && name != null) ToolNamed(session, id, name) else null
                }
                "session.tool.called" ->
                    data.str("id")?.let { id ->
                        val input = data["input"] as? JsonObject
                        ToolCalled(session, id, input?.str("path") ?: input?.str("filePath"))
                    }
                "session.tool.success", "session.tool.failed" ->
                    data.str("id")?.let { ToolEnded(session, it, failed = type == "session.tool.failed") }
                "session.text.ended" -> data.str("text")?.takeIf { it.isNotBlank() }?.let { Reply(session, it) }
                else -> null
            }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
