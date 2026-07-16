package dev.opencode.mobile.ui.changes

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.domain.session.VcsInfo
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.sessionchat.ReviewChangesSummary
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.SessionChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class ChangesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun exposesTwoSourcesWithDesktopCopy() {
        assertEquals("Git changes", ChangeSource.WorkingTree.displayName)
        assertEquals("Last turn changes", ChangeSource.LastTurn.displayName)
        assertEquals(listOf(ChangeSource.WorkingTree, ChangeSource.LastTurn), ChangeSource.entries)
    }

    @Test fun loadsSortedSnapshotAndFilteredTotals() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    results =
                        ArrayDeque(
                            listOf(
                                Result.success(
                                    listOf(
                                        file("B.kt", additions = 3, deletions = 2),
                                        file("A.kt", additions = 2, deletions = 1),
                                    ),
                                ),
                            ),
                        ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/repo", "m")
            advanceUntilIdle()

            var content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(listOf("A.kt", "B.kt"), content.files.map(FileDiff::path))
            assertEquals("2 changed files", content.summary)
            assertEquals(5, content.additions)
            assertEquals(listOf(Request("s", "m", "/repo")), repository.requests)

            viewModel.updateFilter("B")
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(listOf("B.kt"), content.filteredFiles.map(FileDiff::path))
            assertEquals("1 changed file", content.summary)
            assertEquals(3, content.additions)
            assertEquals(2, content.deletions)

            viewModel.updateFilter("missing")
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("0 changed files", content.summary)
            assertEquals(0, content.additions)
            assertEquals(0, content.deletions)
        }

    @Test fun refreshPreservesFilterAndReplacesStableSnapshot() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    results =
                        ArrayDeque(
                            listOf(
                                Result.success(listOf(file("keep.kt"), file("other.kt"))),
                                Result.success(listOf(file("keep-new.kt"))),
                            ),
                        ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m")
            advanceUntilIdle()
            viewModel.updateFilter("keep")
            viewModel.refresh()
            advanceUntilIdle()

            val content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("keep", content.filter)
            assertEquals(listOf("keep-new.kt"), content.filteredFiles.map(FileDiff::path))
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test fun refreshFailureKeepsSnapshotAndExposesRetry() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    results =
                        ArrayDeque(
                            listOf(
                                Result.success(listOf(file("keep.kt"))),
                                Result.failure(SessionError.Transport(RuntimeException("offline"))),
                            ),
                        ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m")
            advanceUntilIdle()
            viewModel.updateFilter("keep")
            viewModel.refresh()
            viewModel.updateFilter("latest")
            advanceUntilIdle()

            val content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(listOf("keep.kt"), content.files.map(FileDiff::path))
            assertEquals("latest", content.filter)
            assertEquals("offline", viewModel.uiState.value.refreshError)
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test fun gitEntryUsesFixedSourceOrderAndWorktreeDiff() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    vcsInfoResults = ArrayDeque(listOf(Result.success(VcsInfo("feature", "main")))),
                    vcsDiffResults = ArrayDeque(listOf(Result.success(listOf(file("git.kt"))))),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()

            assertEquals(listOf(ChangeSource.WorkingTree, ChangeSource.LastTurn), viewModel.uiState.value.availableSources)
            assertEquals(ChangeSource.WorkingTree, viewModel.uiState.value.selectedSource)
            assertEquals(listOf(VcsRequest(VcsDiffMode.WorkingTree, "/w", 12)), repository.vcsRequests)
        }

    @Test fun unsupportedGitFallsBackButTransientFailureIsRetryable() =
        runTest(dispatcher) {
            val unsupported =
                FakeRepository(
                    results = ArrayDeque(listOf(Result.success(listOf(file("last.kt"))))),
                    vcsInfoResults = ArrayDeque(listOf(Result.success(VcsInfo(null, null)))),
                )
            val fallback = ChangesViewModel(unsupported)
            fallback.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()
            assertEquals(ChangeSource.LastTurn, fallback.uiState.value.selectedSource)
            assertEquals(listOf(ChangeSource.LastTurn), fallback.uiState.value.availableSources)

            val transient =
                FakeRepository(
                    vcsInfoResults = ArrayDeque(listOf(Result.failure(SessionError.Transport(RuntimeException("offline"))))),
                )
            val retryable = ChangesViewModel(transient)
            retryable.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()
            assertEquals(ChangesPhase.Error("offline"), retryable.uiState.value.phase)
        }

    @Test fun missingGitDiffEndpointFallsBackToLastTurn() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    results = ArrayDeque(listOf(Result.success(listOf(file("last.kt"))))),
                    vcsInfoResults = ArrayDeque(listOf(Result.success(VcsInfo("feature", "main")))),
                    vcsDiffResults = ArrayDeque(listOf(Result.failure(SessionError.Http(404, "missing")))),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()
            assertEquals(ChangeSource.LastTurn, viewModel.uiState.value.selectedSource)
            assertEquals(
                "last.kt",
                (viewModel.uiState.value.phase as ChangesPhase.Content)
                    .changeSet.files
                    .first()
                    .path,
            )
        }

    @Test fun viewerStartsCollapsedNavigatesHunksAndHandlesUnavailablePreview() {
        val twoHunks =
            FileDiff(
                "A.kt",
                "@@ -1 +1 @@\n-old\n+new\n@@ -5 +5 @@\n-before\n+after",
                2,
                2,
                FileDiffStatus.Modified,
            )
        var viewer = FileViewerState(twoHunks)
        assertTrue(viewer.hasPreview)
        assertFalse(viewer.canSelectPreviousHunk)
        assertTrue(viewer.canSelectNextHunk)
        assertFalse(viewer.canExpandContext)
        viewer = viewer.selectNextHunk()
        assertTrue(viewer.canSelectPreviousHunk)
        assertFalse(viewer.canSelectNextHunk)

        val before = (1..8).joinToString("\n") { " before $it" }
        val after = (1..8).joinToString("\n") { " after $it" }
        var expandable =
            FileViewerState(
                FileDiff(
                    "B.kt",
                    "@@ -1,17 +1,17 @@\n$before\n-old\n+new\n$after",
                    1,
                    1,
                    FileDiffStatus.Modified,
                ),
            )
        assertTrue(expandable.canExpandContext)
        expandable = expandable.toggleContextMode()
        assertEquals(UnifiedDiffContextMode.Expanded, expandable.contextMode)
        assertFalse(FileViewerState(FileDiff("image.png", null, 0, 0, FileDiffStatus.Modified)).hasPreview)
    }

    @Test fun entryPointsChooseGitAndLastTurn() {
        val state =
            SessionChatUiState(
                "s",
                "Session",
                SessionChatTimelineState.Ready,
                messages = listOf(row("user", MessageRole.User), row("assistant", MessageRole.Assistant)),
                reviewChangesMessageId = "user",
                reviewChangesSummary = ReviewChangesSummary(1, 1, 0),
            )
        assertEquals(ChangeSource.WorkingTree, state.changesNavigationTarget("/repo").initialSource)
        assertEquals(ChangeSource.LastTurn, state.reviewChangesNavigationTarget("/repo")?.initialSource)
    }

    @Test fun fileChangeNavigationUsesOwningTurnAndOptionalPath() {
        val state =
            SessionChatUiState(
                "s",
                "Session",
                SessionChatTimelineState.Ready,
                messages =
                    listOf(
                        row("user-1", MessageRole.User),
                        row("assistant-1", MessageRole.Assistant),
                        row("user-2", MessageRole.User),
                        row("assistant-2", MessageRole.Assistant),
                    ),
            )
        val single =
            state.fileChangeNavigationTarget(
                worktree = "/repo",
                assistantMessageId = "assistant-1",
                initialFilePath = "src/Main.kt",
            )
        assertEquals("user-1", single?.messageId)
        assertEquals(ChangeSource.LastTurn, single?.initialSource)
        assertEquals("src/Main.kt", single?.initialFilePath)

        val multi =
            state.fileChangeNavigationTarget(
                worktree = "/repo",
                assistantMessageId = "assistant-2",
                initialFilePath = null,
            )
        assertEquals("user-2", multi?.messageId)
        assertNull(multi?.initialFilePath)
    }

    private fun row(
        id: String,
        role: MessageRole,
    ) = SessionChatMessageItem(id, role, "", "", false)

    private fun file(
        path: String,
        additions: Int = 1,
        deletions: Int = 1,
        status: FileDiffStatus = FileDiffStatus.Modified,
    ) = FileDiff(path, "@@ -1 +1 @@\n-old\n+new", additions, deletions, status)
}

