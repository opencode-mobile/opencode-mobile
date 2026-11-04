import Foundation

/// A subfolder row in the Add project sheet.
public struct AddProjectFolderItem: Identifiable, Equatable {
    public var id: String { path }
    public let name: String
    public let path: String
    /// Already added to home on this server.
    public let isAdded: Bool
    /// Visible subfolders, once the row's own listing loaded.
    public var folderCount: Int? = nil
    public var isGitRepository = false

    /// "On Home", the folder count, or "On Home · N folders". Nil when neither is known.
    public var subtitle: OpenCodeText? {
        guard isAdded else { return folderCount.map(Self.countLabel) }
        guard let folderCount else { return "add_project.on_home" }
        let count = Self.countLabel(folderCount).resolved
        return OpenCodeText(LocalizedStringResource("add_project.on_home_with_count", defaultValue: "On Home \u{00B7} \(count)"))
    }

    public static func countLabel(_ count: Int) -> OpenCodeText {
        if count == 0 { return "add_project.folder_empty" }
        return OpenCodeText(LocalizedStringResource("add_project.folder_count", defaultValue: "\(count) folders"))
    }

    public init(
        name: String,
        path: String,
        isAdded: Bool,
        folderCount: Int? = nil,
        isGitRepository: Bool = false
    ) {
        self.name = name
        self.path = path
        self.isAdded = isAdded
        self.folderCount = folderCount
        self.isGitRepository = isGitRepository
    }
}

/// The folder the browser's action bar names: what Add would add, or the folder in view.
public struct AddProjectBarTarget: Equatable {
    public let name: String
    public let path: String
    public let isGitRepository: Bool

    public init(
        name: String,
        path: String,
        isGitRepository: Bool
    ) {
        self.name = name
        self.path = path
        self.isGitRepository = isGitRepository
    }
}

/// A chip that jumps the path field to a root the server cannot enumerate (Home, `/`, a
/// mounted volume, a Windows drive or share).
public struct AddProjectRoot: Identifiable, Equatable {
    public var id: String { path }
    /// "Home", else the root path or volume name verbatim.
    public let label: OpenCodeText
    public let path: String
    public let selected: Bool

    public init(
        label: OpenCodeText,
        path: String,
        selected: Bool
    ) {
        self.label = label
        self.path = path
        self.selected = selected
    }
}

/// A project the server already knows that is not on home yet.
public struct AddProjectSuggestion: Identifiable, Equatable {
    public var id: String { path }
    public let name: String
    public let path: String
    public let activityLabel: String?

    public init(
        name: String,
        path: String,
        activityLabel: String?
    ) {
        self.name = name
        self.path = path
        self.activityLabel = activityLabel
    }
}

/// Add project sheet: a path field over the server's folders, root chips, and the server's own
/// projects. Everything here is derived from the field text and the listings loaded so far.
public struct AddProjectSheetState: Equatable {
    public static let pickFolderTitle: OpenCodeText = "add_project.pick_a_folder"
    public static let addThisFolderTitle: OpenCodeText = "add_project.add_this_folder"
    public static let alreadyAddedTitle: OpenCodeText = "add_project.already_on_home"
    public static let homeFolderTitle: OpenCodeText = "add_project.add_home_folder"
    public static let pathNotFoundTitle: OpenCodeText = "add_project.path_not_found"
    public static let gitTag: OpenCodeText = "add_project.git_tag"
    public static let noSubfoldersMessage: OpenCodeText = "add_project.no_subfolders"
    /// Row listings loaded at once for folder counts and git tags.
    public static let folderSummaryConcurrency = 4
    public static var truncatedListingHint: OpenCodeText {
        let limit = DirectoryListingPolicy.maxVisibleDirectories
        return OpenCodeText(LocalizedStringResource(
            "add_project.truncation_hint",
            defaultValue: "Showing the first \(limit) folders. Type more of the name to narrow the list."
        ))
    }

