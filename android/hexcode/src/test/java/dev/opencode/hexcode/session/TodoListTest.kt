package dev.opencode.hexcode.session

import dev.opencode.hexcode.session.TodoItem.Status.Completed
import dev.opencode.hexcode.session.TodoItem.Status.InProgress
import dev.opencode.hexcode.session.TodoItem.Status.Pending
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** app-plan.md, To-do list and To-do row, with the shapes OpenCode v2.2.5 sent (captured live). */
class TodoListTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** A completed `todowrite` part as `GET /api/session/{id}/message` returns it. */
    private fun todowrite(
        metadata: String?,
        input: String = "[]",
        status: String = "completed",
    ) = """{"type":"tool","id":"call-1","name":"todowrite","executed":false,"state":{"status":"$status",
        "input":{"todos":$input},"content":[{"type":"text","text":"Todos updated"}]""" +
        (if (metadata != null) ""","metadata":{"todos":$metadata,"truncated":false}""" else "") +
        """},"time":{"created":1,"ran":2,"completed":3}}"""

    private fun message(vararg parts: String) = obj("""{"id":"msg_1","type":"assistant","content":[${parts.joinToString(",")}]}""")

    private val three =
        """[{"content":"read math.ts","status":"completed"},{"content":"read README.md","status":"in_progress"},""" +
            """{"content":"reply with the number of lines in each","status":"pending"}]"""

    @Test
    fun theListComesFromTheNewestCompletedTodowritePart() {
        val older = message(todowrite("""[{"content":"old","status":"pending"}]"""))
        val newer =
            message(
                """{"type":"text","text":"Reading."}""",
                todowrite("""[{"content":"first call","status":"pending"}]"""),
                todowrite(three),
                // Still running: not the list yet.
                todowrite("""[{"content":"running","status":"pending"}]""", status = "running"),
            )
        assertEquals(
            listOf(
                TodoItem("read math.ts", Completed),
                TodoItem("read README.md", InProgress),
                TodoItem("reply with the number of lines in each", Pending),
            ),
            TodoList.fromMessages(listOf(newer, older)),
        )
    }

    @Test
    fun withoutMetadataTheInputIsTheList() {
        assertEquals(
            listOf(TodoItem("a", Pending)),
            TodoList.fromMessages(listOf(message(todowrite(metadata = null, input = """[{"content":"a","status":"pending"}]""")))),
        )
    }

    @Test
    fun messagesWithoutTodowriteHaveNoList() {
        assertNull(TodoList.fromMessages(listOf(message("""{"type":"tool","name":"read","state":{"status":"completed"}}"""))))
        assertNull(TodoList.fromMessages(emptyList()))
    }

    @Test
    fun theListComesFromAToolSuccessEvent() {
        val data =
            obj(
                """{"sessionID":"ses_1","assistantMessageID":"msg_1","id":"call-1","content":[{"type":"text","text":"Todos updated"}],
                "metadata":{"todos":$three,"truncated":false},"executed":false}""",
            )
        assertEquals(3, TodoList.fromToolSuccess(data)?.size)
        // Another tool's success carries no list.
        assertNull(TodoList.fromToolSuccess(obj("""{"sessionID":"ses_1","metadata":{"truncated":false}}""")))
    }

    @Test
    fun cancelledItemsAreLeftOut() {
        val todos =
            Json.parseToJsonElement(
                """[{"content":"a","status":"completed"},{"content":"b","status":"cancelled"},{"content":"c","status":"pending"}]""",
            ) as JsonArray
        assertEquals(listOf(TodoItem("a", Completed), TodoItem("c", Pending)), TodoList.read(todos))
    }

    @Test
    fun theLabelNamesTheItemInProgress() {
        assertEquals(
            TodoLabel.Current(3, 4, "Token refresh"),
            TodoList.label(listOf(item(Completed), item(Completed), TodoItem("Token refresh", InProgress), item(Pending))),
        )
    }

    @Test
    fun withNoneInProgressTheLabelNamesTheFirstPending() {
        assertEquals(
            TodoLabel.Current(2, 3, "Auth tests"),
            TodoList.label(listOf(item(Completed), TodoItem("Auth tests", Pending), item(Pending))),
        )
    }

    @Test
    fun everyItemCompletedIsAllDone() {
        assertEquals(TodoLabel.AllDone(4), TodoList.label(List(4) { item(Completed) }))
    }

    @Test
    fun noListOrAnEmptyOneHasNoRow() {
        assertNull(TodoList.label(emptyList()))
        assertEquals(emptyList<TodoItem>(), TodoList.read(JsonArray(emptyList())))
    }

    @Test
    fun theItemIsClippedAt40CharactersShown() {
        val label = TodoList.label(listOf(TodoItem("Rewrite every test in `auth/` that leans on the old session helper", InProgress)))
        assertEquals(TodoLabel.Current(1, 1, "Rewrite every test in `auth/` that leans…"), label)
    }

    private fun item(status: TodoItem.Status) = TodoItem("x", status)

    @Test
    fun anUnknownStatusCountsAsPending() {
        val todos = Json.parseToJsonElement("""[{"content":"a","status":"blocked"},{"content":"b"}]""") as JsonArray
        assertEquals(listOf(TodoItem("a", Pending), TodoItem("b", Pending)), TodoList.read(todos))
    }

    @Test
    fun anEmptyTodowriteListClearsTheRow() {
        // The newest call wrote an empty list: that's the list, not "none here, look further back".
        val newer = message(todowrite("[]"))
        val older = message(todowrite("""[{"content":"old","status":"pending"}]"""))
        assertEquals(emptyList<TodoItem>(), TodoList.fromMessages(listOf(newer, older)))
        val event = obj("""{"sessionID":"ses_1","metadata":{"todos":[],"truncated":false}}""")
        assertEquals(emptyList<TodoItem>(), TodoList.fromToolSuccess(event))
        assertNull(TodoList.label(emptyList()))
    }
}
