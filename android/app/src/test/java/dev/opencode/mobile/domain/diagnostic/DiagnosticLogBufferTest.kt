package dev.opencode.mobile.domain.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogBufferTest {
    @Test
    fun evictsEntriesOlderThanFiveMinutes() {
        var now = 10_000_000L
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { now })

        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "old")
        now += DiagnosticLogBuffer.WINDOW_MILLISECONDS + 1
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "new")

        val snapshot = buffer.snapshot()
        assertEquals(listOf("new"), snapshot.entries.map { it.message })
        assertEquals(5, snapshot.windowMinutes)
    }

    @Test
    fun softCapDropsOldestAndRecordsCount() {
        var now = 1_000L
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { now })

        repeat(DiagnosticLogBuffer.SOFT_CAP + 25) { index ->
            now += 1
            buffer.append(DiagnosticLogArea.Session, DiagnosticLogLevel.Info, "line-$index")
        }

        val snapshot = buffer.snapshot()
        assertEquals(DiagnosticLogBuffer.SOFT_CAP, snapshot.entries.size)
        assertEquals(25, snapshot.droppedBySoftCap)
        assertEquals("line-25", snapshot.entries.first().message)
        assertEquals("line-${DiagnosticLogBuffer.SOFT_CAP + 24}", snapshot.entries.last().message)
    }

    @Test
    fun clearResetsEntriesAndDroppedCount() {
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { 42L })
        buffer.append(DiagnosticLogArea.Permission, DiagnosticLogLevel.Warning, "pending count=1")
        buffer.snapshot()
        buffer.clear()
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.entries.isEmpty())
        assertEquals(0, snapshot.droppedBySoftCap)
    }

    @Test
    fun redactsSecretsAndPreservesSafeStatusLines() {
        assertTrue(
            DiagnosticLogRedactor.redact("auth failed password=super-secret token=abc123")
                .contains("[REDACTED]"),
        )
        assertFalse(DiagnosticLogRedactor.redact("password=super-secret").contains("super-secret"))
        assertFalse(DiagnosticLogRedactor.redact("Authorization: Basic dXNlcjpwYXNz").contains("dXNlcjpwYXNz"))
        assertFalse(DiagnosticLogRedactor.redact("Bearer eyJhbGciOi").contains("eyJhbGciOi"))

        val safe = DiagnosticLogRedactor.redact("permission refresh count=2 trigger=permission.asked")
        assertEquals("permission refresh count=2 trigger=permission.asked", safe)
    }

    @Test
    fun appendRedactsBeforeStorage() {
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { 99L })
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "opened password=leak status=200")
        val message = buffer.snapshot().entries.single().message
        assertFalse(message.contains("leak"))
        assertTrue(message.contains("[REDACTED]"))
        assertTrue(message.contains("status=200"))
    }

    @Test
    fun exportJsonlIncludesMetaAndEntriesWithoutSecrets() {
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { 1_700_000_000_000L })
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "GET /global/event opened status=200")
        buffer.append(DiagnosticLogArea.Permission, DiagnosticLogLevel.Warning, "refresh failed password=nope")

        val text = buffer.exportJsonl()
        assertTrue(text.contains("\"type\":\"meta\""))
        assertTrue(text.contains("\"windowMinutes\":5"))
        assertTrue(text.contains("\"area\":\"sse\""))
        assertFalse(text.contains("nope"))
        assertTrue(text.contains("[REDACTED]"))
    }
}
