package dev.opencode.mobile.ui.files

import dev.opencode.mobile.domain.filesystem.ProjectEntry
import dev.opencode.mobile.domain.filesystem.ProjectFileContent
import dev.opencode.mobile.domain.filesystem.ProjectFileHighlighter
import dev.opencode.mobile.domain.filesystem.ProjectFilesException
import dev.opencode.mobile.domain.filesystem.ProjectFilesRepository
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeFilesRepository) = FilesViewModel(repository, FakeHighlighter(), dispatcher)

    @Test
    fun bind_loadsRootAndHidesDotEntriesUntilToggled() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository()
            val viewModel = viewModel(repository)

            viewModel.bind("/work/demo")
            assertEquals(FilesFolderState.Loading, viewModel.uiState.value.root)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("demo", state.projectName)
            assertEquals(listOf("src", "empty", "README.md"), state.entryNames())
            assertEquals(listOf(""), repository.listRequests)

            viewModel.toggleHidden()
            assertEquals(listOf(".github", "src", "empty", ".env", "README.md"), viewModel.uiState.value.entryNames())
        }

    @Test
    fun toggleDirectory_loadsChildrenLazilyOnceAndKeepsExpandedState() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository()
            val viewModel = viewModel(repository)
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.toggleDirectory("src")
            val loadingRow = viewModel.uiState.value.entryRow("src")
            assertTrue(loadingRow.isExpanded)
            assertTrue(loadingRow.isLoading)
            advanceUntilIdle()

            val rows = viewModel.uiState.value.treeRows
            assertEquals(listOf("src", "src/ui", "src/main.kt", "empty", "README.md"), rows.entryPaths())
            assertEquals(
                1,
                viewModel.uiState.value
                    .entryRow("src/main.kt")
                    .depth,
            )
            assertFalse(
                viewModel.uiState.value
                    .entryRow("src")
                    .isLoading,
            )

            viewModel.toggleDirectory("src/ui")
            advanceUntilIdle()
            viewModel.toggleDirectory("src")
            assertEquals(
                listOf("src", "empty", "README.md"),
                viewModel.uiState.value.treeRows
                    .entryPaths(),
            )

            viewModel.toggleDirectory("src")
            advanceUntilIdle()
            assertEquals(
                listOf("src", "src/ui", "src/ui/Button.kt", "src/main.kt", "empty", "README.md"),
                viewModel.uiState.value.treeRows
                    .entryPaths(),
            )
            assertEquals(listOf("", "src", "src/ui"), repository.listRequests)
        }

    @Test
    fun emptyFolder_showsInlineEmptyRow() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeFilesRepository())
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.toggleDirectory("empty")
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.treeRows
                    .filterIsInstance<FilesTreeRow.EmptyFolder>()
                    .single()
            assertEquals("empty", row.folderPath)
            assertEquals(1, row.depth)
        }

    @Test
    fun childLoadFailure_showsInlineErrorThenRetryLoads() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository(failingFolders = mutableSetOf("src"))
            val viewModel = viewModel(repository)
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.toggleDirectory("src")
            advanceUntilIdle()
            val error =
                viewModel.uiState.value.treeRows
                    .filterIsInstance<FilesTreeRow.FolderError>()
                    .single()
            assertEquals("src", error.folderPath)
            assertEquals("Server said no.", error.message)

            repository.failingFolders.clear()
            viewModel.retryFolder("src")
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.treeRows
                    .none { it is FilesTreeRow.FolderError },
            )
            assertTrue(
                "src/main.kt" in
                    viewModel.uiState.value.treeRows
                        .entryPaths(),
            )
        }

    @Test
    fun rootFailure_isFullErrorAndRetryRecovers() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository(failingFolders = mutableSetOf(""))
            val viewModel = viewModel(repository)
            viewModel.bind("/repo")
            advanceUntilIdle()

            assertEquals(FilesFolderState.Failed("Server said no."), viewModel.uiState.value.root)
            assertTrue(
                viewModel.uiState.value.treeRows
                    .isEmpty(),
            )

            repository.failingFolders.clear()
            viewModel.retryFolder(FilesViewModel.ROOT)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.root is FilesFolderState.Loaded)
            assertEquals(listOf("src", "empty", "README.md"), viewModel.uiState.value.entryNames())
        }

    @Test
    fun setQuery_debouncesFindAndClearingReturnsToTree() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository()
            val viewModel = viewModel(repository)
            viewModel.bind("/repo")
            advanceUntilIdle()
            viewModel.toggleDirectory("src")
            advanceUntilIdle()
            val treeBefore = viewModel.uiState.value.treeRows

            viewModel.setQuery("ma")
            assertEquals(FilesFindState.Loading, viewModel.uiState.value.find)
            advanceTimeBy(200)
            viewModel.setQuery("main")
            advanceTimeBy(200)
            runCurrent()
            assertTrue(repository.findRequests.isEmpty())
            advanceTimeBy(60)
            runCurrent()

            assertEquals(listOf("main"), repository.findRequests)
            val results = viewModel.uiState.value.find as FilesFindState.Results
            assertEquals(listOf("src/main.kt"), results.entries.map { it.relativePath })
            assertTrue(viewModel.uiState.value.isFinding)

            viewModel.setQuery("")
            advanceUntilIdle()
            assertEquals(FilesFindState.Idle, viewModel.uiState.value.find)
            assertFalse(viewModel.uiState.value.isFinding)
            assertEquals(treeBefore, viewModel.uiState.value.treeRows)
        }

    @Test
    fun find_withoutMatches_isEmptyResults() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeFilesRepository())
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.setQuery("zzz")
            advanceUntilIdle()

            assertEquals(FilesFindState.Results(emptyList()), viewModel.uiState.value.find)
        }

    @Test
    fun tabs_openSelectAndCloseActivateLastRemaining() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository()
            val viewModel = viewModel(repository)
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.openFile("README.md")
            viewModel.openFile("src/main.kt")
            viewModel.openFile("notes.txt")
            advanceUntilIdle()
            assertEquals(listOf("README.md", "src/main.kt", "notes.txt"), viewModel.uiState.value.tabs)
            assertEquals("notes.txt", viewModel.uiState.value.activePath)

            viewModel.selectTab("README.md")
            advanceUntilIdle()
            assertEquals("README.md", viewModel.uiState.value.activePath)
            assertEquals(3, repository.readRequests.size)

            viewModel.closeTab("README.md")
            assertEquals(listOf("src/main.kt", "notes.txt"), viewModel.uiState.value.tabs)
            assertEquals("notes.txt", viewModel.uiState.value.activePath)
            assertNull(viewModel.uiState.value.viewers["README.md"])

            viewModel.closeTab("src/main.kt")
            assertEquals("notes.txt", viewModel.uiState.value.activePath)
            viewModel.closeTab("notes.txt")
            assertTrue(
                viewModel.uiState.value.tabs
                    .isEmpty(),
            )
            assertNull(viewModel.uiState.value.activePath)
        }

    @Test
    fun openText_publishesHighlightedLinesAndMeta() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeFilesRepository())
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.openFile("src/main.kt")
            assertEquals(
                FilesViewerContent.Loading,
                viewModel.uiState.value.activeViewer
                    ?.content,
            )
            advanceUntilIdle()

            val text =
                viewModel.uiState.value.activeViewer
                    ?.content as FilesViewerContent.Text
            assertEquals(listOf("fun main() {", "}"), text.lines.map { it.text })
            assertEquals(listOf(CodeSpan(0, 3, CodeTokenRole.Keyword)), text.lines.first().spans)
            assertEquals("Kotlin · 2 lines", text.metaLabel)
            assertFalse(text.isTruncated)
            assertNull(text.markdown)
        }

    @Test
    fun openMarkdown_defaultsToPreviewAndTogglesSource() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeFilesRepository())
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.openFile("README.md")
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.activeViewer!!
                    .showsMarkdownPreview,
            )

            viewModel.toggleMarkdownSource("README.md")
            assertFalse(
                viewModel.uiState.value.activeViewer!!
                    .showsMarkdownPreview,
            )
            assertTrue(
                viewModel.uiState.value.activeViewer!!
                    .showsMarkdownSource,
            )
        }

    @Test
    fun openFile_mapsBinaryImageAndEmptyStates() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeFilesRepository())
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.openFile("app.bin")
            viewModel.openFile("logo.png")
            viewModel.openFile("empty.txt")
            advanceUntilIdle()

            val viewers = viewModel.uiState.value.viewers
            assertEquals(FilesViewerContent.Binary("application/octet-stream"), viewers.getValue("app.bin").content)
            assertTrue(viewers.getValue("logo.png").content is FilesViewerContent.Image)
            assertEquals(FilesViewerContent.Empty, viewers.getValue("empty.txt").content)
        }

    @Test
    fun openFile_mapsTooLargeWithAndWithoutSize() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeFilesRepository())
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.openFile("build.log")
            viewModel.openFile("huge.png")
            advanceUntilIdle()

            val viewers = viewModel.uiState.value.viewers
            val log = viewers.getValue("build.log").content as FilesViewerContent.TooLarge
            assertEquals(FilesViewerContent.TooLarge(byteCount = null), log)
            assertEquals("Too large to preview", log.title)
            assertEquals("This file is bigger than the app previews.", log.message)
            val image = viewers.getValue("huge.png").content as FilesViewerContent.TooLarge
            assertEquals("This file is bigger than the app previews.\n40 MB", image.message)
        }

    @Test
    fun formatFileSize_usesDecimalUnits() {
        assertEquals("1 byte", formatFileSize(1))
        assertEquals("999 bytes", formatFileSize(999))
        assertEquals("1.5 KB", formatFileSize(1_500))
        assertEquals("10 MB", formatFileSize(10_485_760))
        assertEquals("1 MB", formatFileSize(999_600))
        assertEquals("2.1 GB", formatFileSize(2_100_000_000))
    }

    @Test
    fun fileFailure_showsErrorAndRetryReloads() =
        runTest(dispatcher) {
            val repository = FakeFilesRepository(failingFiles = mutableSetOf("notes.txt"))
            val viewModel = viewModel(repository)
            viewModel.bind("/repo")
            advanceUntilIdle()

            viewModel.openFile("notes.txt")
            advanceUntilIdle()
            assertEquals(
                FilesViewerContent.Failed("Server said no."),
                viewModel.uiState.value.activeViewer
                    ?.content,
            )

            repository.failingFiles.clear()
            viewModel.retryFile("notes.txt")
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.activeViewer
                    ?.content is FilesViewerContent.Text,
            )
        }

    @Test
    fun toggleWrap_flipsSharedSetting() {
        val viewModel = viewModel(FakeFilesRepository())
        assertFalse(viewModel.uiState.value.wrapLines)
        viewModel.toggleWrap()
        assertTrue(viewModel.uiState.value.wrapLines)
    }

    private fun FilesUiState.entryNames(): List<String> = treeRows.filterIsInstance<FilesTreeRow.Entry>().map { it.entry.name }

    private fun List<FilesTreeRow>.entryPaths(): List<String> = filterIsInstance<FilesTreeRow.Entry>().map { it.entry.relativePath }

    private fun FilesUiState.entryRow(path: String): FilesTreeRow.Entry =
        treeRows.filterIsInstance<FilesTreeRow.Entry>().first { it.entry.relativePath == path }
}

