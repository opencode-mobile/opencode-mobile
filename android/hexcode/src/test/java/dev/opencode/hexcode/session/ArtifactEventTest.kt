package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream's events the artifact feature reads (OpenCode v2.2.5 `session-event.ts`). */
class ArtifactEventTest {
    private fun line(
        type: String,
        data: String,
    ) = """{"type":"$type","created":1,"data":$data}"""

    @Test
    fun readsARunsStartAndEnd() {
        assertEquals(ArtifactEvent.RunStarted("s"), ArtifactEvent.decode(line("session.execution.started", """{"sessionID":"s"}""")))
        assertEquals(ArtifactEvent.RunEnded("s"), ArtifactEvent.decode(line("session.execution.succeeded", """{"sessionID":"s"}""")))
        assertEquals(
            ArtifactEvent.RunEnded("s", interrupted = true),
            ArtifactEvent.decode(line("session.execution.interrupted", """{"sessionID":"s","reason":"user"}""")),
        )
    }

    @Test
    fun aFailedRunCarriesItsErrorAndTheFreeLimitIsKnown() {
        val event =
            ArtifactEvent.decode(
                line(
                    "session.execution.failed",
                    """{"sessionID":"s","error":{"type":"provider.free-limit","message":"Free usage limit reached","status":429,"retryAfterMs":30000000}}""",
                ),
            ) as ArtifactEvent.RunEnded
        assertEquals(ArtifactEvent.RunError("provider.free-limit", "Free usage limit reached", 429), event.error)
        assertTrue(event.error!!.freeLimit)
        assertTrue(!ArtifactEvent.RunError("provider.auth", "no key", 401).freeLimit)
    }

    @Test
    fun readsToolsByNameAndPath() {
        assertEquals(
            ArtifactEvent.ToolNamed("s", "c1", "edit"),
            ArtifactEvent.decode(
                line("session.tool.input.started", """{"sessionID":"s","assistantMessageID":"m","id":"c1","name":"edit"}"""),
            ),
        )
        assertEquals(
            ArtifactEvent.ToolCalled("s", "c1", "/tmp/c/opencode.json"),
            ArtifactEvent.decode(
                line(
                    "session.tool.called",
                    """{"sessionID":"s","assistantMessageID":"m","id":"c1","input":{"path":"/tmp/c/opencode.json"},"executed":true}""",
                ),
            ),
        )
        assertEquals(
            ArtifactEvent.ToolEnded("s", "c1", failed = true),
            ArtifactEvent.decode(line("session.tool.failed", """{"sessionID":"s","id":"c1","error":{"type":"x","message":"y"}}""")),
        )
    }

    @Test
    fun readsRepliesApprovalsAndConfigChanges() {
        assertEquals(
            ArtifactEvent.Reply("s", "DONE"),
            ArtifactEvent.decode(line("session.text.ended", """{"sessionID":"s","text":"DONE"}""")),
        )
        assertNull(ArtifactEvent.decode(line("session.text.ended", """{"sessionID":"s","text":"  "}""")))
        val asked =
            ArtifactEvent.decode(
                line("permission.asked", """{"id":"per_1","sessionID":"s","action":"edit","resources":["/a"],"save":["*"]}"""),
            )
        assertEquals("per_1", (asked as ArtifactEvent.Approval).item.id)
        assertEquals(listOf("*"), asked.item.approval?.save)
        assertEquals(
            ArtifactEvent.ApprovalReplied("s", "per_1"),
            ArtifactEvent.decode(line("permission.replied", """{"sessionID":"s","requestID":"per_1","reply":"once"}""")),
        )
        assertEquals(ArtifactEvent.ConfigUpdated, ArtifactEvent.decode(line("config.updated", "{}")))
        assertNull(ArtifactEvent.decode(line("session.inbox.enqueued", """{"sessionID":"s"}""")))
    }
}
