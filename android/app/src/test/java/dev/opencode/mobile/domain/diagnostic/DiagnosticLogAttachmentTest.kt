package dev.opencode.mobile.domain.diagnostic

import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogAttachmentTest {
    @Test
    fun from_exportsRedactedJsonlAsTextAttachment() {
        val buffer = DiagnosticLogBuffer(clockEpochMilliseconds = { 1_700_000_000_000L })
        buffer.append(DiagnosticLogArea.Sse, DiagnosticLogLevel.Info, "opened password=secret status=200")

        val attachment = DiagnosticLogAttachment.from(buffer)
        val decoded = String(requireNotNull(AttachmentDataUrl.decode(attachment.dataUrl, attachment.mime)))

        assertEquals(DiagnosticLogAttachment.FILENAME, attachment.filename)
        assertEquals(DiagnosticLogAttachment.MIME, attachment.mime)
        assertEquals(AttachmentKind.Text, attachment.kind)
        assertTrue(decoded.contains("\"type\":\"meta\""))
        assertTrue(decoded.contains("\"area\":\"sse\""))
        assertTrue(decoded.contains("[REDACTED]"))
        assertFalse(decoded.contains("secret"))
    }
}
