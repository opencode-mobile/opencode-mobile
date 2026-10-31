package dev.opencode.mobile.ui.sessionchat.view

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares a timeline image through the system share sheet. The bytes go to one file in the
 * cache's `shared-images/` FileProvider path (replacing the previous share), never anywhere
 * another app can list.
 */
internal object SessionChatImageShare {
    private const val DIRECTORY = "shared-images"

    /** Null when the image could not be written. Call from a coroutine; the write runs on IO. */
    suspend fun chooser(
        context: Context,
        bytes: ByteArray,
        filename: String,
    ): Intent? {
        val name = safeFilename(filename)
        val uri =
            withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.cacheDir, DIRECTORY)
                    directory.deleteRecursively()
                    directory.mkdirs()
                    val file = File(directory, name).apply { writeBytes(bytes) }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            } ?: return null
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = AttachmentDataUrl.mimeFromFilename(name) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Share image")
    }

    /** A plain file name: no directories, no characters a share target could trip on. */
    internal fun safeFilename(filename: String): String {
        val base = filename.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimStart('.').take(96)
        return cleaned.ifBlank { "image.png" }
    }
}