private class FakeFilesRepository(
    val failingFolders: MutableSet<String> = mutableSetOf(),
    val failingFiles: MutableSet<String> = mutableSetOf(),
) : ProjectFilesRepository {
    val listRequests = mutableListOf<String>()
    val findRequests = mutableListOf<String>()
    val readRequests = mutableListOf<String>()

    private val tree =
        mapOf(
            "" to
                listOf(
                    dir(".github"),
                    dir("src"),
                    dir("empty"),
                    file(".env"),
                    file("README.md"),
                ),
            "src" to listOf(dir("src/ui"), file("src/main.kt")),
            "src/ui" to listOf(file("src/ui/Button.kt")),
            "empty" to emptyList(),
        )

    override suspend fun listEntries(
        worktree: String,
        relativeDirectory: String,
    ): List<ProjectEntry> {
        listRequests += relativeDirectory
        if (relativeDirectory in failingFolders) throw ProjectFilesException("Couldn't load folder", "Server said no.")
        return tree[relativeDirectory].orEmpty()
    }

    override suspend fun findFiles(
        worktree: String,
        query: String,
        limit: Int,
    ): List<ProjectEntry> {
        findRequests += query
        return tree.values.flatten().filter { !it.isDirectory && it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun readFile(
        worktree: String,
        relativePath: String,
    ): ProjectFileContent {
        readRequests += relativePath
        if (relativePath in failingFiles) throw ProjectFilesException("Couldn't open file", "Server said no.")
        return when (relativePath) {
            "src/main.kt" -> ProjectFileContent.Text("fun main() {\n}\n")
            "README.md" -> ProjectFileContent.Text("# Demo\n\nHello **world**.")
            "app.bin" -> ProjectFileContent.Binary(mimeType = "application/octet-stream", byteCount = 10)
            "logo.png" -> ProjectFileContent.Image(byteArrayOf(1, 2, 3), "image/png")
            "empty.txt" -> ProjectFileContent.Empty
            "build.log" -> ProjectFileContent.TooLarge(byteCount = null)
            "huge.png" -> ProjectFileContent.TooLarge(byteCount = 40_000_000)
            else -> ProjectFileContent.Text("plain notes")
        }
    }

    private fun dir(path: String) = ProjectEntry(path.substringAfterLast('/'), path, isDirectory = true)

    private fun file(path: String) = ProjectEntry(path.substringAfterLast('/'), path, isDirectory = false)
}

private class FakeHighlighter : ProjectFileHighlighter {
    override suspend fun highlight(
        path: String,
        grammar: String,
        source: String,
    ): List<CodeSpan> = if (source.startsWith("fun")) listOf(CodeSpan(0, 3, CodeTokenRole.Keyword)) else emptyList()
}