private data class Request(
    val sessionId: String,
    val messageId: String?,
    val directory: String?,
)

private data class VcsRequest(
    val mode: VcsDiffMode,
    val directory: String?,
    val context: Int,
)

private class FakeRepository(
    private val results: ArrayDeque<Result<List<FileDiff>>> = ArrayDeque(),
    private val vcsInfoResults: ArrayDeque<Result<VcsInfo>> = ArrayDeque(),
    private val vcsDiffResults: ArrayDeque<Result<List<FileDiff>>> = ArrayDeque(),
) : SessionRepository {
    val requests = mutableListOf<Request>()
    val vcsRequests = mutableListOf<VcsRequest>()

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> {
        requests += Request(id, messageId, directory)
        return results.removeFirst().getOrThrow()
    }

    override suspend fun getVcsInfo(directory: String?): VcsInfo =
        if (vcsInfoResults.isEmpty()) throw SessionError.Http(404, "unsupported") else vcsInfoResults.removeFirst().getOrThrow()

    override suspend fun getVcsDiff(
        mode: VcsDiffMode,
        directory: String?,
        context: Int,
    ): List<FileDiff> {
        vcsRequests += VcsRequest(mode, directory, context)
        return vcsDiffResults.removeFirst().getOrThrow()
    }

    override suspend fun listSessions(directory: String?) = emptyList<Session>()

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?) = emptyList<SessionStatus>()

    override suspend fun getSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ) = emptyList<Session>()

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ) = emptyList<SessionTodo>()

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = false

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = false

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ) = false

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ) = false
}
