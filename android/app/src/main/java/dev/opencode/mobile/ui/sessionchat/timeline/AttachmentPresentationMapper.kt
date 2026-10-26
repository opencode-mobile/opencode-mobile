package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.AttachmentPreviewCache
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.ui.components.OpenCodeAttachmentImageSource
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
            signature = signature(attachment.mime, attachment.id),
            imageBytes = attachment.previewBytes,
        )

    fun from(file: MessagePart.File): SessionChatAttachmentItem {
        val imageBytes = AttachmentPreviewCache.shared.preview(file)
        return SessionChatAttachmentItem(
            id = file.id,
            filename = file.filename,
            variant = file.kind.presentationVariant,
            signature = signature(file.mime, file.serverPath ?: file.id),
            imageBytes = imageBytes,
            imageSource = imageSource(file),
        )
    }

    /** Only images get a source: the timeline draws everything else as a chip. */
    private fun imageSource(file: MessagePart.File): OpenCodeAttachmentImageSource? =
        when {
            file.kind != AttachmentKind.Image -> null
            file.serverPath != null -> OpenCodeAttachmentImageSource.ServerPath(file.serverPath)
            file.dataUrl.isNotEmpty() -> OpenCodeAttachmentImageSource.DataUrl(file.dataUrl, file.mime)
            else -> null
        }

    fun from(item: SessionChatAttachmentItem): OpenCodeAttachmentPresentation =
        OpenCodeAttachmentPresentation(
            id = item.id,
            filename = item.filename,
            variant = item.variant,
            imageBytes = item.imageBytes,
            imageSource = item.imageSource,
        )

    private val AttachmentKind.presentationVariant: OpenCodeAttachmentVariant
        get() =
            when (this) {
                AttachmentKind.Image -> OpenCodeAttachmentVariant.Image
                AttachmentKind.Pdf -> OpenCodeAttachmentVariant.Pdf
                AttachmentKind.Text -> OpenCodeAttachmentVariant.Text
                AttachmentKind.File -> OpenCodeAttachmentVariant.File
            }

    private fun signature(
        mime: String,
        dataUrl: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$mime\u0000$dataUrl".toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
