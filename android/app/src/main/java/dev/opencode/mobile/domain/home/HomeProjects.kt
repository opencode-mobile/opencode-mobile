package dev.opencode.mobile.domain.home

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.project.Project

/** The server's current project for one added path. */
data class ResolvedHomePath(
    val projectId: String,
    /** The project's main checkout (v1 `worktree`, v2 `canonical`). */
    val canonicalWorktree: String,
    val name: String? = null,
    val isRepository: Boolean = false,
) {
    /** Paths whose project ids are equal and mergeable become one Home project. */
    val isMergeable: Boolean
        get() = projectId.isNotBlank() && projectId != HomeProjectGrouping.GLOBAL_PROJECT_ID

    companion object {
        fun from(project: Project): ResolvedHomePath {
            val canonical = ServerPathPolicy.canonical(project.worktree.trim())
            val mergeable = project.id.isNotBlank() && project.id != HomeProjectGrouping.GLOBAL_PROJECT_ID
            return ResolvedHomePath(
                projectId = project.id,
                canonicalWorktree = canonical,
                name = project.name?.trim()?.takeIf { it.isNotEmpty() },
                // v2 locations carry no vcs; grouping also counts a known branch.
                isRepository = project.isRepository,
            )
        }
    }
}

data class HomeWorktree(
    val directory: String,
    /** Null when unknown or detached HEAD. */
    val branch: String? = null,
    val isMain: Boolean = false,
) {
    val folderName: String
        get() = ServerPathPolicy.name(directory).ifBlank { directory }
}

data class HomeProject(
    /** Project id, or `path:<added path>` when the path does not merge. */
    val key: String,
    val projectId: String?,
    val displayName: String,
    /** Added paths in added-list order. */
    val memberPaths: List<String>,
    val isRepository: Boolean,
    /** Main checkout first, then by folder name. */
    val worktrees: List<HomeWorktree>,
    /**
     * The server reports version control for the path even when the project does not merge
     * (a git repository without a commit resolves to `global`).
     */
    val hasVcs: Boolean = isRepository,
) {
    /** Location for project-level requests. */
    val primaryPath: String
        get() = memberPaths.first()

    val hasManyWorktrees: Boolean
        get() = worktrees.size > 1

    /** Where a tapped New session starts: the main checkout, else the primary added path. */
    val mainCheckoutDirectory: String
        get() = worktrees.firstOrNull { it.isMain }?.directory ?: primaryPath

    fun worktree(directory: String?): HomeWorktree? {
        val canonical = directory?.trim()?.takeIf { it.isNotEmpty() }?.let(ServerPathPolicy::canonical) ?: return null
        return worktrees.firstOrNull { it.directory == canonical }
    }

    fun contains(path: String): Boolean {
        val canonical = ServerPathPolicy.canonical(path.trim())
        return memberPaths.any { ServerPathPolicy.canonical(it) == canonical } || worktree(canonical) != null
    }
}

object HomeProjectGrouping {
    const val GLOBAL_PROJECT_ID = "global"
    const val PATH_KEY_PREFIX = "path:"

    /**
     * One Home project per mergeable project id (order of its first member), and one per path
     * whose project is `global` or did not resolve. [inventories] are the listed worktree
     * directories per added path; [branches] are known branch names per worktree directory.
     */
    fun group(
        addedPaths: List<String>,
        resolved: Map<String, ResolvedHomePath>,
        inventories: Map<String, Set<String>> = emptyMap(),
        branches: Map<String, String> = emptyMap(),
    ): List<HomeProject> {
        val membersByKey = LinkedHashMap<String, MutableList<String>>()
        for (path in addedPaths.distinct()) {
            membersByKey.getOrPut(keyFor(path, resolved[path])) { mutableListOf() } += path
        }
        return membersByKey.map { (key, members) ->
            val resolution = members.firstNotNullOfOrNull { resolved[it] }
            val mergeable = resolution?.isMergeable == true
            val primary = members.first()
            val worktrees =
                if (mergeable && resolution != null) {
                    repositoryWorktrees(resolution.canonicalWorktree, members, inventories, branches)
                } else {
                    val directory = ServerPathPolicy.canonical(primary.trim())
                    listOf(HomeWorktree(directory = directory, isMain = true))
                }
            // The server reports a VCS (v1), or a worktree has a known branch (v2 locations carry no vcs).
            val isRepository = mergeable && (resolution?.isRepository == true || worktrees.any { it.branch != null })
            HomeProject(
                key = key,
                projectId = resolution?.projectId?.takeIf { mergeable },
                displayName = displayName(resolution, primary, mergeable),
                memberPaths = members,
                isRepository = isRepository,
                worktrees = worktrees,
                hasVcs = isRepository || resolution?.isRepository == true,
            )
        }
    }

    fun keyFor(
        path: String,
        resolution: ResolvedHomePath?,
    ): String = if (resolution?.isMergeable == true) resolution.projectId else PATH_KEY_PREFIX + path

