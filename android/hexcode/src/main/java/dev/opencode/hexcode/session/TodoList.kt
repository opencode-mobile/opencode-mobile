package dev.opencode.hexcode.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One item of the agent's to-do list. */
data class TodoItem(
    val content: String,
    val status: Status,
) {
    enum class Status { Pending, InProgress, Completed }
}

/** What the to-do row says after its segments. */
sealed interface TodoLabel {
    /** "3 of 4 · <the item>": the item in progress, else the first pending one, at [position] (from 1) of [count]. */
    data class Current(
        val position: Int,
        val count: Int,
        val text: String,
    ) : TodoLabel

    /** "All 4 done". */
    data class AllDone(
        val count: Int,
    ) : TodoLabel
}

/**
 * The agent's own to-do list (app-plan.md, To-do list): the latest `todowrite` call. On open and reconnect it's the
 * newest completed `todowrite` tool part in the session's assistant messages ([fromMessages]); after that each
 * `session.tool.success` whose `metadata.todos` is a list replaces it ([fromToolSuccess]). Cancelled items are left
 * out. Shapes as OpenCode v2.2.5 sends them.
 */
object TodoList {
    /**
     * [todos] (`[{content, status}]`) as items, cancelled ones left out and an unknown status counted as pending; null
     * when it isn't a list. An empty list is a list: it clears the row.
     */
    fun read(todos: JsonElement?): List<TodoItem>? {
        val list = todos as? JsonArray ?: return null
        return list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val content = item.string("content")?.trim().orEmpty()
            val status =
                when (item.string("status")) {
                    "cancelled" -> return@mapNotNull null
                    "in_progress" -> TodoItem.Status.InProgress
                    "completed" -> TodoItem.Status.Completed
                    // Pending, and any status this version doesn't know.
                    else -> TodoItem.Status.Pending
                }
            if (content.isEmpty()) null else TodoItem(content, status)
        }
    }

    /**
     * The list from the newest completed `todowrite` part in [messages] (assistant messages, newest first, each with
     * its `content` parts): its `state.metadata.todos`, else `state.input.todos`. Null when these messages have none.
     */
    fun fromMessages(messages: List<JsonObject>): List<TodoItem>? {
        for (message in messages) {
            val parts = message["content"] as? JsonArray ?: continue
            // Within a message, a later part is newer.
            for (part in parts.reversed()) {
                val tool = part as? JsonObject ?: continue
                if (tool.string("type") != "tool" || tool.string("name") != "todowrite") continue
                val state = tool["state"] as? JsonObject ?: continue
                if (state.string("status") != "completed") continue
                val list = read((state["metadata"] as? JsonObject)?.get("todos")) ?: read((state["input"] as? JsonObject)?.get("todos"))
                if (list != null) return list
            }
        }
        return null
    }

    /** The list a `session.tool.success` event's [data] carries in `metadata.todos`; null when it carries none. */
    fun fromToolSuccess(data: JsonObject): List<TodoItem>? = read((data["metadata"] as? JsonObject)?.get("todos"))

    /** What the row says for [items]; null when there's no row (no items). */
    fun label(items: List<TodoItem>): TodoLabel? {
        if (items.isEmpty()) return null
        val current =
            items.indexOfFirst { it.status == TodoItem.Status.InProgress }.takeIf { it >= 0 }
                ?: items.indexOfFirst { it.status == TodoItem.Status.Pending }.takeIf { it >= 0 }
                ?: return TodoLabel.AllDone(items.size)
        return TodoLabel.Current(current + 1, items.size, items[current].content)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
