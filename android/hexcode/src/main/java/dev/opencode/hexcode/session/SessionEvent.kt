package dev.opencode.hexcode.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A question or an approval the agent waits for, in today's session or one of its subagents. */
data class PendingItem(
    val id: String,
    val sessionId: String,
    val kind: Kind,
    /** The question's text, or the command or action to approve. */
    val text: String,
    /** The whole request, for the approval sheet; null for a question. */
    val approval: ApprovalRequest? = null,
    /** The whole form, for the question sheet; null for an approval. */
    val form: QuestionForm? = null,
) {
    enum class Kind { Question, Approval }

    companion object {
        /** What the approval card says: the command or other resources, else the message, else the action. */
        fun approvalText(
            action: String,
            resources: List<String>,
            message: String?,
        ): String =
            resources.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
                ?: message?.ifEmpty { null }
                ?: action

        /** What the question card says: the first field's question, else its header, else the form's title. */
        fun questionText(
            title: String?,
            firstFieldTitle: String?,
            firstFieldDescription: String?,
        ): String = listOf(firstFieldDescription, firstFieldTitle, title).firstOrNull { !it.isNullOrEmpty() }.orEmpty()
    }
}

/** One event from `GET /api/event`, with what home needs from it (m2-api-notes.md, §4). */
sealed interface SessionEvent {
    /** The first frame of every stream. */
    data object Connected : SessionEvent

    /** [at] is the envelope's `created`, which equals the session's `time.busy`. */
    data class ExecutionStarted(
        val sessionId: String,
        val at: Long?,
    ) : SessionEvent

    /** `session.execution.succeeded`, `.failed` or `.interrupted`. */
    data class ExecutionEnded(
        val sessionId: String,
    ) : SessionEvent

    data class PermissionAsked(
        val item: PendingItem,
    ) : SessionEvent

    data class PermissionReplied(
        val sessionId: String,
        val id: String,
    ) : SessionEvent

    data class FormCreated(
        val item: PendingItem,
    ) : SessionEvent

    /** `form.replied` or `form.cancelled`. */
    data class FormClosed(
        val sessionId: String,
        val id: String,
    ) : SessionEvent

    data class InboxEnqueued(
        val sessionId: String,
        val id: String,
        val delivery: String,
        val text: String = "",
        /** The message's files by name and type; their base64, which the event carries in full, is dropped. */
        val files: List<InboxFile> = emptyList(),
        /**
         * Put there by the server, not the user (`item.type: "synthetic"`), such as a subagent's `<subagent …>` note
         * that wakes the session when it finishes.
         */
        val synthetic: Boolean = false,
        /** A finished subagent's note (`payload.metadata.source: "subagent"`), which wakes the session. */
        val fromSubagent: Boolean = false,
        /**
         * The goal's kickoff or a round's continuation (`payload.metadata.source: "goal"`), `synthetic` or `user`: never
         * a waiting message, and never the You line.
         */
        val fromGoal: Boolean = false,
        /** The goal a kickoff starts (`payload.metadata.goalID`), so Clear or Replace can take it back. */
        val goalId: String? = null,
    ) : SessionEvent

    /** `session.inbox.delivered` or `.cancelled`. */
    data class InboxRemoved(
        val sessionId: String,
        val id: String,
        /** Delivered to the session (not cancelled): the message is on its way to the agent. */
        val delivered: Boolean = false,
    ) : SessionEvent

    data class InboxDeliveryChanged(
        val sessionId: String,
        val id: String,
        val delivery: String,
    ) : SessionEvent

    /** A session was made; with [parentId] today's, it's a subagent, [title] being its task and [agent] its agent. */
    data class SessionCreated(
        val id: String,
        val parentId: String?,
        val title: String = "",
        val agent: String? = null,
    ) : SessionEvent

    /** [at] is the envelope's `created`: when the shell started. */
    data class ShellCreated(
        val id: String,
        val sessionId: String,
        val at: Long? = null,
        val command: String = "",
    ) : SessionEvent

    /** Carries no session id: it counts when its shell came from a counted [ShellCreated]. */
    data class ShellExited(
        val id: String,
    ) : SessionEvent

    /** `session.tool.success` whose `metadata.todos` is a list: the agent's to-do list now ([TodoList]). */
    data class TodosUpdated(
        val sessionId: String,
        val items: List<TodoItem>,
    ) : SessionEvent

    /** `rpc.opencode.goal.changed`: the session's goal now, after every write; null when it was cleared. */
    data class GoalChanged(
        val sessionId: String,
        val goal: Goal?,
    ) : SessionEvent

    /** Any other `session.tool.success`, or a `session.tool.failed`: Details reads Recent again. */
    data class ToolFinished(
        val sessionId: String,
    ) : SessionEvent