    private fun displayName(
        resolution: ResolvedHomePath?,
        primaryPath: String,
        mergeable: Boolean,
    ): String {
        resolution?.name?.let { return it }
        val canonical = resolution?.canonicalWorktree?.takeIf { mergeable && it.isNotBlank() && it != "/" }
        val folder = canonical?.let(ServerPathPolicy::name)?.takeIf { it.isNotBlank() }
        return folder ?: ServerPathPolicy.name(primaryPath).ifBlank { primaryPath }
    }

    private fun repositoryWorktrees(
        canonicalWorktree: String,
        members: List<String>,
        inventories: Map<String, Set<String>>,
        branches: Map<String, String>,
    ): List<HomeWorktree> {
        val main = ServerPathPolicy.canonical(canonicalWorktree)
        val directories = LinkedHashSet<String>()
        if (main.isNotBlank()) directories += main
        members.forEach { directories += ServerPathPolicy.canonical(it.trim()) }
        members.forEach { member -> inventories[member].orEmpty().forEach { directories += ServerPathPolicy.canonical(it.trim()) } }
        return directories
            .filter { it.isNotBlank() }
            .map { HomeWorktree(directory = it, branch = branches[it], isMain = it == main) }
            .sortedWith(compareBy<HomeWorktree>({ !it.isMain }, { it.folderName.lowercase() }, { it.directory }))
    }

    /** Branch as Home shows it: null when blank or detached (`HEAD`). */
    fun normalizedBranch(branch: String?): String? = branch?.trim()?.takeIf { it.isNotEmpty() && it != "HEAD" }
}

/** What Home lists: everything, one project, or one worktree of a project. */
sealed class HomeScope {
    data object All : HomeScope()

    data class Project(
        val key: String,
    ) : HomeScope()

    data class Worktree(
        val projectKey: String,
        val directory: String,
    ) : HomeScope()

    companion object {
        const val ALL_ID = "all"
        const val WORKTREE_ID_PREFIX = "worktree:"
        private const val WORKTREE_ID_SEPARATOR = '\u001F'

        /** `worktree:<project key>\u001F<directory>`. */
        fun worktreeId(
            projectKey: String,
            directory: String,
        ): String = WORKTREE_ID_PREFIX + projectKey + WORKTREE_ID_SEPARATOR + ServerPathPolicy.canonical(directory)

        fun id(scope: HomeScope): String =
            when (scope) {
                All -> ALL_ID
                is Project -> scope.key
                is Worktree -> worktreeId(scope.projectKey, scope.directory)
            }

        /**
         * Reads a scope id against the current projects. An added path (an older scope, or a
         * just-added folder) maps to the project it belongs to, and so does a worktree of a
         * project that no longer lists more than one, or any worktree while [worktreesEnabled]
         * is off. Null when nothing matches.
         */
        fun resolve(
            id: String,
            projects: List<HomeProject>,
            worktreesEnabled: Boolean = true,
        ): HomeScope? {
            if (id == ALL_ID) return All
            projects.firstOrNull { it.key == id }?.let { return Project(it.key) }
            if (id.startsWith(WORKTREE_ID_PREFIX)) {
                val body = id.removePrefix(WORKTREE_ID_PREFIX)
                val projectKey = body.substringBefore(WORKTREE_ID_SEPARATOR, missingDelimiterValue = "")
                val directory = ServerPathPolicy.canonical(body.substringAfter(WORKTREE_ID_SEPARATOR))
                // The directory decides: a checkout whose project key changed still finds its project.
                val project =
                    projects.firstOrNull { it.key == projectKey && it.worktree(directory) != null }
                        ?: projects.firstOrNull { it.worktree(directory) != null }
                        ?: return null
                return if (worktreesEnabled && project.hasManyWorktrees) Worktree(project.key, directory) else Project(project.key)
            }
            val canonical = ServerPathPolicy.canonical(id.trim())
            projects
                .firstOrNull { project -> project.memberPaths.any { ServerPathPolicy.canonical(it) == canonical } }
                ?.let { return Project(it.key) }
            return null
        }
    }
}

object HomeSessionLocation {
    data class Part(
        val text: String,
        val isBranch: Boolean = false,
    )

    /**
     * Row subtitle parts for a session of a repository project: the project name (All scope
     * only), the branch, and the worktree folder when it is not the main checkout or the branch
     * is unknown. Null for a non-repository project, which keeps its path subtitle, and for
     * every project while [worktreesEnabled] is off.
     */
    fun parts(
        project: HomeProject,
        sessionDirectory: String?,
        includeProjectName: Boolean,
        worktreesEnabled: Boolean = true,
    ): List<Part>? {
        if (!worktreesEnabled || !project.isRepository) return null
        val directory =
            sessionDirectory?.trim()?.takeIf { it.isNotEmpty() }?.let(ServerPathPolicy::canonical)
                ?: ServerPathPolicy.canonical(project.primaryPath)
        val worktree = project.worktree(directory) ?: HomeWorktree(directory = directory)
        return buildList {
            if (includeProjectName) add(Part(project.displayName))
            worktree.branch?.let { add(Part(it, isBranch = true)) }
            if (!worktree.isMain || worktree.branch == null) add(Part(worktree.folderName))
        }
    }

