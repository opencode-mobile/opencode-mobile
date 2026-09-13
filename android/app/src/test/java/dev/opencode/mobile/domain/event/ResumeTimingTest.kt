package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTimingTest {
    @Test
    fun logsOnceFromForegroundOrigin() {
        ResumeTiming.reset()
        val buffer = DiagnosticLogBuffer.shared
        ResumeTiming.begin(1_000L)
        ResumeTiming.markCachePainted(1_025L)
        ResumeTiming.markConnected(1_080L)
        ResumeTiming.markBootstrapDone(1_140L)
        ResumeTiming.markBootstrapDone(1_200L)
        val line =
            buffer
                .snapshot()
                .entries
                .map { it.message }
                .last { it.startsWith("resume:") }
        assertEquals(
            "resume: cache painted +25ms, connected +80ms, bootstrap done +140ms",
            line,
        )
        assertEquals(
            DiagnosticLogArea.Sse.wireValue,
            buffer
                .snapshot()
                .entries
                .last { it.message == line }
                .area,
        )
        ResumeTiming.reset()
        assertTrue(buffer.snapshot().entries.count { it.message.startsWith("resume:") } >= 1)
    }
}
