import Foundation
import OpenCodeKit

/// A subfolder row in Browse folders.
struct FolderEntry: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    /// Visible subfolders, once the row's own listing loaded.
    var folderCount: Int?
    var isGitRepository = false

    /// The folder count, or nil until it is known.
    var subtitle: String? { folderCount.map { Self.countLabel($0) } }

    static func countLabel(_ count: Int) -> String {
        if count == 0 { return String(localized: "add_project.folder_empty", defaultValue: "Empty") }
        return String(localized: "add_project.folder_count", defaultValue: "\(count) folders")
    }
}

/// The folder the pinned bar names: what "Use this folder" would pick, or the folder in view.
struct FolderBarTarget: Equatable {
    let name: String
    let path: String
    let isGitRepository: Bool
}

/// A chip that jumps the path field to a root the server cannot enumerate (Home, `/`, a
/// mounted volume, a Windows drive or share).
struct FolderRoot: Identifiable, Equatable {
    var id: String { path }
    /// "Home", else the root path or volume name verbatim.
    let label: String
    let path: String
    let selected: Bool
}

/// A repository the server knows, for the project list.
struct ProjectSuggestion: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    let activityLabel: String?
}

/// Pick a project and Browse folders: the server's recent repositories, then a path field over
/// the server's folders with root chips and a pinned bar. Everything here is derived from the
/// field text and the listings loaded so far. Hex Code's own copy of the rules behind OpenCode
/// Mobile's Add project sheet, without adding anything to the server.
struct FolderBrowserState: Equatable {
    static let gitTag = String(localized: "add_project.git_tag", defaultValue: "git")
    /// Row listings loaded at once for folder counts and git tags.
    static let folderSummaryConcurrency = 4
    /// Listing failures never show server text, which can carry paths or HTML.
    static let listErrorMessage = String(localized: "add_project.list_error.message", defaultValue: "Check the path and try again.")

    var fieldText = ""
    /// The server's home (on v2, its working directory) in native form.
    var homePath = ""
    /// Folder whose subfolders are listed; nil while the field resolves to nothing.
    var directory: String?
    /// Name prefix typed after the field's last separator.
    var filter = ""
    /// Subfolders of `directory` whose names start with `filter`, capped after filtering.
    var entries: [FolderEntry] = []
    /// Shown under `entries` when more folders match than are shown.
    var truncationHint: String?
    var isLoading = false
    var listError: String?
    /// The field's folder does not exist. The list shows `emptyMessage`; no Retry.
    var pathNotFound = false
    /// Shown when the listing loaded but no row matches, or the folder does not exist.
    var emptyMessage: String?
    var roots: [FolderRoot] = []
    var suggestions: [ProjectSuggestion] = []
    /// False until the server's project list came back (or failed).
    var suggestionsResolved = false
    var upTarget: String?
    /// The folder "Use this folder" picks.
    var useTarget: String?
    /// Target row of the pinned bar; nil while the field names no folder.
    var barTarget: FolderBarTarget?
    /// Folder Return opens: the exact typed child, or the only match. Return never picks.
    var goTarget: String?
    var useTitle = Self.useThisFolderTitle
    var useEnabled = false
    /// True while Browse folders replaces the project list.
    var isBrowsing = false

    static let empty = FolderBrowserState()

    static var useThisFolderTitle: String { String(localized: "add_project.add_this_folder", defaultValue: "Use this folder") }

