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

/**
 * The same folder however the server spells it: macOS links `/tmp`, `/var` and `/etc` into
 * `/private`, and a server can report either form for one worktree.
 */
internal fun worktreePathKey(directory: String): String {
    val canonical = ServerPathPolicy.canonical(directory.trim())
    for (link in SYMLINKED_ROOTS) {
        if (canonical == "/private$link" || canonical.startsWith("/private$link/")) return canonical.removePrefix("/private")
    }
    return canonical
}

private val SYMLINKED_ROOTS = listOf("/tmp", "/var", "/etc")

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
    /**
     * Canonical directories the server listed as worktrees, never a main checkout. Only these can
     * be removed; other non-main entries of a project (an added path the server does not list)
     * are not worktrees the server knows.
     */
    val removableDirectories: Set<String> = emptySet(),
    /** Home has listed worktrees at least once; until then the counts mean nothing. */
    val worktreesLoaded: Boolean = true,
) {
    private val removableKeys: Set<String> by lazy { removableDirectories.mapTo(HashSet(), ::worktreePathKey) }

    /**
     * A project's worktrees the server can remove, one per folder: the server's spelling wins when
     * Home knows the folder under two paths. Never the main checkout, under either spelling.
     */
    fun removableWorktrees(project: HomeProject): List<HomeWorktree> {
        val mains = project.worktrees.filter { it.isMain }.mapTo(HashSet()) { worktreePathKey(it.directory) }
        return project.worktrees
            .filter { !it.isMain }
            .groupBy { worktreePathKey(it.directory) }
            .filterKeys { it in removableKeys && it !in mains }
            .values
            .map { same -> same.firstOrNull { it.directory in removableDirectories } ?: same.first() }
    }

    /** Sessions in [directory], under either spelling of the folder. */
    fun sessionsIn(directory: String): List<HomeWorktreeSession> {
        val key = worktreePathKey(directory)
        return sessions.filter { worktreePathKey(it.directory) == key }
    }

    /** Removable worktrees, across every project. */
    val worktreeCount: Int
        get() = projects.sumOf { project -> removableWorktrees(project).size }

    val projectsWithWorktrees: Int
        get() = projects.count { project -> removableWorktrees(project).isNotEmpty() }
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
