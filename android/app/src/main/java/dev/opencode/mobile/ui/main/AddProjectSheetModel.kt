package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ServerProjectSuggestions
import dev.opencode.mobile.domain.time.RelativeTimeFormatting

/** Add project sheet: a path field over the folder it names, root chips, and server projects. */
data class AddProjectSheetUi(
    val fieldText: String,
    /** The folder being listed, or null when the field names nothing usable. */
    val directory: String?,
    /** Name prefix filtering [directory]'s subfolders. */
    val filter: String,
    /** Subfolders of [directory] matching [filter]. */
    val entries: List<AddProjectFolderUi>,
    val isLoading: Boolean,
    val listError: String?,
    /** "No subfolders" or the no-match message, when the listing is empty. */
    val emptyMessage: String?,
    val roots: List<AddProjectRootUi>,
    val suggestions: List<AddProjectSuggestionUi>,
    val showSuggestions: Boolean,
    /** Header over the folder list; only shown with the suggestions section. */
    val folderSectionTitle: String?,
    val upTarget: String?,
    val addTarget: String?,
    val addTitle: String,
    val addEnabled: Boolean,
    val isValidating: Boolean,
    val fieldError: String?,
) {
    companion object {
        const val PICK_A_FOLDER = "Pick a folder"
        const val ALREADY_ON_HOME = "Already on home"
        const val ADD_HOME_FOLDER = "Add home folder"
        const val NO_SUBFOLDERS = "No subfolders"
        const val LIST_ERROR_TITLE = "Unable to list this folder"
        const val LIST_ERROR_MESSAGE = "Check the path and try again."

        fun empty(): AddProjectSheetUi = AddProjectSheetModel.build(AddProjectSheetSession())
    }
}

data class AddProjectFolderUi(
    val name: String,
    val path: String,
    val isOnHome: Boolean,
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

    data class Loaded(
        val entries: List<DirectoryNode>,
    ) : AddProjectListing

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
    val addedWorktrees: List<String> = emptyList(),
    /** Windows roots of folders listed successfully during this session. */
    val listedRoots: Set<String> = emptySet(),
    val isValidating: Boolean = false,
    val fieldError: String? = null,
) {
    val parts: ServerPathPolicy.FieldParts
        get() = ServerPathPolicy.fieldParts(fieldText, home)

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

    fun build(
        session: AddProjectSheetSession,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AddProjectSheetUi {
        val (directory, filter) = session.parts
        val added = session.addedWorktrees.mapTo(HashSet()) { ServerPathPolicy.canonical(it) }
        val listing = directory?.let { session.listings[it] }
        val loaded = (listing as? AddProjectListing.Loaded)?.entries
        val windows = directory?.let(ServerPathPolicy::isWindows) == true
        val entries =
            loaded
                .orEmpty()
                .filter { filter.isEmpty() || it.name.startsWith(filter, ignoreCase = true) }
                .map { node ->
                    AddProjectFolderUi(
                        name = node.name,
                        path = node.absolutePath,
                        isOnHome = ServerPathPolicy.canonical(node.absolutePath) in added,
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
                (session.isOpening || (directory != null && (listing == null || listing is AddProjectListing.Loading)))
        val emptyMessage =
            when {
                loaded == null || entries.isNotEmpty() -> null
                filter.isEmpty() -> AddProjectSheetUi.NO_SUBFOLDERS
                else -> "Nothing in ${ServerPathPolicy.name(directory.orEmpty())} starts with \u201C$filter\u201D"
            }
        val addTarget =
            when {
                loaded == null -> null
                filter.isEmpty() -> directory
                else -> loaded.firstOrNull { it.name.equals(filter, ignoreCase = windows) }?.absolutePath
            }
        val alreadyAdded = addTarget != null && ServerPathPolicy.canonical(addTarget) in added
        val addTitle =
            when {
                addTarget == null -> AddProjectSheetUi.PICK_A_FOLDER
                alreadyAdded -> AddProjectSheetUi.ALREADY_ON_HOME
                addTarget == session.home -> AddProjectSheetUi.ADD_HOME_FOLDER
                else -> "Add ${ServerPathPolicy.name(addTarget)}"
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
            isLoading = isLoading,
            listError = listError,
            emptyMessage = emptyMessage,
            roots = roots(session, directory, filter),
            suggestions = suggestions,
            showSuggestions = showSuggestions,
            folderSectionTitle = if (showSuggestions && directory != null) "In ${ServerPathPolicy.name(directory)}" else null,
            upTarget =
                when {
                    directory == null -> null
                    filter.isNotEmpty() -> directory
                    else -> ServerPathPolicy.parentPath(directory)
                },
            addTarget = addTarget,
            addTitle = addTitle,
            addEnabled = addTarget != null && !alreadyAdded && !session.isValidating,
            isValidating = session.isValidating,
            fieldError = session.fieldError,
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
            session.volumes.forEach { volume -> candidates += volume.name to volume.absolutePath }
        }
        return candidates
            .distinctBy { (_, path) -> path }
            .map { (label, path) ->
                AddProjectRootUi(label = label, path = path, selected = filter.isEmpty() && directory == path)
            }
    }

    /** `C:\` reads as `C:`; a UNC share root keeps its `\\server\share` form. */
    private fun windowsRootLabel(root: String): String = if (root.startsWith("\\\\")) root else root.trimEnd('\\')
}
