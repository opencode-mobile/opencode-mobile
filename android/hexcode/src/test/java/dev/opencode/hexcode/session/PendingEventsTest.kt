package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4's events, decoded from frames captured live on v2.2.5, and what they do to today's session: the whole request or
 * form, Pending ends with the run, subagents' titles, and server notes that never wait to send.
 */
class PendingEventsTest {
    private val root = "ses_e4e1050f8ffe9B0VNP7mbPXtoO"
    private val child = "ses_e4e10394fffevEubO4oMJhf4Jt"

    private fun frame(
        type: String,
        data: String,
    ) = """{"id":"evt_1","created":1793986643635,"type":"$type","location":{"directory":"/tmp/p"},"data":$data}"""

    private fun LiveSessionState.after(vararg events: SessionEvent?): LiveSessionState =
        events.filterNotNull().fold(this) { state, event -> state.apply(event, 9_000).first }

    @Test
    fun aSubagentIsKnownFromItsFlatSessionCreated() {
        val event =
            SessionEvent.decode(
                frame(
                    "session.created",
                    """{"sessionID":"$child","slug":"playful-planet","version":"2.2.5","projectID":"c6e7","parentID":"$root",
                    "location":{"directory":"/tmp/p"},"subpath":"","title":"m4 date check","agent":"general"}""",
                ),
            )
        assertEquals(SessionEvent.SessionCreated(child, root, "m4 date check", "general"), event)
        val state = LiveSessionState(root).after(event)
        assertTrue(child in state.children)
        assertEquals(ChildInfo("m4 date check", "general"), state.childInfo[child])
    }

    @Test
    fun anApprovalCarriesTheWholeRequest() {
        val event =
            SessionEvent.decode(
                frame(
                    "permission.asked",
                    """{"id":"per_1","sessionID":"$child","action":"shell","resources":["date"],"save":["date *"],
                    "source":{"type":"tool","messageID":"msg_1","id":"call-1"}}""",
                ),
            ) as SessionEvent.PermissionAsked
        assertEquals("date", event.item.text)
        assertEquals(ApprovalRequest("shell", listOf("date"), listOf("date *")), event.item.approval)
    }

    @Test
    fun aQuestionCarriesTheWholeForm() {
        val event =
            SessionEvent.decode(
                frame(
                    "form.created",
                    """{"form":{"id":"frm_1","sessionID":"$root","title":"Questions","metadata":{"kind":"question"},
                    "fields":[{"key":"q0","title":"Colour","description":"Which colour?","type":"string",
                    "options":[{"value":"Red","label":"Red","description":"warm"}],"custom":true}]}}""",
                ),
            ) as SessionEvent.FormCreated
        assertEquals("Which colour?", event.item.text)
        val field =
            event.item.form!!
                .fields
                .single()
        assertEquals(QuestionField("q0", "Colour", "Which colour?", "string", listOf(QuestionOption("Red", "Red", "warm")), true), field)
    }

    @Test
    fun anApprovalFollowedByTheRunsInterruptionLeavesNothingPending() {
        val asked = SessionEvent.PermissionAsked(PendingItem("per_1", root, PendingItem.Kind.Approval, "sleep 40"))
        val state =
            LiveSessionState(root).after(
                SessionEvent.ExecutionStarted(root, 1),
                asked,
                SessionEvent.decode(frame("session.execution.interrupted", """{"sessionID":"$root","reason":"user"}""")),
            )
        assertTrue(state.pending.isEmpty())
        assertNull(state.firstPending)
    }

    @Test
    fun aSubagentsRunEndingDropsOnlyItsOwnPending() {
        val state =
            LiveSessionState(root, children = setOf(child)).after(
                SessionEvent.ExecutionStarted(child, 5),
                SessionEvent.PermissionAsked(PendingItem("per_c", child, PendingItem.Kind.Approval, "date")),
                SessionEvent.FormCreated(PendingItem("frm_r", root, PendingItem.Kind.Question, "Which?")),
                SessionEvent.ExecutionEnded(child),
            )
        assertEquals(listOf("frm_r"), state.pending.map { it.id })
        assertTrue(state.runningChildren.isEmpty())
    }

    @Test
    fun aSubagentsNoteNeverWaitsToSend() {
        val note =
            SessionEvent.decode(
                frame(
                    "session.inbox.enqueued",
                    """{"inboxID":"msg_n","sessionID":"$root","item":{"type":"synthetic",
                    "payload":{"text":"<subagent sessionID=\"$child\" state=\"completed\">done</subagent>"},"delivery":"steer"}}""",
                ),
            ) as SessionEvent.InboxEnqueued
        assertTrue(note.synthetic)
        assertTrue(LiveSessionState(root).after(note).inbox.isEmpty())
    }

    @Test
    fun aSubagentsRunStartGivesItsElapsedTime() {
        val state =
            LiveSessionState(root).after(
                SessionEvent.SessionCreated(child, root, "m4 date check", "general"),
                SessionEvent.ExecutionStarted(child, 4_000),
            )
        assertEquals(4_000L, state.childInfo[child]?.busySince)
        assertEquals(setOf(child), state.runningChildren)
    }

    @Test
    fun toolsFinishingCountForRecentAndShellsKeepTheirCommand() {
        val failed = SessionEvent.decode(frame("session.tool.failed", """{"sessionID":"$root","id":"call-1","error":{"type":"aborted"}}"""))
        val success = SessionEvent.decode(frame("session.tool.success", """{"sessionID":"$root","id":"call-2","metadata":{"exit":0}}"""))
        assertEquals(SessionEvent.ToolFinished(root), failed)
        assertEquals(SessionEvent.ToolFinished(root), success)
        val shell =
            SessionEvent.decode(
                frame(
                    "shell.created",
                    """{"info":{"id":"sh_1","status":"running","command":"bun dev","metadata":{"sessionID":"$root"},"time":{"started":1}}}""",
                ),
            )
        val state = LiveSessionState(root).after(failed, success, shell, SessionEvent.ToolFinished("ses_other"))
        assertEquals(2, state.toolEvents)
        assertEquals("bun dev", state.shellRuns.single().command)
        assertTrue("session.tool.failed" in SessionEvent.TYPES)
    }

    @Test
    fun aRejectTakesTheSessionsOtherApprovalsWithIt() {
        val state =
            LiveSessionState(root).after(
                SessionEvent.PermissionAsked(PendingItem("per_1", root, PendingItem.Kind.Approval, "a")),
                SessionEvent.PermissionAsked(PendingItem("per_2", root, PendingItem.Kind.Approval, "b")),
                SessionEvent.FormCreated(PendingItem("frm_1", root, PendingItem.Kind.Question, "c")),
            )
        assertEquals(listOf("frm_1"), state.withoutApprovals(root).pending.map { it.id })
        assertEquals(listOf("per_2", "frm_1"), state.withoutPending("per_1").pending.map { it.id })
    }
}
