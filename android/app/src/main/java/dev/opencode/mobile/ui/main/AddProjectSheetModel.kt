package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.DirectoryListingPolicy
import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ServerProjectSuggestions
import dev.opencode.mobile.domain.time.RelativeTimeFormatting

/**
 * Add project: a short suggestion sheet, or the full-screen browser when [isBrowsing].
 * The browser is a path field over the folder it names, root chips, and that folder's children.
 */
data class AddProjectSheetUi(
    val fieldText: String,
    /** The folder being listed, or null when the field names nothing usable. */
    val directory: String?,
    /** Name prefix filtering [directory]'s subfolders. */
    val filter: String,
    /** Subfolders of [directory] matching [filter], capped at the first 200. */
    val entries: List<AddProjectFolderUi>,
    /** Non-interactive row under [entries] when more folders matched than are shown. */
    val truncationHint: String?,
    val isLoading: Boolean,
    val listError: String?,
    /** "No subfolders", the no-match message, or "Path not found", when there are no rows. */
    val emptyMessage: String?,
    /** The field's folder does not exist (or is a file). */
    val pathNotFound: Boolean = false,
    val roots: List<AddProjectRootUi>,
    val suggestions: List<AddProjectSuggestionUi>,
    /** False until this presentation has heard back from the server project list. */
    val suggestionsResolved: Boolean = false,
    val showSuggestions: Boolean,
    /** Header over the folder list; only shown with the suggestions section. */
    val folderSectionTitle: String?,
    val upTarget: String?,
    val addTarget: String?,
    /** What the browser's action bar names: [addTarget], else the field's folder, even when missing. */
    val barTarget: AddProjectBarTargetUi? = null,
    /** The folder Return opens: the exact child match, else the only matching row. Null when Return does nothing. */
    val goTarget: String? = null,
    /** The action bar button title; a disabled reason when [addEnabled] is false. */
    val addTitle: String,
    val addEnabled: Boolean,
    val isValidating: Boolean,
    val fieldError: String?,
    /** True while Browse folders has replaced the suggestion sheet with the full-screen browser. */
    val isBrowsing: Boolean,
) {
    companion object {
        const val TITLE = "Add project"
        const val GIT_TAG = "git"
        const val PICK_A_FOLDER = "Pick a folder"
        const val BROWSE_FOLDERS = "Browse folders"
        const val NO_OTHER_PROJECTS = "No other projects on this server."
        const val ALREADY_ON_HOME = "Already on Home"
        const val ADD_HOME_FOLDER = "Add home folder"
        const val ADD_THIS_FOLDER = "Add this folder"
        const val PATH_NOT_FOUND = "Path not found"
        const val ON_HOME = "On Home"
        const val EMPTY_FOLDER = "Empty"
        const val NO_SUBFOLDERS = "No subfolders"
        const val TRUNCATION_HINT =
            "Showing the first ${DirectoryListingPolicy.MAX_VISIBLE_DIRECTORIES} folders. " +
                "Type more of the name to narrow the list."
        const val LIST_ERROR_TITLE = "Unable to list this folder"
        const val LIST_ERROR_MESSAGE = "Check the path and try again."

        fun empty(): AddProjectSheetUi = AddProjectSheetModel.build(AddProjectSheetSession())
    }
}

data class AddProjectFolderUi(
    val name: String,
    val path: String,
    val isOnHome: Boolean,
    /** Visible child folders, once this folder's listing has loaded. */
    val folderCount: Int? = null,
    val isGitRepository: Boolean = false,
    /** "On Home" and the folder count, joined with " · "; null when neither is known. */
    val subtitle: String? = null,
)

/** The folder the browser's action bar would add. */
data class AddProjectBarTargetUi(
    val name: String,
    val path: String,
    val isGitRepository: Boolean,
)

data class AddProjectRootUi(
    val label: String,
    val path: String,
    val selected: Boolean,
)

data class AddProjectSuggestionUi(
    val name: String,
    val path: String,
    val activityLabel: String?,
)

/** One folder listing, cached for the sheet's lifetime. */
sealed interface AddProjectListing {
    data object Loading : AddProjectListing

    /** Every child folder, hidden ones included; the sheet filters, then caps. */
    data class Loaded(
        val entries: List<DirectoryNode>,
        val isGitRepository: Boolean = false,
    ) : AddProjectListing {
        /** Child folders whose name does not start with `.`. */
        val visibleFolderCount: Int
            get() = entries.count { !DirectoryListingPolicy.isHidden(it) }

        constructor(listing: DirectoryListing) : this(listing.folders, listing.isGitRepository)
    }

    /** The folder does not exist, or is a file. */
    data object NotFound : AddProjectListing

    data class Failed(
        val message: String,
    ) : AddProjectListing
}

