package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v2 tool lifecycle events after `input.started` usually omit `name`. TUI keeps
 * the name on its in-memory tools map. Mobile projects each event into a full
 * `message.part.updated`, so a dummy `"tool"` must never become identity.
 */
object ToolPartIdentity {
    fun isPlaceholder(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        return trimmed.isEmpty() || trimmed.equals("tool", ignoreCase = true)
    }

    fun canonical(explicit: String?): String = if (isPlaceholder(explicit)) "" else explicit!!.trim()

    fun coalesce(
        incoming: String,
        existing: String,
    ): String {
        if (isPlaceholder(incoming) && !isPlaceholder(existing)) return existing
        return incoming.ifBlank { existing }
    }

    fun resolvedName(
        explicit: String?,
        input: JsonObject?,
    ): String {
        val fromWire = canonical(explicit)
        if (fromWire.isNotEmpty()) return fromWire
        if (hasQuestions(input)) return "question"
        if (hasNonBlank(input, "query")) return "websearch"
        if (hasFilePath(input)) return "read"
        return ""
    }

    fun isQuestion(
        name: String,
        input: JsonObject?,
    ): Boolean = name.equals("question", ignoreCase = true) || hasQuestions(input)

    fun calledTitle(name: String): String = if (isPlaceholder(name)) "Tool" else "Called $name"

    /** TUI never paints a card whose name is still the dummy `"tool"`. */
    fun isVisibleOnTimeline(tool: MessagePart.Tool): Boolean {
        if (tool.toolName.equals("todowrite", ignoreCase = true)) return false
        val question = isQuestion(tool.toolName, tool.input)
        if (question && (tool.status == ToolStatus.Pending || tool.status == ToolStatus.Running)) {
            return false
        }
        if (isPlaceholder(resolvedName(tool.toolName, tool.input)) && !question) return false
        return true
    }

    private fun hasQuestions(input: JsonObject?): Boolean = (input?.get("questions") as? JsonArray)?.isNotEmpty() == true

    private fun hasFilePath(input: JsonObject?): Boolean = hasNonBlank(input, "filePath") || hasNonBlank(input, "file")

    private fun hasNonBlank(
        input: JsonObject?,
        key: String,
    ): Boolean = (input?.get(key) as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
}
