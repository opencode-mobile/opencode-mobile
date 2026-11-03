package dev.opencode.mobile.domain.home

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A Home session as the Worktrees screen sees it. */
data class HomeWorktreeSession(
    val id: String,
    val title: String,
    /** Canonical directory the session runs in. */
    val directory: String,
    /** Running or retrying. */
    val isRunning: Boolean,
)

/** Home's projects and sessions, published for the Worktrees screen. */
data class HomeWorktreesSnapshot(
    val projects: List<HomeProject> = emptyList(),
    val sessions: List<HomeWorktreeSession> = emptyList(),
) {
    fun sessionsIn(directory: String): List<HomeWorktreeSession> {
        val canonical = ServerPathPolicy.canonical(directory.trim())
        return sessions.filter { it.directory == canonical }
    }

    /** Worktrees other than main checkouts, across every project. */
    val worktreeCount: Int
        get() = projects.sumOf { project -> project.worktrees.count { !it.isMain } }

    val projectsWithWorktrees: Int
        get() = projects.count { project -> project.worktrees.any { !it.isMain } }
}

/** What the Worktrees screen asks Home to do. */
sealed interface HomeWorktreeRequest {
    /** Switch Home's scope picker to the worktree. */
    data class ShowSessions(
        val projectKey: String,
        val directory: String,
    ) : HomeWorktreeRequest

    /** Start a new session in the worktree. */
    data class NewSession(
        val directory: String,
    ) : HomeWorktreeRequest

    /** The server removed the worktree: list the project's worktrees again. */
    data class Removed(
        val projectKey: String,
        val directory: String,
    ) : HomeWorktreeRequest
}

/**
 * Connects Home and the Worktrees screen, which is its own route: Home publishes its projects
 * and sessions, the Worktrees screen sends requests back. Worktrees removed from this app are
 * kept, per connection, so their sessions stay on Home marked as removed.
 */
@Singleton
class HomeWorktreesFeed
    @Inject
    constructor() {
        private val _snapshot = MutableStateFlow(HomeWorktreesSnapshot())
        val snapshot: StateFlow<HomeWorktreesSnapshot> = _snapshot.asStateFlow()

        private val _removed = MutableStateFlow<Map<String, String>>(emptyMap())

        /** Removed worktrees: canonical directory to project key. */
        val removed: StateFlow<Map<String, String>> = _removed.asStateFlow()

        private val _requests = Channel<HomeWorktreeRequest>(Channel.BUFFERED)
        val requests: Flow<HomeWorktreeRequest> = _requests.receiveAsFlow()

        fun publish(snapshot: HomeWorktreesSnapshot) {
            _snapshot.value = snapshot
        }

        fun send(request: HomeWorktreeRequest) {
            if (request is HomeWorktreeRequest.Removed) {
                val directory = ServerPathPolicy.canonical(request.directory.trim())
                _removed.update { it + (directory to request.projectKey) }
            }
            _requests.trySend(request)
        }

        /** Another server: nothing of the last one applies. */
        fun clear() {
            _snapshot.value = HomeWorktreesSnapshot()
            _removed.value = emptyMap()
        }
    }
