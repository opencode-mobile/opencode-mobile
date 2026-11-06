package dev.opencode.hexcode.home

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
