package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Filtering the unfiltered stream to today's session and its subagents (app-plan.md, Today's session on screen). */
class LiveSessionStateTest {
    private val root = LiveSessionState("ses_1")
    private val now = 9_000L

    private fun LiveSessionState.after(vararg events: SessionEvent): LiveSessionState =
        events.fold(this) { state, event -> state.apply(event, now).first }

    private fun approval(
        id: String,
        session: String,
    ) = SessionEvent.PermissionAsked(PendingItem(id, session, PendingItem.Kind.Approval, "ls"))

    @Test
    fun anotherSessionsEventsChangeNothing() {
        val other = "ses_other"
        val state =
            root.after(
                SessionEvent.ExecutionStarted(other, 5),
                approval("per_x", other),
                SessionEvent.InboxEnqueued(other, "msg_x", "queue"),
                SessionEvent.ShellCreated("sh_x", other),
                SessionEvent.SessionCreated("ses_grandchild", other),
            )
        assertEquals(root, state)
        assertEquals(null to null, root.apply(SessionEvent.ExecutionEnded(other), now).let { it.first.busySince to it.second })
    }

    @Test
    fun aSubagentsApprovalCountsOnceItIsKnownAsAChild() {
        val before = root.after(approval("per_c", "ses_child"))
        assertNull(before.firstPending)
        val state = root.after(SessionEvent.SessionCreated("ses_child", "ses_1"), approval("per_c", "ses_child"))
        assertEquals("per_c", state.firstPending?.id)
        assertNull(state.after(SessionEvent.PermissionReplied("ses_child", "per_c")).firstPending)
    }

    @Test
    fun theOldestPendingItemShowsFirstAndRepeatsAreIgnored() {
        val state = root.after(approval("per_1", "ses_1"), approval("per_2", "ses_1"), approval("per_1", "ses_1"))
        assertEquals(listOf("per_1", "per_2"), state.pending.map { it.id })
        assertEquals("per_2", state.after(SessionEvent.PermissionReplied("ses_1", "per_1")).firstPending?.id)
    }

    @Test
    fun oneRunKeepsItsFirstStartAcrossQueuedTurnsAndItsEndAsksForASummary() {
        val busy = root.after(SessionEvent.ExecutionStarted("ses_1", 100), SessionEvent.ExecutionStarted("ses_1", 200))
        assertEquals(100L, busy.busySince)
        val (idle, effect) = busy.apply(SessionEvent.ExecutionEnded("ses_1"), now)
        assertNull(idle.busySince)
        assertEquals(LiveEffect.RunEnded, effect)
        assertEquals(true, idle.hasRun)
    }

    @Test
    fun aStartWithoutATimeUsesNow() {
        assertEquals(now, root.after(SessionEvent.ExecutionStarted("ses_1", null)).busySince)
    }

    @Test
    fun theWaitingCountIsQueuedMessagesOnly() {
        val state =
            root.after(
                SessionEvent.InboxEnqueued("ses_1", "a", "queue"),
                SessionEvent.InboxEnqueued("ses_1", "b", "steer"),
                SessionEvent.InboxEnqueued("ses_1", "c", "queue"),
            )
        assertEquals(2, state.waiting)
        assertEquals(1, state.after(SessionEvent.InboxRemoved("ses_1", "a")).waiting)
        assertEquals(3, state.after(SessionEvent.InboxDeliveryChanged("ses_1", "b", "queue")).waiting)
    }

    @Test
    fun shellExitedHasNoSessionAndMatchesByItsShellId() {
        val state = root.after(SessionEvent.ShellCreated("sh_1", "ses_1"), SessionEvent.ShellCreated("sh_2", "ses_other"))
        assertEquals(setOf("sh_1"), state.shells.keys)
        assertEquals(emptySet<String>(), state.after(SessionEvent.ShellExited("sh_1")).shells.keys)
    }

    @Test
    fun theSnapshotReplacesWhatWasMissedAndKeepsShells() {
        val state = root.after(SessionEvent.ShellCreated("sh_1", "ses_1"), approval("per_old", "ses_1"))
        val snapshot =
            LiveSnapshot(
                busySince = 50,
                hasRun = true,
                children = listOf("ses_child"),
                pending = listOf(PendingItem("frm_1", "ses_child", PendingItem.Kind.Question, "Which?")),
                inbox = listOf(InboxEntry("m", "queue")),
            )
        val next = state.apply(snapshot)
        assertEquals(50L, next.busySince)
        assertEquals(setOf("ses_child"), next.children)
        assertEquals(listOf("frm_1"), next.pending.map { it.id })
        assertEquals(1, next.waiting)
        assertEquals(setOf("sh_1"), next.shells.keys)
    }

    @Test
    fun todaysToolSuccessReplacesTheListAndASubagentsDoesNot() {
        val list = listOf(TodoItem("a", TodoItem.Status.InProgress))
        val state = root.after(SessionEvent.SessionCreated("ses_child", "ses_1"), SessionEvent.TodosUpdated("ses_1", list))
        assertEquals(list, state.todos)
        assertEquals(list, state.after(SessionEvent.TodosUpdated("ses_child", emptyList())).todos)
        assertEquals(emptyList<TodoItem>(), state.after(SessionEvent.TodosUpdated("ses_1", emptyList())).todos)
    }

    @Test
    fun theSnapshotsListReplacesTheOneShownUnlessItCouldNotBeRead() {
        val shown = root.after(SessionEvent.TodosUpdated("ses_1", listOf(TodoItem("a", TodoItem.Status.Pending))))
        val snapshot =
            LiveSnapshot(null, true, emptyList(), emptyList(), emptyList(), todos = listOf(TodoItem("b", TodoItem.Status.Completed)))
        assertEquals(
            "b",
            shown
                .apply(snapshot)
                .todos
                .single()
                .content,
        )
        assertEquals(
            "a",
            shown
                .apply(snapshot.copy(todos = emptyList(), todosRead = false))
                .todos
                .single()
                .content,
        )
    }

    @Test
    fun runningCountsSubagentsAndShellsAfter10s() {
        val state =
            root.after(
                SessionEvent.SessionCreated("ses_child", "ses_1"),
                SessionEvent.ExecutionStarted("ses_child", 1),
                SessionEvent.ShellCreated("sh_1", "ses_1", at = 1_000),
            )
        assertEquals(1, state.running(now = 5_000))
        assertEquals(2, state.running(now = 11_000))
        assertEquals(1, state.after(SessionEvent.ExecutionEnded("ses_child")).running(now = 11_000))
        // A subagent's run never makes today's session busy.
        assertEquals(null, state.busySince)
    }
}
