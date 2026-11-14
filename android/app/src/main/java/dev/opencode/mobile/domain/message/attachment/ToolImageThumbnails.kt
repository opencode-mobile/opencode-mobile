package dev.opencode.mobile.domain.message

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Size and count budgets for [ToolImageThumbnails]; tests inject small ones. */
data class ToolThumbnailLimits(
    /** A thumbnail larger than this is re-encoded smaller, then skipped. */
    val maxThumbnailBytes: Int = 64 * 1024,
    /** Long edges tried in order until the output fits [maxThumbnailBytes]. */
    val longEdges: List<Int> = listOf(400, 300),
    val jpegQuality: Int = 70,
    /** Only the newest tool images of a session keep a thumbnail on disk. */
    val perSessionCount: Int = 24,
    val perSessionBytes: Long = 1024L * 1024L,
    /** Over this, the least recently viewed thumbnails go until [globalTargetBytes]. */
    val globalMaxBytes: Long = 50L * 1024L * 1024L,
    val globalTargetBytes: Long = 40L * 1024L * 1024L,
)

/** What one eviction or orphan sweep found and removed. */
data class ToolThumbnailReport(
    val bytes: Long,
    val files: Int,
    val evicted: Int,
)

/**
 * On-disk JPEG thumbnails of tool-result images, so a timeline restored from the session cache
 * can draw them without the full data URLs (which never go to disk). Layout:
 * `<root>/<connection hash>/<session id>/<image id>.jpg`, with ids reduced to safe file-name
 * characters; the connection is hashed so no server URL or host lands in a path.
 *
 * Every method does file I/O: call it off the main thread. Writes, evictions, sweeps and
 * deletes run one at a time.
 */
