package dev.opencode.mobile.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.filesystem.ProjectFileContent
import dev.opencode.mobile.domain.filesystem.ProjectFileHighlightSession
import dev.opencode.mobile.domain.filesystem.ProjectFileHighlighter
import dev.opencode.mobile.domain.filesystem.ProjectFileLanguage
import dev.opencode.mobile.domain.filesystem.ProjectFileLineSpans
import dev.opencode.mobile.domain.filesystem.ProjectFileListingPolicy
import dev.opencode.mobile.domain.filesystem.ProjectFilesException
import dev.opencode.mobile.domain.filesystem.ProjectFilesRepository
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.ui.components.OpenCodeCodeLine
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
import kotlinx.coroutines.yield
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
    private val visibleLines = mutableMapOf<String, MutableStateFlow<IntRange>>()
    private var highlightEpoch: Int? = null

    fun bind(worktree: String) {
        if (_uiState.value.worktree == worktree) return
        folderJobs.values.forEach(Job::cancel)
        fileJobs.values.forEach(Job::cancel)
        findJob?.cancel()
        folderJobs.clear()
        fileJobs.clear()
        visibleLines.clear()
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
        visibleLines.remove(path)
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

    /** The File Viewer's visible lines for [path]; progressive coloring colors them first. */
    fun onVisibleLinesChange(
        path: String,
        firstLine: Int,
        lastLine: Int,
    ) {
        visibleLinesOf(path).value = firstLine..maxOf(firstLine, lastLine)
    }

    /**
     * The highlighting policy epoch ([dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences.epoch]);
     * when it changed since the last call (an engine or TextMate setting), open text files are colored again.
     */
    fun onHighlightingChange(epoch: Int) {
        val previous = highlightEpoch
        highlightEpoch = epoch
        if (previous == null || previous == epoch) return
        _uiState.value.viewers.forEach { (path, viewer) ->
            val content = viewer.content as? FilesViewerContent.Text ?: return@forEach
            fileJobs.remove(path)?.cancel()
            fileJobs[path] = viewModelScope.launch { highlightText(path, content) }
        }
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
        highlightText(path, plain)
    }

    /** Colors [shown] (the content on screen) with one whole-file pass, or progressively through a session. */
    private suspend fun highlightText(
        path: String,
        shown: FilesViewerContent.Text,
    ) {
        val grammar = ProjectFileLanguage.forPath(path).grammar ?: return
        try {
            val startedAt = System.nanoTime()
            val session = withContext(workDispatcher) { highlighter.openSession(path, grammar, shown.source) }
            if (session != null) {
                colorProgressively(path, shown, session, startedAt)
                return
            }
            val spans = highlighter.highlight(path, grammar, shown.source)
            if (spans.isEmpty()) return
            val highlighted = withContext(workDispatcher) { OpenCodeCodeLines.split(shown.source, spans) }
            updateViewer(path) { viewer ->
                if (viewer.content === shown) viewer.copy(content = shown.copy(lines = highlighted)) else viewer
            }
            DiagnosticLog.info(
                DiagnosticLogArea.Connection,
                "files whole-file highlight lines=${shown.lines.size} ms=${(System.nanoTime() - startedAt) / 1_000_000}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagnosticLog.warning(DiagnosticLogArea.Connection, "files highlight failed", error)
        }
    }

    /**
     * Colors the visible lines (plus a margin) first, then the rest from the top in chunks with a
     * deadline each, publishing after every call. A scroll far down is picked up before the next
     * chunk; the session computes the line states it needs to get there.
     */
    private suspend fun colorProgressively(
        path: String,
        shown: FilesViewerContent.Text,
        session: ProjectFileHighlightSession,
        startedAt: Long,
    ) {
        val source = shown.source
        val lineCount = shown.lines.size
        val starts = withContext(workDispatcher) { lineStarts(source, lineCount) }
        val lines = ArrayList(shown.lines)
        val colored = BooleanArray(lineCount)
        val visible = visibleLinesOf(path)
        var published = shown
        var frontier = 0
        var calls = 0
        var firstColorNanos = -1L
        while (true) {
            val range = visible.value
            val window = maxOf(0, range.first - VISIBLE_MARGIN_LINES)..minOf(lineCount - 1, range.last + VISIBLE_MARGIN_LINES)
            val firstVisible = window.firstOrNull { !colored[it] }
            val start: Int
            val end: Int
            val deadline: Double
            if (firstVisible != null) {
                start = firstVisible
                end = window.last { !colored[it] } + 1
                deadline = VISIBLE_DEADLINE_MILLIS
            } else {
                while (frontier < lineCount && colored[frontier]) frontier++
                if (frontier >= lineCount) {
                    DiagnosticLog.info(
                        DiagnosticLogArea.Connection,
                        "files progressive highlight lines=$lineCount calls=$calls " +
                            "firstColorMs=${firstColorNanos / 1_000_000} fullMs=${(System.nanoTime() - startedAt) / 1_000_000}",
                    )
                    return
                }
                start = frontier
                end = minOf(lineCount, frontier + CHUNK_LINES)
                deadline = CHUNK_DEADLINE_MILLIS
            }
            val changed =
                withContext(workDispatcher) {
                    applyLineSpans(source, starts, session.highlight(start, end, deadline), lines, colored)
                }
            calls++
            if (changed) {
                val next = published.copy(lines = ArrayList(lines))
                var current = false
                updateViewer(path) { viewer ->
                    if (viewer.content === published) {
                        current = true
                        viewer.copy(content = next)
                    } else {
                        viewer
                    }
                }
                if (!current) return
                published = next
                if (firstColorNanos < 0) firstColorNanos = System.nanoTime() - startedAt
            }
            yield()
        }
    }

    private fun visibleLinesOf(path: String): MutableStateFlow<IntRange> =
        visibleLines.getOrPut(path) { MutableStateFlow(0 until INITIAL_VISIBLE_LINES) }

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

        /** Lines assumed visible until the viewer reports its range. */
        internal const val INITIAL_VISIBLE_LINES = 80

        /** Lines above and below the visible range colored with it. */
        internal const val VISIBLE_MARGIN_LINES = 20

        /** Lines per background call, each stopped by [CHUNK_DEADLINE_MILLIS]. */
        internal const val CHUNK_LINES = 2_000

        private const val VISIBLE_DEADLINE_MILLIS = 16.0
        private const val CHUNK_DEADLINE_MILLIS = 8.0

        /** Where each of the first [lineCount] lines of [source] starts. */
        private fun lineStarts(
            source: String,
            lineCount: Int,
        ): IntArray {
            val starts = IntArray(lineCount)
            var line = 1
            var index = source.indexOf('\n')
            while (index >= 0 && line < lineCount) {
                starts[line++] = index + 1
                index = source.indexOf('\n', index + 1)
            }
            return starts
        }

        /** Rebuilds the lines [result] covers with its spans and marks them colored; false when it covered none. */
        private fun applyLineSpans(
            source: String,
            starts: IntArray,
            result: ProjectFileLineSpans,
            lines: MutableList<OpenCodeCodeLine>,
            colored: BooleanArray,
        ): Boolean {
            val endLine = minOf(result.endLine, lines.size)
            if (result.startLine >= endLine) return false
            var span = 0
            for (line in result.startLine until endLine) {
                val start = starts[line]
                var end =
                    when {
                        line + 1 < starts.size -> starts[line + 1] - 1
                        source.endsWith('\n') -> source.length - 1
                        else -> source.length
                    }
                if (end > start && source[end - 1] == '\r') end--
                while (span < result.spans.size && result.spans[span].end <= start) span++
                val lineSpans = ArrayList<CodeSpan>()
                var index = span
                while (index < result.spans.size && result.spans[index].start < end) {
                    val each = result.spans[index]
                    val from = maxOf(each.start, start)
                    val to = minOf(each.end, end)
                    if (from < to) lineSpans += CodeSpan(from - start, to - start, each.role)
                    index++
                }
                lines[line] = OpenCodeCodeLines.split(source.substring(start, end), lineSpans).single()
                colored[line] = true
            }
            return true
        }

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