    companion object {
        /** The exact types home subscribes to, each sent as its own `types=` (the server refuses wildcards and lists). */
        val TYPES =
            listOf(
                "session.execution.started",
                "session.execution.succeeded",
                "session.execution.failed",
                "session.execution.interrupted",
                "permission.asked",
                "permission.replied",
                "form.created",
                "form.replied",
                "form.cancelled",
                "session.inbox.enqueued",
                "session.inbox.delivered",
                "session.inbox.cancelled",
                "session.inbox.delivery.changed",
                "session.created",
                "shell.created",
                "shell.exited",
                // Details' Recent; for the to-do list, one whose `metadata.todos` is a list.
                "session.tool.failed",
                "session.tool.success",
                // The goal line. The server accepts any `rpc.*` name without checking it, so a typo fails silently.
                "rpc.opencode.goal.changed",
            )

        private val json = Json { ignoreUnknownKeys = true }

        /** Reads one `data:` payload; null for a type home doesn't use, or a payload it can't read. */
        fun decode(line: String): SessionEvent? {
            val envelope = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
            val type = envelope.string("type") ?: return null
            val data = envelope["data"] as? JsonObject ?: JsonObject(emptyMap())
            val sessionId = data.string("sessionID")
            return when (type) {
                "server.connected" -> Connected
                "session.execution.started" -> sessionId?.let { ExecutionStarted(it, (envelope["created"] as? JsonPrimitive)?.longOrNull) }
                "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" ->
                    sessionId?.let { ExecutionEnded(it) }
                "permission.asked" -> {
                    val id = data.string("id") ?: return null
                    sessionId ?: return null
                    val text = PendingItem.approvalText(data.string("action").orEmpty(), data.strings("resources"), data.string("message"))
                    PermissionAsked(PendingItem(id, sessionId, PendingItem.Kind.Approval, text, approval = ApprovalRequest.read(data)))
                }
                "permission.replied" -> {
                    val id = data.string("requestID") ?: return null
                    sessionId?.let { PermissionReplied(it, id) }
                }
                "form.created" -> {
                    val form = data["form"] as? JsonObject ?: return null
                    val id = form.string("id") ?: return null
                    val formSession = form.string("sessionID") ?: return null
                    val first = (form["fields"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val text = PendingItem.questionText(form.string("title"), first?.string("title"), first?.string("description"))
                    FormCreated(PendingItem(id, formSession, PendingItem.Kind.Question, text, form = QuestionForm.read(form)))
                }
                "form.replied", "form.cancelled" -> {
                    val id = data.string("id") ?: return null
                    sessionId?.let { FormClosed(it, id) }
                }
                "session.inbox.enqueued" -> {
                    val id = data.string("inboxID") ?: return null
                    val item = data["item"] as? JsonObject
                    val delivery = item?.string("delivery") ?: "queue"
                    val payload = item?.get("payload") as? JsonObject
                    val text = payload?.string("text").orEmpty()
                    val files =
                        (payload?.get("files") as? JsonArray)
                            ?.mapNotNull { it as? JsonObject }
                            ?.map { InboxFile(it.string("name"), it.string("mime")) }
                            .orEmpty()
                    val synthetic = item?.string("type") == "synthetic"
                    val metadata = payload?.get("metadata") as? JsonObject
                    val source = metadata?.string("source")
                    val fromSubagent = synthetic && (source == "subagent" || text.trimStart().startsWith("<subagent"))
                    val fromGoal = source == "goal"
                    val goalId = metadata?.string("goalID")?.takeIf { fromGoal }
                    sessionId?.let { InboxEnqueued(it, id, delivery, text, files, synthetic, fromSubagent, fromGoal, goalId) }
                }
                "session.inbox.delivered", "session.inbox.cancelled" -> {
                    val id = data.string("inboxID") ?: return null
                    sessionId?.let { InboxRemoved(it, id, delivered = type == "session.inbox.delivered") }
                }
                "session.inbox.delivery.changed" -> {
                    val id = data.string("inboxID") ?: return null
                    val delivery = data.string("delivery") ?: return null
                    sessionId?.let { InboxDeliveryChanged(it, id, delivery) }
                }
                // v2.2.5 sends the session's fields flat in `data` (`sessionID`, `parentID`, `title`, `agent`); an older
                // shape nested them in `info`.
                "session.created" -> {
                    val info = data["info"] as? JsonObject ?: data
                    val id = info.string("id") ?: sessionId ?: return null
                    SessionCreated(id, info.string("parentID"), info.string("title").orEmpty(), info.string("agent"))
                }
                "shell.created" -> {
                    val info = data["info"] as? JsonObject ?: return null
                    val id = info.string("id") ?: return null
                    val shellSession = (info["metadata"] as? JsonObject)?.string("sessionID") ?: return null
                    ShellCreated(id, shellSession, (envelope["created"] as? JsonPrimitive)?.longOrNull, info.string("command").orEmpty())
                }
                "shell.exited" -> data.string("id")?.let { ShellExited(it) }
                "session.tool.success" -> {
                    sessionId ?: return null
                    TodoList.fromToolSuccess(data)?.let { TodosUpdated(sessionId, it) } ?: ToolFinished(sessionId)
                }
                "session.tool.failed" -> sessionId?.let { ToolFinished(it) }
                "rpc.opencode.goal.changed" -> sessionId?.let { GoalChanged(it, (data["goal"] as? JsonObject)?.let(Goal::read)) }
                else -> null
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty()
    }
}
