package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDomainAdapterTest {
    private val adapter = EventDomainAdapter(Json { ignoreUnknownKeys = true })

    @Test
    fun parseEvent_readsWrappedPayload() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsSessionIdFromMessageInfo() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.updated","properties":{"info":{"sessionID":"ses-1","id":"msg-1","role":"user"}}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsSessionIdFromMessagePart() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.part.updated","properties":{"part":{"sessionID":"ses-1","id":"prt-1","type":"text"}}}}""",
            )

        assertEquals("message.part.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsMessagePartDelta() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"message.part.delta","properties":{"sessionID":"ses-1","messageID":"msg-1","partID":"prt-1","field":"text","delta":"Hi"}}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvent_readsV2NativeEnvelope() {
        val event =
            adapter.parseEvent(
                """{"id":"evt_1","type":"server.connected","data":{}}""",
            )

        assertEquals("server.connected", event?.type)
        assertNull(event?.sessionId)
    }

    @Test
    fun parseEvent_projectsV2TextDeltaIntoMessagePartDelta() {
        val event =
            adapter.parseEvent(
                """{"id":"evt_2","type":"session.next.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","textID":"prt-1","delta":"Hi"}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals("msg-2", event?.rawProperties?.get("messageID")?.jsonPrimitive?.content)
        assertEquals("prt-1", event?.rawProperties?.get("partID")?.jsonPrimitive?.content)
        assertEquals("text", event?.rawProperties?.get("field")?.jsonPrimitive?.content)
        assertEquals("Hi", event?.rawProperties?.get("delta")?.jsonPrimitive?.content)
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvents_projectsStepStartedIntoAssistantMessageAndBusyStatus() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.step.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals(listOf("message.updated", "session.status"), events.map { it.type })
        assertEquals("ses-1", events[0].sessionId)
        assertEquals("assistant", events[0].rawProperties?.get("info")?.jsonObject?.get("role")?.jsonPrimitive?.content)
        assertEquals("busy", events[1].rawProperties?.get("status")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvent_projectsPromptAdmittedIntoUserMessageUpdated() {
        val event =
            adapter.parseEvent(
                """{"type":"session.next.prompt.admitted","data":{"sessionID":"ses-1","messageID":"msg-1"}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals("user", event?.rawProperties?.get("info")?.jsonObject?.get("role")?.jsonPrimitive?.content)
        assertEquals("msg-1", event?.rawProperties?.get("info")?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvents_projectsStepFailedIntoSessionErrorAndIdle() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.step.failed","data":{"sessionID":"ses-1","error":{"type":"ProviderError","message":"model overloaded"}}}""",
            )

        assertEquals(listOf("session.error", "session.idle"), events.map { it.type })
        assertEquals("ses-1", events[0].sessionId)
        assertEquals(
            "model overloaded",
            events[0].rawProperties?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun parseEvent_projectsStepEndedIntoSessionIdle() {
        val event =
            adapter.parseEvent(
                """{"type":"session.next.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals("session.idle", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_projectsLiveExecutionStartedIntoBusyStatus() {
        val event =
            adapter.parseEvent(
                """{"id":"evt_3","type":"session.execution.started","data":{"sessionID":"ses-1"}}""",
            )

        assertEquals("session.status", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals("busy", event?.rawProperties?.get("status")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue(event!!.triggersStatusRefresh)
    }

    @Test
    fun parseEvent_projectsLiveExecutionSucceededIntoIdle() {
        val event =
            adapter.parseEvent(
                """{"type":"session.execution.succeeded","data":{"sessionID":"ses-1"}}""",
            )

        assertEquals("session.idle", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertTrue(event!!.triggersStatusRefresh)
    }

    @Test
    fun parseEvent_doesNotTreatLiveStepEndedAsIdle() {
        val event =
            adapter.parseEvent(
                """{"type":"session.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals("session.step.ended", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertTrue(!event!!.triggersStatusRefresh)
    }

    @Test
    fun parseEvent_projectsLiveTextDeltaUsingOrdinalPartId() {
        val event =
            adapter.parseEvent(
                """{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","ordinal":0,"delta":"Hi"}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals("msg-2", event?.rawProperties?.get("messageID")?.jsonPrimitive?.content)
        assertEquals("text-0", event?.rawProperties?.get("partID")?.jsonPrimitive?.content)
        assertEquals("Hi", event?.rawProperties?.get("delta")?.jsonPrimitive?.content)
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvent_projectsLiveRenamedIntoSessionUpdated() {
        val event =
            adapter.parseEvent(
                """{"type":"session.renamed","data":{"sessionID":"ses-1","title":"Testing conversation"}}""",
            )

        assertEquals("session.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals(
            "Testing conversation",
            event?.rawProperties?.get("info")?.jsonObject?.get("title")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun parseEvent_remapsPermissionV2Asked() {
        val event =
            adapter.parseEvent(
                """{"type":"permission.v2.asked","data":{"sessionID":"ses-1"}}""",
            )

        assertEquals("permission.asked", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsV2SameNamedLegacyMessageUpdated() {
        val event =
            adapter.parseEvent(
                """{"type":"message.updated","data":{"sessionID":"ses-1","info":{"id":"msg-1","role":"user"}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_readsUnwrappedPayload() {
        val event =
            adapter.parseEvent(
                """{"type":"session.created","properties":{"sessionID":"ses-2"}}""",
            )

        assertEquals("session.created", event?.type)
        assertEquals("ses-2", event?.sessionId)
    }

    @Test
    fun statusLabel_mapsBusyAndRetry() {
        val busy = adapter.statusLabel(SessionStatus(sessionId = "1", raw = mapOf("type" to "busy")))
        val retry = adapter.statusLabel(SessionStatus(sessionId = "1", raw = mapOf("type" to "retry")))
        val idle = adapter.statusLabel(SessionStatus(sessionId = "1", raw = mapOf("type" to "idle")))

        assertEquals("Working", busy)
        assertEquals("Retrying", retry)
        assertNull(idle)
    }

    @Test
    fun sessionStatusFromEvent_readsBusyPayload() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}}""",
            )

        val status = adapter.sessionStatusFromEvent(event!!)
        assertEquals("ses-1", status?.sessionId)
        assertEquals("busy", status?.raw?.get("type"))
        assertEquals("Working", status?.let(adapter::statusLabel))
    }

    @Test
    fun sessionTitleUpdate_readsInfoTitle() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1","title":"Live title"}}}}""",
            )

        val update = adapter.sessionTitleUpdate(event!!)
        assertEquals("ses-1", update?.sessionId)
        assertEquals("Live title", update?.title)
    }

    @Test
    fun sessionTitleUpdate_ignoresNonUpdatedEvents() {
        val event =
            adapter.parseEvent(
                """{"payload":{"type":"session.created","properties":{"info":{"id":"ses-1","title":"New"}}}}""",
            )

        assertNull(adapter.sessionTitleUpdate(event!!))
    }
}
