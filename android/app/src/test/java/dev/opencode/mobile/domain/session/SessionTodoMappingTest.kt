package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.dto.TodoDto
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTodoMappingTest {
    private val adapter = SessionDomainAdapter()

    @Test
    fun `maps todo dto fields and position`() {
        val todos =
            adapter.toTodos(
                listOf(
                    TodoDto(content = "A", status = "pending", priority = "high"),
                    TodoDto(content = "B", status = "in_progress", priority = "medium"),
                ),
            )
        assertEquals(0, todos[0].position)
        assertEquals("A", todos[0].content)
        assertEquals(SessionTodoStatus.Pending, todos[0].status)
        assertEquals(1, todos[1].position)
        assertEquals(SessionTodoStatus.InProgress, todos[1].status)
    }

    @Test
    fun `maps event properties as full list`() {
        val properties =
            buildJsonObject {
                put("sessionID", "ses_1")
                put(
                    "todos",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("content", "Ship")
                                put("status", "completed")
                                put("priority", "low")
                            },
                        )
                    },
                )
            }
        val todos = adapter.toTodosFromEventProperties(properties)!!
        assertEquals(1, todos.size)
        assertEquals("Ship", todos[0].content)
        assertEquals(SessionTodoStatus.Completed, todos[0].status)
    }

    @Test
    fun `active preview prefers in_progress then pending then last completed`() {
        val todos =
            listOf(
                SessionTodo(0, "done", SessionTodoStatus.Completed, "low"),
                SessionTodo(1, "wait", SessionTodoStatus.Pending, "low"),
                SessionTodo(2, "now", SessionTodoStatus.InProgress, "high"),
            )
        assertEquals("now", SessionTodoList.activePreview(todos)?.content)

        val withoutInProgress = todos.filterNot { it.status == SessionTodoStatus.InProgress }
        assertEquals("wait", SessionTodoList.activePreview(withoutInProgress)?.content)

        val completedOnly =
            listOf(
                SessionTodo(0, "a", SessionTodoStatus.Completed, "low"),
                SessionTodo(1, "b", SessionTodoStatus.Completed, "low"),
            )
        assertEquals("b", SessionTodoList.activePreview(completedOnly)?.content)
    }

    @Test
    fun `progress counts completed only and cancelled finishes list`() {
        val todos =
            listOf(
                SessionTodo(0, "a", SessionTodoStatus.Completed, "low"),
                SessionTodo(1, "b", SessionTodoStatus.Cancelled, "low"),
                SessionTodo(2, "c", SessionTodoStatus.Pending, "low"),
            )
        assertEquals(1, SessionTodoList.completedCount(todos))
        assertFalse(SessionTodoList.isFinished(todos))

        val finished =
            listOf(
                SessionTodo(0, "a", SessionTodoStatus.Completed, "low"),
                SessionTodo(1, "b", SessionTodoStatus.Cancelled, "low"),
            )
        assertTrue(SessionTodoList.isFinished(finished))
        assertEquals(1, SessionTodoList.completedCount(finished))
        assertFalse(SessionTodoList.isVisible(finished))
        assertTrue(SessionTodoList.isVisible(todos))
        assertNull(SessionTodoList.activePreview(emptyList()))
        assertFalse(SessionTodoList.shouldHoldFinished(sawUnfinished = false, todos = finished))
        assertTrue(SessionTodoList.shouldHoldFinished(sawUnfinished = true, todos = finished))
        assertFalse(SessionTodoList.shouldHoldFinished(sawUnfinished = true, todos = todos))
    }
}