/** Everything one Add project sheet session knows; [AddProjectSheetModel.build] derives the UI. */
data class AddProjectSheetSession(
    val home: String = "",
    val isOpening: Boolean = true,
    val openError: String? = null,
    val fieldText: String = "",
    val listings: Map<String, AddProjectListing> = emptyMap(),
    /** Folders in `/Volumes` on a POSIX server. */
    val volumes: List<DirectoryNode> = emptyList(),
    val serverProjects: List<Project> = emptyList(),
    /** True once this presentation's project-list fetch has finished, including a failure. */
    val suggestionsResolved: Boolean = false,
    val addedWorktrees: List<String> = emptyList(),
    /** Windows roots of folders listed successfully during this session. */
    val listedRoots: Set<String> = emptySet(),
    val isValidating: Boolean = false,
    val fieldError: String? = null,
    val browsing: Boolean = false,
) {
    val parts: ServerPathPolicy.FieldParts
        get() = ServerPathPolicy.fieldParts(fieldText, home)

    /** [path]'s cached listing, when one has loaded (by the browser or a row summary). */
    fun loadedListing(path: String): AddProjectListing.Loaded? = listings[ServerPathPolicy.canonical(path)] as? AddProjectListing.Loaded

    /** A cached listing holds `.git`, or a server project at [path] is a repository. */
    fun isGitRepository(path: String): Boolean {
        if (loadedListing(path)?.isGitRepository == true) return true
        val canonical = ServerPathPolicy.canonical(path)
        return serverProjects.any { it.isRepository && ServerPathPolicy.canonical(it.worktree) == canonical }
    }

    /** Remembers [directory]'s Windows root once a listing there succeeded. */
    fun withListedRoot(directory: String): AddProjectSheetSession {
        if (!ServerPathPolicy.isWindows(directory)) return this
        val root = ServerPathPolicy.root(directory)
        if (root.isEmpty() || root in listedRoots) return this
        return copy(listedRoots = listedRoots + root)
    }
}

object AddProjectSheetModel {
    const val VOLUMES_PATH = "/Volumes"

    /** Typing pause before the field's new folder is listed. */
    const val LISTING_DEBOUNCE_MS = 250L

    /** Row summaries (folder count, git) listed at once. */
    const val FOLDER_SUMMARY_CONCURRENCY = 4

