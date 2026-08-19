package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.ServerProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class SseSubscribeTargetsTest {
    @Test
    fun v2_isApiEventOnly() {
        val targets =
            SseSubscribeTargets.forProtocol(
                ServerProtocol.V2,
                directories = listOf("/tmp/a", "/tmp/b"),
            )
        assertEquals(listOf(SseSubscribeTargets.Target("api/event")), targets)
    }

    @Test
    fun v1_isGlobalPlusDistinctInstanceDirectories() {
        val targets =
            SseSubscribeTargets.forProtocol(
                ServerProtocol.V1,
                directories = listOf("/tmp/a", " /tmp/a ", "", "/tmp/b"),
            )
        assertEquals(
            listOf(
                SseSubscribeTargets.Target("global/event"),
                SseSubscribeTargets.Target("event", "/tmp/a"),
                SseSubscribeTargets.Target("event", "/tmp/b"),
            ),
            targets,
        )
    }
}
