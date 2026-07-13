package dev.opencode.mobile.ui.changes

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.ui.sessionchat.ChangesNavigationTarget
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsFirstPathSortedFileAndScopesRequest() =
        runTest(dispatcher) {
            val repository = FakeRepository(ArrayDeque(listOf(Result.success(listOf(file("file2.kt"), file("file10.kt"))))))
            val viewModel = ChangesViewModel(repository)
            viewModel.bind("ses-1", "/repo", "msg-1")
            advanceUntilIdle()
            assertEquals(ChangesUiState.Content(file("file10.kt")), viewModel.uiState.value)
            assertEquals(listOf(Request("ses-1", "msg-1", "/repo")), repository.requests)
        }

    @Test fun coversNoTurnNoChangesUnavailablePatchErrorAndRetry() =
        runTest(dispatcher) {
            val noTurn = ChangesViewModel(FakeRepository(ArrayDeque()))
            noTurn.bind("s", "/w", null)
            assertEquals(ChangesUiState.NoTurn, noTurn.uiState.value)

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
            assertEquals(ChangesUiState.NoChanges, viewModel.uiState.value)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(ChangesUiState.UnavailablePatch(binary), viewModel.uiState.value)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(ChangesUiState.Error("offline"), viewModel.uiState.value)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(ChangesUiState.Content(file("ok.kt")), viewModel.uiState.value)
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

    private fun row(
        id: String,
        role: MessageRole,
        pending: Boolean = false,
    ) = SessionChatMessageItem(id, role, "", "", pending)

    private fun file(path: String) = FileDiff(path, "@@ -1,1 +1,1 @@\n-old\n+new", 1, 1, FileDiffStatus.Modified)
}

private data class Request(
    val sessionId: String,
    val messageId: String?,
    val directory: String?,
)

private class FakeRepository(
    private val results: ArrayDeque<Result<List<FileDiff>>>,
) : SessionRepository {
    val requests = mutableListOf<Request>()

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> {
        requests +=
            Request(id, messageId, directory)
        return results.removeFirst().getOrThrow()
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
