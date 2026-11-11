package dev.opencode.mobile.domain.message

/** Where an attachment's bytes go: inline in the prompt, or streamed to the server first. */
sealed interface AttachmentUpload {
    /** Sent inline as a `data:` URL, as before uploads existed. */
    data object Inline : AttachmentUpload

    /** Streaming to the server; [percent] is null until the first progress report. */
    data class Uploading(
        val percent: Int? = null,
    ) : AttachmentUpload

    /** On the server at [serverPath]; the prompt names the path instead of carrying the bytes. */
    data class Uploaded(
        val serverPath: String,
    ) : AttachmentUpload
}

data class Attachment(
    val id: String,
    val filename: String,
    val mime: String,
    val kind: AttachmentKind,
    val dataUrl: String,
    val byteCount: Long,
    val previewBytes: ByteArray? = null,
    val localPath: String? = null,
    val upload: AttachmentUpload = AttachmentUpload.Inline,
) {
    val usesFileBackedBody: Boolean
        get() = localPath != null && upload is AttachmentUpload.Inline

    val isUploading: Boolean
        get() = upload is AttachmentUpload.Uploading

    val isUploaded: Boolean
        get() = upload is AttachmentUpload.Uploaded

    /** The attachment travels in the prompt body (not already on the server, not still uploading). */
    val isInline: Boolean
        get() = upload is AttachmentUpload.Inline

    /** Where the file lives on the server once it is uploaded; null while inline or uploading. */
    val uploadedServerPath: String?
        get() = (upload as? AttachmentUpload.Uploaded)?.serverPath
}

/** The attachments that travel in the prompt body; uploaded ones are named in the text instead. */
fun List<Attachment>.inlineAttachments(): List<Attachment> = filter { it.isInline }
