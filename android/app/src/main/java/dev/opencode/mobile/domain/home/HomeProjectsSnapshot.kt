package dev.opencode.mobile.domain.home

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A path saved to the Connection Profile outside Home; [resolution] is null when it was already there. */
data class HomeProjectAdded(
    val worktree: String,
    val resolution: ResolvedHomePath?,
)

/**
 * The Home projects (with their worktrees in Home order) as Home last grouped them, so other
 * screens can name a session's project and worktrees without regrouping. Home publishes;
 * readers treat an empty list as unknown.
 */
@Singleton
class HomeProjectsSnapshot
    @Inject
    constructor() {
        private val _projects = MutableStateFlow<List<HomeProject>>(emptyList())
        val projects: StateFlow<List<HomeProject>> = _projects.asStateFlow()

        private val _worktreeRefreshRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

        /** Directories whose project's worktrees changed elsewhere (a checkout created in Session Chat). */
        val worktreeRefreshRequests: SharedFlow<String> = _worktreeRefreshRequests.asSharedFlow()

        private val _projectAddedRequests = MutableSharedFlow<HomeProjectAdded>(extraBufferCapacity = 8)

        /** Paths the New session draft's Add project sheet saved (#1021); Home lists them. */
        val projectAddedRequests: SharedFlow<HomeProjectAdded> = _projectAddedRequests.asSharedFlow()

        fun publish(projects: List<HomeProject>) {
            _projects.value = projects
        }

        /** Asks Home to list the worktrees of [directory]'s project again. */
        fun requestWorktreeRefresh(directory: String) {
            _worktreeRefreshRequests.tryEmit(directory)
        }

        /** Asks Home to list [added], which another screen saved to the Connection Profile. */
        fun requestProjectAdded(added: HomeProjectAdded) {
            _projectAddedRequests.tryEmit(added)
        }

        /** The project whose worktrees (or added paths) include [directory]. */
        fun projectContaining(directory: String?): HomeProject? {
            val canonical = directory?.trim()?.takeIf { it.isNotEmpty() }?.let(ServerPathPolicy::canonical) ?: return null
            return _projects.value.firstOrNull { it.worktree(canonical) != null }
                ?: _projects.value.firstOrNull { it.contains(canonical) }
        }
    }
