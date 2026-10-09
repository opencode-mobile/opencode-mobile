package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun parseEvent_readsEnvelopeIdFromPayloadAndRaw() {
        val wrapped =
            adapter.parseEvent(
                """{"directory":"/tmp","payload":{"id":"evt_1","type":"message.part.delta","properties":{"sessionID":"ses-1"}}}""",
            )
        assertEquals("evt_1", wrapped?.id)
        val instance =
            adapter.parseEvent(
                """{"id":"evt_2","type":"message.part.delta","properties":{"sessionID":"ses-1"}}""",
            )
        assertEquals("evt_2", instance?.id)
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
        assertEquals(
            "msg-2",
            event
                ?.rawProperties
                ?.get("messageID")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "prt-1",
            event
                ?.rawProperties
                ?.get("partID")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "text",
            event
                ?.rawProperties
                ?.get("field")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "Hi",
            event
                ?.rawProperties
                ?.get("delta")
                ?.jsonPrimitive
                ?.content,
        )
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
        assertEquals(
            "assistant",
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("role")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "busy",
            events[1]
                .rawProperties
                ?.get("status")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_projectsPromptAdmittedIntoUserMessageUpdated() {
        val event =
            adapter.parseEvent(
                """{"type":"session.next.prompt.admitted","data":{"sessionID":"ses-1","messageID":"msg-1"}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals(
            "user",
            event
                ?.rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("role")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "msg-1",
            event
                ?.rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            true,
            event
                ?.rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("waiting")
                ?.jsonPrimitive
                ?.booleanOrNull,
        )
    }

    @Test
    fun parseEvents_projectsPromptedIntoUserMessageAndTextPart() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "user",
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("role")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            false,
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("waiting")
                ?.jsonPrimitive
                ?.booleanOrNull,
        )
        assertEquals(
            "steer",
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("delivery")
                ?.jsonPrimitive
                ?.content,
        )
        val part = events[1].rawProperties?.get("part")?.jsonObject
        assertEquals("text", part?.get("type")?.jsonPrimitive?.content)
        assertEquals("Hello from CLI", part?.get("text")?.jsonPrimitive?.content)
        assertEquals("msg-1", part?.get("messageID")?.jsonPrimitive?.content)
        assertEquals("msg-1:text:0", part?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvents_projectsVersionedPromptedType() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.prompted.1","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "Hello from CLI",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsPromptedFilesIntoFileParts() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"See this","files":[{"uri":"data:image/png;base64,AA==","mime":"image/png","name":"a.png"}]},"delivery":"steer"}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated", "message.part.updated"), events.map { it.type })
        val file = events[2].rawProperties?.get("part")?.jsonObject
        assertEquals("file", file?.get("type")?.jsonPrimitive?.content)
        assertEquals("data:image/png;base64,AA==", file?.get("url")?.jsonPrimitive?.content)
        assertEquals("image/png", file?.get("mime")?.jsonPrimitive?.content)
        assertEquals("a.png", file?.get("filename")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvents_projectsPromptedFilesWithoutMimeFromDataUrl() {
        val events =
            adapter.parseEvents(
                """{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"What","files":[{"uri":"data:image/jpeg;base64,AA==","name":"Photo 1.jpeg"}]},"delivery":"steer"}}}""",
            )

        assertEquals(
            listOf("message.updated", "message.part.updated", "message.part.updated"),
            events.map { it.type },
        )
        val file = events[2].rawProperties?.get("part")?.jsonObject
        assertEquals("file", file?.get("type")?.jsonPrimitive?.content)
        assertEquals("data:image/jpeg;base64,AA==", file?.get("url")?.jsonPrimitive?.content)
        assertEquals("image/jpeg", file?.get("mime")?.jsonPrimitive?.content)
        assertEquals("Photo 1.jpeg", file?.get("filename")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvents_projectsPromptedFilesWithoutUri() {
        val events =
            adapter.parseEvents(
                """{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"What","files":[{"mime":"image/jpeg","name":"Photo 1.jpeg"}]},"delivery":"steer"}}}""",
            )

        assertEquals(
            listOf("message.updated", "message.part.updated", "message.part.updated"),
            events.map { it.type },
        )
        val file = events[2].rawProperties?.get("part")?.jsonObject
        assertEquals("file", file?.get("type")?.jsonPrimitive?.content)
        assertEquals(null, file?.get("url"))
        assertEquals("image/jpeg", file?.get("mime")?.jsonPrimitive?.content)
        assertEquals("Photo 1.jpeg", file?.get("filename")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvents_projectsGlobalBusPromptedPayload() {
        val events =
            adapter.parseEvents(
                """{"directory":"/repo","payload":{"id":"evt_1","type":"session.next.prompted","properties":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "Hello from CLI",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("ses-1", events[0].sessionId)
    }

    @Test
    fun parseEvents_projectsFlattenedPromptedFields() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.prompted","sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "Hello from CLI",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsSyncSiblingPrompted() {
        val events =
            adapter.parseEvents(
                """{"payload":{"type":"sync","syncEvent":{"type":"session.next.prompted.1","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"}}}}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "Hello from CLI",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_keepsPromptWhenNestedDataAlsoPresent() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"data":{"ignored":true}}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "Hello from CLI",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsPromptedJsonStringPrompt() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":"{\"text\":\"Hello from CLI\"}"}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(
            "Hello from CLI",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_projectsToolInputDeltaIntoPartDelta() {
        val event =
            adapter.parseEvent(
                """{"type":"session.tool.input.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","delta":"path"}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals(
            "input",
            event
                ?.rawProperties
                ?.get("field")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "prt-1",
            event
                ?.rawProperties
                ?.get("partID")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsLiveToolEnvelopeCreatedIntoStateTimeStart() {
        val event =
            adapter.parseEvent(
                """{"type":"session.tool.input.started","created":1710000000000,"data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","name":"task"}}""",
            )

        assertEquals("message.part.updated", event?.type)
        val part = event?.rawProperties?.get("part")?.jsonObject
        val state = part?.get("state")?.jsonObject
        assertEquals("prt-1", part?.get("id")?.jsonPrimitive?.content)
        assertEquals("task", part?.get("tool")?.jsonPrimitive?.content)
        assertEquals("pending", state?.get("status")?.jsonPrimitive?.content)
        assertEquals(
            1_710_000_000_000L,
            state
                ?.get("time")
                ?.jsonObject
                ?.get("start")
                ?.jsonPrimitive
                ?.longOrNull,
        )
    }

    @Test
    fun parseEvent_parsesToolInputJsonString() {
        val event =
            adapter.parseEvent(
                """{"type":"session.tool.called","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","callID":"prt-1","name":"task","input":"{\"agent\":\"explore\",\"description\":\"Find auth\"}"}}""",
            )

        val input =
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("state")
                ?.jsonObject
                ?.get("input")
                ?.jsonObject
        assertEquals("explore", input?.get("agent")?.jsonPrimitive?.content)
        assertEquals("Find auth", input?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvent_projectsInboxCancelledIntoMessageRemoved() {
        val event =
            adapter.parseEvent(
                """{"type":"session.inbox.cancelled","data":{"inboxID":"msg-2","sessionID":"ses-1"}}""",
            )

        assertEquals("message.removed", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals(
            "msg-2",
            event
                ?.rawProperties
                ?.get("messageID")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvents_marksInboxSyntheticItemTextAsSynthetic() {
        val events =
            adapter.parseEvents(
                """{"type":"session.inbox.enqueued","data":{"inboxID":"msg-3","sessionID":"ses-1","item":{"type":"synthetic","payload":{"text":"<system-reminder>Plan mode</system-reminder>"},"delivery":"steer"}}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        val part = events[1].rawProperties?.get("part")?.jsonObject
        assertEquals("true", part?.get("synthetic")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvents_inboxUserItemTextIsNotSynthetic() {
        val events =
            adapter.parseEvents(
                """{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello"},"delivery":"steer"}}}""",
            )

        assertNull(
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("synthetic"),
        )
    }

    @Test
    fun parseEvents_projectsInboxEnqueuedItemPayloadIntoUserText() {
        val events =
            adapter.parseEvents(
                """{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello from inbox","files":[]},"delivery":"steer"}}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals("ses-1", events[0].sessionId)
        assertEquals(
            "msg-2",
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            true,
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("waiting")
                ?.jsonPrimitive
                ?.booleanOrNull,
        )
        assertEquals(
            "steer",
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("delivery")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "Hello from inbox",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "msg-2",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("messageID")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_projectsInboxDeliveryChangedAsStillWaiting() {
        val event =
            adapter.parseEvent(
                """{"type":"session.inbox.delivery.changed","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"delivery":"queue"}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals(
            true,
            event
                ?.rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("waiting")
                ?.jsonPrimitive
                ?.booleanOrNull,
        )
        assertEquals(
            "queue",
            event
                ?.rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("delivery")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsInboxEnqueuedCreatedIntoUserInfoTime() {
        val events =
            adapter.parseEvents(
                """{"type":"session.inbox.enqueued","created":1710000000000,"data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello from inbox","files":[]},"delivery":"steer"}}}""",
            )

        assertEquals("message.updated", events[0].type)
        assertEquals(
            1_710_000_000_000L,
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("time")
                ?.jsonObject
                ?.get("created")
                ?.jsonPrimitive
                ?.longOrNull,
        )
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
            events[0]
                .rawProperties
                ?.get("error")
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsStepEndedIntoAssistantMessageWithoutSnapshot() {
        val events =
            adapter.parseEvents(
                """{"type":"session.next.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals(listOf("message.updated"), events.map { it.type })
        assertEquals("ses-1", events[0].sessionId)
        assertEquals(
            "assistant",
            events[0]
                .rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("role")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(events.none { it.triggersStatusRefresh })
        assertTrue(events.none { it.type == "session.active.snapshot" })
    }

    @Test
    fun parseEvents_promptedTextPartSurvivesDeduper() {
        val events =
            adapter.parseEvents(
                """{"id":"evt_prompt","type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"What are you up to?"}}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertEquals(listOf("evt_prompt:0", "evt_prompt:1"), events.map { it.id })
        val deduper = SseEventDeduper()
        assertTrue(events.all { deduper.accept(it.id) })
        assertEquals(
            "What are you up to?",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_stampsDistinctIdsOnProjectedSiblings() {
        val events =
            adapter.parseEvents(
                """{"id":"evt_step","type":"session.shell.started","data":{"sessionID":"ses-1","callID":"sh-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals(listOf("evt_step:0", "evt_step:1"), events.map { it.id })
        val deduper = SseEventDeduper()
        assertTrue(events.all { deduper.accept(it.id) })
    }

    @Test
    fun parseEvents_projectsReasoningEndedWithEndOnly() {
        val events =
            adapter.parseEvents(
                """{"type":"session.reasoning.ended","created":2000,"data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"r-1","text":"hmm"}}""",
            )

        val time =
            events
                .single()
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("time")
                ?.jsonObject
        assertNull(time?.get("start"))
        assertEquals(2000L, time?.get("end")?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun parseEvent_projectsLiveExecutionStartedIntoBusyStatus() {
        val event =
            adapter.parseEvent(
                """{"id":"evt_3","type":"session.execution.started","data":{"sessionID":"ses-1"}}""",
            )

        assertEquals("session.status", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals(
            "busy",
            event
                ?.rawProperties
                ?.get("status")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
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
    fun parseEvents_projectsReasoningStartedIntoAssistantAndReasoningPart() {
        val events =
            adapter.parseEvents(
                """{"type":"session.reasoning.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"r-1"}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        val part = events[1].rawProperties?.get("part")?.jsonObject
        assertEquals("reasoning", part?.get("type")?.jsonPrimitive?.content)
        assertEquals("r-1", part?.get("id")?.jsonPrimitive?.content)
        assertEquals("msg-2", part?.get("messageID")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvent_projectsReasoningDeltaIntoPartDelta() {
        val event =
            adapter.parseEvent(
                """{"type":"session.reasoning.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"r-1","delta":"hmm"}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals(
            "reasoning",
            event
                ?.rawProperties
                ?.get("field")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "hmm",
            event
                ?.rawProperties
                ?.get("delta")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "r-1",
            event
                ?.rawProperties
                ?.get("partID")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvent_doesNotTreatLiveStepEndedAsIdle() {
        val events =
            adapter.parseEvents(
                """{"type":"session.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals(listOf("message.updated"), events.map { it.type })
        assertEquals("ses-1", events[0].sessionId)
        assertTrue(events.none { it.type == "session.idle" })
        assertTrue(events.none { it.type == "session.active.snapshot" })
        assertTrue(events.none { it.triggersStatusRefresh })
    }

    @Test
    fun parseEvents_projectsLiveShellStartedIntoBusyAndBashPart() {
        val events =
            adapter.parseEvents(
                """{"type":"session.shell.started","data":{"sessionID":"ses-1","callID":"sh-1","assistantMessageID":"msg-2"}}""",
            )

        assertEquals(listOf("session.status", "message.part.updated"), events.map { it.type })
        assertEquals(
            "busy",
            events[0]
                .rawProperties
                ?.get("status")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "bash",
            events[1]
                .rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("tool")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsLiveCompactionEndedIntoPartNotIdle() {
        val events =
            adapter.parseEvents(
                """{"type":"session.compaction.ended","data":{"sessionID":"ses-1","messageID":"msg-c"}}""",
            )

        assertEquals(listOf("message.updated", "message.part.updated"), events.map { it.type })
        assertTrue(events.none { it.type == "session.idle" })
        assertTrue(events.none { it.type == "session.active.snapshot" })
    }

    @Test
    fun parseEvents_keysLiveCompactionToTheStartedItemAndCarriesTheSummary() {
        fun part(line: String) =
            adapter
                .parseEvents(line)
                .single { it.type == "message.part.updated" }
                .rawProperties!!["part"]!!
                .jsonObject

        // Captured v2 shapes: `started` carries the inbox `inputID`, `ended` no id at all.
        val started =
            part(
                """{"id":"evt_0f9026d76001","created":1790884081021,"type":"session.compaction.started",""" +
                    """"data":{"sessionID":"ses-1","reason":"manual","recent":"","inputID":"msg_0f9026d76001"}}""",
            )
        val ended =
            part(
                """{"id":"evt_0f9027b10001","created":1790884084400,"type":"session.compaction.ended",""" +
                    """"data":{"sessionID":"ses-1","reason":"manual","text":"## Objective\n- Hi.","recent":""}}""",
            )
        val autoEnded =
            part(
                """{"id":"evt_0f9030000001","created":1790884090000,"type":"session.compaction.ended",""" +
                    """"data":{"sessionID":"ses-1","reason":"auto","text":"## Objective","recent":""}}""",
            )

        assertEquals("msg_0f9026d76001", started["messageID"]?.jsonPrimitive?.content)
        assertEquals("false", started["completed"]?.jsonPrimitive?.content)
        assertEquals("msg_0f9026d76001", ended["messageID"]?.jsonPrimitive?.content)
        assertEquals("true", ended["completed"]?.jsonPrimitive?.content)
        assertEquals("## Objective\n- Hi.", ended["summary"]?.jsonPrimitive?.content)
        // Without a started item the server appends one keyed by the ended event.
        assertEquals("msg_0f9030000001", autoEnded["messageID"]?.jsonPrimitive?.content)
        assertTrue(
            adapter
                .parseEvents("""{"type":"session.compaction.delta","data":{"sessionID":"ses-1","text":"## Obj"}}""")
                .isEmpty(),
        )
    }

    @Test
    fun parseEvents_projectsV2RevertCommittedWithItsRevertPoint() {
        val events =
            adapter.parseEvents(
                """{"id":"evt_00d055e78001","created":1786924850808,"type":"session.revert.committed",""" +
                    """"data":{"sessionID":"ses-1","to":"msg_00cf91b85001"}}""",
            )

        assertEquals(listOf("session.revert.committed"), events.map { it.type })
        assertEquals("ses-1", events.single().sessionId)
        assertEquals(
            "msg_00cf91b85001",
            events
                .single()
                .rawProperties
                ?.get("messageID")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvents_projectsLiveCompactionFailedIntoPartErrorAndIdle() {
        val events =
            adapter.parseEvents(
                """{"type":"session.compaction.failed","data":{"sessionID":"ses-1","messageID":"msg-c"}}""",
            )

        assertEquals(
            listOf("message.updated", "message.part.updated", "session.error", "session.idle"),
            events.map { it.type },
        )
        assertTrue(events.last().triggersStatusRefresh)
    }

    @Test
    fun parseEvents_scopesV2OrdinalPartIdsToTheirAssistantMessage() {
        // Captured v2 shape: each step's first text and reasoning are ordinal 0
        // and carry no part id, so the id must include the assistant message.
        fun partId(line: String): String? =
            adapter
                .parseEvents(line)
                .firstNotNullOfOrNull { event ->
                    (event.rawProperties?.get("part") as? JsonObject)
                        ?.get("id")
                        ?.jsonPrimitive
                        ?.content
                        ?: event.rawProperties
                            ?.get("partID")
                            ?.jsonPrimitive
                            ?.content
                }

        val ids =
            listOf(
                """{"type":"session.text.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-a","ordinal":0}}""",
                """{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-a","ordinal":0,"delta":"Look"}}""",
                """{"type":"session.text.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-b","ordinal":0,"text":"Done"}}""",
                """{"type":"session.reasoning.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-a","ordinal":0,"state":{}}}""",
                """{"type":"session.reasoning.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-b","ordinal":1,"delta":"Hm"}}""",
            ).map(::partId)

        assertEquals(
            listOf("msg-a:text:0", "msg-a:text:0", "msg-b:text:0", "msg-a:reasoning:0", "msg-b:reasoning:1"),
            ids,
        )
    }

    @Test
    fun parseEvent_projectsLiveTextDeltaUsingOrdinalPartId() {
        val event =
            adapter.parseEvent(
                """{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","ordinal":0,"delta":"Hi"}}""",
            )

        assertEquals("message.part.delta", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals(
            "msg-2",
            event
                ?.rawProperties
                ?.get("messageID")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "msg-2:text:0",
            event
                ?.rawProperties
                ?.get("partID")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "Hi",
            event
                ?.rawProperties
                ?.get("delta")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvent_projectsUsageUpdatedIntoAssistantTokens() {
        val event =
            adapter.parseEvent(
                """{"type":"session.usage.updated","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","tokens":{"input":120,"output":4,"reasoning":0}}}""",
            )

        assertEquals("message.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
        val info = event?.rawProperties?.get("info")?.jsonObject
        assertEquals("msg-2", info?.get("id")?.jsonPrimitive?.content)
        assertEquals(
            120,
            info
                ?.get("tokens")
                ?.jsonObject
                ?.get("input")
                ?.jsonPrimitive
                ?.intOrNull,
        )
        assertTrue(event!!.triggersMessageRefresh)
    }

    @Test
    fun parseEvent_dropsUsageUpdatedWithoutAssistantId() {
        val event =
            adapter.parseEvent(
                """{"type":"session.usage.updated","data":{"sessionID":"ses-1","tokens":{"input":120,"output":4}}}""",
            )

        assertNull(event)
    }

    @Test
    fun parse_garbageJson_isUnrecognized() {
        assertEquals(EventParseOutcome.Unrecognized, adapter.parse("not-json"))
    }

    @Test
    fun parse_objectWithoutType_isUnrecognized() {
        assertEquals(EventParseOutcome.Unrecognized, adapter.parse("""{"foo":1}"""))
    }

    @Test
    fun parse_usageUpdatedWithoutAssistantId_isDropped() {
        val outcome =
            adapter.parse(
                """{"type":"session.usage.updated","data":{"sessionID":"ses-1","tokens":{"input":120,"output":4}}}""",
            )
        assertEquals(EventParseOutcome.Dropped("session.usage.updated"), outcome)
    }

    @Test
    fun parse_usageUpdatedWithAssistantId_emitsMessageUpdated() {
        val outcome =
            adapter.parse(
                """{"type":"session.usage.updated","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","tokens":{"input":120,"output":4}}}""",
            )
        val events = outcome as EventParseOutcome.Events
        assertEquals(listOf("message.updated"), events.events.map { it.type })
    }

    @Test
    fun parse_syncWithoutSyncEvent_isUnrecognized() {
        assertEquals(
            EventParseOutcome.Unrecognized,
            adapter.parse("""{"payload":{"type":"sync","properties":{}}}"""),
        )
    }

    @Test
    fun parseEvent_projectsForkedIntoTopLevelSessionCreated() {
        val event =
            adapter.parseEvent(
                """{"type":"session.forked","data":{"sessionID":"ses_fork","parentID":"ses_src","boundary":{"type":"through","messageID":"msg_1"}}}""",
            )

        assertEquals("session.created", event?.type)
        assertEquals("ses_fork", event?.sessionId)
        assertTrue(event?.triggersHomeRefresh == true)
        assertNull(event?.rawProperties?.get("parentID"))
        val forkedFrom = event?.rawProperties?.get("forkedFromSessionID")
        assertEquals("ses_src", forkedFrom?.jsonPrimitive?.content)
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
            event
                ?.rawProperties
                ?.get("info")
                ?.jsonObject
                ?.get("title")
                ?.jsonPrimitive
                ?.content,
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
    fun parseEvent_projectsFormCreatedIntoQuestionAsked() {
        val event =
            adapter.parseEvent(
                """{"type":"form.created","data":{"form":{"id":"frm_1","sessionID":"ses-1","title":"Questions"}}}""",
            )

        assertEquals("question.asked", event?.type)
        assertEquals("ses-1", event?.sessionId)
    }

    @Test
    fun parseEvent_projectsFormRepliedIntoQuestionReplied() {
        val event =
            adapter.parseEvent(
                """{"type":"form.replied","data":{"id":"frm_1","sessionID":"ses-1","answer":{"q0":"Wait"}}}""",
            )

        assertEquals("question.replied", event?.type)
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
        assertEquals(SessionActivity.Running, adapter.sessionActivityFromEvent(event)?.activity)
    }

    @Test
    fun sessionActivityFromEvent_parsesRetryPayload() {
        val events =
            adapter.parseEvents(
                """{"type":"session.retry.scheduled","data":{"sessionID":"ses-1","attempt":2,"next":1234,"message":"backoff"}}""",
            )

        val parsed = adapter.sessionActivityFromEvent(events.single())
        assertEquals("ses-1", parsed?.sessionId)
        assertEquals(
            SessionActivity.Retrying(attempt = 2, nextAtMs = 1234L, message = "backoff"),
            parsed?.activity,
        )
        assertEquals("Retrying · 2", parsed?.activity?.statusLabel())
        assertEquals("Retrying · 2 · backoff", parsed?.activity?.chatStatusLabel())
    }

    @Test
    fun sessionActivityFromEvent_readsV2RetryAtAndErrorMessage() {
        val events =
            adapter.parseEvents(
                """{"type":"session.retry.scheduled","data":{"sessionID":"ses-1","attempt":3,"at":9000,"error":{"type":"provider.rate-limit","message":"{\"type\":\"message\",\"message\":\"Slow down\"}"}}}""",
            )
        assertEquals(
            SessionActivity.Retrying(attempt = 3, nextAtMs = 9000L, message = "Slow down"),
            adapter.sessionActivityFromEvent(events.single())?.activity,
        )
    }

    @Test
    fun stepFailed_withAssistantId_putsTheErrorOnTheMessageAndSkipsTheSnackbarEvent() {
        val events =
            adapter.parseEvents(
                """{"type":"session.step.failed","data":{"sessionID":"ses-1","assistantMessageID":"a1","error":{"type":"provider.auth","message":"Sign in","status":401,"response":{"body":"denied"}}}}""",
            )
        assertEquals(listOf("message.updated"), events.map { it.type })
        val info = events.single().rawProperties?.get("info") as kotlinx.serialization.json.JsonObject
        val data = info["error"] as kotlinx.serialization.json.JsonObject
        val payload = data["data"] as kotlinx.serialization.json.JsonObject
        assertEquals("provider.auth", (data["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Sign in", (payload["message"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("401", (payload["statusCode"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("denied", (payload["responseBody"] as kotlinx.serialization.json.JsonPrimitive).content)
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

    @Test
    fun parseEvent_readsOfficialSseEnvelopeWithEventAndJsonStringData() {
        val event =
            adapter.parseEvent(
                """{"id":"evt_9","event":"session.next.tool.called","data":"{\"sessionID\":\"ses-1\",\"assistantMessageID\":\"msg-2\",\"callID\":\"call-1\",\"tool\":\"bash\",\"input\":{}}"}""",
            )

        assertEquals("message.part.updated", event?.type)
        assertEquals("ses-1", event?.sessionId)
        assertEquals(
            "bash",
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("tool")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "running",
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("state")
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_toolCalledWithoutNameDoesNotInventPlaceholderTool() {
        val event =
            adapter.parseEvent(
                """{"type":"session.next.tool.called","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{}}}""",
            )

        assertEquals(
            "",
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("tool")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_infersQuestionToolFromInputQuestions() {
        val event =
            adapter.parseEvent(
                """{"type":"session.next.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{"questions":[{"question":"One"},{"question":"Two"},{"question":"Three"}]}}}""",
            )

        assertEquals(
            "question",
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("tool")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_toolProgressMetadataCarriesSubagentSessionId() {
        val event =
            adapter.parseEvent(
                """{"type":"session.tool.progress","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","metadata":{"sessionID":"child-1","status":"running"}}}""",
            )

        val state =
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("state")
                ?.jsonObject
        assertEquals("running", state?.get("status")?.jsonPrimitive?.content)
        assertEquals(
            "child-1",
            state
                ?.get("metadata")
                ?.jsonObject
                ?.get("sessionID")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun parseEvent_toolProgressMetadataTitleBecomesTheLiveTitle() {
        val event =
            adapter.parseEvent(
                """{"type":"session.tool.progress","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","metadata":{"title":"Run migrations · step 3/6","step":3}}}""",
            )

        val state =
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("state")
                ?.jsonObject
        assertEquals("Run migrations · step 3/6", state?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvent_toolSuccessMergesStructuredAndMetadata() {
        val event =
            adapter.parseEvent(
                """{"type":"session.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","structured":{"count":2},"metadata":{"sessionID":"child-1"}}}""",
            )

        val metadata =
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("state")
                ?.jsonObject
                ?.get("metadata")
                ?.jsonObject
        assertEquals("child-1", metadata?.get("sessionID")?.jsonPrimitive?.content)
        assertEquals("2", metadata?.get("count")?.jsonPrimitive?.content)
    }

    @Test
    fun parseEvent_infersWebsearchToolFromInputQuery() {
        val event =
            adapter.parseEvent(
                """{"type":"session.next.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{"query":"opencode mobile"}}}""",
            )

        assertEquals(
            "websearch",
            event
                ?.rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("tool")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun assistantMessageCompleted_nullCompletedIsNotFinished() {
        val event =
            adapter.parseEvent(
                """{"type":"message.updated","properties":{"info":{"role":"assistant","time":{"completed":null}}}}""",
            )
        assertFalse(adapter.assistantMessageCompleted(event!!))
    }

    @Test
    fun assistantMessageCompleted_timestampIsFinished() {
        val event =
            adapter.parseEvent(
                """{"type":"message.updated","properties":{"info":{"role":"assistant","time":{"completed":123}}}}""",
            )
        assertTrue(adapter.assistantMessageCompleted(event!!))
    }

    @Test
    fun assistantMessageCompleted_missingCompletedIsNotFinished() {
        val event =
            adapter.parseEvent(
                """{"type":"message.updated","properties":{"info":{"role":"assistant","time":{"created":1}}}}""",
            )
        assertFalse(adapter.assistantMessageCompleted(event!!))
    }

    @Test
    fun assistantMessageCompleted_userRoleIsNotFinished() {
        val event =
            adapter.parseEvent(
                """{"type":"message.updated","properties":{"info":{"role":"user","time":{"completed":123}}}}""",
            )
        assertFalse(adapter.assistantMessageCompleted(event!!))
    }
}
