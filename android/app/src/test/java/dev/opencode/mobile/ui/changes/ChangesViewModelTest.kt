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
import dev.opencode.mobile.ui.components.ReviewChangesPillPreviewCase
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.reviewChangesPillAccessibilityLabel
import dev.opencode.mobile.ui.components.reviewChangesPillSummary
import dev.opencode.mobile.ui.sessionchat.ChangesNavigationTarget
import dev.opencode.mobile.ui.sessionchat.ReviewChangesPillVisibility
import dev.opencode.mobile.ui.sessionchat.ReviewChangesSummary
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.SessionChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun exposesSourceSpecificEmptyCopy() {
        assertEquals("No changes", ChangeSource.LastTurn.emptyTitle)
        assertEquals("The working tree has no uncommitted changes.", ChangeSource.WorkingTree.emptyDetail)
        assertEquals("No Main branch changes", ChangeSource.MainBranch.emptyTitle)
    }

    @Test fun loadsPathSortedChangeSetWithAggregateCountsAndScopesRequest() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    ArrayDeque(
                        listOf(
                            Result.success(
                                listOf(
                                    file("file2.kt", additions = 3, deletions = 2),
                                    file("file10.kt", additions = 2, deletions = 1),
                                ),
                            ),
                        ),
                    ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("ses-1", "/repo", "msg-1")
            advanceUntilIdle()
            val content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(listOf("file10.kt", "file2.kt"), content.files.map(FileDiff::path))
            assertEquals("file10.kt", content.selectedFile.path)
            assertEquals(5, content.additions)
            assertEquals(3, content.deletions)
            assertEquals("2 changed files, 5 additions, 3 deletions", content.aggregateAccessibilityLabel)
            assertEquals(listOf(Request("ses-1", "msg-1", "/repo")), repository.requests)
        }

    @Test fun filtersSelectsAndClearsWithExplicitNoMatchesState() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel("app/Main.kt", "docs/Plan.md", "test/MainTest.kt")

            viewModel.updateFilter("main")
            var content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(listOf("app/Main.kt", "test/MainTest.kt"), content.filteredFiles.map(FileDiff::path))
            assertEquals("app/Main.kt", content.selectedFile.path)

            viewModel.selectFile("test/MainTest.kt")
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("test/MainTest.kt", content.selectedFile.path)

            viewModel.updateFilter("missing")
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(emptyList<FileDiff>(), content.filteredFiles)
            assertEquals("test/MainTest.kt", content.selectedFile.path)

            viewModel.clearFilter()
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(3, content.filteredFiles.size)
            assertEquals("test/MainTest.kt", content.selectedFile.path)
        }

    @Test fun previousAndNextMoveWithinFilteredFilesAndExposeEnabledStates() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel("app/A.kt", "app/B.kt", "docs/C.md")
            viewModel.updateFilter("app/")

            var content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(false, content.canSelectPrevious)
            assertEquals(true, content.canSelectNext)

            viewModel.selectNextFile()
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("app/B.kt", content.selectedFile.path)
            assertEquals(true, content.canSelectPrevious)
            assertEquals(false, content.canSelectNext)

            viewModel.selectPreviousFile()
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("app/A.kt", content.selectedFile.path)
        }

    @Test fun exposesSelectedFileStatusPositionCountsAndAccessibilityLabel() =
        runTest(dispatcher) {
            val viewModel = loadedViewModel("Sources/App.kt", "Sources/New.kt")
            viewModel.selectFile("Sources/New.kt")
            val content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet

            assertEquals("Added · 2 of 2", content.selectedFileSummary)
            assertEquals("Added · +1 −1", content.filePickerSummary(content.selectedFile))
            assertEquals(
                "Sources/New.kt, Added, file 2 of 2, 1 addition, 1 deletion",
                content.selectedFileAccessibilityLabel,
            )
        }

    @Test fun navigatesHunksTogglesContextAndKeepsReviewedFilesVisible() =
        runTest(dispatcher) {
            val patch = "@@ -1 +1 @@\n-old\n+new\n@@ -20 +20 @@\n-before\n+after"
            val files =
                listOf(
                    FileDiff("A.kt", patch, 2, 2, FileDiffStatus.Modified),
                    file("B.kt"),
                )
            val viewModel = ChangesViewModel(FakeRepository(ArrayDeque(listOf(Result.success(files)))))
            viewModel.bind("s", "/w", "m")
            advanceUntilIdle()

            var content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("Hunk 1 of 2", content.selectedHunkPositionLabel)
            assertEquals(false, content.canSelectPreviousHunk)
            assertEquals(true, content.canSelectNextHunk)
            assertEquals(UnifiedDiffContextMode.Collapsed, content.contextMode)

            viewModel.selectNextHunk()
            viewModel.toggleContextMode()
            viewModel.toggleSelectedFileReviewed()
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("Hunk 2 of 2", content.selectedHunkPositionLabel)
            assertEquals(true, content.canSelectPreviousHunk)
            assertEquals(false, content.canSelectNextHunk)
            assertEquals(UnifiedDiffContextMode.Expanded, content.contextMode)
            assertEquals(true, content.selectedFileReviewed)
            assertEquals(2, content.files.size)

            viewModel.selectFile("B.kt")
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(false, content.selectedFileReviewed)
            assertEquals(setOf("A.kt"), content.reviewedPaths)
            assertEquals("Hunk 1 of 1", content.selectedHunkPositionLabel)

            viewModel.prepareForSourceSwitch()
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(emptySet<String>(), content.reviewedPaths)
            assertEquals(UnifiedDiffContextMode.Collapsed, content.contextMode)
        }

    @Test fun explicitRefreshClearsReviewedMarkersAndViewerControls() =
        runTest(dispatcher) {
            val files = listOf(file("A.kt"))
            val repository = FakeRepository(ArrayDeque(listOf(Result.success(files), Result.success(files))))
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m")
            advanceUntilIdle()
            viewModel.toggleSelectedFileReviewed()
            viewModel.toggleContextMode()

            viewModel.refresh()
            advanceUntilIdle()

            val content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(emptySet<String>(), content.reviewedPaths)
            assertEquals(UnifiedDiffContextMode.Collapsed, content.contextMode)
            assertEquals("Hunk 1 of 1", content.selectedHunkPositionLabel)
            assertEquals(2, repository.requests.size)
        }

    @Test fun repositoryUpdatesDoNotMutateOpenSnapshotUntilExplicitRefresh() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    ArrayDeque(
                        listOf(
                            Result.success(listOf(file("snapshot.kt"))),
                            Result.success(listOf(file("refreshed.kt"))),
                        ),
                    ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m")
            advanceUntilIdle()

            // Foreground events update other live stores; Changes intentionally owns no event or polling subscription.
            advanceUntilIdle()
            assertEquals("snapshot.kt", (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet.selectedFile.path)
            assertEquals(1, repository.requests.size)

            viewModel.refresh()
            advanceUntilIdle()
            assertEquals("refreshed.kt", (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet.selectedFile.path)
            assertEquals(2, repository.requests.size)
        }

    @Test fun workingTreeEntryLoadsVcsSourcesAndGitDiff() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    vcsInfoResults = ArrayDeque(listOf(Result.success(VcsInfo("feature/review", "main")))),
                    vcsDiffResults = ArrayDeque(listOf(Result.success(listOf(file("working.kt"))))),
                )
            val viewModel = ChangesViewModel(repository)

            viewModel.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()

            assertEquals(ChangeSource.WorkingTree, viewModel.uiState.value.selectedSource)
            assertEquals(
                listOf(ChangeSource.LastTurn, ChangeSource.WorkingTree, ChangeSource.MainBranch),
                viewModel.uiState.value.availableSources,
            )
            assertEquals("working.kt", (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet.selectedFile.path)
            assertEquals(listOf(VcsRequest(VcsDiffMode.WorkingTree, "/w", 12)), repository.vcsRequests)
        }

    @Test fun unsupportedVcsFallsBackToLastTurnButTransientFailureIsRetryable() =
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
            assertEquals("last.kt", (fallback.uiState.value.phase as ChangesPhase.Content).changeSet.selectedFile.path)

            val transient =
                FakeRepository(
                    vcsInfoResults = ArrayDeque(listOf(Result.failure(SessionError.Transport(RuntimeException("offline"))))),
                )
            val retryable = ChangesViewModel(transient)
            retryable.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()
            assertEquals(ChangesPhase.Error("offline"), retryable.uiState.value.phase)
            assertEquals(emptyList<Request>(), transient.requests)
        }

    @Test fun missingVcsDiffEndpointFallsBackToLastTurn() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    results = ArrayDeque(listOf(Result.success(listOf(file("last.kt"))))),
                    vcsInfoResults = ArrayDeque(listOf(Result.success(VcsInfo("feature/review", "main")))),
                    vcsDiffResults = ArrayDeque(listOf(Result.failure(SessionError.Http(404, "missing")))),
                )
            val viewModel = ChangesViewModel(repository)

            viewModel.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()

            assertEquals(ChangeSource.LastTurn, viewModel.uiState.value.selectedSource)
            assertEquals(listOf(ChangeSource.LastTurn), viewModel.uiState.value.availableSources)
            assertEquals("last.kt", (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet.selectedFile.path)
        }

    @Test fun sourceSwitchAndRefreshReloadSelectedStableSnapshotAndResetControls() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(
                    results = ArrayDeque(listOf(Result.success(listOf(file("last.kt"))))),
                    vcsInfoResults =
                        ArrayDeque(
                            listOf(
                                Result.success(VcsInfo("feature/review", "main")),
                                Result.success(VcsInfo("feature/review", "main")),
                            ),
                        ),
                    vcsDiffResults =
                        ArrayDeque(
                            listOf(
                                Result.success(listOf(file("working.kt"))),
                                Result.success(listOf(file("main.kt"))),
                                Result.success(listOf(file("main-refreshed.kt"))),
                            ),
                        ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m", ChangeSource.WorkingTree)
            advanceUntilIdle()
            viewModel.toggleContextMode()
            viewModel.toggleSelectedFileReviewed()

            viewModel.selectSource(ChangeSource.LastTurn)
            advanceUntilIdle()
            viewModel.selectSource(ChangeSource.MainBranch)
            advanceUntilIdle()
            var content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("main.kt", content.selectedFile.path)
            assertEquals(emptySet<String>(), content.reviewedPaths)
            assertEquals(UnifiedDiffContextMode.Collapsed, content.contextMode)

            viewModel.refresh()
            advanceUntilIdle()
            content = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals("main-refreshed.kt", content.selectedFile.path)
            assertEquals(ChangeSource.MainBranch, viewModel.uiState.value.selectedSource)
        }

    @Test fun coversNoTurnNoChangesUnavailablePatchErrorAndRetry() =
        runTest(dispatcher) {
            val noTurn = ChangesViewModel(FakeRepository(ArrayDeque()))
            noTurn.bind("s", "/w", null)
            advanceUntilIdle()
            assertEquals(ChangesPhase.NoTurn, noTurn.uiState.value.phase)

            val binary = FileDiff("binary.png", null, 0, 0, FileDiffStatus.Modified)
            val repository =
                FakeRepository(
                    ArrayDeque(
                        listOf(
                            Result.success(emptyList()),
                            Result.success(listOf(binary)),
                            Result.failure(RuntimeException("offline")),
                            Result.success(listOf(file("ok.kt"))),
                        ),
                    ),
                )
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("s", "/w", "m")
            advanceUntilIdle()
            assertEquals(ChangesPhase.NoChanges, viewModel.uiState.value.phase)
            viewModel.retry()
            advanceUntilIdle()
            val binaryContent = (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet
            assertEquals(binary, binaryContent.selectedFile)
            assertEquals(false, binaryContent.selectedFileHasPreview)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(ChangesPhase.Error("offline"), viewModel.uiState.value.phase)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(file("ok.kt"), (viewModel.uiState.value.phase as ChangesPhase.Content).changeSet.selectedFile)
        }

    @Test fun reviewChangesNavigationCapturesSessionWorktreeAndLatestCompletedUserMessage() {
        val state =
            SessionChatUiState(
                "s",
                "Session",
                SessionChatTimelineState.Ready,
                messages =
                    listOf(
                        row("user-1", MessageRole.User),
                        row("assistant", MessageRole.Assistant),
                        row("pending", MessageRole.User, true),
                    ),
            )
        assertEquals(
            ChangesNavigationTarget("s", "/repo", "user-1"),
            state.changesNavigationTarget("/repo"),
        )
    }

    @Test fun reviewChangesPillUsesLatestCompletedTurnAndLastTurnSource() {
        val state =
            SessionChatUiState(
                "s",
                "Session",
                SessionChatTimelineState.Ready,
                messages = listOf(row("user-1", MessageRole.User), row("assistant", MessageRole.Assistant)),
                reviewChangesMessageId = "user-1",
                reviewChangesSummary = ReviewChangesSummary(2, 4, 2),
            )

        assertEquals(
            ChangesNavigationTarget("s", "/repo", "user-1", ChangeSource.LastTurn),
            state.reviewChangesNavigationTarget("/repo"),
        )
    }

    @Test fun reviewChangesPillHidesForPendingLatestTurnOrUnavailableCount() {
        val state =
            SessionChatUiState(
                "s",
                "Session",
                SessionChatTimelineState.Ready,
                messages =
                    listOf(
                        row("user-1", MessageRole.User),
                        row("assistant", MessageRole.Assistant),
                        row("user-2", MessageRole.User, true),
                    ),
                reviewChangesSummary = ReviewChangesSummary(3, 4, 2),
            )

        assertEquals(null, state.reviewChangesNavigationTarget("/repo"))
        assertFalse(ReviewChangesPillVisibility.shouldShow(null, isPinnedToEnd = true, showScrollToEnd = false))
        assertFalse(ReviewChangesPillVisibility.shouldShow(state.reviewChangesSummary, isPinnedToEnd = false, showScrollToEnd = true))
        assertTrue(ReviewChangesPillVisibility.shouldShow(state.reviewChangesSummary, isPinnedToEnd = true, showScrollToEnd = false))
    }

    @Test fun reviewChangesPillFormatsSummaryAndOmitsZeroLineTotals() {
        assertEquals("1 file  +12  −3", reviewChangesPillSummary(1, 12, 3))
        assertEquals("5 files  +12", reviewChangesPillSummary(5, 12, 0))
        assertEquals("5 files  −3", reviewChangesPillSummary(5, 0, 3))
        assertEquals("5 files", reviewChangesPillSummary(5, 0, 0))

        assertEquals("Review changes, 1 file, 1 addition, 1 deletion", reviewChangesPillAccessibilityLabel(1, 1, 1))
        assertEquals("Review changes, 5 files, 12 additions", reviewChangesPillAccessibilityLabel(5, 12, 0))
        assertEquals("Review changes, 5 files, 3 deletions", reviewChangesPillAccessibilityLabel(5, 0, 3))
        assertEquals("Review changes, 5 files", reviewChangesPillAccessibilityLabel(5, 0, 0))
    }

    @Test fun reviewChangesPillPreviewMatrixCoversLightAndDarkStates() {
        assertEquals(8, ReviewChangesPillPreviewCase.entries.size)
        assertEquals(setOf(false, true), ReviewChangesPillPreviewCase.entries.map { it.darkTheme }.toSet())
        assertEquals(setOf(false, true), ReviewChangesPillPreviewCase.entries.map { it.enabled }.toSet())
        assertEquals(setOf(1, 4), ReviewChangesPillPreviewCase.entries.map { it.changedFileCount }.toSet())
        assertTrue(ReviewChangesPillPreviewCase.entries.any { it.additions > 0 && it.deletions > 0 })
        assertTrue(ReviewChangesPillPreviewCase.entries.any { it.additions > 0 && it.deletions == 0 })
        assertTrue(ReviewChangesPillPreviewCase.entries.any { it.additions == 0 && it.deletions > 0 })
        assertTrue(ReviewChangesPillPreviewCase.entries.any { it.additions == 0 && it.deletions == 0 })
    }

    private fun row(
        id: String,
        role: MessageRole,
        pending: Boolean = false,
    ) = SessionChatMessageItem(id, role, "", "", pending)

    private suspend fun TestScope.loadedViewModel(vararg paths: String): ChangesViewModel {
        val files =
            paths.mapIndexed { index, path ->
                file(
                    path,
                    status =
                        if (index == 1) {
                            FileDiffStatus.Added
                        } else {
                            FileDiffStatus.Modified
                        },
                )
            }
        val viewModel = ChangesViewModel(FakeRepository(ArrayDeque(listOf(Result.success(files)))))
        viewModel.bind("s", "/w", "m")
        advanceUntilIdle()
        return viewModel
    }

    private fun file(
        path: String,
        additions: Int = 1,
        deletions: Int = 1,
        status: FileDiffStatus = FileDiffStatus.Modified,
    ) = FileDiff(path, "@@ -1,1 +1,1 @@\n-old\n+new", additions, deletions, status)
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
        requests +=
            Request(id, messageId, directory)
        return results.removeFirst().getOrThrow()
    }

    override suspend fun getVcsInfo(directory: String?): VcsInfo =
        if (vcsInfoResults.isEmpty()) {
            throw SessionError.Http(404, "unsupported")
        } else {
            vcsInfoResults.removeFirst().getOrThrow()
        }

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
