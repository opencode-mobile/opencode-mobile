package dev.opencode.mobile.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.filesystem.ProjectFileContent
import dev.opencode.mobile.domain.filesystem.ProjectFileHighlighter
import dev.opencode.mobile.domain.filesystem.ProjectFileLanguage
import dev.opencode.mobile.domain.filesystem.ProjectFileListingPolicy
import dev.opencode.mobile.domain.filesystem.ProjectFilesException
import dev.opencode.mobile.domain.filesystem.ProjectFilesRepository
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.ui.components.OpenCodeCodeLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Screen Model for the read-only File Browser rooted at a Session Chat worktree: lazy tree,
 * server find, and open files (tabs on tablets) with cached viewer content.
 */
@HiltViewModel
class FilesViewModel internal constructor(
    private val repository: ProjectFilesRepository,
    private val highlighter: ProjectFileHighlighter,
    private val workDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        repository: ProjectFilesRepository,
        highlighter: ProjectFileHighlighter,
    ) : this(repository, highlighter, Dispatchers.Default)

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private var folders: Map<String, FilesFolderState> = emptyMap()
    private var expanded: Set<String> = emptySet()
    private val folderJobs = mutableMapOf<String, Job>()
    private val fileJobs = mutableMapOf<String, Job>()
    private var findJob: Job? = null

    fun bind(worktree: String) {
        if (_uiState.value.worktree == worktree) return
        folderJobs.values.forEach(Job::cancel)
        fileJobs.values.forEach(Job::cancel)
        findJob?.cancel()
        folderJobs.clear()
        fileJobs.clear()
        folders = emptyMap()
        expanded = emptySet()
        _uiState.value =
            FilesUiState(
                worktree = worktree,
                projectName = worktree.trimEnd('/').substringAfterLast('/').ifEmpty { worktree },
            )
        loadFolder(ROOT)
    }

    fun toggleDirectory(path: String) {
        if (path in expanded) {
            expanded = expanded - path
            publishTree()
            return
        }
        expanded = expanded + path
        val state = folders[path]
        if (state == null || state is FilesFolderState.Failed) {
            loadFolder(path)
        } else {
            publishTree()
        }
    }

    /** Retries the root ([ROOT]) or a child folder that failed to load. */
    fun retryFolder(path: String) {
        loadFolder(path)
    }

    fun toggleHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
        publishTree()
    }

    /** Reloads the root and every expanded folder (keeping what is shown until it arrives) and re-runs find. */
    fun refresh() {
        val keep = expanded + ROOT
        folders = folders.filterKeys { it in keep }
        keep.forEach { path -> loadFolder(path, showLoading = folders[path] !is FilesFolderState.Loaded) }
        if (_uiState.value.isFinding) runFind(_uiState.value.query, debounce = false)
    }

    fun setQuery(query: String) {
        if (query == _uiState.value.query) return
        _uiState.update { it.copy(query = query) }
        runFind(query, debounce = true)
    }

    fun retryFind() {
        runFind(_uiState.value.query, debounce = false)
    }

    /** Opens [path] (or activates its tab) and loads its content unless already cached. */
    fun openFile(path: String) {
        val state = _uiState.value
        val tabs = if (path in state.tabs) state.tabs else state.tabs + path
        val cached = state.viewers[path]
        _uiState.update { it.copy(tabs = tabs, activePath = path) }
        if (cached == null || cached.content is FilesViewerContent.Failed) loadFile(path)
    }

    fun selectTab(path: String) {
        if (path !in _uiState.value.tabs) return
        openFile(path)
    }

    /** Closes a tab; closing the active one activates the last remaining tab. */
    fun closeTab(path: String) {
        fileJobs.remove(path)?.cancel()
        _uiState.update { state ->
            val tabs = state.tabs - path
            state.copy(
                tabs = tabs,
                viewers = state.viewers - path,
                activePath = if (state.activePath == path) tabs.lastOrNull() else state.activePath,
            )
        }
    }

    fun retryFile(path: String) {
        if (path in _uiState.value.tabs) loadFile(path)
    }

    fun toggleWrap() {
        _uiState.update { it.copy(wrapLines = !it.wrapLines) }
    }

    fun toggleMarkdownSource(path: String) {
        updateViewer(path) { it.copy(showsMarkdownSource = !it.showsMarkdownSource) }
    }

    private fun loadFolder(
        path: String,
        showLoading: Boolean = true,
    ) {
        val worktree = _uiState.value.worktree
        if (worktree.isEmpty()) return
        folderJobs.remove(path)?.cancel()
        if (showLoading) {
            folders = folders + (path to FilesFolderState.Loading)
            publishTree()
        }
        folderJobs[path] =
            viewModelScope.launch {
                val next =
                    try {
                        FilesFolderState.Loaded(repository.listEntries(worktree, path))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "files list failed", error)
                        FilesFolderState.Failed(error.userDetail())
                    }
                folders = folders + (path to next)
                folderJobs.remove(path)
                publishTree()
            }
    }

    private fun runFind(
        query: String,
        debounce: Boolean,
    ) {
        findJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(find = FilesFindState.Idle) }
            return
        }
        val worktree = _uiState.value.worktree
        _uiState.update { it.copy(find = FilesFindState.Loading) }
        findJob =
            viewModelScope.launch {
                if (debounce) delay(FIND_DEBOUNCE_MILLIS)
                val next =
                    try {
                        FilesFindState.Results(repository.findFiles(worktree, trimmed))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "files find failed", error)
                        FilesFindState.Failed(error.userDetail())
                    }
                _uiState.update { it.copy(find = next) }
            }
    }

    private fun loadFile(path: String) {
        val worktree = _uiState.value.worktree
        fileJobs.remove(path)?.cancel()
        _uiState.update { state ->
            val viewer = state.viewers[path] ?: FilesViewerState(path)
            state.copy(viewers = state.viewers + (path to viewer.copy(content = FilesViewerContent.Loading)))
        }
        fileJobs[path] =
            viewModelScope.launch {
                val content =
                    try {
                        repository.readFile(worktree, path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "files read failed", error)
                        updateViewer(path) { it.copy(content = FilesViewerContent.Failed(error.userDetail())) }
                        return@launch
                    }
                when (content) {
                    is ProjectFileContent.Text -> showText(path, content)
                    is ProjectFileContent.Image ->
                        updateViewer(path) { it.copy(content = FilesViewerContent.Image(content.bytes, content.mimeType)) }
                    is ProjectFileContent.Binary ->
                        updateViewer(path) { it.copy(content = FilesViewerContent.Binary(content.mimeType)) }
                    ProjectFileContent.Empty -> updateViewer(path) { it.copy(content = FilesViewerContent.Empty) }
                    is ProjectFileContent.TooLarge ->
                        updateViewer(path) { it.copy(content = FilesViewerContent.TooLarge(content.byteCount)) }
                }
            }
    }

    /** Publishes plain lines first, then the same lines with syntax spans. */
    private suspend fun showText(
        path: String,
        content: ProjectFileContent.Text,
    ) {
        val language = ProjectFileLanguage.forPath(path)
        val (lines, markdown) =
            withContext(workDispatcher) {
                val markdown =
                    if (language.isMarkdown) {
                        runCatching {
                            OpenCodeMarkdownParser.parse(content.text, codeHighlighter = OpenCodeSemanticFallbackHighlighter)
                        }.getOrNull()
                    } else {
                        null
                    }
                OpenCodeCodeLines.split(content.text) to markdown
            }
        val plain =
            FilesViewerContent.Text(
                lines = lines,
                source = content.text,
                languageName = language.displayName,
                isTruncated = content.isTruncated,
                markdown = markdown,
            )
        updateViewer(path) { it.copy(content = plain) }
        val grammar = language.grammar ?: return
        val spans =
            try {
                highlighter.highlight(path, grammar, content.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "files highlight failed", error)
                return
            }
        if (spans.isEmpty()) return
        val highlighted = withContext(workDispatcher) { OpenCodeCodeLines.split(content.text, spans) }
        updateViewer(path) { viewer ->
            if (viewer.content === plain) viewer.copy(content = plain.copy(lines = highlighted)) else viewer
        }
    }

    private fun updateViewer(
        path: String,
        transform: (FilesViewerState) -> FilesViewerState,
    ) {
        _uiState.update { state ->
            val viewer = state.viewers[path] ?: return@update state
            state.copy(viewers = state.viewers + (path to transform(viewer)))
        }
    }

    private fun publishTree() {
        _uiState.update { state ->
            state.copy(
                root = folders[ROOT] ?: FilesFolderState.Loading,
                treeRows = buildTreeRows(folders, expanded, state.showHidden),
            )
        }
    }

    override fun onCleared() {
        folderJobs.values.forEach(Job::cancel)
        fileJobs.values.forEach(Job::cancel)
        findJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** The worktree root folder key. */
        const val ROOT = ""

        const val FIND_DEBOUNCE_MILLIS = 250L

        internal fun buildTreeRows(
            folders: Map<String, FilesFolderState>,
            expanded: Set<String>,
            showHidden: Boolean,
        ): List<FilesTreeRow> {
            val rows = ArrayList<FilesTreeRow>()

            fun appendChildren(
                folder: String,
                depth: Int,
            ) {
                when (val state = folders[folder]) {
                    null, FilesFolderState.Loading -> Unit
                    is FilesFolderState.Failed ->
                        if (folder != ROOT) rows += FilesTreeRow.FolderError(folder, depth, state.message)
                    is FilesFolderState.Loaded -> {
                        val visible = ProjectFileListingPolicy.visible(state.entries, showHidden)
                        if (visible.isEmpty()) rows += FilesTreeRow.EmptyFolder(folder, depth)
                        visible.forEach { entry ->
                            val isExpanded = entry.isDirectory && entry.relativePath in expanded
                            val childState = folders[entry.relativePath]
                            val isLoading = isExpanded && (childState == null || childState == FilesFolderState.Loading)
                            rows += FilesTreeRow.Entry(entry, depth, isExpanded = isExpanded, isLoading = isLoading)
                            if (isExpanded && !isLoading) appendChildren(entry.relativePath, depth + 1)
                        }
                    }
                }
            }
            appendChildren(ROOT, 0)
            return rows
        }

        private fun Exception.userDetail(): String =
            (this as? ProjectFilesException)?.detail ?: "Check the connection to the server and try again."
    }
}