    /// Listing failures never show server text, which can carry paths or HTML.
    public static let listErrorMessage: OpenCodeText = "add_project.list_error.message"

    public var fieldText = ""
    /// The server's home (on v2, its working directory) in native form.
    public var homePath = ""
    /// Folder whose subfolders are listed; nil while the field resolves to nothing.
    public var directory: String?
    /// Name prefix typed after the field's last separator.
    public var filter = ""
    /// Subfolders of `directory` whose names start with `filter`, capped after filtering.
    public var entries: [AddProjectFolderItem] = []
    /// Non-interactive row under `entries` when more folders match than are shown.
    public var truncationHint: OpenCodeText?
    public var isLoading = false
    public var listError: OpenCodeText?
    /// The field's folder does not exist. The list shows `emptyMessage`; no Retry.
    public var pathNotFound = false
    /// Shown when the listing loaded but no row matches, or the folder does not exist.
    public var emptyMessage: OpenCodeText?
    public var roots: [AddProjectRoot] = []
    public var suggestions: [AddProjectSuggestion] = []
    /// False until this presentation has heard back from the server project list.
    public var suggestionsResolved = false
    public var showSuggestions = false
    /// Header over the folder rows while suggestions are shown above them.
    public var folderSectionTitle: OpenCodeText?
    public var upTarget: String?
    public var addTarget: String?
    /// Target row of the browser's action bar; nil while the field names no folder.
    public var barTarget: AddProjectBarTarget?
    /// Folder Return opens: the exact typed child, or the only match. Return never adds.
    public var goTarget: String?
    public var addTitle = addThisFolderTitle
    public var addEnabled = false
    public var isValidating = false
    /// The server's or system's error text, verbatim.
    public var fieldError: OpenCodeText?
    /// True while Browse folders has replaced the suggestion sheet with the full-screen browser.
    public var isBrowsing = false

    public static let empty = AddProjectSheetState()

    public init() {}

    public static func emptyFilterMessage(directory: String, filter: String) -> OpenCodeText {
        let folder = ServerPathPolicy.name(directory)
        return OpenCodeText(LocalizedStringResource(
            "add_project.nothing_starts_with",
            defaultValue: "Nothing in \(folder) starts with \u{201C}\(filter)\u{201D}"
        ))
    }

