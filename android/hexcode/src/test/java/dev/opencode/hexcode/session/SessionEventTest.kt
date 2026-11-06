package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads as OpenCode v2.2.5 sends them (m2-api-notes.md, §4, captured live). */
class SessionEventTest {
    @Test
    fun theTypesAreTheExactSeventeenThePlanLists() {
        assertEquals(17, SessionEvent.TYPES.size)
        assertEquals(SessionEvent.TYPES.distinct(), SessionEvent.TYPES)
        assertEquals(listOf<String>(), SessionEvent.TYPES.filter { '*' in it || ',' in it })
    }

    @Test
    fun runStartAndEnd() {
        assertEquals(SessionEvent.Connected, SessionEvent.decode("""{"id":"evt_1","type":"server.connected","data":{}}"""))
        assertEquals(
            SessionEvent.ExecutionStarted("ses_1", 1793914275814),
            SessionEvent.decode("""{"id":"e","created":1793914275814,"type":"session.execution.started","data":{"sessionID":"ses_1"}}"""),
        )
        for (end in listOf("succeeded", "failed", "interrupted")) {
            assertEquals(
                SessionEvent.ExecutionEnded("ses_1"),
                SessionEvent.decode("""{"type":"session.execution.$end","data":{"sessionID":"ses_1","reason":"user"}}"""),
            )
        }
    }

    @Test
    fun anApprovalSaysTheCommandElseTheMessageElseTheAction() {
        val asked =
            SessionEvent.decode(
                """{"type":"permission.asked","data":{"id":"per_1","sessionID":"ses_1","action":"shell","resources":["ls"],
                "save":["ls *"],"source":{"type":"tool","messageID":"m","id":"c"}}}""",
            )
        assertEquals(SessionEvent.PermissionAsked(PendingItem("per_1", "ses_1", PendingItem.Kind.Approval, "ls")), asked)
        assertEquals("Edit the config", PendingItem.approvalText("edit", emptyList(), "Edit the config"))
        assertEquals("edit", PendingItem.approvalText("edit", listOf(""), null))
        assertEquals(
            SessionEvent.PermissionReplied("ses_1", "per_1"),
            SessionEvent.decode("""{"type":"permission.replied","data":{"sessionID":"ses_1","requestID":"per_1","reply":"once"}}"""),
        )
    }

    @Test
    fun aQuestionSaysTheFirstFieldsQuestionAndNestsItsSession() {
        val created =
            SessionEvent.decode(
                """{"type":"form.created","data":{"form":{"id":"frm_1","sessionID":"ses_child","title":"Questions",
                "metadata":{"kind":"question"},"fields":[{"key":"q0","title":"Database","description":"Which database?"},
                {"key":"q1","title":"Other"}]}}}""",
            )
        assertEquals(SessionEvent.FormCreated(PendingItem("frm_1", "ses_child", PendingItem.Kind.Question, "Which database?")), created)
        assertEquals("Database", PendingItem.questionText("Questions", "Database", null))
        assertEquals("Questions", PendingItem.questionText("Questions", null, ""))
        for (closed in listOf("replied", "cancelled")) {
            assertEquals(
                SessionEvent.FormClosed("ses_1", "frm_1"),
                SessionEvent.decode("""{"type":"form.$closed","data":{"id":"frm_1","sessionID":"ses_1","answer":{"q0":"Yes"}}}"""),
            )
        }
    }

    @Test
    fun theInbox() {
        assertEquals(
            SessionEvent.InboxEnqueued("ses_1", "msg_B", "queue", "m2 queued A"),
            SessionEvent.decode(
                """{"type":"session.inbox.enqueued","data":{"sessionID":"ses_1","inboxID":"msg_B",
                "item":{"type":"user","payload":{"text":"m2 queued A"},"delivery":"queue"}}}""",
            ),
        )
        assertEquals(
            SessionEvent.InboxRemoved("ses_1", "msg_B"),
            SessionEvent.decode("""{"type":"session.inbox.cancelled","data":{"sessionID":"ses_1","inboxID":"msg_B"}}"""),
        )
        assertEquals(
            SessionEvent.InboxRemoved("ses_1", "msg_C", delivered = true),
            SessionEvent.decode("""{"type":"session.inbox.delivered","data":{"sessionID":"ses_1","inboxID":"msg_C"}}"""),
        )
        assertEquals(
            SessionEvent.InboxDeliveryChanged("ses_1", "msg_C", "steer"),
            SessionEvent.decode(
                """{"type":"session.inbox.delivery.changed","data":{"sessionID":"ses_1","inboxID":"msg_C","delivery":"steer"}}""",
            ),
        )
    }