    fun text(parts: List<Part>): String = parts.joinToString(SEPARATOR) { it.text }

    const val SEPARATOR = " · "
}

/** Copy shared by the scope picker, dock, and Run in chooser. */
object HomeWorktreeCopy {
    const val MAIN_CHECKOUT = "main checkout"
    const val RUN_IN = "Run in"
    const val OTHER_PROJECTS = "Other projects"
    const val SHOW_FEWER = "Show fewer"
    const val EDIT = "Edit"
    const val DONE = "Done"
    const val REORDER_NOTE = "Drag to reorder. Worktrees stay with their project."

    fun showAll(count: Int): String = "Show all $count"

    fun worktreeCount(count: Int): String = "$count worktrees"

    fun allWorktrees(count: Int): String = "All $count worktrees"

    fun runningCount(count: Int): String = "$count running"

    fun joinedProject(displayName: String): String = "Same repo as $displayName. Showing it as a worktree there."

    fun worktreeTitle(worktree: HomeWorktree): String = worktree.branch ?: worktree.folderName

    fun worktreeSubtitle(worktree: HomeWorktree): String = if (worktree.isMain) MAIN_CHECKOUT else worktree.folderName

    /** Dock subtitle for a worktree scope: `branch · folder`, the folder alone, or the branch alone on the main checkout. */
    fun worktreePill(worktree: HomeWorktree): String {
        val branch = worktree.branch ?: return worktree.folderName
        return if (worktree.isMain) branch else "$branch${HomeSessionLocation.SEPARATOR}${worktree.folderName}"
    }
}

/**
 * Expand and edit state of the project picker: at most one project shows its worktrees, a
 * project with more than [COLLAPSED_WORKTREE_LIMIT] shows the rest on request, and Edit
 * collapses everything while projects reorder.
 */
data class HomeProjectPickerState(
    val expandedProjectKey: String? = null,
    val showAllProjectKeys: Set<String> = emptySet(),
    val isEditing: Boolean = false,
) {
    /** Opens [key] and closes any other; the open one closes. */
    fun toggleExpanded(key: String): HomeProjectPickerState = copy(expandedProjectKey = if (expandedProjectKey == key) null else key)

    fun toggleShowAll(key: String): HomeProjectPickerState =
        copy(showAllProjectKeys = if (key in showAllProjectKeys) showAllProjectKeys - key else showAllProjectKeys + key)

    /** Edit starts and ends collapsed. */
    fun setEditing(editing: Boolean): HomeProjectPickerState =
        HomeProjectPickerState(expandedProjectKey = null, showAllProjectKeys = emptySet(), isEditing = editing)

    fun isExpanded(key: String): Boolean = !isEditing && expandedProjectKey == key

    /** Worktrees listed under project [key] while it is expanded: all, or the first few. */
    fun <T> visibleWorktrees(
        key: String,
        worktrees: List<T>,
    ): List<T> =
        if (worktrees.size <= COLLAPSED_WORKTREE_LIMIT || key in showAllProjectKeys) {
            worktrees
        } else {
            worktrees.take(COLLAPSED_WORKTREE_LIMIT)
        }

    /** "Show all N" / "Show fewer" under expanded project [key] with more than the limit; null otherwise. */
    fun showAllLabel(
        key: String,
        worktreeCount: Int,
    ): String? {
        if (worktreeCount <= COLLAPSED_WORKTREE_LIMIT) return null
        return if (key in showAllProjectKeys) HomeWorktreeCopy.SHOW_FEWER else HomeWorktreeCopy.showAll(worktreeCount)
    }

    companion object {
        const val COLLAPSED_WORKTREE_LIMIT = 5

        /**
         * State each time the picker opens: a worktree scope starts with its project expanded,
         * showing all when that worktree is past the first few. Anything else starts collapsed.
         */
        fun initial(
            selectedScope: HomeScope?,
            projects: List<HomeProject>,
        ): HomeProjectPickerState {
            val worktreeScope = selectedScope as? HomeScope.Worktree ?: return HomeProjectPickerState()
            val project = projects.firstOrNull { it.key == worktreeScope.projectKey } ?: return HomeProjectPickerState()
            val index = project.worktrees.indexOfFirst { it.directory == ServerPathPolicy.canonical(worktreeScope.directory) }
            return HomeProjectPickerState(
                expandedProjectKey = project.key,
                showAllProjectKeys = if (index >= COLLAPSED_WORKTREE_LIMIT) setOf(project.key) else emptySet(),
            )
        }
    }
}
