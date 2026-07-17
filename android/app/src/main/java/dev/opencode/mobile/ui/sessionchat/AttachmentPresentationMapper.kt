package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentThumbnailer
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.ui.components.OpenCodeAttachmentPresentation
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
import java.security.MessageDigest

internal object AttachmentPresentationMapper {
    fun from(attachment: Attachment): OpenCodeAttachmentPresentation =
        OpenCodeAttachmentPresentation(
            id = attachment.id,
            filename = attachment.filename,
            variant = attachment.kind.presentationVariant,
            imageBytes = attachment.previewBytes,
        )

    fun toItem(attachment: Attachment): SessionChatAttachmentItem =
        SessionChatAttachmentItem(
            id = attachment.id,
            filename = attachment.filename,
            variant = attachment.kind.presentationVariant,
            signature = signature(attachment.mime, attachment.dataUrl),
            imageBytes = attachment.previewBytes,
        )

    fun from(file: MessagePart.File): SessionChatAttachmentItem {
        val imageBytes =
            if (file.kind == AttachmentKind.Image) {
                AttachmentDataUrl.decode(file.dataUrl, file.mime)?.let(AttachmentThumbnailer::create)
            } else {
                null
            }
        return SessionChatAttachmentItem(
            id = file.id,
            filename = file.filename,
            variant = file.kind.presentationVariant,
            signature = signature(file.mime, file.dataUrl),
            imageBytes = imageBytes,
        )
    }

    fun from(item: SessionChatAttachmentItem): OpenCodeAttachmentPresentation =
        OpenCodeAttachmentPresentation(
            id = item.id,
            filename = item.filename,
            variant = item.variant,
            imageBytes = item.imageBytes,
        )

    private val AttachmentKind.presentationVariant: OpenCodeAttachmentVariant
        get() =
            when (this) {
                AttachmentKind.Image -> OpenCodeAttachmentVariant.Image
                AttachmentKind.Pdf -> OpenCodeAttachmentVariant.Pdf
                AttachmentKind.Text -> OpenCodeAttachmentVariant.Text
            }

    private fun signature(
        mime: String,
        dataUrl: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$mime\u0000$dataUrl".toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
