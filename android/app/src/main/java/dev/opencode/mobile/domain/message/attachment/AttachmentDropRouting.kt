package dev.opencode.mobile.domain.message

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

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
        val reason: AttachmentRejection,
    ) : AttachmentDropRoute
}

/** The drag-over card: what a drop would attach, or why nothing can be. */
data class AttachmentDropSummary(
    val title: UiText,
    /** Why items are skipped carries the first routing reason; see [AttachmentDropRoute.Skipped]. */
    val subtitle: UiText,
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
    const val MAX_PREVIEWS = 4

    /** `DocumentsContract.Document.MIME_TYPE_DIR`, without pulling the Android class into the domain. */
    const val DIRECTORY_MIME = "vnd.android.document/directory"
    private const val INODE_DIRECTORY_MIME = "inode/directory"
    private val pastedImageName = Regex("""^Pasted image (\d+)\.png$""")

    fun route(
        item: AttachmentDropItem,
        context: AttachmentDropContext,
    ): AttachmentDropRoute {
        if (item.kind == AttachmentDropKind.Folder) return AttachmentDropRoute.Skipped(AttachmentRejection.Folder)
        val duplicate =
            item.filename != null &&
                item.byteCount != null &&
                context.draft.any { it.filename == item.filename && it.byteCount == item.byteCount }
        if (duplicate) return AttachmentDropRoute.Skipped(AttachmentRejection.Duplicate)
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
                item.kind == AttachmentDropKind.Image && !context.imagesSupported -> AttachmentRejection.ModelWithoutImages
                item.kind == AttachmentDropKind.Pdf && !context.pdfSupported -> AttachmentRejection.ModelWithoutPdf
                !fits -> AttachmentRejection.TooLarge()
                else -> AttachmentRejection.UnsupportedType
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
                title =
                    UiText.resource(
                        if (countKnown && items.size == 1) R.string.attachment_drop_reject_one else R.string.attachment_drop_reject_many,
                    ),
                subtitle = firstReason?.text ?: UiText.verbatim(""),
                previews = emptyList(),
                rejected = true,
            )
        }
        val images = accepted.all { it.kind == AttachmentDropKind.Image }
        val title =
            when {
                countKnown && images -> UiText.plural(R.plurals.attachment_drop_attach_images_count, accepted.size)
                countKnown -> UiText.plural(R.plurals.attachment_drop_attach_files_count, accepted.size)
                images -> UiText.resource(R.string.attachment_drop_attach_images)
                else -> UiText.resource(R.string.attachment_drop_attach_files)
            }
        val subtitle =
            when {
                firstReason == null -> UiText.resource(R.string.attachment_drop_adds_to_draft)
                countKnown ->
                    UiText.resource(
                        R.string.attachment_drop_skipped_detail,
                        UiText.plural(R.plurals.attachment_drop_skipped_count, skipped),
                        firstReason.text,
                    )
                else ->
                    UiText.resource(
                        R.string.attachment_drop_skipped_detail,
                        UiText.resource(R.string.attachment_drop_some_skipped),
                        firstReason.text,
                    )
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
