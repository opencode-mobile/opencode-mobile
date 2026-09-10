package dev.opencode.mobile.domain.event

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseDiagnosticLoggingTest {
    @Test
    fun parseFailedMessage_recordsTypeAndLengthWithoutBody() {
        assertEquals(
            "SSE event parse failed type=session.text.ended chars=42",
            SseDiagnosticLogging.parseFailedMessage("""{"type":"session.text.ended","payload":{}}"""),
        )
        assertEquals(
            "SSE event parse failed type=- chars=3",
            SseDiagnosticLogging.parseFailedMessage("not"),
        )
    }

    @Test
    fun bufferMessage_skipsMessageParts() {
        assertNull(
            SseDiagnosticLogging.bufferMessage(
                OpencodeEvent(type = "message.part.delta", sessionId = "ses-1"),
            ),
        )
        assertNull(
            SseDiagnosticLogging.bufferMessage(
                OpencodeEvent(type = "message.part.updated", sessionId = "ses-1"),
            ),
        )
        assertNull(
            SseDiagnosticLogging.bufferMessage(
                OpencodeEvent(type = "message.updated", sessionId = "ses-1"),
            ),
        )
    }

    @Test
    fun bufferMessage_recordsStatusMetadata() {
        val event =
            OpencodeEvent(
                type = "session.status",
                sessionId = "ses-1",
                rawProperties =
                    buildJsonObject {
                        put(
                            "status",
                            buildJsonObject { put("type", "busy") },
                        )
                    },
            )
        assertEquals(
            "SSE event type=session.status session=ses-1 status=busy",
            SseDiagnosticLogging.bufferMessage(event),
        )
    }

    @Test
    fun bufferMessage_recordsIdleAndError() {
        assertEquals(
            "SSE event type=session.idle session=ses-1",
            SseDiagnosticLogging.bufferMessage(
                OpencodeEvent(type = "session.idle", sessionId = "ses-1"),
            ),
        )
        val error =
            OpencodeEvent(
                type = "session.error",
                sessionId = "ses-1",
                rawProperties = buildJsonObject { put("message", "model overloaded") },
            )
        assertEquals(
            "SSE event type=session.error session=ses-1 error=model overloaded",
            SseDiagnosticLogging.bufferMessage(error),
        )
    }

    @Test
    fun bufferMessage_sessionErrorSkipsNonPrimitiveFields() {
        val error =
            OpencodeEvent(
                type = "session.error",
                sessionId = "ses-1",
                rawProperties =
                    buildJsonObject {
                        put(
                            "error",
                            buildJsonObject {
                                put(
                                    "data",
                                    buildJsonObject {
                                        put("message", buildJsonObject { put("code", "x") })
                                    },
                                )
                                put("message", "model overloaded")
                            },
                        )
                    },
            )
        assertEquals(
            "SSE event type=session.error session=ses-1 error=model overloaded",
            SseDiagnosticLogging.bufferMessage(error),
        )
    }

    @Test
    fun bufferMessage_sessionStatusSkipsNonPrimitiveType() {
        val event =
            OpencodeEvent(
                type = "session.status",
                sessionId = "ses-1",
                rawProperties =
                    buildJsonObject {
                        put(
                            "status",
                            buildJsonObject {
                                put("type", buildJsonObject { put("nested", true) })
                            },
                        )
                    },
            )
        assertEquals(
            "SSE event type=session.status session=ses-1 status=-",
            SseDiagnosticLogging.bufferMessage(event),
        )
    }
}