    @Test
    fun subagentsAndShells() {
        assertEquals(
            SessionEvent.SessionCreated("ses_child", "ses_1"),
            SessionEvent.decode(
                """{"type":"session.created","data":{"sessionID":"ses_child","info":{"id":"ses_child","parentID":"ses_1"}}}""",
            ),
        )
        assertEquals(
            SessionEvent.ShellCreated("sh_1", "ses_1"),
            SessionEvent.decode(
                """{"type":"shell.created","data":{"info":{"id":"sh_1","status":"running","command":"ls","metadata":{"sessionID":"ses_1"},"time":{"started":1}}}}""",
            ),
        )
        assertEquals(
            SessionEvent.ShellExited("sh_1"),
            SessionEvent.decode("""{"type":"shell.exited","data":{"id":"sh_1","exit":0,"status":"exited"}}"""),
        )
    }

    @Test
    fun otherTypesAndBrokenPayloadsAreNull() {
        assertNull(SessionEvent.decode("""{"type":"session.step.started","data":{"sessionID":"ses_1"}}"""))
        assertNull(SessionEvent.decode("""{"type":"session.execution.started","data":{}}"""))
        assertNull(SessionEvent.decode("not json"))
        assertNull(SessionEvent.decode("""[1,2]"""))
    }

    @Test
    fun theHintTextRulesFromThePlan() {
        // Approval: resources joined with ", ", else its message, else its action.
        assertEquals("ls, cat a.txt", PendingItem.approvalText("shell", listOf("ls", "cat a.txt"), "Run commands"))
        assertEquals("Run commands", PendingItem.approvalText("shell", emptyList(), "Run commands"))
        assertEquals("shell", PendingItem.approvalText("shell", emptyList(), null))
        assertEquals("shell", PendingItem.approvalText("shell", emptyList(), ""))
        // Question: the first field's description, else that field's title, else the form's title.
        assertEquals("Which database?", PendingItem.questionText("Questions", "Database", "Which database?"))
        assertEquals("Database", PendingItem.questionText("Questions", "Database", null))
        assertEquals("Questions", PendingItem.questionText("Questions", null, null))
        assertEquals("", PendingItem.questionText(null, null, null))
    }

    @Test
    fun anApprovalWithoutResourcesUsesItsMessage() {
        assertEquals(
            SessionEvent.PermissionAsked(PendingItem("per_2", "ses_1", PendingItem.Kind.Approval, "Edit the config")),
            SessionEvent.decode(
                """{"type":"permission.asked","data":{"id":"per_2","sessionID":"ses_1","action":"edit","resources":[],"message":"Edit the config"}}""",
            ),
        )
    }

    @Test
    fun aToolSuccessWithTodosIsTheNewList() {
        val line =
            """{"id":"evt_1","created":5,"type":"session.tool.success","data":{"sessionID":"ses_1","assistantMessageID":"msg_1",""" +
                """"id":"call-1","content":[{"type":"text","text":"Todos updated: 0/1 completed"}],""" +
                """"metadata":{"todos":[{"content":"read math.ts","status":"in_progress"}],"truncated":false},"executed":false}}"""
        assertEquals(
            SessionEvent.TodosUpdated("ses_1", listOf(TodoItem("read math.ts", TodoItem.Status.InProgress))),
            SessionEvent.decode(line),
        )
    }

    @Test
    fun anotherToolsSuccessIsIgnored() {
        val line =
            """{"type":"session.tool.success","data":{"sessionID":"ses_1","id":"call-2","content":[],"metadata":{"truncated":false}}}"""
        assertEquals(null, SessionEvent.decode(line))
    }

    @Test
    fun theStreamAsksForToolSuccess() {
        assertTrue("session.tool.success" in SessionEvent.TYPES)
    }
}
