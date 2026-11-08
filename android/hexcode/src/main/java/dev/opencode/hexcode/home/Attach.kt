package dev.opencode.hexcode.home

import dev.opencode.hexcode.session.PromptFile
import dev.opencode.hexcode.session.PromptImage
import java.io.File

/** Where an attached image came from, which names it in the request. */
enum class ImageKind {
    Photo,
    Screenshot,
}

/** A screenshot or photo on the draft (app-plan.md, Attach). [file] is its prepared JPEG; null while it's prepared. */
data class DraftImage(
    val id: Long,
    val file: File? = null,
    val kind: ImageKind = ImageKind.Photo,
) {
    val preparing: Boolean get() = file == null
}

/** Whether today's session's model can see images (`capabilities.input` has `image`), and its name for the notices. */
data class ImageInput(
    val model: String,
    val sees: Boolean,
)

/** An image prepared to send: a JPEG in the app's cache, at most [ImageFit.MAX_SIDE] on its longest side. */
data class PreparedImage(
    val file: File,
    val kind: ImageKind,
    val width: Int,
    val height: Int,
)

/** Turns a picked or taken image into the JPEG that's sent ([AndroidImagePreparer] in the app). */
interface ImagePreparer {
    /** Prepares the image at [source] (a `content://` URI), off the main thread. Throws when it can't be read. */
    suspend fun prepare(source: String): PreparedImage

    /** Deletes every prepared image except [files]: the ones on a draft, held for Undo, or waiting to send. */
    suspend fun keepOnly(files: Set<File>)
}

/** No images (tests, previews): every image fails to prepare. */
object NoImagePreparer : ImagePreparer {
    override suspend fun prepare(source: String): PreparedImage = error("Images aren't prepared here")

    override suspend fun keepOnly(files: Set<File>) = Unit
}

/** The request's files (app-plan.md, Attach › Sending). */
object AttachNames {
    /** "Photo 1.jpg", "Photo 2.jpg", "Screenshot 1.jpg": each kind counted on its own, in draft order. */
    fun of(images: List<DraftImage>): List<PromptImage> {
        val counts = mutableMapOf<ImageKind, Int>()
        return images.mapNotNull { image ->
            val file = image.file ?: return@mapNotNull null
            val n = (counts[image.kind] ?: 0) + 1
            counts[image.kind] = n
            PromptImage("${image.kind.name} $n.jpg", file)
        }
    }
}

/**
 * A file on the draft (app-plan.md, Attach files): from Files, or a video from Photos and videos. It's copied into the
 * cache and uploaded at once, with its exact bytes; [path] is where the computer keeps it, null while it uploads.
 * [name] is empty until the copy says what it's called.
 */
data class DraftFile(
    val id: Long,
    val name: String = "",
    val mime: String = FileNames.OCTET_STREAM,
    /** Shown with the play icon: a video, from the photo picker or Files. */
    val video: Boolean = false,
    /** Bytes uploaded so far, of [total] (0 until it's known). */
    val sent: Long = 0,
    val total: Long = 0,
    val path: String? = null,
) {
    val uploading: Boolean get() = path == null

    /** At most 4 capitals for the tile ("PDF", "ZIP"). */
    val extension: String get() = FileNames.extension(name)

    /** What the prompt names, once it's uploaded. */
    val prompt: PromptFile? get() = path?.let { PromptFile(name, mime, it) }
}

/** A file picked to send, copied into the app's cache: its safe name, its type and its size. */
data class StagedFile(
    val file: File,
    val name: String,
    val mime: String,
    val size: Long,
)

/** The upload route answered as if it had changed (`400`, `404`, `405`, `415`), or isn't there: Files is dimmed. */
class UploadUnavailableException(
    message: String,
) : Exception(message)

/** Copies picked files into the cache and uploads them to the computer ([AndroidFileAttacher] in the app). */
interface FileAttacher {
    /** Files can be offered: the server declares the upload route and gives `paths.tmp`. */
    suspend fun available(): Boolean

    /** Copies the file at [source] (a `content://` URI) into the cache, off the main thread. Throws when it can't be read. */
    suspend fun stage(source: String): StagedFile

    /**
     * Uploads [file] to `<paths.tmp>/uploads/<uuid>/<name>` and returns the path the server wrote. Throws
     * [UploadUnavailableException] when the route has changed, or another error when the upload failed.
     */
    suspend fun upload(
        file: StagedFile,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): String

    /** Deletes the cached copy. */
    suspend fun discard(file: StagedFile)

    /** Deletes every cached copy: at start, those left from before the app last closed. */
    suspend fun discardAll()
}

/** No files (tests, previews): Files is dimmed and every file fails to copy. */
object NoFileAttacher : FileAttacher {
    override suspend fun available(): Boolean = false

