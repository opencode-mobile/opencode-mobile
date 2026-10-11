package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A child session the linker may attach to a subagent tool part. */
data class SessionChatChildSessionCandidate(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long?,
)

/**
 * Fills `metadata.sessionID` on subagent tool parts that lack it, so their
 * rows and chips can open the child session.
 *
 * v2 reports a running child's id once, on `session.tool.progress`; the stored
 * running part keeps empty metadata, so a history reload loses it until the
 * subagent finishes. Like the web client's `taskSession` fallback, a part
 * without an id matches a child of this session whose title starts with the
 * part's `description` (the server titles the child with it). A resumed
 * subagent names its child in `input.sessionID`.
 */
object SessionChatChildSessionLinker {
    fun isSubagentTool(toolName: String): Boolean {
        val name = toolName.lowercase()
        return name == "task" || name == "subagent"
    }

    /** Subagent part ids that carry no child id of their own. */
    fun unlinkedPartIds(messages: List<Message>): Set<String> =
        buildSet {
            for (message in messages) {
                for (part in message.parts) {
                    if (part !is MessagePart.Tool || !isSubagentTool(part.toolName)) continue
                    if (ownSessionId(part) != null || description(part) == null) continue
                    part.id?.let(::add)
                }
            }
        }

    /**
     * Links parts in timeline order. Children already named by a part are not
     * reused, and among the rest the oldest match wins, so parallel subagents
     * with the same description map to their children in spawn order.
     */
    fun link(
        messages: List<Message>,
        children: List<SessionChatChildSessionCandidate>,
    ): List<Message> {
        if (messages.none(::hasUnresolvedSubagent)) return messages
        val claimed =
            messages
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
                .filter { isSubagentTool(it.toolName) }
                .mapNotNullTo(mutableSetOf(), ::ownSessionId)
        val available =
            children
                .filter { it.id !in claimed }
                .sortedWith(compareBy<SessionChatChildSessionCandidate> { it.createdAtEpochMs ?: 0L }.thenBy { it.id })
                .toMutableList()
        return messages.map { message ->
            if (!hasUnresolvedSubagent(message)) return@map message
            val parts =
                message.parts.map { part ->
                    if (part !is MessagePart.Tool || !isSubagentTool(part.toolName) || metadataSessionId(part) != null) {
                        return@map part
                    }
                    val resumed = inputSessionId(part)
                    if (resumed != null) return@map part.withSessionId(resumed)
                    val description = description(part) ?: return@map part
                    val index = available.indexOfFirst { it.title.startsWith(description) }
                    if (index < 0) return@map part
                    part.withSessionId(available.removeAt(index).id)
                }
            message.copy(parts = parts)
        }
    }

    private fun hasUnresolvedSubagent(message: Message): Boolean =
        message.parts.any { part ->
            part is MessagePart.Tool && isSubagentTool(part.toolName) && metadataSessionId(part) == null
        }

    private fun MessagePart.Tool.withSessionId(id: String): MessagePart.Tool =
        copy(metadata = JsonObject((metadata ?: JsonObject(emptyMap())) + ("sessionID" to JsonPrimitive(id))))

    private fun ownSessionId(tool: MessagePart.Tool): String? = metadataSessionId(tool) ?: inputSessionId(tool)

    private fun metadataSessionId(tool: MessagePart.Tool): String? =
        string(tool.metadata, "sessionID") ?: string(tool.metadata, "sessionId")

    private fun inputSessionId(tool: MessagePart.Tool): String? = string(tool.input, "sessionID")

    private fun description(tool: MessagePart.Tool): String? = string(tool.input, "description")

    private fun string(
        json: JsonObject?,
        key: String,
    ): String? = (json?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
}
