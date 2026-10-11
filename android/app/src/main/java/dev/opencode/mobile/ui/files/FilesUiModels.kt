package dev.opencode.mobile.ui.files

import dev.opencode.mobile.domain.filesystem.ProjectEntry
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.components.OpenCodeCodeLine

/** Children of one folder in the File Browser tree. */
sealed interface FilesFolderState {
    data object Loading : FilesFolderState

    data class Loaded(
        val entries: List<ProjectEntry>,
    ) : FilesFolderState

    data class Failed(
        val message: String,
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
        val message: String,
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
        val message: String,
    ) : FilesFindState
}

sealed interface FilesViewerContent {
    data object Loading : FilesViewerContent

    data class Text(
        val lines: List<OpenCodeCodeLine>,
        val languageName: String,
        val isTruncated: Boolean,
        /** Rendered preview for Markdown files; null for other files or when parsing failed. */
        val markdown: MarkdownDocument? = null,
    ) : FilesViewerContent {
        val lineCount: Int get() = lines.size

        val metaLabel: String
            get() = "$languageName · $lineCount ${if (lineCount == 1) "line" else "lines"}"
    }

    class Image(
        val bytes: ByteArray,
        val mimeType: String?,
    ) : FilesViewerContent

    data class Binary(
        val mimeType: String?,
    ) : FilesViewerContent

    data object Empty : FilesViewerContent

    data class Failed(
        val message: String,
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
