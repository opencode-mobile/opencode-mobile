package dev.opencode.mobile.domain.filesystem

/** One File Browser row: a file or folder under the Session Chat worktree. */
data class ProjectEntry(
    val name: String,
    /** Relative to the worktree, `/`-separated, no trailing slash. */
    val relativePath: String,
    val isDirectory: Boolean,
    /** v1 `.gitignore` match. v2 has no ignored flag, so it is always false there. */
    val isIgnored: Boolean = false,
) {
    val isHidden: Boolean get() = name.startsWith(".")

    /** Parent folder relative path, or empty for a root child. */
    val parentPath: String get() = relativePath.substringBeforeLast('/', "")
}

/** What the File Viewer can show for one file. */
sealed interface ProjectFileContent {
    data class Text(
        val text: String,
        val isTruncated: Boolean = false,
        /** Full UTF-8 size when the server reported or sent it. */
        val totalBytes: Long? = null,
    ) : ProjectFileContent

    class Image(
        val bytes: ByteArray,
        val mimeType: String?,
    ) : ProjectFileContent {
        override fun equals(other: Any?): Boolean = other is Image && other.mimeType == mimeType && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + (mimeType?.hashCode() ?: 0)

        override fun toString(): String = "Image(bytes=${bytes.size}, mimeType=$mimeType)"
    }

    data class Binary(
        val mimeType: String? = null,
        val byteCount: Long? = null,
    ) : ProjectFileContent

    data object Empty : ProjectFileContent

    /** Past a read cap (v1 body or image preview); [byteCount] is the file size when known. */
    data class TooLarge(
        val byteCount: Long? = null,
    ) : ProjectFileContent
}

/** A File Browser request that failed (HTTP error, transport, or undecodable body). */
class ProjectFilesException(
    message: String,
    /** One-line reason for the user, such as the HTTP status. */
    val detail: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object ProjectFileLimits {
    /** Text preview cap; larger files show the first 1 MiB. */
    const val TEXT_PREVIEW_BYTES: Int = 1024 * 1024

    /** Images above this show as too large to preview. */
    const val IMAGE_PREVIEW_BYTES: Int = 10 * 1024 * 1024

    /** v1 reads stop past this body size (the server has no cap) and show as too large to preview. */
    const val V1_BODY_BYTES: Int = 16 * 1024 * 1024

    /** A NUL byte in this prefix marks a v2 file as binary. */
    const val BINARY_SNIFF_BYTES: Int = 8 * 1024

    const val FIND_LIMIT: Int = 50
}
