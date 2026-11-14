package dev.opencode.hexcode.firstrun

import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.DirectoryListingPolicy
import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ServerProjectSuggestions
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The folder each day's session runs in. */
@Serializable
data class ProjectChoice(
    val name: String,
    val path: String,
)

/** One row under "On this computer". */
data class ProjectRow(
    val choice: ProjectChoice,
    /** The path with the home folder shortened to `~`. */
    val shownPath: String,
    val activity: Ago?,
)

/** How long ago something happened, as the prototype says it: "5m ago", "2h ago", "Yesterday", "3d ago", "2w ago". */
sealed interface Ago {
    data object Now : Ago

    data class Minutes(
        val count: Int,
    ) : Ago

    data class Hours(
        val count: Int,
    ) : Ago

    data object Yesterday : Ago

    data class Days(
        val count: Int,
    ) : Ago

    data class Weeks(
        val count: Int,
    ) : Ago

    companion object {
        fun of(
            epochMs: Long,
            nowEpochMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Ago {
            val minutes = ((nowEpochMs - epochMs).coerceAtLeast(0L) / 60_000L).toInt()
            if (minutes < 1) return Now
            if (minutes < 60) return Minutes(minutes)
            val then = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(then, today).toInt()
            return when {
                days == 0 -> Hours(minutes / 60)
                days == 1 -> Yesterday
                days < 7 -> Days(days)
                else -> Weeks(days / 7)
            }
        }
    }
}

/**
 * "Pick a project", after OpenCode Mobile's Add project: the server's own repositories, newest activity first, at
 * most five ([ServerProjectSuggestions]).
 */
object ProjectPicker {
    fun rows(
        projects: List<Project>,
        home: String,
        nowEpochMs: Long,
    ): List<ProjectRow> =
        ServerProjectSuggestions.suggestions(projects, addedWorktrees = emptyList()).map { project ->
            ProjectRow(
                choice =
                    ProjectChoice(
                        name = project.displayName.ifBlank { ServerPathPolicy.name(project.worktree) },
                        path = project.worktree,
                    ),
                shownPath = shortPath(project.worktree, home),
                activity = project.activityAtEpochMs?.let { Ago.of(it, nowEpochMs) },
            )
        }

    /** `/Users/example/Projects/app` reads `~/Projects/app` when the home folder is `/Users/example`. */
    fun shortPath(
        path: String,
        home: String,
    ): String {
        if (home.isBlank() || ServerPathPolicy.isWindows(home)) return path
        val base = ServerPathPolicy.canonical(home)
        val canonical = ServerPathPolicy.canonical(path)
        return when {
            canonical == base -> "~"
            canonical.startsWith("$base/") -> "~" + canonical.removePrefix(base)
            else -> path
        }
    }
}

/** One folder's listing as the browser holds it. */
sealed interface FolderListing {
    data object Loading : FolderListing

    data class Loaded(
        val listing: DirectoryListing,
    ) : FolderListing

    data object NotFound : FolderListing

    data object Failed : FolderListing
}

data class FolderRow(
    val name: String,
    val path: String,
    val isGitRepository: Boolean,
    /** Visible subfolders, once this folder's own listing has loaded. */
    val folderCount: Int?,
)

data class FolderRoot(
    /** Null for the home folder, which the screen names "Home". */
    val label: String?,
    val path: String,
    val selected: Boolean,
)

/**
 * "Browse folders", after OpenCode Mobile's folder browser: the folder being looked at, its subfolders with their
 * `git` tags and folder counts, root chips, and the folder the pinned bar would use. Rows only navigate; the bar
 * picks the folder it names. Listings are cached for the browser's lifetime.
 */
data class FolderBrowserState(
    val home: String,
    val directory: String = home,
    val listings: Map<String, FolderListing> = emptyMap(),
    /** Folders in `/Volumes` on a POSIX server, offered as roots. */
    val volumes: List<DirectoryNode> = emptyList(),
) {
    val listing: FolderListing
        get() = listings[ServerPathPolicy.canonical(directory)] ?: FolderListing.Loading

    val rows: List<FolderRow>
        get() {
            val loaded = (listing as? FolderListing.Loaded)?.listing ?: return emptyList()
            return DirectoryListingPolicy.visibleEntries(loaded.folders, filter = "").rows.map { node ->
                val child = loadedListing(node.absolutePath)
                FolderRow(
                    name = node.name,
                    path = node.absolutePath,
                    isGitRepository = child?.isGitRepository == true,
                    folderCount = child?.visibleFolderCount,
                )
            }
        }

    val upTarget: String?
        get() = ServerPathPolicy.parentPath(directory)

    val isGitRepository: Boolean
        get() = loadedListing(directory)?.isGitRepository == true

    /** The home folder itself is not offered: a day's session wants a project, not everything. */
    val canUse: Boolean
        get() = listing is FolderListing.Loaded && ServerPathPolicy.canonical(directory) != ServerPathPolicy.canonical(home)

    val choice: ProjectChoice
        get() = ProjectChoice(name = ServerPathPolicy.name(directory), path = directory)

    val roots: List<FolderRoot>
        get() {
            if (home.isBlank()) return emptyList()
            val candidates = mutableListOf<Pair<String?, String>>(null to home)
            if (!ServerPathPolicy.isWindows(home)) {
                candidates += "/" to "/"
                volumes.filterNot(DirectoryListingPolicy::isHidden).forEach { candidates += it.name to it.absolutePath }
            } else {
                val root = ServerPathPolicy.root(home)
                if (root.isNotEmpty()) candidates += root.trimEnd('\\') to root
            }
            return candidates.distinctBy { it.second }.map { (label, path) ->
                FolderRoot(label, path, selected = ServerPathPolicy.canonical(path) == ServerPathPolicy.canonical(directory))
            }
        }

    fun open(path: String): FolderBrowserState = copy(directory = path)

    fun withListing(
        path: String,
        listing: FolderListing,
    ): FolderBrowserState = copy(listings = listings + (ServerPathPolicy.canonical(path) to listing))

    /** Not listed yet, or the last try failed. */
    fun needsListing(path: String): Boolean = listings[ServerPathPolicy.canonical(path)].let { it == null || it == FolderListing.Failed }

    private fun loadedListing(path: String): DirectoryListing? =
        (listings[ServerPathPolicy.canonical(path)] as? FolderListing.Loaded)?.listing

    companion object {
        const val VOLUMES_PATH = "/Volumes"

        /** Row summaries (folder count, git) listed at once. */
        const val SUMMARY_CONCURRENCY = 4
    }
}