    override suspend fun stage(source: String): StagedFile = error("Files aren't copied here")

    override suspend fun upload(
        file: StagedFile,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): String = throw UploadUnavailableException("No uploads here")

    override suspend fun discard(file: StagedFile) = Unit

    override suspend fun discardAll() = Unit
}

/** A pick from Photos and videos: its URI and the type the picker gave, which tells a video from an image. */
data class PickedMedia(
    val uri: String,
    val mime: String?,
) {
    val video: Boolean get() = mime?.startsWith("video/") == true
}

/** Naming uploaded files (app-plan.md, Attach files › Naming). */
object FileNames {
    const val MAX_LENGTH = 120
    const val OCTET_STREAM = "application/octet-stream"

    /** An extension longer than this isn't one worth keeping when a name is cut. */
    private const val MAX_KEPT_EXTENSION = 16

    /**
     * The file's own name: `/`, `\` and control characters become `_`, then it's trimmed and cut to [MAX_LENGTH]
     * keeping its extension, never in the middle of a character. A blank name is "File".
     */
    fun safe(name: String?): String {
        val cleaned =
            name
                .orEmpty()
                .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
                .joinToString("")
                .trim()
        if (cleaned.isEmpty()) return "File"
        if (cleaned.length <= MAX_LENGTH) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= MAX_KEPT_EXTENSION) cleaned.substring(dot) else ""
        var cut = MAX_LENGTH - ext.length
        if (cleaned[cut - 1].isHighSurrogate()) cut -= 1
        return cleaned.take(cut).trimEnd() + ext
    }

    /** `<tmp>/uploads/<uuid>/<name>`: a folder per file, so two of the same name never collide or overwrite. */
    fun serverPath(
        tmp: String,
        uuid: String,
        name: String,
    ): String {
        val separator = if (tmp.contains('\\') && !tmp.contains('/')) '\\' else '/'
        return "${tmp.trimEnd('/', '\\')}${separator}uploads$separator$uuid$separator$name"
    }

    /** The extension in capitals, at most 4 letters ("PDF", "XLSX"); empty when there's none. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).take(4).uppercase()
    }
}

/** The caption after the draft's attachments (app-plan.md, Attach files › Draft row). */
sealed interface AttachCaption {
    /** "Uploading · 45%": every running upload together. */
    data class Uploading(
        val percent: Int,
    ) : AttachCaption

    /** "<Model> can't see images · they won't be sent". */
    data object NoVision : AttachCaption

    /** "2 images · go with your next message". */
    data class Images(
        val count: Int,
    ) : AttachCaption

    /** "report.pdf · goes with your next message". */
    data class File(
        val name: String,
    ) : AttachCaption

    /** "2 files · go with your next message". */
    data class Files(
        val count: Int,
    ) : AttachCaption

    /** "2 images, 1 file · go with your next message". */
    data class Mixed(
        val images: Int,
        val files: Int,
    ) : AttachCaption

    companion object {
        /** The draft's caption, for a model that [sees] images or not. */
        fun of(
            draft: Draft,
            sees: Boolean,
        ): AttachCaption {
            draft.uploadPercent?.let { return Uploading(it) }
            val images = draft.images.size
            val files = draft.files.size
            return when {
                images > 0 && !sees -> NoVision
                files == 0 -> Images(images)
                images == 0 && files == 1 -> File(draft.files.single().name)
                images == 0 -> Files(files)
                else -> Mixed(images, files)
            }
        }
    }
}

/** A name shortened in the middle, so its start and its extension both show ("quarterly-re…2026.pdf"). */
object MiddleEllipsis {
    fun of(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        var head = max / 2
        var tail = (max - 1) / 2
        if (text[head - 1].isHighSurrogate()) head -= 1
        if (text[text.length - tail].isLowSurrogate()) tail -= 1
        return text.take(head) + "…" + text.takeLast(tail)
    }
}

/** What an answer of `POST /api/experimental/fs/write` means (app-plan.md, Attach files › Upload fails). */
sealed interface UploadAnswer {
    /** Written at [path], the answer's `data.path`: what the prompt names. */
    data class Written(
        val path: String,
    ) : UploadAnswer

    /** `400`, `404`, `405` or `415`, or a `200` without `data.path`: the route has changed, and Files is dimmed. */
    data object RouteChanged : UploadAnswer

    /** Any other answer: this upload failed. */
    data object Failed : UploadAnswer

    companion object {
        private val changed = setOf(400, 404, 405, 415)

        fun of(
            status: Int,
            path: String?,
        ): UploadAnswer =
            when {
                status in 200..299 -> path?.let(::Written) ?: RouteChanged
                status in changed -> RouteChanged
                else -> Failed
            }
    }
}
