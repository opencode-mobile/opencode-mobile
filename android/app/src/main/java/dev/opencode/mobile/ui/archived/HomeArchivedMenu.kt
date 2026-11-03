package dev.opencode.mobile.ui.archived

import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.session.ArchivedSessionList
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Home menu's "Archived sessions" entry: its scope and, once counted, how many are archived there. */
data class HomeArchivedMenuEntry(
    val scopeId: String,
    val scopeTitle: UiText,
    /** Null while counting, or when the count failed. */
    val count: Int? = null,
)

/**
 * Counts the archived sessions of Home's scope for the ⋯ menu entry. [state] is null on servers
 * that cannot list and restore archived sessions, so the menu leaves the entry out.
 */
class HomeArchivedMenu(
    private val sessionRepository: SessionRepository,
    private val projects: StateFlow<List<HomeProject>>,
    private val scope: CoroutineScope,
    private val supported: () -> Boolean,
) {
    private val _state = MutableStateFlow<HomeArchivedMenuEntry?>(null)
    val state: StateFlow<HomeArchivedMenuEntry?> = _state.asStateFlow()
    private var job: Job? = null

    /** Recounts for Home scope [scopeId]; Home calls it as the menu opens. */
    fun refresh(
        scopeId: String,
        worktreesEnabled: Boolean,
    ) {
        job?.cancel()
        if (!supported()) {
            _state.value = null
            return
        }
        val homeProjects = projects.value
        val resolved = HomeScope.resolve(scopeId, homeProjects, worktreesEnabled) ?: HomeScope.All
        val title = archivedScopeTitle(resolved, homeProjects)
        val previous = _state.value?.takeIf { it.scopeId == scopeId }
        _state.value = HomeArchivedMenuEntry(scopeId, title, previous?.count)
        job =
            scope.launch {
                val count =
                    try {
                        count(resolved, homeProjects)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                _state.value = HomeArchivedMenuEntry(scopeId, title, count)
            }
    }

    private suspend fun count(
        resolved: HomeScope,
        homeProjects: List<HomeProject>,
    ): Int {
        val inScope =
            when (resolved) {
                HomeScope.All -> homeProjects
                is HomeScope.Project -> homeProjects.filter { it.key == resolved.key }
                is HomeScope.Worktree -> homeProjects.filter { it.key == resolved.projectKey }
            }
        val sessions =
            coroutineScope {
                inScope
                    .map { project -> async { ArchivedSessionList.load(sessionRepository, project.memberPaths) } }
                    .awaitAll()
                    .flatten()
            }
        val worktree = (resolved as? HomeScope.Worktree)?.directory
        return ArchivedSessionList
            .sorted(sessions)
            .count { session ->
                worktree == null || session.directory?.let(ServerPathPolicy::canonical) == ServerPathPolicy.canonical(worktree)
            }
    }
}
