package dev.opencode.mobile.domain.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class DiagnosticLogTest {
    @Test
    fun errorBuffersCauseChain() {
        DiagnosticLogBuffer.resetSharedForTests()
        try {
            DiagnosticLog.error(
                DiagnosticLogArea.Chat,
                "sendPromptAsync failed",
                RuntimeException("Transport", ConnectException("Failed to connect to /10.0.0.1:4096")),
            )
            val message =
                DiagnosticLogBuffer.shared
                    .snapshot()
                    .entries
                    .single()
                    .message
            assertTrue(message.contains("sendPromptAsync failed"))
            assertTrue(message.contains("cause="))
            assertTrue(message.contains("ConnectException"))
            assertTrue(message.contains("Failed to connect to /10.0.0.1:4096"))
        } finally {
            DiagnosticLogBuffer.resetSharedForTests()
        }
    }

    @Test
    fun errorRedactsSecretsInCause() {
        DiagnosticLogBuffer.resetSharedForTests()
        try {
            DiagnosticLog.error(
                DiagnosticLogArea.Connection,
                "probe failed",
                RuntimeException("password=super-secret"),
            )
            val message =
                DiagnosticLogBuffer.shared
                    .snapshot()
                    .entries
                    .single()
                    .message
            assertFalse(message.contains("super-secret"))
            assertTrue(message.contains("[REDACTED]"))
            assertTrue(message.contains("cause="))
        } finally {
            DiagnosticLogBuffer.resetSharedForTests()
        }
    }
}