class ToolImageThumbnails(
    private val root: File,
    private val limits: ToolThumbnailLimits = ToolThumbnailLimits(),
    private val encode: (source: ByteArray, longEdge: Int, quality: Int) -> ByteArray? = ToolThumbnailEncoder::encode,
    private val decodeDataUrl: (dataUrl: String, mime: String) -> ByteArray? = AttachmentDataUrl::decode,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    /**
     * Brings [sessionId]'s thumbnails in line with [messages]: the newest
     * [ToolThumbnailLimits.perSessionCount] tool images within the session byte budget keep
     * (or get) a thumbnail; every other file in the session folder goes. Only images with
     * their full data URL can get a new one. Returns image id to thumbnail path, and whether
     * any file was written.
     */
    fun sync(
        connectionId: String,
        sessionId: String,
        messages: List<Message>,
    ): ToolThumbnailSync =
        synchronized(lock) {
            val images =
                messages
                    .flatMap { message -> message.parts.filterIsInstance<MessagePart.Tool>().flatMap { it.images } }
                    .distinctBy { it.id }
            val directory = sessionDirectory(connectionId, sessionId)
            val kept = linkedMapOf<String, String>()
            var keptBytes = 0L
            var wrote = false
            for (image in images.takeLast(limits.perSessionCount).asReversed()) {
                val file = File(directory, fileName(image.id))
                var size = if (file.isFile) file.length() else 0L
                if (size == 0L && image.hasFullData) {
                    val thumbnail = thumbnail(image) ?: continue
                    if (keptBytes + thumbnail.size > limits.perSessionBytes) continue
                    if (!write(file, thumbnail)) continue
                    size = thumbnail.size.toLong()
                    wrote = true
                }
                if (size == 0L) continue
                if (keptBytes + size > limits.perSessionBytes) {
                    file.delete()
                    continue
                }
                keptBytes += size
                kept[image.id] = file.absolutePath
            }
            val keptNames = kept.keys.mapTo(HashSet()) { fileName(it) }
            directory.listFiles()?.forEach { file -> if (file.name !in keptNames) file.delete() }
            if (kept.isEmpty()) deleteIfEmpty(directory)
            ToolThumbnailSync(paths = kept, wrote = wrote)
        }

    /** The saved thumbnail of [imageId], or null when there is none. Does not touch it. */
    fun existingPath(
        connectionId: String,
        sessionId: String,
        imageId: String,
    ): String? = File(sessionDirectory(connectionId, sessionId), fileName(imageId)).takeIf { it.isFile }?.absolutePath

    /**
     * Thumbnail bytes at [path], marking the file viewed (modification time) for
     * least-recently-viewed eviction. Null when it is gone or lies outside this store.
     */
    fun read(path: String): ByteArray? {
        if (!isInside(File(path))) return null
        return readFile(path, clock())
    }

    /** JPEG thumbnail of [image]'s full data, or null when it cannot fit the size cap. */
    fun thumbnail(image: MessagePart.File): ByteArray? {
        val source = decodeDataUrl(image.dataUrl, image.mime) ?: return null
        return fit(source)
    }

    /** [source] encoded at each long edge in turn until it fits; null when none does. */
    fun fit(source: ByteArray): ByteArray? {
        for (edge in limits.longEdges) {
            val encoded = runCatching { encode(source, edge, limits.jpegQuality) }.getOrNull() ?: return null
            if (encoded.size <= limits.maxThumbnailBytes) return encoded
        }
        return null
    }

    /** Above [ToolThumbnailLimits.globalMaxBytes], deletes least recently viewed files down to the target. */
    fun evictIfNeeded(): ToolThumbnailReport =
        synchronized(lock) {
            val files = allFiles()
            var total = files.sumOf { it.length() }
            var evicted = 0
            if (total > limits.globalMaxBytes) {
                for (file in files.sortedBy { it.lastModified() }) {
                    if (total <= limits.globalTargetBytes) break
                    val size = file.length()
                    if (file.delete()) {
                        total -= size
                        evicted += 1
                    }
                }
                pruneEmptyDirectories()
            }
            ToolThumbnailReport(bytes = total, files = files.size - evicted, evicted = evicted).also(::log)
        }

    /**
     * Deletes session folders with no cached message timeline in [entries] (connection id,
     * session id), then any connection folder left empty.
     */
    fun sweepOrphans(entries: Collection<Pair<String, String>>): ToolThumbnailReport =
        synchronized(lock) {
            val live = entries.mapTo(HashSet()) { (connectionId, sessionId) -> connectionFolder(connectionId) to safeName(sessionId) }
            var evicted = 0
            root.listFiles()?.filter { it.isDirectory }?.forEach { connection ->
                connection.listFiles()?.forEach { session ->
                    if ((connection.name to session.name) !in live) {
                        evicted += session.walkBottomUp().count { it.isFile }
                        session.deleteRecursively()
                    }
                }
                deleteIfEmpty(connection)
            }
            val files = allFiles()
            ToolThumbnailReport(bytes = files.sumOf { it.length() }, files = files.size, evicted = evicted).also(::log)
        }

    fun deleteAll() {
        synchronized(lock) { root.deleteRecursively() }
    }

    internal fun sessionDirectory(
        connectionId: String,
        sessionId: String,
    ): File = File(File(root, connectionFolder(connectionId)), safeName(sessionId))

    private fun write(
        file: File,
        bytes: ByteArray,
    ): Boolean =
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.delete()
                error("rename failed")
            }
        }.isSuccess

    private fun allFiles(): List<File> = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else emptyList()

    private fun pruneEmptyDirectories() {
        root.listFiles()?.forEach { connection ->
            connection.listFiles()?.forEach(::deleteIfEmpty)
            deleteIfEmpty(connection)
        }
    }

    private fun deleteIfEmpty(directory: File) {
        if (directory.isDirectory && directory.list()?.isEmpty() == true) directory.delete()
    }

    private fun isInside(file: File): Boolean =
        runCatching { file.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)

    private fun log(report: ToolThumbnailReport) {
        DiagnosticLog.info(
            DiagnosticLogArea.Session,
            "thumbCache bytes=${report.bytes} files=${report.files} evicted=${report.evicted}",
        )
    }

    companion object {
        const val DIRECTORY_NAME = "tool-thumbs"

        /**
         * Bytes of a thumbnail at [path] (a [MessagePart.File.thumbnailPath] the domain set),
         * marking it viewed for least-recently-viewed eviction. Null when it is gone. Does I/O.
         */
        fun readFile(
            path: String,
            nowMs: Long = System.currentTimeMillis(),
        ): ByteArray? {
            val file = File(path)
            val storeRoot = file.parentFile?.parentFile?.parentFile
            if (storeRoot?.name != DIRECTORY_NAME) return null
            return runCatching { file.readBytes() }
                .onSuccess { file.setLastModified(nowMs) }
                .getOrNull()
        }

        /** First 32 hex characters of SHA-256: a stable folder name that reveals nothing. */
        fun connectionFolder(connectionId: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(connectionId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)

        /** File-name-safe id: anything outside `[A-Za-z0-9._-]` becomes `_`, never a bare dot name. */
        fun safeName(id: String): String =
            id
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trimStart('.')
                .take(120)
                .ifEmpty { "_" }

        fun fileName(imageId: String): String = "${safeName(imageId)}.jpg"
    }
}

data class ToolThumbnailSync(
    /** Image id to absolute thumbnail path, for the images that have one. */
    val paths: Map<String, String>,
    /** A thumbnail was written, so a global eviction pass is due. */
    val wrote: Boolean,
)

/** Downscales and JPEG-encodes with the platform bitmap codecs. */
internal object ToolThumbnailEncoder {
    fun encode(
        source: ByteArray,
        longEdge: Int,
        quality: Int,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Power-of-two subsampling first, so a large screenshot never decodes at full size.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
        val scale = minOf(1f, longEdge.toFloat() / maxOf(decoded.width, decoded.height))
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        // JPEG has no alpha: draw onto white so transparent areas do not turn black.
        val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                decoded,
                null,
                Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            decoded.recycle()
            return ByteArrayOutputStream().use { output ->
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                output.toByteArray()
            }
        } finally {
            flattened.recycle()
        }
    }
}
