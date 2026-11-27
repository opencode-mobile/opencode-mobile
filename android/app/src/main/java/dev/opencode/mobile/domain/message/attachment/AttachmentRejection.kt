package dev.opencode.mobile.domain.message

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

/**
 * Why a picked, pasted or dropped attachment was not added. Carried from where it is rejected to
 * the screen, which shows [text]; [logMessage] is English for diagnostics only.
 */
sealed interface AttachmentRejection {
    val logMessage: String

    /** A folder; only files attach. */
    data object Folder : AttachmentRejection {
        override val logMessage = "folder"
    }

    /** Same name and size as a file already in the draft. */
    data object Duplicate : AttachmentRejection {
        override val logMessage = "duplicate"
    }

    /** Over the inline cap of [limitBytes] while the server takes no uploads. */
    data class TooLarge(
        val limitBytes: Long = AttachmentLimits.MAX_DECODED_BYTES,
    ) : AttachmentRejection {
        override val logMessage get() = "too large limit=$limitBytes"
    }

    /** Not an image, PDF or text file, while the server takes no uploads. */
    data object UnsupportedType : AttachmentRejection {
        override val logMessage = "unsupported type"
    }

    /** The selected model reads no images. */
    data object ModelWithoutImages : AttachmentRejection {
        override val logMessage = "model reads no images"
    }

    /** The selected model reads no PDFs. */
    data object ModelWithoutPdf : AttachmentRejection {
        override val logMessage = "model reads no pdf"
    }

    /** The selected model reads some of several attachments but not all. */
    data object ModelWithoutSome : AttachmentRejection {
        override val logMessage = "model reads some kinds only"
    }

    /** The content could not be read from its provider. */
    data object Unreadable : AttachmentRejection {
        override val logMessage = "unreadable"
    }

    /** A streamed upload failed. */
    data object UploadFailed : AttachmentRejection {
        override val logMessage = "upload failed"
    }
}

/** Thrown where ingestion rejects content; the message is [AttachmentRejection.logMessage]. */
class AttachmentRejectedException(
    val rejection: AttachmentRejection,
) : IllegalArgumentException(rejection.logMessage)

/** The rejection a failed ingestion carries; anything unexpected reads as unreadable. */
fun Throwable.attachmentRejection(): AttachmentRejection = (this as? AttachmentRejectedException)?.rejection ?: AttachmentRejection.Unreadable

/** What the user reads (`attachment_error_*`, same keys on iOS). */
val AttachmentRejection.text: UiText
    get() =
        when (this) {
            AttachmentRejection.Folder -> UiText.resource(R.string.attachment_error_folder)
            AttachmentRejection.Duplicate -> UiText.resource(R.string.attachment_error_duplicate)
            is AttachmentRejection.TooLarge ->
                UiText.resource(R.string.attachment_error_too_large, (limitBytes / BYTES_PER_MB).toInt())
            AttachmentRejection.UnsupportedType -> UiText.resource(R.string.attachment_error_unsupported_type)
            AttachmentRejection.ModelWithoutImages -> UiText.resource(R.string.attachment_error_model_no_images)
            AttachmentRejection.ModelWithoutPdf -> UiText.resource(R.string.attachment_error_model_no_pdf)
            AttachmentRejection.ModelWithoutSome -> UiText.resource(R.string.attachment_error_model_some)
            AttachmentRejection.Unreadable -> UiText.resource(R.string.attachment_error_unreadable)
            AttachmentRejection.UploadFailed -> UiText.resource(R.string.attachment_error_upload_failed)
        }

private const val BYTES_PER_MB = 1024L * 1024L
