package dev.opencode.mobile.domain.message

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

class AttachmentIngestionService private constructor(
    private val contentResolver: ContentResolver?,
    private val thumbnailer: (ByteArray) -> ByteArray? = AttachmentThumbnailer::create,
) {
    constructor(contentResolver: ContentResolver) : this(contentResolver, AttachmentThumbnailer::create)

    internal constructor(thumbnailer: (ByteArray) -> ByteArray?) : this(null, thumbnailer)

    /**
     * [uploadAvailable]: the server takes streamed uploads, so a file is copied to the store with no
     * size cap and any type is accepted (unknown types are classified by [AttachmentMime]). Without
     * it, ingestion is the data-URL path unchanged: 20 MB cap, images, PDFs and text only.
     */
    fun ingest(
        uris: List<Uri>,
        uploadAvailable: Boolean = false,
    ): AttachmentIngestionResult {
        val attachments = mutableListOf<Attachment>()
        val errors = mutableListOf<String>()
        uris.forEach { uri ->
            runCatching { if (uploadAvailable) ingestStreamed(uri) else ingest(uri) }
                .onSuccess { attachments += it }
                .onFailure { throwable ->
                    val message = throwable.message ?: READ_ERROR
                    errors += message
                    DiagnosticLog.warning(DiagnosticLogArea.Chat, "attachment rejected error=$message")
                }
        }
        DiagnosticLog.info(
            DiagnosticLogArea.Chat,
            "attachments selected accepted=${attachments.size} rejected=${errors.size}",
        )
        return AttachmentIngestionResult(attachments, errors)
    }

    private fun ingestStreamed(uri: Uri): Attachment {
        val contentResolver = requireNotNull(contentResolver)
        val metadata = metadata(uri)
        val declared = AttachmentMime.resolveDeclared(contentResolver.getType(uri), metadata.filename)
        val stored = newStoreFile(metadata.filename)
        val head = ByteArrayOutputStream()
        val total =
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    stored.outputStream().use { output -> copyCapturingHead(input, output, head) }
                } ?: throw IllegalArgumentException(READ_ERROR)
            } catch (error: Throwable) {
                runCatching { stored.delete() }
                throw error
            }
        val mime = AttachmentMime.normalize(declared, head.toByteArray())
        val kind = AttachmentDataUrl.kindOrFile(mime)
        val preview =
            if (kind == AttachmentKind.Image && total <= AttachmentLimits.MAX_DECODED_BYTES) {
                runCatching { thumbnailer(stored.readBytes())?.takeIf { it.size <= MAX_PREVIEW_BYTES } }.getOrNull()
            } else {
                null
            }
        DiagnosticLog.info(DiagnosticLogArea.Chat, "attachment staged mime=$mime bytes=$total")
        return Attachment(
            id = UUID.randomUUID().toString(),
            filename = metadata.filename.ifBlank { "Attachment" },
            mime = mime,
            kind = kind,
            dataUrl = "",
            byteCount = total,
            previewBytes = preview,
            localPath = stored.absolutePath,
        )
    }

    private fun ingest(uri: Uri): Attachment {
        val contentResolver = requireNotNull(contentResolver)
        val metadata = metadata(uri)
        if (metadata.size != null && metadata.size > AttachmentLimits.MAX_DECODED_BYTES) {
            throw IllegalArgumentException(AttachmentLimits.TOO_LARGE_ERROR)
        }
        val accepted =
            acceptedType(contentResolver.getType(uri), metadata.filename)
                ?: throw IllegalArgumentException(UNSUPPORTED_ERROR)
        // Providers may report no size; never buffer more than the limit before deciding.
        val bytes =
            contentResolver.openInputStream(uri)?.use { readBounded(it, AttachmentLimits.MAX_DECODED_BYTES) }
                ?: throw IllegalArgumentException(READ_ERROR)
        return ingest(metadata.filename, accepted, bytes)
    }

    internal fun ingest(
        filename: String,
        rawMime: String?,
        bytes: ByteArray,
    ): Attachment {
        val accepted = acceptedType(rawMime, filename) ?: throw IllegalArgumentException(UNSUPPORTED_ERROR)
        return ingest(filename, accepted, bytes)
    }

    private fun ingest(
        filename: String,
        accepted: AcceptedType,
        bytes: ByteArray,
    ): Attachment {
        if (bytes.size.toLong() > AttachmentLimits.MAX_DECODED_BYTES) {
            throw IllegalArgumentException(AttachmentLimits.TOO_LARGE_ERROR)
        }
        if (AttachmentDataUrl.kind(accepted.mime) == null) {
            throw IllegalArgumentException(UNSUPPORTED_ERROR)
        }
        val preview =
            if (accepted.kind == AttachmentKind.Image) {
                thumbnailer(bytes)?.takeIf { it.size <= MAX_PREVIEW_BYTES }
            } else {
                null
            }
        val stored = persist(bytes, filename)
        DiagnosticLog.info(DiagnosticLogArea.Chat, "attachment accepted mime=${accepted.mime} bytes=${bytes.size}")
        return Attachment(
            id = UUID.randomUUID().toString(),
            filename = filename.ifBlank { "Attachment" },
            mime = accepted.mime,
            kind = accepted.kind,
            dataUrl = "",
            byteCount = bytes.size.toLong(),
            previewBytes = preview,
            localPath = stored.absolutePath,
        )
    }

    private fun persist(
        bytes: ByteArray,
        filename: String,
    ): File = newStoreFile(filename).apply { writeBytes(bytes) }

    private fun newStoreFile(filename: String): File {
        val directory = File(System.getProperty("java.io.tmpdir"), AttachmentLimits.STORE_DIRECTORY_NAME).apply { mkdirs() }
        val safeName = filename.replace("/", "_").ifBlank { "attachment" }
        return File(directory, "${UUID.randomUUID()}-$safeName")
    }

    private fun metadata(uri: Uri): AttachmentMetadata {
        val contentResolver = requireNotNull(contentResolver)
        var filename: String? = null
        var size: Long? = null
        contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) filename = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        return AttachmentMetadata(
            filename = filename?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Attachment",
            size = size,
        )
    }

    private fun acceptedType(
        rawMime: String?,
        filename: String,
    ): AcceptedType? {
        val mime = rawMime?.lowercase(Locale.US)?.substringBefore(';')
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.US)
        val imageMime =
            when {
                mime in IMAGE_MIMES -> mime
                extension == "png" -> "image/png"
                extension in setOf("jpg", "jpeg") -> "image/jpeg"
                extension == "gif" -> "image/gif"
                extension == "webp" -> "image/webp"
                else -> null
            }
        return when {
            imageMime != null -> AcceptedType(imageMime, AttachmentKind.Image)
            mime == "application/pdf" || extension == "pdf" -> AcceptedType("application/pdf", AttachmentKind.Pdf)
            mime?.startsWith("text/") == true || extension in TEXT_EXTENSIONS -> AcceptedType("text/plain", AttachmentKind.Text)
            else -> null
        }
    }

    private data class AttachmentMetadata(
        val filename: String,
        val size: Long? = null,
    )

    private data class AcceptedType(
        val mime: String,
        val kind: AttachmentKind,
    )

    companion object {
        /**
         * Reads at most [limit] bytes; one byte past the limit throws
         * [AttachmentLimits.TOO_LARGE_ERROR] instead of growing the buffer further (#755).
         */
        internal fun readBounded(
            input: InputStream,
            limit: Long,
        ): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IllegalArgumentException(AttachmentLimits.TOO_LARGE_ERROR)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        /** Copies [input] to [output], keeping the first [AttachmentMime.SNIFF_BYTES] in [head]; returns the byte count. */
        internal fun copyCapturingHead(
            input: InputStream,
            output: java.io.OutputStream,
            head: ByteArrayOutputStream,
        ): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val room = AttachmentMime.SNIFF_BYTES - head.size()
                if (room > 0) head.write(buffer, 0, minOf(room, read))
                output.write(buffer, 0, read)
                total += read
            }
            return total
        }

        const val UNSUPPORTED_ERROR = "Only images, PDFs, or text files can be attached here."
        const val READ_ERROR = "The attachment could not be read."
        internal const val MAX_PREVIEW_BYTES = 128 * 1024
        private val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val TEXT_EXTENSIONS =
            setOf(
                "txt",
                "md",
                "markdown",
                "csv",
                "json",
                "jsonl",
                "xml",
                "yaml",
                "yml",
                "toml",
                "conf",
                "kt",
                "kts",
                "java",
                "swift",
                "js",
                "jsx",
                "mjs",
                "ts",
                "tsx",
                "css",
                "html",
                "htm",
                "py",
                "rb",
                "rs",
                "go",
                "c",
                "cc",
                "h",
                "cpp",
                "hpp",
                "mm",
                "sh",
                "zsh",
                "sql",
                "log",
            )
    }
}

internal object AttachmentThumbnailer {
    private const val MAX_PREVIEW_DIMENSION = 256

    fun create(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION * 2 ||
            bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION * 2
        ) {
            sampleSize *= 2
        }
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null
        val scale = minOf(1f, MAX_PREVIEW_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height))
        val thumbnail =
            if (scale < 1f) {
                Bitmap
                    .createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { decoded.recycle() }
            } else {
                decoded
            }
        return ByteArrayOutputStream().use { output ->
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, output)
            thumbnail.recycle()
            output.toByteArray().takeIf { it.size <= AttachmentIngestionService.MAX_PREVIEW_BYTES }
        }
    }
}

data class AttachmentIngestionResult(
    val attachments: List<Attachment>,
    val errors: List<String>,
)