    public static func noFolderNamedTitle(_ filter: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("add_project.no_folder_named", defaultValue: "No folder named \u{201C}\(filter)\u{201D}"))
    }

    /// Builds the sheet from what the Screen Model has loaded for this presentation.
    public static func make(_ inputs: AddProjectSheetInputs) -> AddProjectSheetState {
        var sheet = AddProjectSheetState()
        sheet.fieldText = inputs.fieldText
        sheet.homePath = inputs.home
        sheet.isValidating = inputs.isValidating
        sheet.fieldError = OpenCodeText.verbatim(ifPresent: inputs.fieldError)
        sheet.isBrowsing = inputs.isBrowsing
        sheet.suggestionsResolved = inputs.suggestionsResolved
        guard !inputs.home.isEmpty else {
            sheet.isLoading = inputs.pathsError == nil
            sheet.listError = inputs.pathsError
            return sheet
        }
        let added = Set(inputs.added.map(ServerPathPolicy.canonical))
        let parts = ServerPathPolicy.fieldParts(inputs.fieldText, home: inputs.home)
        sheet.directory = parts.directory
        sheet.filter = parts.filter
        sheet.roots = roots(inputs, directory: parts.directory, filter: parts.filter)
        sheet.suggestions = ServerProjectSuggestions
            .suggestions(
                from: inputs.serverProjects.filter { !inputs.addedProjectIDs.contains($0.id) },
                excluding: inputs.added
            )
            .map { project in
                AddProjectSuggestion(
                    name: project.displayName,
                    path: project.worktree,
                    activityLabel: project.activityAtEpochMs.map { RelativeTimeFormatting.format(epochMs: $0) }
                )
            }

        guard let directory = parts.directory else { return sheet }
        let filter = parts.filter
        let windows = ServerPathPolicy.isWindows(directory)
        let repositories = Set(
            inputs.serverProjects.filter(\.isRepository).map { ServerPathPolicy.canonical($0.worktree) }
        )
        func isGitRepository(_ path: String) -> Bool {
            inputs.listings[path]?.isGitRepository == true
                || repositories.contains(ServerPathPolicy.canonical(path))
        }
        sheet.upTarget = filter.isEmpty ? ServerPathPolicy.parentPath(directory) : directory
        let listing = inputs.listings[directory]
        if inputs.missingDirectories.contains(directory) {
            sheet.pathNotFound = true
            sheet.emptyMessage = pathNotFoundTitle
        } else if let error = inputs.listErrors[directory] {
            sheet.listError = error
        } else if listing == nil {
            sheet.isLoading = true
        }
        sheet.showSuggestions = directory == inputs.home && filter.isEmpty && !sheet.suggestions.isEmpty
        if sheet.showSuggestions {
            let folder = ServerPathPolicy.name(directory)
            sheet.folderSectionTitle = OpenCodeText(LocalizedStringResource("add_project.in_folder", defaultValue: "In \(folder)"))
        }

        if let listing, !sheet.pathNotFound {
            let visible = DirectoryListingPolicy.visibleEntries(listing.folders, filter: filter)
            sheet.entries = visible.rows
                .map { node in
                    AddProjectFolderItem(
                        name: node.name,
                        path: node.absolutePath,
                        isAdded: added.contains(ServerPathPolicy.canonical(node.absolutePath)),
                        folderCount: inputs.listings[node.absolutePath]?.visibleFolderCount,
                        isGitRepository: isGitRepository(node.absolutePath)
                    )
                }
            if visible.truncated {
                sheet.truncationHint = truncatedListingHint
            }
            if sheet.entries.isEmpty {
                sheet.emptyMessage = filter.isEmpty
                    ? noSubfoldersMessage
                    : emptyFilterMessage(directory: directory, filter: filter)
            }
            if filter.isEmpty {
                sheet.addTarget = directory
            } else {
                // The full listing, hidden folders included, so a folder past the cap is addable.
                sheet.addTarget = listing.folders.first { node in
                    windows ? node.name.caseInsensitiveCompare(filter) == .orderedSame : node.name == filter
                }?.absolutePath
                sheet.goTarget = sheet.addTarget ?? (visible.rows.count == 1 ? visible.rows[0].absolutePath : nil)
            }
        }

        let barPath = sheet.addTarget ?? directory
        sheet.barTarget = AddProjectBarTarget(
            name: ServerPathPolicy.name(barPath),
            path: barPath,
            isGitRepository: isGitRepository(barPath)
        )
        if sheet.pathNotFound {
            sheet.addTitle = pathNotFoundTitle
        } else if listing == nil {
            sheet.addTitle = addThisFolderTitle
        } else if let target = sheet.addTarget {
            if added.contains(ServerPathPolicy.canonical(target)) {
                sheet.addTitle = alreadyAddedTitle
            } else {
                sheet.addTitle = ServerPathPolicy.canonical(target) == inputs.home
                    ? homeFolderTitle
                    : addThisFolderTitle
                sheet.addEnabled = !inputs.isValidating
            }
        } else {
            sheet.addTitle = sheet.entries.isEmpty ? noFolderNamedTitle(filter) : pickFolderTitle
        }
        return sheet
    }

    /// Home first, then `/` and mounted volumes on POSIX servers, or every Windows drive and share
    /// seen in home, the server's projects, home's projects, and folders listed this session.
    private static func roots(
        _ inputs: AddProjectSheetInputs,
        directory: String?,
        filter: String
    ) -> [AddProjectRoot] {
        var chips: [(label: OpenCodeText, path: String)] = [("add_project.root_home", inputs.home)]
        if ServerPathPolicy.isWindows(inputs.home) {
            var seen: [String] = []
            let candidates = [inputs.home]
                + inputs.serverProjects.map(\.worktree)
                + inputs.added
                + inputs.listedRoots
            for path in candidates where ServerPathPolicy.isWindows(path) {
                let root = ServerPathPolicy.root(path)
                if !root.isEmpty, !seen.contains(root) { seen.append(root) }
            }
            let homeRoot = ServerPathPolicy.root(inputs.home)
            let others = seen.filter { $0 != homeRoot }.sorted {
                $0.localizedCaseInsensitiveCompare($1) == .orderedAscending
            }
            chips += ([homeRoot] + others).map { (OpenCodeText.verbatim(rootLabel($0)), $0) }
        } else if ServerPathPolicy.root(inputs.home) == "/" {
            chips.append((.verbatim("/"), "/"))
            chips += inputs.volumes.map { (OpenCodeText.verbatim($0.name), $0.absolutePath) }
        }
        var seenPaths = Set<String>()
        return chips.compactMap { chip in
            guard seenPaths.insert(chip.path).inserted else { return nil }
            return AddProjectRoot(
                label: chip.label,
                path: chip.path,
                selected: filter.isEmpty && directory == chip.path
            )
        }
    }

    /// `C:` for a drive root; shares and `/` read as themselves.
    public static func rootLabel(_ root: String) -> String {
        if root.count == 3, root.hasSuffix(":\\") { return String(root.dropLast()) }
        return root
    }
}

