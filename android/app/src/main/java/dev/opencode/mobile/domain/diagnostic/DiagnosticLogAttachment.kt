package dev.opencode.mobile.domain.diagnostic

import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.AttachmentKind
import java.util.UUID

object DiagnosticLogAttachment {
    const val FILENAME = "opencode-diagnostic-logs.jsonl"
    const val MIME = "text/plain"

    fun from(buffer: DiagnosticLogBuffer): Attachment {
        val bytes = buffer.exportJsonl().toByteArray(Charsets.UTF_8)
        val dataUrl = requireNotNull(AttachmentDataUrl.make(bytes, MIME))
        return Attachment(
            id = UUID.randomUUID().toString(),
            filename = FILENAME,
            mime = MIME,
            kind = AttachmentKind.Text,
            dataUrl = dataUrl,
            byteCount = bytes.size.toLong(),
        )
    }
}
