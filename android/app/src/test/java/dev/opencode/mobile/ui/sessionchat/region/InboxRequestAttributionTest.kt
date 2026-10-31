package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxRequestAttributionTest {
    private val sessions =
        listOf(
            Session(id = "root", title = "Fix tests"),
            Session(id = "child", title = "Run the suite (@general subagent)", parentId = "root"),
            Session(id = "grandchild", title = "Look around (@explore subagent)", parentId = "child"),
            Session(id = "plain", title = "Untitled child", parentId = "root"),
        )

    private fun resolve(sessionId: String) = InboxRequestAttribution.resolve(sessionId, "root", sessions)

    @Test
    fun ownSessionRequest_hasNoAttribution() {
        assertNull(resolve("root"))
    }

    @Test
    fun subagentRequest_namesAgentFromSessionTitle() {
        val attribution = resolve("child")!!
        assertEquals("general", attribution.agentName)
        assertEquals("G", attribution.letter)
        assertEquals("root", attribution.parentId)
        assertEquals("general · subagent wants to run: npm test", attribution.permissionSummary("bash", "npm test"))
    }

    @Test
    fun v2SessionAgent_namesTheSubagentWithoutATitleSuffix() {
        val child = Session(id = "v2-child", title = "Run the tests", parentId = "root", agentName = "general")
        val attribution = InboxRequestAttribution.resolve("v2-child", "root", listOf(child))!!
        assertEquals("G", attribution.letter)
        assertEquals("general · subagent wants to run: npm test", attribution.permissionSummary("shell", "npm test"))
    }

    @Test
    fun nestedSubagent_opensUnderItsOwnParent() {
        val attribution = resolve("grandchild")!!
        assertEquals("explore · subagent asks", attribution.questionSummary)
        assertEquals("child", attribution.parentId)
    }

    @Test
    fun subagentWithoutAgentInTitle_fallsBackToPlainSubagent() {
        val attribution = resolve("plain")!!
        assertNull(attribution.agentName)
        assertEquals("U", attribution.letter)
        assertEquals("subagent asks", attribution.questionSummary)
        assertEquals("subagent wants to edit: README.md", attribution.permissionSummary("edit", "README.md"))
    }

    @Test
    fun unknownSession_stillAttributesToTheRoot() {
        val attribution = InboxRequestAttribution.resolve("missing", "root", emptyList())!!
        assertEquals("root", attribution.parentId)
        assertEquals("Subagent", attribution.sessionTitle)
    }

    @Test
    fun agentName_parsesOnlyTheTaskToolSuffix() {
        assertEquals("explore", InboxRequestAttribution.agentName("Scan (@explore subagent)"))
        assertEquals("explore", InboxRequestAttribution.agentName("Scan (@explore subagent) "))
        assertNull(InboxRequestAttribution.agentName("Scan (@ subagent)"))
        assertNull(InboxRequestAttribution.agentName("Scan @explore subagent"))
        assertNull(InboxRequestAttribution.agentName(""))
    }

    @Test
    fun permissionSummary_withoutTargetOrKnownAction() {
        assertEquals(
            "general · subagent wants to use external directory",
            resolve("child")!!.permissionSummary("external_directory", null),
        )
    }

    @Test
    fun paging_clampsAtTheEndsAndFollowsTheSelectedId() {
        val ids = listOf("a", "b", "c")
        assertEquals(0, InboxRequestPaging.index(ids, null))
        assertEquals(2, InboxRequestPaging.index(ids, "c"))
        assertEquals(0, InboxRequestPaging.index(ids, "gone"))
        assertEquals("b", InboxRequestPaging.pagedId(ids, null, 1))
        assertEquals("c", InboxRequestPaging.pagedId(ids, "c", 1))
        assertEquals("a", InboxRequestPaging.pagedId(ids, "a", -1))
        assertNull(InboxRequestPaging.pagedId(emptyList(), null, 1))
    }
}
