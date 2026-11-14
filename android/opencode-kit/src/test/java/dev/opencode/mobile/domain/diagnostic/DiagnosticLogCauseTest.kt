package dev.opencode.mobile.domain.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.ConnectException

class DiagnosticLogCauseTest {
    @Test
    fun summarizeIncludesTypeAndMessage() {
        val summary = DiagnosticLogCause.summarize(ConnectException("Failed to connect to /10.0.0.1:4096"))
        assertEquals("cause=ConnectException: Failed to connect to /10.0.0.1:4096", summary)
    }

    @Test
    fun summarizeWalksCauseChain() {
        val summary =
            DiagnosticLogCause.summarize(
                RuntimeException(
                    "wrapper",
                    ConnectException("Failed to connect to /10.0.0.1:4096"),
                ),
            )
        assertEquals(
            "cause=RuntimeException: wrapper <- ConnectException: Failed to connect to /10.0.0.1:4096",
            summary,
        )
    }

    @Test
    fun summarizeUsesTypeWhenMessageIsBlank() {
        assertEquals("cause=IllegalStateException", DiagnosticLogCause.summarize(IllegalStateException()))
    }

    @Test
    fun summarizeUsesFirstLineOfMultilineMessage() {
        val summary = DiagnosticLogCause.summarize(RuntimeException("first line\nsecond line"))
        assertEquals("cause=RuntimeException: first line", summary)
        assertFalse(summary.contains("second line"))
    }

    @Test
    fun summarizeStopsOnCauseCycles() {
        val cyclic = CyclicException()
        val summary = DiagnosticLogCause.summarize(cyclic)
        assertEquals("cause=CyclicException: loop", summary)
    }

    @Test
    fun appendToLeavesMessageAloneWithoutThrowable() {
        assertEquals("sendPromptAsync failed", DiagnosticLogCause.appendTo("sendPromptAsync failed", null))
    }

    @Test
    fun appendToAddsCauseSuffix() {
        val combined =
            DiagnosticLogCause.appendTo(
                "sendPromptAsync failed",
                ConnectException("Failed to connect to /10.0.0.1:4096"),
            )
        assertEquals(
            "sendPromptAsync failed cause=ConnectException: Failed to connect to /10.0.0.1:4096",
            combined,
        )
    }

    @Test
    fun appendToDoesNotDuplicateExistingSummary() {
        val throwable = ConnectException("Failed to connect")
        val once = DiagnosticLogCause.appendTo("sendPromptAsync failed", throwable)
        assertEquals(once, DiagnosticLogCause.appendTo(once, throwable))
    }

    private class CyclicException : RuntimeException("loop") {
        override val cause: Throwable
            get() = this
    }
}
