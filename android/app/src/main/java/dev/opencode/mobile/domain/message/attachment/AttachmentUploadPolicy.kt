package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.model.ModelSelectionOption

/** What happens to a picked file: ride in the prompt, stream to the server first, or stop. */
sealed interface AttachmentDestination {
    data object Inline : AttachmentDestination

    data object Upload : AttachmentDestination

    data class Reject(
        val message: String,
    ) : AttachmentDestination
}

enum class InlineRejection {
    TooLarge,
    Unsupported,
}

/**
 * Desktop policy: a file the selected model reads natively (image, PDF) and that fits the
 * server's inline cap goes in the prompt as a data URL; everything else is uploaded when the
 * server can take it. Without an upload route the data-URL path applies unchanged, including
 * its size limit.
 */
object AttachmentUploadPolicy {
    const val DUPLICATE_ERROR = "This file has already been uploaded"
    const val UPLOAD_FAILED_ERROR = "Upload failed"

    fun destination(
        mime: String,
        byteCount: Long,
        model: ModelSelectionOption?,
        uploadAvailable: Boolean,
    ): AttachmentDestination {
        val fitsInline = byteCount <= AttachmentLimits.MAX_DECODED_BYTES
        if (uploadAvailable) {
            return if (fitsInline && readsNatively(mime, model)) AttachmentDestination.Inline else AttachmentDestination.Upload
        }
        return if (fitsInline) AttachmentDestination.Inline else AttachmentDestination.Reject(AttachmentLimits.TOO_LARGE_ERROR)
    }

    /** `capabilities.input.image` / `.pdf` of the selected model; unknown model reads as native. */
    fun readsNatively(
        mime: String,
        model: ModelSelectionOption?,
    ): Boolean =
        when (AttachmentDataUrl.kind(mime)) {
            AttachmentKind.Image -> model?.supportsImageInput != false
            AttachmentKind.Pdf -> model?.supportsPdfInput != false
            else -> false
        }

    /**
     * Why [attachment] cannot ride in the prompt as a data URL, or null when it can: over the inline
     * cap, or a type the model does not read (a generic file never travels inline).
     */
    fun inlineRejection(
        attachment: Attachment,
        model: ModelSelectionOption?,
    ): InlineRejection? =
        when {
            attachment.byteCount > AttachmentLimits.MAX_DECODED_BYTES -> InlineRejection.TooLarge
            attachment.kind == AttachmentKind.File || model?.acceptsAttachmentKind(attachment.kind) == false ->
                InlineRejection.Unsupported
            else -> null
        }

    /** Same name and byte size as a file already in the draft, pending or uploaded. */
    fun isDuplicate(
        candidate: Attachment,
        draft: List<Attachment>,
    ): Boolean = draft.any { it.id != candidate.id && it.filename == candidate.filename && it.byteCount == candidate.byteCount }
}
