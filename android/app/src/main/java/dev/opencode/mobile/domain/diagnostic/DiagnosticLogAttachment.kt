package dev.opencode.mobile.domain.diagnostic

import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentLimits
import dev.opencode.mobile.domain.message.AttachmentRejectedException
import dev.opencode.mobile.domain.message.AttachmentRejection
import java.util.UUID

object DiagnosticLogAttachment {
    const val FILENAME = "opencode-diagnostic-logs.jsonl"
    const val MIME = "text/plain"

    fun from(buffer: DiagnosticLogBuffer): Attachment {
        val bytes = buffer.exportJsonl().toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > AttachmentLimits.MAX_DECODED_BYTES) {
            throw AttachmentRejectedException(AttachmentRejection.TooLarge())
        }
        val directory = java.io.File(System.getProperty("java.io.tmpdir"), AttachmentLimits.STORE_DIRECTORY_NAME).apply { mkdirs() }
        val file = java.io.File(directory, "${UUID.randomUUID()}-$FILENAME").apply { writeBytes(bytes) }
        return Attachment(
            id = UUID.randomUUID().toString(),
            filename = FILENAME,
            mime = MIME,
            kind = AttachmentKind.Text,
            dataUrl = "",
            byteCount = bytes.size.toLong(),
            localPath = file.absolutePath,
        )
    }
}
