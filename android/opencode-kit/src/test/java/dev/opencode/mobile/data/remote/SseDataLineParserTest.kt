package dev.opencode.mobile.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SseDataLineParserTest {
    @Test
    fun flushesCompleteDataPayloadsWithoutBlankSeparators() {
        val parser = SseDataLineParser()
        val connected = """{"payload":{"type":"server.connected","properties":{}}}"""
        val heartbeat = """{"payload":{"type":"server.heartbeat","properties":{}}}"""

        assertEquals(listOf(connected), parser.ingest("data: $connected"))
        assertEquals(listOf(heartbeat), parser.ingest("data: $heartbeat"))
    }

    @Test
    fun flushesOnBlankSeparatorWhenPayloadAlreadyBuffered() {
        val parser = SseDataLineParser()
        // Incomplete alone would wait; blank separator forces flush (SSE framing).
        val partial = """{"payload":{"type":"server.connected""""
        assertEquals(emptyList<String>(), parser.ingest("data: $partial"))
        assertEquals(listOf(partial), parser.ingest(""))
    }

    @Test
    fun ignoresCommentLines() {
        val parser = SseDataLineParser()
        assertEquals(emptyList<String>(), parser.ingest(": keep-alive"))
    }

    @Test
    fun parsesRawJsonLines() {
        val parser = SseDataLineParser()
        val connected = """{"payload":{"type":"server.connected","properties":{}}}"""
        assertEquals(listOf(connected), parser.ingest(connected))
    }

    @Test
    fun finishEmitsTrailingBuffer() {
        val parser = SseDataLineParser()
        val trailing = """{"payload":{"type":"server.connected""""
        assertEquals(emptyList<String>(), parser.ingest("data: $trailing"))
        assertEquals(listOf(trailing), parser.finish())
    }

    @Test
    fun aDataLineOfAboutAMegabyteIsOnePayload() {
        // `session.inbox.enqueued` carries each image's whole base64: one photo makes a line of about 0.7 MB.
        val event = """{"type":"session.inbox.enqueued","data":{"files":[{"data":"${"A".repeat(1_000_000)}","name":"a.jpg"}]}}"""
        val parser = SseDataLineParser()
        assertEquals(listOf(event), parser.ingest("data: $event"))
    }
}
