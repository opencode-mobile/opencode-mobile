package dev.opencode.mobile.domain.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticLogBufferTest {
    @Test
    fun evictsEntriesOlderThanRetainWindow() {
        var now = 10_000_000L
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { now })

        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "old")
        now += DiagnosticLogBuffer.RETAIN_MILLISECONDS + 1
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "new")

        val snapshot = buffer.snapshot()
        assertEquals(listOf("new"), snapshot.entries.map { it.message })
        assertEquals(60, snapshot.retainMinutes)
    }

    @Test
    fun keepsEntriesAcrossRestartInsideRetainWindow() {
        val directory = Files.createTempDirectory("diagnostic-logs-restart").toFile()
        val file = DiagnosticLogBuffer.fileIn(directory)
        var now = 1_700_000_000_000L

        val first = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
        first.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "before restart")
        first.flushPersisted()

        now += 6 * 60 * 1_000L
        val reopened = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
        assertEquals(listOf("before restart"), reopened.snapshot().entries.map { it.message })
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
            DiagnosticLogRedactor
                .redact("auth failed password=super-secret token=abc123")
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
        val message =
            buffer
                .snapshot()
                .entries
                .single()
                .message
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
        assertTrue(text.contains("\"windowMinutes\":15"))
        assertTrue(text.contains("\"retainMinutes\":60"))
        assertTrue(text.contains("\"exportWindowMinutes\":15"))
        assertTrue(text.contains("\"area\":\"sse\""))
        assertFalse(text.contains("nope"))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun appendDoesNotPersistSynchronously() {
        val directory = Files.createTempDirectory("diagnostic-logs-async").toFile()
        val file = DiagnosticLogBuffer.fileIn(directory)
        val buffer =
            DiagnosticLogBuffer(
                file = file,
                clockEpochMilliseconds = { 99L },
                persistDelayMilliseconds = null,
            )
        repeat(50) { index ->
            buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "delta-$index")
        }
        assertFalse(file.exists())
        assertEquals(50, buffer.snapshot().entries.size)

        buffer.flushPersisted()
        assertTrue(file.exists())
        val reopened = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { 99L })
        assertEquals(50, reopened.snapshot().entries.size)
    }

    @Test
    fun persistsAcrossRelaunchWithinWindow() {
        val directory = Files.createTempDirectory("diagnostic-logs-persist").toFile()
        val file = DiagnosticLogBuffer.fileIn(directory)
        var now = 1_700_000_000_000L

        val first = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
        first.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "before restart")
        first.append(DiagnosticLogArea.Session, DiagnosticLogLevel.Warning, "busy stuck")
        first.flushPersisted()

        val reopened = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
        assertEquals(
            listOf("before restart", "busy stuck"),
            reopened.snapshot().entries.map { it.message },
        )
        assertTrue(file.exists())

        now += DiagnosticLogBuffer.RETAIN_MILLISECONDS + 1
        val expired = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
        assertTrue(expired.snapshot().entries.isEmpty())
    }

    @Test
    fun exportJsonlFiltersToChosenWindow() {
        var now = 1_700_000_000_000L
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { now })
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "old")
        now += 20 * 60 * 1_000L
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "recent")

        val fifteen = buffer.exportJsonl(DiagnosticLogExportWindow.FifteenMinutes)
        assertTrue(fifteen.contains("recent"))
        assertFalse(fifteen.contains("\"message\":\"old\""))
        assertTrue(fifteen.contains("\"exportWindowMinutes\":15"))

        val sixty = buffer.exportJsonl(DiagnosticLogExportWindow.SixtyMinutes)
        assertTrue(sixty.contains("old"))
        assertTrue(sixty.contains("recent"))
        assertTrue(sixty.contains("\"exportWindowMinutes\":60"))
    }

    @Test
    fun clearRemovesPersistedFile() {
        val directory = Files.createTempDirectory("diagnostic-logs-clear").toFile()
        val file = DiagnosticLogBuffer.fileIn(directory)
        val buffer = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { 99L })
        buffer.append(DiagnosticLogArea.Permission, DiagnosticLogLevel.Info, "pending count=1")
        buffer.flushPersisted()
        assertTrue(file.exists())

        buffer.clear()
        assertTrue(buffer.snapshot().entries.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun bindSharedAbsorbsEarlyEphemeralEntriesIntoFileBackedBuffer() {
        DiagnosticLogBuffer.resetSharedForTests()
        try {
            val directory = Files.createTempDirectory("diagnostic-logs-bind").toFile()
            val file = DiagnosticLogBuffer.fileIn(directory)
            var now = 1_700_000_000_000L

            // Pre-DI facade path: install a memory-only shared buffer first.
            val ephemeral = DiagnosticLogBuffer(clockEpochMilliseconds = { now })
            DiagnosticLogBuffer.bindShared(ephemeral)
            ephemeral.append(
                DiagnosticLogArea.Sse,
                DiagnosticLogLevel.Info,
                "early before bind",
            )

            // Prior session residue on disk (separate writer, same clock).
            val previous =
                DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
            previous.append(
                DiagnosticLogArea.Session,
                DiagnosticLogLevel.Warning,
                "from previous process",
            )
            previous.flushPersisted()

            val bound =
                DiagnosticLogBuffer(
                    file = file,
                    clockEpochMilliseconds = { now },
                )
            DiagnosticLogBuffer.bindShared(bound)

            assertTrue(DiagnosticLogBuffer.shared === bound)
            assertEquals(
                listOf("from previous process", "early before bind"),
                bound.snapshot().entries.map { it.message },
            )

            val reopened = DiagnosticLogBuffer(file = file, clockEpochMilliseconds = { now })
            assertEquals(
                listOf("from previous process", "early before bind"),
                reopened.snapshot().entries.map { it.message },
            )
        } finally {
            DiagnosticLogBuffer.resetSharedForTests()
        }
    }
}
