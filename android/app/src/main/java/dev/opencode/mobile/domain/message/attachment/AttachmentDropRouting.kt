package dev.opencode.mobile.domain.message

/** What a pasted or dropped item is, decided before its bytes are read (#870). */
enum class AttachmentDropKind {
    Image,
    Pdf,
    Text,
    File,
    Folder,
}

/** A pasted or dropped item. A drag names its items only loosely, so name and size may be unknown. */
data class AttachmentDropItem(
    val kind: AttachmentDropKind,
    val filename: String? = null,
    val byteCount: Long? = null,
)

/** The selected model, the server and the draft, as far as routing a dropped item needs them. */
data class AttachmentDropContext(
    val imagesSupported: Boolean = true,
    val pdfSupported: Boolean = true,
    val uploadAvailable: Boolean = false,
    val draft: List<Attachment> = emptyList(),
)

sealed interface AttachmentDropRoute {
    data object Accepted : AttachmentDropRoute

    data class Skipped(
        val reason: String,
    ) : AttachmentDropRoute
}

/** The drag-over card: what a drop would attach, or why nothing can be. */
data class AttachmentDropSummary(
    val title: String,
    val subtitle: String,
    /** Kinds of the first accepted items, at most [AttachmentDropRouting.MAX_PREVIEWS]. */
    val previews: List<AttachmentDropKind>,
    /** Nothing in the drag can be attached: the card shows its error style. */
    val rejected: Boolean,
)

/**
 * Routes pasted and dropped items through the #644 policy before their bytes are read, and words
 * the drag-over card. Shared with iOS through `shared/attachment-drop/attachment-drop-cases.json`.
 */
object AttachmentDropRouting {
    const val FOLDER_ERROR = "Folders can't be attached."
    const val IMAGE_UNSUPPORTED_ERROR = "This model does not support image attachments."
    const val PDF_UNSUPPORTED_ERROR = "This model does not support PDF attachments."
    const val MAX_PREVIEWS = 4

    /** `DocumentsContract.Document.MIME_TYPE_DIR`, without pulling the Android class into the domain. */
    const val DIRECTORY_MIME = "vnd.android.document/directory"
    private const val INODE_DIRECTORY_MIME = "inode/directory"
    private const val ADDS_TO_DRAFT = "Adds them to the message you are writing"
    private val pastedImageName = Regex("""^Pasted image (\d+)\.png$""")

    fun route(
        item: AttachmentDropItem,
        context: AttachmentDropContext,
    ): AttachmentDropRoute {
        if (item.kind == AttachmentDropKind.Folder) return AttachmentDropRoute.Skipped(FOLDER_ERROR)
        val duplicate =
            item.filename != null &&
                item.byteCount != null &&
                context.draft.any { it.filename == item.filename && it.byteCount == item.byteCount }
        if (duplicate) return AttachmentDropRoute.Skipped(AttachmentUploadPolicy.DUPLICATE_ERROR)
        val fits = item.byteCount == null || item.byteCount <= AttachmentLimits.MAX_DECODED_BYTES
        val inline =
            when (item.kind) {
                AttachmentDropKind.Image -> context.imagesSupported
                AttachmentDropKind.Pdf -> context.pdfSupported
                AttachmentDropKind.Text -> true
                AttachmentDropKind.File, AttachmentDropKind.Folder -> false
            }
        if ((inline && fits) || context.uploadAvailable) return AttachmentDropRoute.Accepted
        val reason =
            when {
                item.kind == AttachmentDropKind.Image && !context.imagesSupported -> IMAGE_UNSUPPORTED_ERROR
                item.kind == AttachmentDropKind.Pdf && !context.pdfSupported -> PDF_UNSUPPORTED_ERROR
                !fits -> AttachmentLimits.TOO_LARGE_ERROR
                else -> AttachmentIngestionService.UNSUPPORTED_ERROR
            }
        return AttachmentDropRoute.Skipped(reason)
    }

    /**
     * Null when [items] is empty (a text-only drag shows no card). [countKnown] is false when the
     * platform names only the kinds in a drag, not how many items it holds (Android before the drop).
     */
    fun summary(
        items: List<AttachmentDropItem>,
        context: AttachmentDropContext,
        countKnown: Boolean = true,
    ): AttachmentDropSummary? {
        if (items.isEmpty()) return null
        val routed = items.map { it to route(it, context) }
        val accepted = routed.filter { it.second == AttachmentDropRoute.Accepted }.map { it.first }
        val firstReason =
            routed
                .firstNotNullOfOrNull { (it.second as? AttachmentDropRoute.Skipped)?.reason }
        val skipped = routed.size - accepted.size
        if (accepted.isEmpty()) {
            return AttachmentDropSummary(
                title = if (countKnown && items.size == 1) "Can't attach this" else "Can't attach these",
                subtitle = firstReason.orEmpty(),
                previews = emptyList(),
                rejected = true,
            )
        }
        val noun = if (accepted.all { it.kind == AttachmentDropKind.Image }) "image" else "file"
        val title =
            if (countKnown) {
                "Drop to attach ${accepted.size} $noun${if (accepted.size == 1) "" else "s"}"
            } else {
                "Drop to attach ${noun}s"
            }
        val subtitle =
            when {
                firstReason == null -> ADDS_TO_DRAFT
                countKnown -> "$skipped will be skipped · $firstReason"
                else -> "Some will be skipped · $firstReason"
            }
        return AttachmentDropSummary(
            title = title,
            subtitle = subtitle,
            previews = accepted.take(MAX_PREVIEWS).map { it.kind },
            rejected = false,
        )
    }

    /** `Pasted image N.png`, one past the highest N already in the draft. */
    fun pastedImageName(draftFilenames: List<String>): String {
        val highest =
            draftFilenames
                .mapNotNull { name ->
                    pastedImageName
                        .find(name)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }.maxOrNull() ?: 0
        return "Pasted image ${highest + 1}.png"
    }

    /** Kind of an item from its type and name, the way ingestion will classify it. */
    fun kind(
        mime: String?,
        filename: String?,
    ): AttachmentDropKind {
        val type = mime?.substringBefore(';')?.trim()?.lowercase()
        if (type == DIRECTORY_MIME || type == INODE_DIRECTORY_MIME) return AttachmentDropKind.Folder
        // A drag may name only `image/*` before the drop; photos are png or jpeg in practice.
        if (type == "image/*") return AttachmentDropKind.Image
        val name = filename.orEmpty()
        val declared = AttachmentMime.resolveDeclared(type, name)?.lowercase()
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            declared != null && AttachmentDataUrl.kind(declared) == AttachmentKind.Image -> AttachmentDropKind.Image
            declared == "application/pdf" -> AttachmentDropKind.Pdf
            declared != null && AttachmentMime.isTextLike(declared) -> AttachmentDropKind.Text
            (declared == null || declared == AttachmentMime.BINARY) && extension in AttachmentIngestionService.TEXT_EXTENSIONS ->
                AttachmentDropKind.Text
            else -> AttachmentDropKind.File
        }
    }
}