/// What the Screen Model has loaded for one Add project presentation.
public struct AddProjectSheetInputs: Equatable {
    public var home = ""
    public var fieldText = ""
    public var pathsError: OpenCodeText?
    public var added: [String] = []
    /// Successful full listings (every child folder, hidden ones included, uncapped) by
    /// canonical folder, kept for the sheet's lifetime.
    public var listings: [String: DirectoryListing] = [:]
    public var listErrors: [String: OpenCodeText] = [:]
    /// Folders the server reported missing this session.
    public var missingDirectories: Set<String> = []
    public var serverProjects: [Project] = []
    /// Project ids already on Home. Their other worktrees count as added.
    public var addedProjectIDs: Set<String> = []
    /// True once this presentation's project-list fetch has finished, including a failure.
    public var suggestionsResolved = false
    /// Folders in `/Volumes` on a POSIX server.
    public var volumes: [DirectoryNode] = []
    /// Roots of folders the field listed this session (Windows drives and shares).
    public var listedRoots: [String] = []
    public var isValidating = false
    public var fieldError: String?
    public var isBrowsing = false

    public init(
        home: String = "",
        fieldText: String = "",
        pathsError: OpenCodeText? = nil,
        added: [String] = [],
        listings: [String: DirectoryListing] = [:],
        listErrors: [String: OpenCodeText] = [:],
        missingDirectories: Set<String> = [],
        serverProjects: [Project] = [],
        addedProjectIDs: Set<String> = [],
        suggestionsResolved: Bool = false,
        volumes: [DirectoryNode] = [],
        listedRoots: [String] = [],
        isValidating: Bool = false,
        fieldError: String? = nil,
        isBrowsing: Bool = false
    ) {
        self.home = home
        self.fieldText = fieldText
        self.pathsError = pathsError
        self.added = added
        self.listings = listings
        self.listErrors = listErrors
        self.missingDirectories = missingDirectories
        self.serverProjects = serverProjects
        self.addedProjectIDs = addedProjectIDs
        self.suggestionsResolved = suggestionsResolved
        self.volumes = volumes
        self.listedRoots = listedRoots
        self.isValidating = isValidating
        self.fieldError = fieldError
        self.isBrowsing = isBrowsing
    }
}