    fun build(
        session: AddProjectSheetSession,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AddProjectSheetUi {
        val (directory, filter) = session.parts
        val added = session.addedWorktrees.mapTo(HashSet()) { ServerPathPolicy.canonical(it) }
        val listing = directory?.let { session.listings[it] }
        val loaded = (listing as? AddProjectListing.Loaded)?.entries
        val pathNotFound = session.openError == null && listing is AddProjectListing.NotFound
        val windows = directory?.let(ServerPathPolicy::isWindows) == true
        val visible = DirectoryListingPolicy.visibleEntries(loaded.orEmpty(), filter)
        val entries =
            visible.rows
                .map { node ->
                    val isOnHome = ServerPathPolicy.canonical(node.absolutePath) in added
                    val folderCount = session.loadedListing(node.absolutePath)?.visibleFolderCount
                    AddProjectFolderUi(
                        name = node.name,
                        path = node.absolutePath,
                        isOnHome = isOnHome,
                        folderCount = folderCount,
                        isGitRepository = session.isGitRepository(node.absolutePath),
                        subtitle = folderSubtitle(isOnHome, folderCount),
                    )
                }
        val listError =
            when {
                session.openError != null -> session.openError
                listing is AddProjectListing.Failed -> listing.message
                else -> null
            }
        val isLoading =
            listError == null &&
                !pathNotFound &&
                (session.isOpening || (directory != null && (listing == null || listing is AddProjectListing.Loading)))
        val emptyMessage =
            when {
                pathNotFound -> AddProjectSheetUi.PATH_NOT_FOUND
                loaded == null || entries.isNotEmpty() -> null
                filter.isEmpty() -> AddProjectSheetUi.NO_SUBFOLDERS
                else -> "Nothing in ${ServerPathPolicy.name(directory.orEmpty())} starts with \u201C$filter\u201D"
            }
        // The full listing, hidden folders and folders past the cap included.
        val exactChild = loaded?.firstOrNull { filter.isNotEmpty() && it.name.equals(filter, ignoreCase = windows) }?.absolutePath
        val addTarget =
            when {
                loaded == null -> null
                filter.isEmpty() -> directory
                else -> exactChild
            }
        val goTarget =
            when {
                loaded == null || filter.isEmpty() -> null
                exactChild != null -> exactChild
                visible.rows.size == 1 && !visible.isTruncated -> visible.rows.single().absolutePath
                else -> null
            }
        val alreadyAdded = addTarget != null && ServerPathPolicy.canonical(addTarget) in added
        val canAdd = !pathNotFound && directory != null && loaded != null && listError == null && addTarget != null && !alreadyAdded
        val addTitle =
            when {
                pathNotFound -> AddProjectSheetUi.PATH_NOT_FOUND
                directory == null || loaded == null || listError != null -> AddProjectSheetUi.ADD_THIS_FOLDER
                addTarget == null && entries.isNotEmpty() -> AddProjectSheetUi.PICK_A_FOLDER
                addTarget == null -> "No folder named \u201C$filter\u201D"
                alreadyAdded -> AddProjectSheetUi.ALREADY_ON_HOME
                addTarget == session.home -> AddProjectSheetUi.ADD_HOME_FOLDER
                else -> AddProjectSheetUi.ADD_THIS_FOLDER
            }
        val barTarget =
            (addTarget ?: directory)?.let { path ->
                AddProjectBarTargetUi(
                    name = ServerPathPolicy.name(path),
                    path = path,
                    isGitRepository = session.isGitRepository(path),
                )
            }
        val suggestions =
            ServerProjectSuggestions.suggestions(session.serverProjects, session.addedWorktrees).map { project ->
                AddProjectSuggestionUi(
                    name = project.displayName,
                    path = project.worktree,
                    activityLabel = project.activityAtEpochMs?.let { RelativeTimeFormatting.format(it, nowEpochMs) },
                )
            }
        val showSuggestions =
            session.home.isNotEmpty() && directory == session.home && filter.isEmpty() && suggestions.isNotEmpty()
        return AddProjectSheetUi(
            fieldText = session.fieldText,
            directory = directory,
            filter = filter,
            entries = entries,
            truncationHint = if (visible.isTruncated) AddProjectSheetUi.TRUNCATION_HINT else null,
            isLoading = isLoading,
            listError = listError,
            emptyMessage = emptyMessage,
            pathNotFound = pathNotFound,
            roots = roots(session, directory, filter),
            suggestions = suggestions,
            suggestionsResolved = session.suggestionsResolved,
            showSuggestions = showSuggestions,
            folderSectionTitle = if (showSuggestions && directory != null) "In ${ServerPathPolicy.name(directory)}" else null,
            upTarget =
                when {
                    directory == null -> null
                    filter.isNotEmpty() -> directory
                    else -> ServerPathPolicy.parentPath(directory)
                },
            addTarget = addTarget,
            barTarget = barTarget,
            goTarget = goTarget,
            addTitle = addTitle,
            addEnabled = canAdd && !session.isValidating,
            isValidating = session.isValidating,
            fieldError = session.fieldError,
            isBrowsing = session.browsing,
        )
    }

    /**
     * The server cannot list drives, so roots come from what the app has seen: home, then on
     * POSIX `/` and `/Volumes/<name>`, on Windows every root of home, server projects, home
     * projects, and folders listed in this session.
     */
    private fun roots(
        session: AddProjectSheetSession,
        directory: String?,
        filter: String,
    ): List<AddProjectRootUi> {
        val home = session.home
        if (home.isEmpty()) return emptyList()
        val candidates = mutableListOf("Home" to home)
        if (ServerPathPolicy.isWindows(home)) {
            val roots =
                (
                    listOf(home) +
                        session.serverProjects.map { it.worktree } +
                        session.addedWorktrees +
                        session.listedRoots
                ).filter(ServerPathPolicy::isWindows)
                    .map(ServerPathPolicy::root)
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
                    .sortedWith(compareBy<String>({ it.startsWith("\\\\") }, { it.lowercase() }))
            roots.forEach { root -> candidates += windowsRootLabel(root) to root }
        } else {
            candidates += "/" to "/"
            session.volumes
                .filterNot(DirectoryListingPolicy::isHidden)
                .forEach { volume -> candidates += volume.name to volume.absolutePath }
        }
        return candidates
            .distinctBy { (_, path) -> path }
            .map { (label, path) ->
                AddProjectRootUi(label = label, path = path, selected = filter.isEmpty() && directory == path)
            }
    }

    /** "On Home" and "N folders" / "Empty", joined; null when neither applies. */
    private fun folderSubtitle(
        isOnHome: Boolean,
        folderCount: Int?,
    ): String? {
        val parts =
            listOfNotNull(
                AddProjectSheetUi.ON_HOME.takeIf { isOnHome },
                folderCount?.let(::folderCountText),
            )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun folderCountText(count: Int): String =
        when (count) {
            0 -> AddProjectSheetUi.EMPTY_FOLDER
            1 -> "1 folder"
            else -> "$count folders"
        }

    /** `C:\` reads as `C:`; a UNC share root keeps its `\\server\share` form. */
    private fun windowsRootLabel(root: String): String = if (root.startsWith("\\\\")) root else root.trimEnd('\\')
}
