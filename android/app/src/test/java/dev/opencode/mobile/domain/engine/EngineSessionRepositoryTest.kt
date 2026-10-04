package dev.opencode.mobile.domain.engine

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineSessionRepositoryTest {
    @Test
    fun delegatesToOpenCodeWhenProfileIsOpenCode() =
        runTest {
            val opencode = RecordingSessionRepository("opencode")
            val t3 = RecordingSessionRepository("t3")
            val repository = repository(opencode, t3, engine = ConnectionEngine.OPENCODE)
            val sessions = repository.listSessions("/repo")
            assertEquals(listOf("opencode"), sessions.map { it.id })
            assertEquals(1, opencode.listCalls)
            assertEquals(0, t3.listCalls)
        }

    @Test
    fun delegatesToT3WhenProfileIsT3() =
        runTest {
            val opencode = RecordingSessionRepository("opencode")
            val t3 = RecordingSessionRepository("t3")
            val repository = repository(opencode, t3, engine = ConnectionEngine.T3)
            val sessions = repository.listSessions("/repo")
            assertEquals(listOf("t3"), sessions.map { it.id })
            assertEquals(0, opencode.listCalls)
            assertEquals(1, t3.listCalls)
        }

    private suspend fun repository(
        opencode: SessionRepository,
        t3: SessionRepository,
        engine: ConnectionEngine,
    ): EngineSessionRepository {
        val profile =
            ConnectionProfile(
                baseUrl = "http://127.0.0.1:8787",
                username = if (engine == ConnectionEngine.T3) ConnectionEngine.T3_USERNAME else "opencode",
                password = "secret",
                engine = engine,
            )
        val manager = ActiveConnectionManager(SingleProfileStore(profile))
        manager.ensureLoaded()
        return EngineSessionRepository.forTest(opencode, t3, manager)
    }
}

private class SingleProfileStore(
    private var profile: ConnectionProfile?,
) : ConnectionProfileStore {
    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() {
        profile = null
    }
}

private class RecordingSessionRepository(
    private val tag: String,
) : SessionRepository {
    var listCalls = 0

    override suspend fun listSessions(directory: String?): List<Session> {
        listCalls += 1
        return listOf(Session(id = tag, title = tag, directory = directory))
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> = emptyList()

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
    ) = emptyList<dev.opencode.mobile.domain.session.SessionTodo>()

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

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ) = emptyList<dev.opencode.mobile.domain.session.FileDiff>()

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