    /// Builds the state from what the picker has loaded.
    static func make(_ inputs: FolderBrowserInputs) -> FolderBrowserState {
        var state = FolderBrowserState()
        state.fieldText = inputs.fieldText
        state.homePath = inputs.home
        state.isBrowsing = inputs.isBrowsing
        state.suggestionsResolved = inputs.suggestionsResolved
        guard !inputs.home.isEmpty else {
            state.isLoading = inputs.pathsError == nil
            state.listError = inputs.pathsError
            return state
        }
        let parts = ServerPathPolicy.fieldParts(inputs.fieldText, home: inputs.home)
        state.directory = parts.directory
        state.filter = parts.filter
        state.roots = roots(inputs, directory: parts.directory, filter: parts.filter)
        state.suggestions = ServerProjectSuggestions
            .suggestions(from: inputs.serverProjects, excluding: [])
            .map { project in
                ProjectSuggestion(
                    name: project.displayName,
                    path: project.worktree,
                    activityLabel: project.activityAtEpochMs.map { RelativeTimeFormatting.format(epochMs: $0) }
                )
            }

        guard let directory = parts.directory else { return state }
        let filter = parts.filter
        let windows = ServerPathPolicy.isWindows(directory)
        let repositories = Set(
            inputs.serverProjects.filter(\.isRepository).map { ServerPathPolicy.canonical($0.worktree) }
        )
        func isGitRepository(_ path: String) -> Bool {
            inputs.listings[path]?.isGitRepository == true
                || repositories.contains(ServerPathPolicy.canonical(path))
        }
        state.upTarget = filter.isEmpty ? ServerPathPolicy.parentPath(directory) : directory
        let listing = inputs.listings[directory]
        if inputs.missingDirectories.contains(directory) {
            state.pathNotFound = true
            state.emptyMessage = pathNotFoundTitle
        } else if let error = inputs.listErrors[directory] {
            state.listError = error
        } else if listing == nil {
            state.isLoading = true
        }

        if let listing, !state.pathNotFound {
            let visible = DirectoryListingPolicy.visibleEntries(listing.folders, filter: filter)
            state.entries = visible.rows.map { node in
                FolderEntry(
                    name: node.name,
                    path: node.absolutePath,
                    folderCount: inputs.listings[node.absolutePath]?.visibleFolderCount,
                    isGitRepository: isGitRepository(node.absolutePath)
                )
            }
            if visible.truncated {
                let limit = DirectoryListingPolicy.maxVisibleDirectories
                state.truncationHint = String(
                    localized: "add_project.truncation_hint",
                    defaultValue: "Showing the first \(limit) folders. Type more of the name to narrow the list."
                )
            }
            if state.entries.isEmpty {
                state.emptyMessage = filter.isEmpty
                    ? String(localized: "add_project.no_subfolders", defaultValue: "No subfolders")
                    : emptyFilterMessage(directory: directory, filter: filter)
            }
            if filter.isEmpty {
                state.useTarget = directory
            } else {
                // The full listing, hidden folders included, so a folder past the cap can be picked.
                state.useTarget = listing.folders.first { node in
                    windows ? node.name.caseInsensitiveCompare(filter) == .orderedSame : node.name == filter
                }?.absolutePath
                state.goTarget = state.useTarget ?? (visible.rows.count == 1 ? visible.rows[0].absolutePath : nil)
            }
        }

        let barPath = state.useTarget ?? directory
        state.barTarget = FolderBarTarget(
            name: ServerPathPolicy.name(barPath),
            path: barPath,
            isGitRepository: isGitRepository(barPath)
        )
        if state.pathNotFound {
            state.useTitle = pathNotFoundTitle
        } else if listing == nil {
            state.useTitle = useThisFolderTitle
        } else if let target = state.useTarget {
            state.useTitle = ServerPathPolicy.canonical(target) == inputs.home
                ? String(localized: "add_project.add_home_folder", defaultValue: "Use the home folder")
                : useThisFolderTitle
            state.useEnabled = true
        } else {
            state.useTitle = state.entries.isEmpty
                ? String(localized: "add_project.no_folder_named", defaultValue: "No folder named \u{201C}\(filter)\u{201D}")
                : String(localized: "add_project.pick_a_folder", defaultValue: "Pick a folder")
        }
        return state
    }

    private static var pathNotFoundTitle: String {
        String(localized: "add_project.path_not_found", defaultValue: "Path not found")
    }

    private static func emptyFilterMessage(directory: String, filter: String) -> String {
        let folder = ServerPathPolicy.name(directory)
        return String(
            localized: "add_project.nothing_starts_with",
            defaultValue: "Nothing in \(folder) starts with \u{201C}\(filter)\u{201D}"
        )
    }

    /// Home first, then `/` and mounted volumes on POSIX servers, or every Windows drive and share
    /// seen in home, the server's projects and folders listed this session.
    private static func roots(_ inputs: FolderBrowserInputs, directory: String?, filter: String) -> [FolderRoot] {
        var chips: [(label: String, path: String)] = [
            (String(localized: "add_project.root_home", defaultValue: "Home"), inputs.home),
        ]
        if ServerPathPolicy.isWindows(inputs.home) {
            var seen: [String] = []
            let candidates = [inputs.home] + inputs.serverProjects.map(\.worktree) + inputs.listedRoots
            for path in candidates where ServerPathPolicy.isWindows(path) {
                let root = ServerPathPolicy.root(path)
                if !root.isEmpty, !seen.contains(root) { seen.append(root) }
            }
            let homeRoot = ServerPathPolicy.root(inputs.home)
            let others = seen.filter { $0 != homeRoot }.sorted {
                $0.localizedCaseInsensitiveCompare($1) == .orderedAscending
            }
            chips += ([homeRoot] + others).map { (rootLabel($0), $0) }
        } else if ServerPathPolicy.root(inputs.home) == "/" {
            chips.append(("/", "/"))
            chips += inputs.volumes.map { ($0.name, $0.absolutePath) }
        }
        var seenPaths = Set<String>()
        return chips.compactMap { chip in
            guard seenPaths.insert(chip.path).inserted else { return nil }
            return FolderRoot(label: chip.label, path: chip.path, selected: filter.isEmpty && directory == chip.path)
        }
    }

    /// `C:` for a drive root; shares and `/` read as themselves.
    static func rootLabel(_ root: String) -> String {
        if root.count == 3, root.hasSuffix(":\\") { return String(root.dropLast()) }
        return root
    }
}

/// What the picker has loaded.
struct FolderBrowserInputs: Equatable {
    var home = ""
    var fieldText = ""
    var pathsError: String?
    /// Successful full listings (every child folder, hidden ones included, uncapped) by
    /// canonical folder, kept while the picker is open.
    var listings: [String: DirectoryListing] = [:]
    var listErrors: [String: String] = [:]
    /// Folders the server reported missing.
    var missingDirectories: Set<String> = []
    var serverProjects: [Project] = []
    /// True once the project list fetch has finished, including a failure.
    var suggestionsResolved = false
    /// Folders in `/Volumes` on a POSIX server.
    var volumes: [DirectoryNode] = []
    /// Roots of folders the field listed (Windows drives and shares).
    var listedRoots: [String] = []
    var isBrowsing = false
}
