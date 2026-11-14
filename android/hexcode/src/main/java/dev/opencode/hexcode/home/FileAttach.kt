package dev.opencode.hexcode.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** The computer's side of uploads: where they go, and the write itself ([HexServices] in the app). */
interface UploadRoute {
    /** `paths.tmp` when the server declares the upload route; null when it doesn't, or an upload found it changed. */
    suspend fun tmp(): String?

    /** An upload found the route changed: uploads stay off for this connection. */
    fun changed()

    /** Streams [file] to [serverPath] with `location[directory]` set to [directory]; the status and `data.path`. */
    suspend fun write(
        serverPath: String,
        file: File,
        directory: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Pair<Int, String?>
}

/**
 * Files picked on the phone (app-plan.md, Attach files): copied into the cache at pick time, with the name and size
 * from `OpenableColumns`, then uploaded with their exact bytes to `<paths.tmp>/uploads/<uuid>/<name>`, the body
 * streamed from the copy. [directory] is today's project, sent as `location[directory]`.
 */
class AndroidFileAttacher(
    context: Context,
    private val route: UploadRoute,
    private val directory: () -> String,
    private val dir: File = File(context.cacheDir, "attach-files"),
) : FileAttacher {
    private val resolver = context.contentResolver

    override suspend fun available(): Boolean = route.tmp() != null

    override suspend fun stage(source: String): StagedFile =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(source)
            var name: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) name = cursor.getString(column)
            }
            val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: FileNames.OCTET_STREAM
            dir.mkdirs()
            val copy = File(dir, UUID.randomUUID().toString())
            try {
                val input = resolver.openInputStream(uri) ?: throw IOException("can't open the file")
                input.use { from -> copy.outputStream().use { to -> from.copyTo(to, COPY_BUFFER) } }
            } catch (error: Exception) {
                copy.delete()
                throw error
            }
            val staged = StagedFile(copy, FileNames.safe(name ?: uri.lastPathSegment), mime, copy.length())
            Log.i(AttachLog.TAG, "copied a file: $mime, ${staged.size / 1024} KB")
            staged
        }

    override suspend fun upload(
        file: StagedFile,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): String {
        val tmp = route.tmp() ?: throw UploadUnavailableException("the server takes no uploads")
        val serverPath = FileNames.serverPath(tmp, UUID.randomUUID().toString(), file.name)
        val (status, path) = route.write(serverPath, file.file, directory(), onProgress)
        Log.i(AttachLog.TAG, "upload answered $status")
        return when (val answer = UploadAnswer.of(status, path)) {
            is UploadAnswer.Written -> answer.path
            UploadAnswer.RouteChanged -> {
                route.changed()
                throw UploadUnavailableException("the upload route answered $status")
            }
            UploadAnswer.Failed -> throw IOException("the upload answered $status")
        }
    }

    override suspend fun discard(file: StagedFile) {
        withContext(Dispatchers.IO) { file.file.delete() }
    }

    override suspend fun discardAll() {
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
    }
}
