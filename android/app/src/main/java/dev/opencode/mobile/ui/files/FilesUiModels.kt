package dev.opencode.mobile.ui.files

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.filesystem.ProjectEntry
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.components.OpenCodeCodeLine
import dev.opencode.mobile.ui.text.UiText
import java.util.Locale
import kotlin.math.roundToLong

/** Children of one folder in the File Browser tree. */
sealed interface FilesFolderState {
    data object Loading : FilesFolderState

    data class Loaded(
        val entries: List<ProjectEntry>,
    ) : FilesFolderState

    /** [message] is the server's detail, verbatim, or app text when there is none. */
    data class Failed(
        val message: UiText,
    ) : FilesFolderState
}

/** One flattened row of the visible tree. */
sealed interface FilesTreeRow {
    val key: String
    val depth: Int

    data class Entry(
        val entry: ProjectEntry,
        override val depth: Int,
        val isExpanded: Boolean = false,
        val isLoading: Boolean = false,
    ) : FilesTreeRow {
        override val key: String get() = "entry:${entry.relativePath}"
    }

    /** "Empty folder" under an expanded folder (or the root) with no visible children. */
    data class EmptyFolder(
        val folderPath: String,
        override val depth: Int,
    ) : FilesTreeRow {
        override val key: String get() = "empty:$folderPath"
    }

    /** Inline child-load failure with Retry. */
    data class FolderError(
        val folderPath: String,
        override val depth: Int,
        val message: UiText,
    ) : FilesTreeRow {
        override val key: String get() = "error:$folderPath"
    }
}

sealed interface FilesFindState {
    data object Idle : FilesFindState

    data object Loading : FilesFindState

    data class Results(
        val entries: List<ProjectEntry>,
    ) : FilesFindState

    data class Failed(
        val message: UiText,
    ) : FilesFindState
}

sealed interface FilesViewerContent {
    data object Loading : FilesViewerContent

    data class Text(
        val lines: List<OpenCodeCodeLine>,
        /** The text as loaded, for actions that need it whole (the Highlighting Lab in Lab builds). */
        val source: String,
        val languageName: String,
        val isTruncated: Boolean,
        /** Rendered preview for Markdown files; null for other files or when parsing failed. */
        val markdown: MarkdownDocument? = null,
    ) : FilesViewerContent {
        val lineCount: Int get() = lines.size

        /** "Kotlin · 2 lines". The language name stays verbatim. */
        val metaLabel: UiText
            get() =
                UiText.resource(
                    R.string.file_viewer_meta,
                    UiText.verbatim(languageName),
                    UiText.plural(R.plurals.file_viewer_line_count, lineCount),
                )
    }

    class Image(
        val bytes: ByteArray,
        val mimeType: String?,
    ) : FilesViewerContent

    data class Binary(
        val mimeType: String?,
    ) : FilesViewerContent

    data object Empty : FilesViewerContent

    /** Past a read cap; [byteCount] is the file size when the server reported it. */
    data class TooLarge(
        val byteCount: Long?,
    ) : FilesViewerContent {
        val title: UiText get() = UiText.resource(R.string.file_viewer_too_large_title)

        /** The message, plus the file size on a second line when known. */
        val message: UiText
            get() =
                byteCount?.let { UiText.resource(R.string.file_viewer_too_large_message_size, formatFileSize(it)) }
                    ?: UiText.resource(R.string.file_viewer_too_large_message)
    }

    data class Failed(
        val message: UiText,
    ) : FilesViewerContent
}

/** One open file (a tab on tablets). */
data class FilesViewerState(
    val path: String,
    val content: FilesViewerContent = FilesViewerContent.Loading,
    val showsMarkdownSource: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')

    val showsMarkdownPreview: Boolean
        get() = !showsMarkdownSource && (content as? FilesViewerContent.Text)?.markdown != null
}

data class FilesUiState(
    val worktree: String = "",
    val projectName: String = "",
    val root: FilesFolderState = FilesFolderState.Loading,
    val treeRows: List<FilesTreeRow> = emptyList(),
    val showHidden: Boolean = false,
    val query: String = "",
    val find: FilesFindState = FilesFindState.Idle,
    /** Open files, oldest first. */
    val tabs: List<String> = emptyList(),
    val activePath: String? = null,
    val viewers: Map<String, FilesViewerState> = emptyMap(),
    val wrapLines: Boolean = false,
) {
    val isFinding: Boolean get() = query.isNotBlank()

    val activeViewer: FilesViewerState? get() = activePath?.let(viewers::get)
}

/**
 * Decimal file size such as `40 MB` or `1.5 KB`, matching the platform file-size formatters.
 * Byte counts are a plural resource; unit symbols and the number format stay as they are.
 */
internal fun formatFileSize(bytes: Long): UiText {
    if (bytes < 1_000) return UiText.plural(R.plurals.file_viewer_size_bytes, bytes.toInt())
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1_000.0
    var unit = 0
    while (value >= 999.5 && unit < units.lastIndex) {
        value /= 1_000.0
        unit++
    }
    val number =
        if (value < 10) {
            String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        } else {
            value.roundToLong().toString()
        }
    return UiText.verbatim("$number ${units[unit]}")
}
