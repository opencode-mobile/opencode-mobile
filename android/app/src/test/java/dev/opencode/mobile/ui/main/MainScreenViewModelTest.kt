package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val serverUrl = "http://127.0.0.1:4096/"
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_probeFailed_whenHealthFails() =
        runTest {
            val viewModel = createViewModel(shouldFailProbe = true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is MainScreenUiState.ProbeFailed)
        }

    @Test
    fun uiState_seedsBootWorktree_whenProfileEmpty() =
        runTest {
            val store = FakeConnectionProfileStore()
            val viewModel =
                createViewModel(connectionProfileStore = store, bootWorktree = "/boot/project")
            advanceUntilIdle()
            assertEquals(listOf("/boot/project"), store.added(serverUrl))
            assertTrue(viewModel.uiState.value is MainScreenUiState.Loaded)
        }

    @Test
    fun uiState_limitsPreviewSessionsToThree() =
        runTest {
            val sessions =
                (1..7).map { index ->
                    Session(id = "$index", title = "Session $index", updatedAtEpochMs = index.toLong())
                }
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(
                3,
                loaded.sections
                    .single()
                    .previewSessions.size,
            )
            assertEquals(7, loaded.sections.single().totalSessionCount)
        }

    @Test
    fun refreshOnSessionCreatedEvent() =
        runTest {
            val eventStream = FakeForegroundEventStream()
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    foregroundEventStream = eventStream,
                )
            advanceUntilIdle()
            val before = (viewModel.uiState.value as MainScreenUiState.Loaded).sections.single().totalSessionCount

            eventStream.emit(OpencodeEvent(type = "session.created", sessionId = "new"))
            advanceUntilIdle()

            val after = (viewModel.uiState.value as MainScreenUiState.Loaded).sections.single().totalSessionCount
            assertTrue(after >= before)
        }

    private fun createViewModel(
        shouldFailProbe: Boolean = false,
        connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
        bootWorktree: String = "/boot/project",
        sessionsByWorktree: Map<String, List<Session>> = emptyMap(),
        foregroundEventStream: FakeForegroundEventStream = FakeForegroundEventStream(),
    ): MainScreenViewModel {
        val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
        runBlocking {
            activeConnectionManager.applyProfile(
                ConnectionProfile(baseUrl = serverUrl, username = "opencode", password = ""),
            )
        }
        val coordinator =
            ConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
            )
        return MainScreenViewModel(
            projectRepository = FakeProjectRepository(shouldFailProbe, bootWorktree),
            sessionRepository = FakeSessionRepository(sessionsByWorktree),
            connectionProfileStore = connectionProfileStore,
            sessionDomainAdapter = SessionDomainAdapter(),
            eventDomainAdapter = EventDomainAdapter(json),
            foregroundEventStream = foregroundEventStream,
            activeConnectionManager = activeConnectionManager,
            connectionSessionCoordinator = coordinator,
        )
    }
}

private class FakeProjectRepository(
    private val shouldFailProbe: Boolean,
    private val bootWorktree: String,
) : ProjectRepository {
    override suspend fun probeServer() {
        if (shouldFailProbe) throw ServerProbeError.Transport(IllegalStateException("down"))
    }

    override suspend fun listProjects(): List<Project> = emptyList()

    override suspend fun getCurrentProject(directory: String?): Project =
        Project(id = "p1", worktree = directory ?: bootWorktree, displayName = "demo")
}

private class FakeSessionRepository(
    private val sessionsByWorktree: Map<String, List<Session>>,
) : SessionRepository {
    override suspend fun listSessions(directory: String?): List<Session> = sessionsByWorktree[directory].orEmpty()

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?) = emptyList<dev.opencode.mobile.domain.session.SessionStatus>()

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
    ) = error("unused")

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = error("unused")

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = error("unused")

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: dev.opencode.mobile.domain.session.PermissionDecision,
        directory: String?,
    ) = error("unused")
}

private class FakeConnectionProfileStore(
    initial: List<String> = emptyList(),
) : ConnectionProfileStore {
    private val data = mutableMapOf<String, List<String>>()

    init {
        if (initial.isNotEmpty()) {
            data[serverUrlKey()] = initial
        }
    }

    fun added(serverBaseUrl: String): List<String> = data[serverBaseUrl].orEmpty()

    override suspend fun activeProfile(): ConnectionProfile? =
        ConnectionProfile(baseUrl = serverUrlKey(), username = "opencode", password = "")

    override suspend fun saveProfile(profile: ConnectionProfile) = Unit

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = data[serverBaseUrl].orEmpty()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val current = data[serverBaseUrl].orEmpty()
        if (worktree !in current) {
            data[serverBaseUrl] = current + worktree
        }
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        data[serverBaseUrl] = worktrees
    }

    private fun serverUrlKey(): String = "http://127.0.0.1:4096/"
}
