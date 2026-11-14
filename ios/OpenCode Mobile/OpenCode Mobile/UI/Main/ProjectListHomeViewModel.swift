import Foundation
import Observation

struct ProjectSectionItem: Identifiable, Equatable {
    let id: String
    let worktree: String
    let displayName: String
    let pathSubtitle: String
    let letter: Character
    let letterAccent: LetterAccent
    let allSessions: [SessionListItem]
    let loadError: String?
    var sessionsFromCache: Bool = false
    var projectID: String? = nil
    var worktreeInventory: WorktreeInventory = .unloaded
    /// The live window holds only the newest sessions; the server may have older ones.
    var hasMoreSessions = false
    /// Oldest update in the live window; nil before it loads or when it is empty.
    var oldestWindowUpdatedAtEpochMs: Int64? = nil

    /// The section with the project and worktree inventory a cached layout seeded.
    func withCachedLayout(projectID: String?, inventory: WorktreeInventory?) -> ProjectSectionItem {
        var copy = self
        copy.projectID = projectID ?? self.projectID
        copy.worktreeInventory = inventory ?? worktreeInventory
        return copy
    }
}

/// A subfolder row in the Add project sheet.
struct AddProjectFolderItem: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    /// Already added to home on this server.
    let isAdded: Bool
    /// Visible subfolders, once the row's own listing loaded.
    var folderCount: Int? = nil
    var isGitRepository = false

    /// "On Home", the folder count, or "On Home · N folders". Nil when neither is known.
    var subtitle: OpenCodeText? {
        guard isAdded else { return folderCount.map(Self.countLabel) }
        guard let folderCount else { return "add_project.on_home" }
        let count = Self.countLabel(folderCount).resolved
        return OpenCodeText(LocalizedStringResource("add_project.on_home_with_count", defaultValue: "On Home \u{00B7} \(count)"))
    }

    static func countLabel(_ count: Int) -> OpenCodeText {
        if count == 0 { return "add_project.folder_empty" }
        return OpenCodeText(LocalizedStringResource("add_project.folder_count", defaultValue: "\(count) folders"))
    }
}

/// The folder the browser's action bar names: what Add would add, or the folder in view.
struct AddProjectBarTarget: Equatable {
    let name: String
    let path: String
    let isGitRepository: Bool
}

/// A chip that jumps the path field to a root the server cannot enumerate (Home, `/`, a
/// mounted volume, a Windows drive or share).
struct AddProjectRoot: Identifiable, Equatable {
    var id: String { path }
    /// "Home", else the root path or volume name verbatim.
    let label: OpenCodeText
    let path: String
    let selected: Bool
}

/// A project the server already knows that is not on home yet.
struct AddProjectSuggestion: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    let activityLabel: String?
}

/// Add project sheet: a path field over the server's folders, root chips, and the server's own
/// projects. Everything here is derived from the field text and the listings loaded so far.
struct AddProjectSheetState: Equatable {
    static let pickFolderTitle: OpenCodeText = "add_project.pick_a_folder"
    static let addThisFolderTitle: OpenCodeText = "add_project.add_this_folder"
    static let alreadyAddedTitle: OpenCodeText = "add_project.already_on_home"
    static let homeFolderTitle: OpenCodeText = "add_project.add_home_folder"
    static let pathNotFoundTitle: OpenCodeText = "add_project.path_not_found"
    static let gitTag: OpenCodeText = "add_project.git_tag"
    static let noSubfoldersMessage: OpenCodeText = "add_project.no_subfolders"
    /// Row listings loaded at once for folder counts and git tags.
    static let folderSummaryConcurrency = 4
    static var truncatedListingHint: OpenCodeText {
        let limit = DirectoryListingPolicy.maxVisibleDirectories
        return OpenCodeText(LocalizedStringResource(
            "add_project.truncation_hint",
            defaultValue: "Showing the first \(limit) folders. Type more of the name to narrow the list."
        ))
    }

    /// Listing failures never show server text, which can carry paths or HTML.
    static let listErrorMessage: OpenCodeText = "add_project.list_error.message"

    var fieldText = ""
    /// The server's home (on v2, its working directory) in native form.
    var homePath = ""
    /// Folder whose subfolders are listed; nil while the field resolves to nothing.
    var directory: String?
    /// Name prefix typed after the field's last separator.
    var filter = ""
    /// Subfolders of `directory` whose names start with `filter`, capped after filtering.
    var entries: [AddProjectFolderItem] = []
    /// Non-interactive row under `entries` when more folders match than are shown.
    var truncationHint: OpenCodeText?
    var isLoading = false
    var listError: OpenCodeText?
    /// The field's folder does not exist. The list shows `emptyMessage`; no Retry.
    var pathNotFound = false
    /// Shown when the listing loaded but no row matches, or the folder does not exist.
    var emptyMessage: OpenCodeText?
    var roots: [AddProjectRoot] = []
    var suggestions: [AddProjectSuggestion] = []
    /// False until this presentation has heard back from the server project list.
    var suggestionsResolved = false
    var showSuggestions = false
    /// Header over the folder rows while suggestions are shown above them.
    var folderSectionTitle: OpenCodeText?
    var upTarget: String?
    var addTarget: String?
    /// Target row of the browser's action bar; nil while the field names no folder.
    var barTarget: AddProjectBarTarget?
    /// Folder Return opens: the exact typed child, or the only match. Return never adds.
    var goTarget: String?
    var addTitle = addThisFolderTitle
    var addEnabled = false
    var isValidating = false
    /// The server's or system's error text, verbatim.
    var fieldError: OpenCodeText?
    /// True while Browse folders has replaced the suggestion sheet with the full-screen browser.
    var isBrowsing = false

    static let empty = AddProjectSheetState()

    static func emptyFilterMessage(directory: String, filter: String) -> OpenCodeText {
        let folder = ServerPathPolicy.name(directory)
        return OpenCodeText(LocalizedStringResource(
            "add_project.nothing_starts_with",
            defaultValue: "Nothing in \(folder) starts with \u{201C}\(filter)\u{201D}"
        ))
    }

    static func noFolderNamedTitle(_ filter: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("add_project.no_folder_named", defaultValue: "No folder named \u{201C}\(filter)\u{201D}"))
    }

    /// Builds the sheet from what the Screen Model has loaded for this presentation.
    static func make(_ inputs: AddProjectSheetInputs) -> AddProjectSheetState {
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
    static func rootLabel(_ root: String) -> String {
        if root.count == 3, root.hasSuffix(":\\") { return String(root.dropLast()) }
        return root
    }
}

/// What the Screen Model has loaded for one Add project presentation.
struct AddProjectSheetInputs: Equatable {
    var home = ""
    var fieldText = ""
    var pathsError: OpenCodeText?
    var added: [String] = []
    /// Successful full listings (every child folder, hidden ones included, uncapped) by
    /// canonical folder, kept for the sheet's lifetime.
    var listings: [String: DirectoryListing] = [:]
    var listErrors: [String: OpenCodeText] = [:]
    /// Folders the server reported missing this session.
    var missingDirectories: Set<String> = []
    var serverProjects: [Project] = []
    /// Project ids already on Home. Their other worktrees count as added.
    var addedProjectIDs: Set<String> = []
    /// True once this presentation's project-list fetch has finished, including a failure.
    var suggestionsResolved = false
    /// Folders in `/Volumes` on a POSIX server.
    var volumes: [DirectoryNode] = []
    /// Roots of folders the field listed this session (Windows drives and shares).
    var listedRoots: [String] = []
    var isValidating = false
    var fieldError: String?
    var isBrowsing = false
}

struct ProjectRemovalItem: Equatable {
    /// Primary path of the Home project.
    let worktree: String
    let displayName: String
    /// Every added path of the project; all leave Home together.
    var worktrees: [String] = []
}

struct HomeSessionActionTarget: Equatable {
    let sessionId: String
    let title: String
    let worktree: String
}

/// Shared shape for the home rename and fork dialogs.
struct HomeSessionTitleDialogState: Equatable {
    var isVisible = false
    var draftTitle = ""
    var isSubmitting = false
    var errorMessage: OpenCodeText?
    /// Set when the action cannot run at all (a fork with no user message); disables confirm.
    var blockedMessage: OpenCodeText?
}

struct HomeSessionForkResultState: Equatable {
    var isVisible = false
    var forkedTitle = ""
}

struct HomeServersChrome: Equatable {
    /// The active profile's name, verbatim, else "Sessions".
    var title: OpenCodeText = "home.sessions_title"
    var isSwitcher = false
    var active: ConnectionProfile?
    var others: [ConnectionProfile] = []
    var sheetVisible = false
}

/// First server load of a project added on home in this session, while it is outstanding.
/// Absent once the load landed (including an empty project) or the project left home.
enum ProjectLoadState: Equatable {
    /// Still waiting on the server. `isSlow` after the slow threshold.
    case loading(isSlow: Bool)
    /// The server load failed or never answered; Retry asks again.
    case failed(message: String)

    static var failedMessage: String {
        String(localized: "home.error.project_load_failed", defaultValue: "Couldn\u{2019}t load sessions")
    }
}

/// Anti-flicker and safety timing for ``ProjectLoadState``. Tests inject short durations.
struct ProjectLoadTiming: Equatable {
    /// Loading is only exposed once the load has run this long.
    var grace: Duration = .milliseconds(300)
    /// Once exposed, loading stays at least this long before it clears.
    var minimumVisible: Duration = .milliseconds(500)
    /// After this long, loading reads as slow.
    var slowThreshold: Duration = .seconds(4)
    /// With no outcome by then, the load counts as failed.
    var timeout: Duration = .seconds(30)

    static let standard = ProjectLoadTiming()
}

enum ProjectListHomeState: Equatable {
    case disconnected
    case probing
    case probeFailed(String)
    case loading
    case empty(
        showAddProjectSheet: Bool,
        addProjectSheet: AddProjectSheetState?,
        projectSessionCreateError: String?
    )
    case loaded(
        sections: [ProjectSectionItem],
        showAddProjectSheet: Bool,
        addProjectSheet: AddProjectSheetState?,
        projectSessionCreateError: String?
    )
}

@MainActor
@Observable
final class ProjectListHomeViewModel {
    private(set) var state: ProjectListHomeState = .probing
    /// Soft pull-to-refresh indicator; content stays on Loaded/Empty.
    private var userRefreshing = false
    private(set) var isRefreshing = false
    /// Sheet presentation is tracked here so SwiftUI `.sheet` can observe it directly.
    private(set) var isAddProjectPresented = false
    private(set) var addProjectSheet = AddProjectSheetState.empty
    private var addProjectInputs = AddProjectSheetInputs()
    /// Bumped per presentation so a late listing from a closed sheet is dropped.
    private var addProjectGeneration = 0
    private var addProjectListingTask: Task<Void, Never>?
    private var addProjectListingsInFlight: Set<String> = []
    /// Row listings for folder counts and git tags: waiting (first in, first out), loading,
    /// and failed this presentation (never retried, never shown as a list error).
    private var addProjectSummaryQueue: [String] = []
    private var addProjectSummariesInFlight: Set<String> = []
    private var addProjectSummaryFailures: Set<String> = []
    private let addProjectListingDebounce: Duration
    /// Projects added this session whose first server load is outstanding, keyed by worktree.
    /// Loading appears only after the grace delay; failures appear at once.
    private(set) var projectLoadStates: [String: ProjectLoadState] = [:]
    @ObservationIgnored private var projectLoads: [String: ProjectLoadTracker] = [:]
    @ObservationIgnored private var projectLoadAttempt = 0
    private let projectLoadTiming: ProjectLoadTiming
    private(set) var pendingProjectRemoval: ProjectRemovalItem?
    /// Shared home snackbar: project removal plus archive / delete failures.
    private(set) var removalSnackbar: OpenCodeSnackbarMessage?
    var sessionActionsTarget: HomeSessionActionTarget? { sessionActionsRegion.target }
    var sessionActionsSheetVisible: Bool { sessionActionsRegion.sheetVisible }
    var sessionActionsCanArchive: Bool { sessionActionsRegion.canArchive }
    var sessionRenameDialog: HomeSessionTitleDialogState { sessionActionsRegion.renameDialog }
    var sessionForkDialog: HomeSessionTitleDialogState { sessionActionsRegion.forkDialog }
    var sessionForkResult: HomeSessionForkResultState { sessionActionsRegion.forkResult }
    var sessionDeleteConfirmVisible: Bool { sessionActionsRegion.deleteConfirmVisible }
    var sessionActionInFlight: Bool { sessionActionsRegion.actionInFlight }
    private let projectRepository: any ProjectRepository
    private let filesystemRepository: any FilesystemRepository
    private let sessionRepository: any SessionRepository
    private let messageRepository: any MessageRepository
    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let liveUpdates: any LiveUpdates
    private let worktreeRepository: any WorktreeRepository
    private let worktreeInventoryCache: WorktreeInventoryCache
    /// The Worktrees screen reads Home's projects and sessions here and asks Home to act.
    let homeWorktreesFeed: HomeWorktreesFeed
    /// New worktree form of the Run in sheet.
    let worktreeCreate: WorktreeCreateModel
    /// Session search over Home's pane (#929).
    let sessionSearch: SessionSearchModel
    /// False once a v1 server answered 404 for its worktree routes.
    private(set) var serverCanCreateWorktrees = true
    private let foregroundEventStream: (any ForegroundEventStream)?
    private let sessionCache: any SessionCache
    private let sessionAdapter = SessionDomainAdapter()
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionStatus: SessionConnectionStatus = .offline
    private(set) var serversChrome = HomeServersChrome()
    /// Server the user just picked. A late save from the previous server must not paint over it.
    private var pendingSwitchID: String?
    private var switchPaintGeneration = 0
    /// Runs server switches one after another, so the last pick is where the app ends up.
    @ObservationIgnored private var switchTask: Task<Void, Never>?
    /// An empty Home failed its probe while other saved addresses were still being tried.
    private var probeWaitsOnAddressCheck = false
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var observedWorktrees: [String] = []
    @ObservationIgnored private var worktreeMarks: HomeWorktreeMarks
    private var worktreeInventories: [String: WorktreeInventory] = [:]
    private var worktreeProjectIDs: [String: String] = [:]
    /// Current project per added path, once resolved.
    private var projectResolutions: [String: HomeProjectResolution] = [:]
    /// Worktree directories the server listed, per added path.
    private var worktreeDirectories: [String: [String]] = [:]
    /// Worktree layouts last read from or written to the Session Cache, per added path.
    @ObservationIgnored private var rememberedWorktreeLayouts: [String: HomeWorktreeLayout] = [:]
    /// v1: non-added worktree directories whose sessions each added path's section folds in.
    private(set) var homeExtraDirectories: [String: [String]] = [:]
    /// Current branch by canonical worktree directory.
    private var worktreeBranches: [String: String] = [:]
    /// Name and VCS from the server's project list, by project id.
    private var serverProjectInfo: [String: (name: String?, isRepository: Bool)] = [:]
    @ObservationIgnored private var branchLookups: HomeBranchLookups
    @ObservationIgnored private var serverProjectsTask: Task<Void, Never>?
    @ObservationIgnored private var worktreeExtrasRefreshTask: Task<Void, Never>?
    private var worktreeMarkTask: Task<Void, Never>?
    private var worktreeEventTask: Task<Void, Never>?
    private var liveSessionsByWorktree: [String: SessionListSnapshot] = [:]
    private var liveStatusesByWorktree: [String: SessionActivitySnapshot] = [:]
    private var liveReadyIdsByWorktree: [String: Set<String>] = [:]
    /// Latest row build per worktree; a build that finishes after a newer one started is dropped.
    @ObservationIgnored private var rowBuildSerial = 0
    @ObservationIgnored private var latestRowBuild: [String: Int] = [:]
    /// First loads whose rows have not landed yet; the skeleton stays until they do.
    @ObservationIgnored private var projectLoadsAwaitingRows: Set<String> = []
    /// Busy sessions whose root is being looked up, and ids asked for meanwhile, per worktree.
    @ObservationIgnored private var ancestryTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var ancestryPending: [String: Set<String>] = [:]
    /// A load more is running for the scope Home shows.
    private(set) var isLoadingMoreSessions = false
    @ObservationIgnored private var loadMoreTask: Task<Void, Never>?
    private let sessionActionsRegion: HomeSessionActions
    /// Home menu's Archived sessions entry; its `entry` is nil on servers that cannot restore archived sessions.
    let archivedMenu: HomeArchivedMenu
    /// Title-edit Analytics fires once per dialog so keystrokes are not events.
    private var loggedRenameTitleEdit = false
    private var loggedForkTitleEdit = false

    init(
        projectRepository: any ProjectRepository,
        filesystemRepository: any FilesystemRepository,
        sessionRepository: any SessionRepository,
        messageRepository: any MessageRepository,
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        sessionLiveStore: any SessionLiveStore,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        sessionReadyStore: any SessionReadyStore,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        liveUpdates: any LiveUpdates = FixedLiveUpdates(),
        addProjectListingDebounce: Duration = .milliseconds(250),
        projectLoadTiming: ProjectLoadTiming = .standard,
        worktreeRepository: any WorktreeRepository = UnavailableWorktreeRepository(),
        foregroundEventStream: (any ForegroundEventStream)? = nil,
        branchRetryDelays: [Duration] = [.seconds(1), .seconds(3)],
        worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
        homeWorktreesFeed: HomeWorktreesFeed? = nil
    ) {
        self.addProjectListingDebounce = addProjectListingDebounce
        self.projectLoadTiming = projectLoadTiming
        self.projectRepository = projectRepository
        self.filesystemRepository = filesystemRepository
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.liveUpdates = liveUpdates
        self.worktreeRepository = worktreeRepository
        self.worktreeInventoryCache = worktreeInventoryCache
        self.homeWorktreesFeed = homeWorktreesFeed ?? HomeWorktreesFeed()
        self.worktreeCreate = WorktreeCreateModel(repository: worktreeRepository)
        self.sessionSearch = SessionSearchModel(
            repository: sessionRepository,
            archivedSessionsSupported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        self.serverCanCreateWorktrees = worktreeRepository.canCreateWorktrees
        self.foregroundEventStream = foregroundEventStream
        let projects = projectRepository
        let sessions = sessionRepository
        let adapter = sessionAdapter
        self.worktreeMarks = HomeWorktreeMarks(
            repository: worktreeRepository,
            resolveProject: { directory in
                guard let project = try? await projects.getCurrentProject(directory: directory) else { return nil }
                let canonical = project.canonical?.isEmpty == false ? project.canonical! : project.worktree
                return HomeProjectResolution(
                    id: project.id,
                    canonical: canonical,
                    name: project.name,
                    isRepository: project.isRepository
                )
            },
            listSessions: { directory in
                // The first page of roots, like the added paths' own windows.
                let listed = (try? await sessions.listRootSessionsWindow(
                    directory: directory,
                    limit: HomeSessionPaging.pageSize
                ))?.sessions ?? []
                return listed.map { session in
                    let item = adapter.listItem(from: session, worktree: directory)
                    if item.directory?.isEmpty == false { return item }
                    return SessionListItem(
                        id: item.id,
                        title: item.title,
                        worktree: item.worktree,
                        statusLabel: item.statusLabel,
                        updatedAtLabel: item.updatedAtLabel,
                        updatedAtEpochMs: item.updatedAtEpochMs,
                        isReady: item.isReady,
                        unreadOutcome: item.unreadOutcome,
                        statusIsStale: item.statusIsStale,
                        parentID: item.parentID,
                        directory: directory
                    )
                }
            }
        )
        self.branchLookups = HomeBranchLookups(retryDelays: branchRetryDelays) { directory in
            do {
                let info = try await sessions.getVcsInfo(directory: directory)
                // A cold v2 server answers without a provider first; ask again shortly.
                guard info.isSettled else { return .unknown }
                return .branch(HomeProjectGrouping.usableBranch(info.currentBranch))
            } catch let SessionError.http(statusCode, _) where (400 ..< 500).contains(statusCode) {
                // No VCS routes, or not a repository: there is no branch to show.
                return .branch(nil)
            } catch {
                return .unknown
            }
        }
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.sessionCache = sessionCache
        self.sessionActionsRegion = HomeSessionActions(
            sessionRepository: sessionRepository,
            messageRepository: messageRepository
        )
        self.archivedMenu = HomeArchivedMenu(
            sessionRepository: sessionRepository,
            supported: { activeConnectionManager.capabilities.supportsArchivedSessions }
        )
        lastHomeHiddenAtEpochMs = HomeDisplayPreferences.shared.lastHomeHiddenAtEpochMs()
        sessionActionsRegion.onLifecycleError = { [weak self] message in
            self?.removalSnackbar = OpenCodeSnackbarMessage(text: message, variant: .error)
        }
        sessionReadyStore.bindProfile(profileID: activeConnectionManager.currentProfile()?.id)
        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                let completesSwitch = self.pendingSwitchID != nil
                if let pending = self.pendingSwitchID {
                    let active = await self.connectionProfileStore.activeProfile()
                    guard active?.id == pending else { return }
                    self.pendingSwitchID = nil
                    self.switchPaintGeneration += 1
                }
                // A switch already painted its projects; their skeletons stay until sessions arrive.
                if !completesSwitch {
                    self.clearProjectLoads()
                }
                self.stopLiveObservations()
                await self.refresh()
            }
        }
        connectionSessionCoordinator.onAddressCheck { [weak self] check in
            Task { @MainActor in
                guard let self, self.pendingSwitchID == nil else { return }
                switch check.state {
                case .running:
                    break
                case .moved:
                    self.probeWaitsOnAddressCheck = false
                    if case .loaded = self.state {
                        await self.sessionLiveStore.invalidate(directory: nil)
                        await self.sessionStatusLiveStore.invalidate(directory: nil)
                    } else {
                        // Empty, unreachable, or still loading against the old address.
                        await self.refresh()
                    }
                case .kept:
                    guard self.probeWaitsOnAddressCheck else { return }
                    self.probeWaitsOnAddressCheck = false
                    await self.refresh()
                }
            }
        }
        connectionSessionCoordinator.onProfileCleared { [weak self] in
            Task { @MainActor in
                self?.clearProjectLoads()
                self?.stopLiveObservations()
                self?.worktreeMarks.onDisconnected()
                self?.worktreeInventoryCache.clear()
                self?.worktreeCreate.dismiss()
                self?.worktreeInventories.removeAll()
                self?.worktreeProjectIDs.removeAll()
                self?.clearHomeProjectInputs()
                self?.state = .disconnected
                self?.serversChrome = HomeServersChrome()
            }
        }
        self.homeWorktreesFeed.onRequest = { [weak self] request in
            Task { @MainActor in await self?.handleWorktreeRequest(request) }
        }
        branchLookups.onChange = { [weak self] branches in
            guard let self, self.worktreeBranches != branches else { return }
            self.worktreeBranches = branches
            // New branch answers update the remembered layouts (unchanged ones are not rewritten).
            for path in self.worktreeDirectories.keys {
                self.rememberWorktreeLayout(path)
            }
        }
        startObservingConnectionState()
    }

    /// Added paths grouped into Home projects: worktrees of one repository are one project.
    var homeProjects: [HomeProject] {
        guard case let .loaded(sections, _, _, _) = state else { return [] }
        return homeProjects(addedPaths: sections.map(\.worktree))
    }

    private func homeProjects(addedPaths: [String]) -> [HomeProject] {
        var resolutions: [String: HomeProjectResolution] = [:]
        for path in addedPaths {
            guard var resolution = projectResolutions[path] else { continue }
            if let info = serverProjectInfo[resolution.id] {
                if resolution.name?.isEmpty != false { resolution.name = info.name }
                resolution.isRepository = resolution.isRepository || info.isRepository
            }
            resolutions[path] = resolution
        }
        return HomeProjectGrouping.group(
            addedPaths: addedPaths,
            resolutions: resolutions,
            inventories: worktreeDirectories,
            branches: worktreeBranches
        )
    }

    private func clearHomeProjectInputs() {
        homeWorktreesFeed.clear()
        worktreeMarks.retain(removedDirectories: [])
        projectResolutions.removeAll()
        worktreeDirectories.removeAll()
        rememberedWorktreeLayouts.removeAll()
        homeExtraDirectories.removeAll()
        worktreeBranches.removeAll()
        serverProjectInfo.removeAll()
        branchLookups.clear()
        serverProjectsTask?.cancel()
        serverProjectsTask = nil
    }

    private func publishRefreshing() {
        if isRefreshing != userRefreshing {
            isRefreshing = userRefreshing
        }
    }

    private func startObservingConnectionState() {
        connectionStateObservationTask?.cancel()
        applyComposedConnectionStatus()
        connectionStateObservationTask = Task {
            for await _ in liveUpdates.updates() {
                applyComposedConnectionStatus()
            }
        }
    }

    private func applyComposedConnectionStatus() {
        liveUpdatesConnectionStatus = SessionConnectionStatus(label: liveUpdates.chrome.label)
    }

    func refresh() async {
        await refreshServersChrome()
        await runHomeLoad()
    }

    func showServersSheet() {
        serversChrome.sheetVisible = true
    }

    func dismissServersSheet() {
        serversChrome.sheetVisible = false
    }

    func switchProfile(id: String) {
        guard let target = serversChrome.others.first(where: { $0.id == id }) else { return }
        pendingSwitchID = id
        switchPaintGeneration += 1
        let generation = switchPaintGeneration
        let previous = serversChrome.active
        serversChrome = HomeServersChrome(
            title: .verbatim(target.displayName),
            isSwitcher: true,
            active: target,
            others: ([previous].compactMap { $0 } + serversChrome.others).filter { $0.id != id },
            sheetVisible: false
        )
        // Drop the previous server's live updates immediately. Local projects paint next;
        // the connection switch and session lists catch up in the background.
        stopLiveObservations()
        DiagnosticLog.info(.connection, "switch profile ui id=\(id)")
        Task { [weak self] in
            await self?.paintSwitchedServer(baseURL: target.baseURL, id: id, generation: generation)
        }
        let previousSwitch = switchTask
        switchTask = Task { [weak self] in
            await previousSwitch?.value
            // A newer pick replaced this one before it started; only the newest one runs.
            guard let self, self.pendingSwitchID == id else { return }
            await self.connectionSessionCoordinator.switchProfile(id: id)
            if await self.connectionProfileStore.activeProfile()?.id != id {
                await self.abandonSwitch(id: id)
            }
        }
    }

    /// The switch to `id` did not stick. Drop the pending pick, so later saves are not ignored,
    /// and reload Home and its title from the server that is actually active.
    private func abandonSwitch(id: String) async {
        guard pendingSwitchID == id else { return }
        DiagnosticLog.warning(.connection, "switch profile did not apply id=\(id)")
        pendingSwitchID = nil
        switchPaintGeneration += 1
        clearProjectLoads()
        stopLiveObservations()
        await refresh()
    }

    /// The target server's saved projects with their cached sessions, read by profile id because
    /// the switch has not bound that server yet. A project with no cache shows the skeleton.
    private func paintSwitchedServer(baseURL: String, id: String, generation: Int) async {
        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: baseURL)
        var sections: [ProjectSectionItem] = []
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree, connectionID: id))
        }
        let resolutions = await cachedProjectResolutions(worktrees, connectionID: id)
        let layouts = await cachedWorktreeLayouts(worktrees, connectionID: id)
        guard generation == switchPaintGeneration, pendingSwitchID == id else { return }
        clearProjectLoads()
        clearHomeProjectInputs()
        projectResolutions = resolutions
        applyWorktreeLayouts(layouts, replacing: true)
        sections = sections.map { $0.withCachedLayout(projectID: worktreeProjectIDs[$0.worktree], inventory: worktreeInventories[$0.worktree]) }
        probeWaitsOnAddressCheck = false
        if sections.isEmpty {
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            return
        }
        for section in sections where !section.sessionsFromCache {
            beginProjectLoad(section.worktree, showImmediately: true)
        }
        state = .loaded(
            sections: sections,
            showAddProjectSheet: false,
            addProjectSheet: nil,
            projectSessionCreateError: nil
        )
    }

    func refreshServersChrome() async {
        let profiles = await connectionProfileStore.listProfiles()
        let active = await connectionProfileStore.activeProfile()
            ?? activeConnectionManager.currentProfile()
        serversChrome = HomeServersChrome(
            title: active.map { OpenCodeText.verbatim($0.displayName) } ?? "home.sessions_title",
            isSwitcher: active != nil,
            active: active,
            others: profiles.filter { $0.id != active?.id },
            sheetVisible: serversChrome.sheetVisible
        )
    }

    /// Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner.
    func pullToRefresh() async {
        switch state {
        case .loaded, .empty:
            guard !userRefreshing else { return }
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.home,
                actionId: ProductAnalytics.Action.homePullToRefresh
            )
            userRefreshing = true
            publishRefreshing()
            defer {
                userRefreshing = false
                publishRefreshing()
            }
            // A stream backing off (e.g. after a rejected credential) retries now.
            foregroundEventStream?.onReconnectWake()
            refreshBranches()
            await sessionLiveStore.invalidate(directory: nil)
            await sessionStatusLiveStore.invalidate(directory: nil)
        default:
            break
        }
    }

    func retryProbe() async {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeProbeFailed,
            actionId: ProductAnalytics.Action.homeRetryProbe
        )
        await refresh()
    }

    func showAddProjectSheet() async {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeAddProject
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeAddProject,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        addProjectGeneration += 1
        addProjectListingTask?.cancel()
        resetAddProjectLoads()
        addProjectInputs = AddProjectSheetInputs()
        isAddProjectPresented = true
        publishAddProjectSheet()
        await loadAddProjectSheet(generation: addProjectGeneration)
    }

    func dismissAddProjectSheet() {
        isAddProjectPresented = false
        addProjectGeneration += 1
        addProjectListingTask?.cancel()
        addProjectListingTask = nil
        resetAddProjectLoads()
        addProjectInputs = AddProjectSheetInputs()
        addProjectSheet = .empty
        switch state {
        case let .empty(_, _, createError):
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, createError):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                addProjectSheet: nil,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    /// Path field edits. Filtering is local; a new folder is listed after a short pause.
    func updateAddProjectField(_ text: String) {
        guard isAddProjectPresented, !addProjectInputs.isValidating,
              text != addProjectInputs.fieldText
        else { return }
        addProjectInputs.fieldText = text
        addProjectInputs.fieldError = nil
        publishAddProjectSheet()
        scheduleAddProjectListing()
    }

    func addProjectUp() async {
        guard isAddProjectPresented, !addProjectInputs.isValidating,
              let target = addProjectSheet.upTarget
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectGoUp)
        await showAddProjectFolder(target)
    }

    func selectAddProjectRoot(path: String) async {
        guard isAddProjectPresented, !addProjectInputs.isValidating else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectRoot)
        await showAddProjectFolder(path)
    }

    func openAddProjectFolder(path: String) async {
        guard isAddProjectPresented, !addProjectInputs.isValidating else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectEnterDirectory)
        await showAddProjectFolder(path)
    }

    /// Return in the path field ("go"): opens the typed folder, or the only matching one.
    /// It never adds; Add stays a deliberate tap.
    func submitAddProjectField() async {
        guard isAddProjectPresented, !addProjectInputs.isValidating,
              let target = addProjectSheet.goTarget
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectEnterDirectory)
        await showAddProjectFolder(target)
    }

    /// A browser row came on screen: load its own listing for the folder count and git tag.
    func addProjectFolderRowAppeared(path: String) {
        guard isAddProjectPresented,
              addProjectInputs.listings[path] == nil,
              !addProjectSummariesInFlight.contains(path),
              !addProjectSummaryFailures.contains(path),
              !addProjectSummaryQueue.contains(path)
        else { return }
        addProjectSummaryQueue.append(path)
        startAddProjectSummaries()
    }

    /// A browser row left the screen before its listing started: drop it from the queue.
    func addProjectFolderRowDisappeared(path: String) {
        addProjectSummaryQueue.removeAll { $0 == path }
    }

    func selectAddProjectSuggestion(path: String) async {
        guard isAddProjectPresented, !addProjectInputs.isValidating else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectSuggestion)
        await addProject(worktree: path)
    }

    /// Replaces the suggestion sheet with the full-screen folder browser.
    func browseAddProjectFolders() {
        guard isAddProjectPresented, !addProjectInputs.isValidating else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectBrowse)
        addProjectInputs.isBrowsing = true
        publishAddProjectSheet()
    }

    /// Back from the browser. Returns to the suggestion sheet at the server home, so
    /// "On this server" is not lost because the field was left in a subfolder.
    func closeAddProjectBrowser() {
        guard isAddProjectPresented, addProjectInputs.isBrowsing else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectBrowseBack)
        addProjectListingTask?.cancel()
        addProjectInputs.isBrowsing = false
        if !addProjectInputs.home.isEmpty {
            addProjectInputs.fieldText = ServerPathPolicy.fieldText(addProjectInputs.home)
            addProjectInputs.fieldError = nil
        }
        publishAddProjectSheet()
    }

    func retryAddProjectListing() async {
        guard isAddProjectPresented, !addProjectInputs.isValidating else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectRetryNavigator)
        let generation = addProjectGeneration
        if addProjectInputs.home.isEmpty {
            await loadAddProjectSheet(generation: generation)
            return
        }
        guard let directory = addProjectSheet.directory else { return }
        addProjectInputs.listErrors[directory] = nil
        await loadAddProjectListing(directory, generation: generation)
    }

    func confirmAddProject() async {
        guard isAddProjectPresented, addProjectSheet.addEnabled,
              let target = addProjectSheet.addTarget
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectCurrent)
        await addProject(worktree: target)
    }

    /// Add-project navigation never carries the browsed path into Analytics.
    private func logAddProjectAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeAddProject,
            actionId: actionId
        )
    }

    func addProject(worktree: String) async {
        let added = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
        if added.contains(worktree) {
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            pendingSelectedFilterId = worktree
            let name = ProjectDomainAdapter.displayName(from: worktree)
            removalSnackbar = OpenCodeSnackbarMessage(
                text: String(localized: LocalizedStringResource("home.snackbar.already_on_home", defaultValue: "\"\(name)\" is already on home"))
            )
            return
        }
        addProjectInputs.isValidating = true
        addProjectInputs.fieldError = nil
        publishAddProjectSheet()
        do {
            // Validate the path is usable, then persist the selected directory (web
            // open-project behavior). Non-git dirs resolve /project/current to "/".
            let project = try await projectRepository.getCurrentProject(directory: worktree)
            let resolution = HomeProjectResolution(project)
            // A worktree of a repository already on Home joins that project.
            let joined = resolution.isMergeable
                ? homeProjects.first { $0.key == resolution.id.trimmingCharacters(in: .whitespacesAndNewlines) }
                : nil
            projectResolutions[worktree] = resolution
            await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL, worktree: worktree)
            beginProjectLoad(worktree, showImmediately: false)
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            // A newly added project always takes the scope, even from All.
            pendingSelectedFilterOverridesAll = true
            pendingSelectedFilterId = worktree
            if let joined {
                removalSnackbar = OpenCodeSnackbarMessage(text: HomeProjectCopy.joinedExisting(joined.displayName))
            }
        } catch {
            addProjectInputs.isValidating = false
            addProjectInputs.fieldError = error.localizedDescription
            publishAddProjectSheet()
        }
    }

    /// Retry from a project's failed first load: shows loading again and refetches its sessions.
    func retryProjectLoad(worktree: String) async {
        guard projectLoads[worktree] != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeRetryProjectLoad
        )
        beginProjectLoad(worktree, showImmediately: true)
        await sessionLiveStore.invalidate(directory: worktree)
    }

    func createSession(in worktree: String) async {
        guard liveUpdates.chrome.canMutate else {
            logCreateSession(outcome: ProductAnalytics.Outcome.blocked)
            setProjectSessionCreateError(
                String(localized: "home.error.reconnect_to_create", defaultValue: "Reconnect to create a session")
            )
            return
        }
        setProjectSessionCreateError(nil)
        logCreateSession(outcome: "started")
        revealNewestToken += 1
        pendingSessionOpen = SessionListItem(
            id: OptimisticSessionCreate.placeholderSessionID,
            title: OptimisticSessionCreate.placeholderTitle,
            worktree: worktree,
            optimisticNavigationInstanceID: UUID()
        )
    }

    // MARK: New worktree (Run in)

    /// The Run in sheet's New worktree row for `project`.
    func newWorktreeAvailability(for project: HomeProject, worktreesEnabled: Bool) -> NewWorktreeAvailability {
        NewWorktreeAvailability.runIn(
            worktreesEnabled: worktreesEnabled,
            isRepository: project.isRepository,
            projectID: project.projectID,
            serverCanCreate: serverCanCreateWorktrees
        )
    }

    /// Switches the Run in sheet to the New worktree form, creating from the main checkout.
    func openNewWorktree(projectKey: String) {
        guard let project = homeProjects.first(where: { $0.key == projectKey }) else { return }
        let source = project.mainCheckoutDirectory
        worktreeCreate.open(
            origin: .runIn(projectKey: projectKey),
            serverProtocol: activeConnectionManager.serverProtocol ?? .v1,
            projectID: project.projectID,
            sourceDirectory: source,
            sourceBranch: project.worktree(at: source)?.branch
        )
    }

    /// Creates the worktree, refreshes the project's checkouts, then starts a session there.
    /// True when the sheet should close.
    func submitNewWorktree() async -> Bool {
        guard case let .runIn(projectKey)? = worktreeCreate.state?.origin else { return false }
        guard let created = await worktreeCreate.submit() else {
            serverCanCreateWorktrees = worktreeRepository.canCreateWorktrees
            return false
        }
        await refreshWorktreeInventory(projectKey: projectKey)
        worktreeCreate.dismiss()
        await createSession(in: created.directory)
        return true
    }

    /// Home has listed some project's worktrees.
    var hasListedWorktrees: Bool { !worktreeDirectories.isEmpty }

    /// What the server listed as worktrees, minus every project's main checkout.
    var removableWorktreeDirectories: Set<String> {
        let mains = Set(homeProjects.flatMap { $0.worktrees.filter(\.isMain).map(\.directory) })
        return Set(
            worktreeDirectories.values
                .flatMap { $0 }
                .map { ServerPathPolicy.canonical($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .filter { !$0.isEmpty && !mains.contains($0) }
        )
    }

    /// Worktree directories removed from this app; their sessions stay on Home marked removed.
    var removedWorktreeDirectories: Set<String> { homeWorktreesFeed.removedDirectories }

    /// Show sessions, New session here, and a finished remove from the Worktrees screen.
    func handleWorktreeRequest(_ request: HomeWorktreeRequest) async {
        switch request {
        case let .showSessions(projectKey, directory):
            pendingSelectedFilterOverridesAll = true
            pendingSelectedFilterId = HomeScope.worktree(projectKey: projectKey, directory: directory).id
        case let .newSession(directory):
            await createSession(in: directory)
        case let .removed(_, directory):
            worktreeMarks.retain(removedDirectories: homeWorktreesFeed.removedDirectories)
            await refreshWorktreeInventory(containing: directory)
        }
    }

    /// Reloads the checkouts of the project that owns `directory` (Session Chat after a create).
    func refreshWorktreeInventory(containing directory: String) async {
        let canonical = ServerPathPolicy.canonical(directory)
        guard let project = homeProjects.first(where: { $0.worktree(at: canonical) != nil || $0.hasMember(canonical) })
        else { return }
        await refreshWorktreeInventory(projectKey: project.key)
    }

    /// v2 refetches the project's list; v1 reloads each added path, as a Home refresh does.
    func refreshWorktreeInventory(projectKey: String) async {
        guard let project = homeProjects.first(where: { $0.key == projectKey }) else { return }
        if activeConnectionManager.serverProtocol == .v2, let projectID = project.projectID,
           worktreeMarks.knows(projectID)
        {
            await refetchWorktreeMark(projectID)
            return
        }
        for path in project.memberPaths {
            let update = await worktreeMarks.load(addedPath: path)
            applyWorktreeMark(update)
        }
    }

    private func logCreateSession(outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeCreateSession,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    private(set) var pendingSessionOpen: SessionListItem?
    private(set) var pendingSelectedFilterId: String?
    /// True when `pendingSelectedFilterId` must replace the All scope too (a newly added project).
    private(set) var pendingSelectedFilterOverridesAll = false
    private(set) var revealNewestToken = 0
    private var lastHomeHiddenAtEpochMs: Int64?

    func consumePendingSessionOpen() {
        pendingSessionOpen = nil
    }

    func consumePendingSelectedFilter() {
        pendingSelectedFilterId = nil
        pendingSelectedFilterOverridesAll = false
    }

    func markHomeHidden(nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        guard lastHomeHiddenAtEpochMs == nil else { return }
        lastHomeHiddenAtEpochMs = nowEpochMs
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nowEpochMs)
    }

    func onHomeRevealed(nowEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        if SessionListScrollPin.shouldResetForStaleAbsence(
            lastHiddenAtEpochMs: lastHomeHiddenAtEpochMs,
            nowEpochMs: nowEpochMs
        ) {
            revealNewestToken += 1
        }
        lastHomeHiddenAtEpochMs = nil
        HomeDisplayPreferences.shared.setLastHomeHiddenAtEpochMs(nil)
    }

    /// Applies a new added-project order from the project picker. The order
    /// is shown immediately and persisted to the Connection Profile; the dock,
    /// home sections, and new-session picker all derive from it.
    func reorderProjects(worktrees: [String]) {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        let current = sections.map(\.worktree)
        guard current != worktrees,
              current.count == worktrees.count,
              Set(current) == Set(worktrees)
        else { return }
        let byWorktree = Dictionary(sections.map { ($0.worktree, $0) }, uniquingKeysWith: { _, latest in latest })
        state = .loaded(
            sections: worktrees.compactMap { byWorktree[$0] },
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
        if !observedWorktrees.isEmpty {
            observedWorktrees = worktrees
        }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeFilterProjectPicker,
            actionId: ProductAnalytics.Action.homeReorderProjects
        )
        let serverBaseURL = serverBaseURL
        let store = connectionProfileStore
        projectOrderPersistence = Task {
            await store.setAddedWorktrees(serverBaseURL: serverBaseURL, worktrees: worktrees)
        }
    }

    /// In-flight persistence of the last `reorderProjects` call, for callers
    /// that need to observe the Connection Profile after a reorder.
    private(set) var projectOrderPersistence: Task<Void, Never>?

    /// `worktree` is a Home project key or any of its added paths.
    func requestRemoveProject(worktree: String) {
        guard let project = homeProjects.first(where: { $0.key == worktree })
            ?? homeProjects.first(where: { $0.hasMember(worktree) })
        else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeRemoveProject
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        pendingProjectRemoval = ProjectRemovalItem(
            worktree: project.primaryPath,
            displayName: project.displayName,
            worktrees: project.memberPaths
        )
    }

    func dismissProjectRemoval() {
        guard pendingProjectRemoval != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            actionId: ProductAnalytics.Action.homeRemoveProjectCancel
        )
        pendingProjectRemoval = nil
    }

    func confirmRemoveProject() async {
        guard let pending = pendingProjectRemoval else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRemoveProjectConfirm,
            actionId: ProductAnalytics.Action.homeRemoveProjectConfirm
        )
        let paths = pending.worktrees.isEmpty ? [pending.worktree] : pending.worktrees
        for path in paths {
            await connectionProfileStore.removeWorktree(serverBaseURL: serverBaseURL, worktree: path)
            await sessionCache.purgeWorktree(serverBaseURL: serverBaseURL, worktree: path)
            clearProjectLoad(path)
            projectResolutions[path] = nil
            worktreeDirectories[path] = nil
            homeExtraDirectories[path] = nil
        }
        pendingProjectRemoval = nil
        await runHomeLoad(skipProbe: true)
        let name = pending.displayName
        removalSnackbar = OpenCodeSnackbarMessage(
            text: String(localized: LocalizedStringResource("home.snackbar.removed", defaultValue: "Removed \"\(name)\" from home"))
        )
    }

    func consumeProjectRemovalSnackbar() {
        guard removalSnackbar != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeDismissRemovalSnackbar
        )
        removalSnackbar = nil
    }

    func onSessionLongPress(sessionId: String, title: String, worktree: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.home,
            actionId: ProductAnalytics.Action.homeLongPressSession
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        sessionActionsRegion.begin(
            sessionId: sessionId,
            title: title,
            worktree: worktree,
            canArchive: activeConnectionManager.capabilities.supportsSessionArchive
        )
    }

    /// Dismissal from the sheet chrome. Action handlers hide the sheet themselves and
    /// keep the target alive for the dialog they open.
    func dismissSessionActions() {
        if sessionActionsSheetVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeSessionActions,
                actionId: ProductAnalytics.Action.sessionActionsDismiss
            )
        }
        sessionActionsRegion.dismissSheet()
    }

    func openSessionRename() {
        guard sessionActionsTarget != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsRename
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeRenameSession,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        loggedRenameTitleEdit = false
        sessionActionsRegion.chooseRename()
    }

    func updateSessionRenameDraft(_ text: String) {
        // Once per dialog: the title text itself must never reach Analytics.
        if !loggedRenameTitleEdit {
            loggedRenameTitleEdit = true
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeRenameSession,
                actionId: ProductAnalytics.Action.homeRenameEditTitle
            )
        }
        sessionActionsRegion.updateRenameDraft(text)
    }

    func cancelSessionRename() {
        guard !sessionRenameDialog.isSubmitting else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRenameSession,
            actionId: ProductAnalytics.Action.homeRenameCancel
        )
        sessionActionsRegion.cancelRename()
    }

    func confirmSessionRename() async {
        if let outcome = await sessionActionsRegion.confirmRename() {
            logHomeRename(outcome: outcome)
        }
    }

    private func logHomeRename(outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeRenameSession,
            actionId: ProductAnalytics.Action.homeRenameSave,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    func openSessionFork() {
        guard sessionActionsTarget != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsFork
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeForkSession,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        loggedForkTitleEdit = false
        sessionActionsRegion.chooseFork()
    }

    func updateSessionForkDraft(_ text: String) {
        if !loggedForkTitleEdit {
            loggedForkTitleEdit = true
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeForkSession,
                actionId: ProductAnalytics.Action.homeForkEditTitle
            )
        }
        sessionActionsRegion.updateForkDraft(text)
    }

    func cancelSessionFork() {
        guard !sessionForkDialog.isSubmitting else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeForkSession,
            actionId: ProductAnalytics.Action.homeForkCancel
        )
        sessionActionsRegion.cancelFork()
    }

    func confirmSessionFork() async {
        if let outcome = await sessionActionsRegion.confirmFork() {
            logHomeFork(outcome: outcome)
            if outcome == ProductAnalytics.Outcome.success {
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.homeForkResult,
                    presentation: ProductAnalytics.Presentation.dialog,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
        }
    }

    private func logHomeFork(outcome: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeForkSession,
            actionId: ProductAnalytics.Action.homeForkConfirm,
            parameters: [ProductAnalytics.Param.outcome: outcome]
        )
    }

    func chooseSessionForkOpen() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeForkResult,
            actionId: ProductAnalytics.Action.homeForkOpen
        )
        pendingSessionOpen = sessionActionsRegion.takeForkedOpenTarget()
    }

    func chooseSessionForkStay() {
        if sessionForkResult.isVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeForkResult,
                actionId: ProductAnalytics.Action.homeForkStay
            )
        }
        sessionActionsRegion.clearForkedOpenTarget()
    }

    func archiveSessionAction() async {
        guard sessionActionsTarget != nil, sessionActionsCanArchive, !sessionActionInFlight else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsArchive
        )
        await sessionActionsRegion.chooseArchive()
    }

    func requestDeleteSessionAction() {
        guard sessionActionsTarget != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeSessionActions,
            actionId: ProductAnalytics.Action.sessionActionsDelete
        )
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        sessionActionsRegion.chooseDelete()
    }

    func cancelDeleteSessionAction() {
        guard !sessionActionInFlight else { return }
        if sessionDeleteConfirmVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
                actionId: ProductAnalytics.Action.homeDeleteCancel
            )
        }
        sessionActionsRegion.dismissDeleteConfirm()
    }

    func confirmDeleteSessionAction() async {
        guard sessionActionsTarget != nil, !sessionActionInFlight else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.homeDeleteSessionConfirm,
            actionId: ProductAnalytics.Action.homeDeleteConfirm
        )
        await sessionActionsRegion.confirmDelete()
    }

    static func suggestedForkTitle(for title: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Forked session" : "Fork of \(trimmed)"
    }

    private func runHomeLoad(skipProbe: Bool = false, preserveContent: Bool = false) async {
        guard activeConnectionManager.connection != nil else {
            clearProjectLoads()
            stopLiveObservations()
            state = .disconnected
            return
        }

        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
        for worktree in projectLoads.keys where !worktrees.contains(worktree) {
            clearProjectLoad(worktree)
        }

        // Disk-first home: local worktrees + Session Cache paint .loaded
        // without .probing/.loading. Live store then SWR-refreshes.
        if !preserveContent, !worktrees.isEmpty {
            if case .loaded = state, liveObservationsRunning(for: worktrees) {
                reloadWorktreeMarks(worktrees)
                return
            }
            // Projects and worktrees first: the cached rows are built and labeled from them.
            await seedProjectResolutions(worktrees)
            await seedWorktreeLayouts(worktrees)
            let sections = await loadSectionsFromCache(worktrees: worktrees)
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                addProjectSheet: nil,
                projectSessionCreateError: nil
            )
            if !skipProbe {
                do {
                    try await probeServer()
                } catch {
                    // Keep cached home; connection chrome reflects reachability.
                }
            }
            startLiveObservations(worktrees: worktrees)
            reloadWorktreeMarks(worktrees)
            return
        }

        if worktrees.isEmpty, !preserveContent {
            stopLiveObservations()
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            if !skipProbe {
                let probedBaseURL = serverBaseURL
                do {
                    try await probeServer()
                } catch {
                    guard probeFailureIsFinal(probedBaseURL: probedBaseURL) else { return }
                    state = .probeFailed(error.localizedDescription)
                }
            }
            return
        }

        if !skipProbe {
            if !preserveContent {
                state = .probing
            }
            do {
                try await probeServer()
            } catch let error as ServerProbeError {
                if preserveContent { return }
                stopLiveObservations()
                state = .probeFailed(error.localizedDescription)
                return
            } catch {
                if preserveContent { return }
                stopLiveObservations()
                state = .probeFailed(error.localizedDescription)
                return
            }
        }

        let previous = state
        if !preserveContent {
            state = .loading
        }
        do {
            if worktrees.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, addProjectSheet, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        addProjectSheet: addProjectSheet,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
                }
                return
            }

            let sections = await loadSections(worktrees: worktrees)
            if sections.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, addProjectSheet, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        addProjectSheet: addProjectSheet,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
                }
                return
            }
            if case let .loaded(_, showAdd, addProjectSheet, createError) = previous, preserveContent {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: showAdd,
                    addProjectSheet: addProjectSheet,
                    projectSessionCreateError: createError
                )
            } else {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: false,
                    addProjectSheet: nil,
                    projectSessionCreateError: nil
                )
            }
            startLiveObservations(worktrees: worktrees)
            reloadWorktreeMarks(worktrees)
        } catch {
            if preserveContent { return }
            stopLiveObservations()
            state = .probeFailed(error.localizedDescription)
        }
    }

    /// Cached projects for paths not resolved yet, so the cached paint groups (and keys its
    /// scopes) the way the live load will. A key that changes later reloads the whole list.
    private func seedProjectResolutions(_ worktrees: [String]) async {
        let missing = worktrees.filter { projectResolutions[$0] == nil }
        for (path, resolution) in await cachedProjectResolutions(missing, connectionID: nil)
            where projectResolutions[path] == nil {
            projectResolutions[path] = resolution
        }
    }

    /// Cached worktree lists and branches for paths whose live inventory has not loaded, so a
    /// cold Home shows the worktree labels and project chip the live load will, not a relabel.
    private func seedWorktreeLayouts(_ worktrees: [String]) async {
        let missing = worktrees.filter { worktreeInventories[$0] == nil }
        applyWorktreeLayouts(await cachedWorktreeLayouts(missing, connectionID: nil))
    }

    private func cachedWorktreeLayouts(
        _ worktrees: [String],
        connectionID: String?
    ) async -> [String: HomeWorktreeLayout] {
        var layouts: [String: HomeWorktreeLayout] = [:]
        for worktree in worktrees {
            if let layout = await sessionCache.seedWorktreeLayout(worktree: worktree, connectionID: connectionID) {
                layouts[worktree] = layout
            }
        }
        return layouts
    }

    /// `replacing` drops what another server left for the same paths (a profile switch paint).
    private func applyWorktreeLayouts(_ layouts: [String: HomeWorktreeLayout], replacing: Bool = false) {
        if replacing {
            for worktree in layouts.keys {
                worktreeInventories[worktree] = nil
                worktreeProjectIDs[worktree] = nil
            }
        }
        var branches: [String: String] = [:]
        for (worktree, layout) in layouts where worktreeInventories[worktree] == nil {
            worktreeDirectories[worktree] = layout.directories
            worktreeInventories[worktree] = .loaded(layout.directories)
            if worktreeProjectIDs[worktree] == nil, let resolution = projectResolutions[worktree], resolution.isMergeable {
                worktreeProjectIDs[worktree] = resolution.id
            }
            rememberedWorktreeLayouts[worktree] = layout
            branches.merge(layout.branches) { current, _ in current }
        }
        if !branches.isEmpty {
            branchLookups.seed(branches)
        }
    }

    /// Remembers what `addedPath` lists now (worktrees and their branches), when it changed.
    private func rememberWorktreeLayout(_ addedPath: String) {
        guard let directories = worktreeDirectories[addedPath], !directories.isEmpty else { return }
        var members = Set(directories.map(ServerPathPolicy.canonical))
        members.insert(ServerPathPolicy.canonical(addedPath))
        if let main = projectResolutions[addedPath]?.mainCheckout { members.insert(main) }
        let layout = HomeWorktreeLayout(
            directories: directories,
            branches: worktreeBranches.filter { members.contains($0.key) }
        )
        guard rememberedWorktreeLayouts[addedPath] != layout else { return }
        rememberedWorktreeLayouts[addedPath] = layout
        sessionCache.rememberWorktreeLayout(worktree: addedPath, layout: layout)
    }

    private func cachedProjectResolutions(
        _ worktrees: [String],
        connectionID: String?
    ) async -> [String: HomeProjectResolution] {
        var resolutions: [String: HomeProjectResolution] = [:]
        for worktree in worktrees {
            if let resolution = await sessionCache.seedProjectResolution(worktree: worktree, connectionID: connectionID) {
                resolutions[worktree] = resolution
            }
        }
        return resolutions
    }

    private func loadSectionsFromCache(worktrees: [String]) async -> [ProjectSectionItem] {
        var sections: [ProjectSectionItem] = []
        sections.reserveCapacity(worktrees.count)
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree))
        }
        return sections
    }

    /// False while another saved address may still answer: the address check's result reloads
    /// Home. Also false when the address moved during the probe, since a newer load is running.
    /// Probes the server; its answer can turn on capabilities the menu entry was waiting for.
    private func probeServer() async throws {
        try await projectRepository.probeServer()
        archivedMenu.recheck(projects: homeProjects)
    }

    private func probeFailureIsFinal(probedBaseURL: String) -> Bool {
        guard serverBaseURL == probedBaseURL else { return false }
        if connectionSessionCoordinator.addressCheck.state == .running {
            probeWaitsOnAddressCheck = true
            return false
        }
        return true
    }

    /// `connectionID` reads another profile's cache (a switch before its server is bound); that
    /// paint has no cached activity, which belongs to the bound profile.
    private func sectionFromCache(worktree: String, connectionID: String? = nil) async -> ProjectSectionItem {
        let seeded: [Session]?
        let cachedActivity: [SessionActivityCacheEntry]
        if let connectionID {
            seeded = await sessionCache.seedSessions(worktree: worktree, connectionID: connectionID)
            cachedActivity = []
        } else {
            seeded = await sessionCache.seedSessions(worktree: worktree)
            cachedActivity = await sessionCache.seedActivity(directory: worktree) ?? []
        }
        let cachedSnapshot = SessionActivitySnapshot(
            entries: Dictionary(
                cachedActivity.map { entry in
                    (
                        entry.sessionId,
                        SessionActivityEntry(activity: entry.activity, source: .cached, observedAtMs: entry.observedAtMs)
                    )
                },
                uniquingKeysWith: { _, latest in latest }
            ),
            seeded: false,
            fromCache: true
        )
        let items = HomeSessionRows.build(HomeSessionRowsInput(
            worktree: worktree,
            sessions: seeded ?? [],
            activity: cachedSnapshot,
            readyIDs: [],
            ancestry: sessionLiveStore.ancestry.snapshot(),
            projectID: rowScopeProjectID(worktree),
            readsServerUnread: false
        )).items
        let name = ProjectDomainAdapter.displayName(from: worktree)
        return ProjectSectionItem(
            id: worktree,
            worktree: worktree,
            displayName: name,
            pathSubtitle: worktree,
            letter: letterCharacter(for: name),
            letterAccent: letterAccent(for: worktree),
            allSessions: items,
            loadError: nil,
            sessionsFromCache: seeded != nil,
            projectID: worktreeProjectIDs[worktree],
            worktreeInventory: worktreeInventories[worktree] ?? .unloaded
        )
    }

    private func loadSections(worktrees: [String]) async -> [ProjectSectionItem] {
        await withTaskGroup(of: (String, ProjectSectionItem).self) { group in
            for worktree in worktrees {
                group.addTask {
                    (worktree, await self.loadSection(worktree: worktree))
                }
            }
            var byWorktree: [String: ProjectSectionItem] = [:]
            for await (worktree, section) in group {
                byWorktree[worktree] = section
            }
            return worktrees.compactMap { byWorktree[$0] }
        }
    }

    private func loadSection(worktree: String) async -> ProjectSectionItem {
        do {
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            let window = try await sessionRepository.listRootSessionsWindow(
                directory: worktree,
                limit: HomeSessionPaging.pageSize
            )
            let items = HomeSessionRows.build(rowsInput(
                worktree: worktree,
                sessions: window.sessions,
                readyIds: liveReadyIdsByWorktree[worktree] ?? []
            )).items
            let name = ProjectDomainAdapter.displayName(from: worktree)
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                allSessions: items,
                loadError: nil
            )
        } catch {
            let name = ProjectDomainAdapter.displayName(from: worktree)
            return ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                allSessions: [],
                loadError: error.localizedDescription
            )
        }
    }

    /// Loads home, the added projects, the server's projects, and `/Volumes` for one presentation.
    private func loadAddProjectSheet(generation: Int) async {
        addProjectInputs.pathsError = nil
        publishAddProjectSheet()
        let added = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
        let paths: ServerPaths
        do {
            paths = try await filesystemRepository.getPaths()
        } catch {
            guard generation == addProjectGeneration else { return }
            DiagnosticLog.warning(.connection, "add project: server paths failed", error: error)
            addProjectInputs.added = added
            addProjectInputs.pathsError = AddProjectSheetState.listErrorMessage
            publishAddProjectSheet()
            return
        }
        guard generation == addProjectGeneration else { return }
        let home = ServerPathPolicy.canonical(paths.home)
        addProjectInputs.added = added
        addProjectInputs.addedProjectIDs = Set(homeProjects.compactMap { project in
            project.key.hasPrefix(HomeProjectGrouping.pathKeyPrefix) ? nil : project.key
        })
        addProjectInputs.home = home
        addProjectInputs.fieldText = ServerPathPolicy.fieldText(home)
        publishAddProjectSheet()

        let projectRepository = projectRepository
        let filesystemRepository = filesystemRepository
        // Suggestions and volumes are optional: failures hide them.
        async let projects = (try? await projectRepository.listProjects()) ?? []
        async let volumes: [DirectoryNode] = ServerPathPolicy.root(home) == "/" && !ServerPathPolicy.isWindows(home)
            ? (try? await filesystemRepository.listDirectory(path: "/Volumes"))?.folders ?? []
            : []
        if let directory = addProjectSheet.directory {
            await loadAddProjectListing(directory, generation: generation)
        }
        let loadedProjects = await projects
        let loadedVolumes = await volumes
        guard generation == addProjectGeneration else { return }
        addProjectInputs.serverProjects = loadedProjects
        addProjectInputs.suggestionsResolved = true
        addProjectInputs.volumes = loadedVolumes.filter { !DirectoryListingPolicy.isHidden($0.name) }
        publishAddProjectSheet()
    }

    /// Points the field at `folder` and lists it now (taps skip the typing pause).
    private func showAddProjectFolder(_ folder: String) async {
        addProjectListingTask?.cancel()
        addProjectInputs.fieldText = ServerPathPolicy.fieldText(folder)
        addProjectInputs.fieldError = nil
        publishAddProjectSheet()
        if let directory = addProjectSheet.directory {
            await loadAddProjectListing(directory, generation: addProjectGeneration)
        }
    }

    /// Lists the field's folder after the typing pause unless it is cached or already loading.
    private func scheduleAddProjectListing() {
        addProjectListingTask?.cancel()
        addProjectListingTask = nil
        guard let directory = addProjectSheet.directory,
              addProjectInputs.listings[directory] == nil,
              addProjectInputs.listErrors[directory] == nil,
              !addProjectInputs.missingDirectories.contains(directory),
              !addProjectListingsInFlight.contains(directory)
        else { return }
        let generation = addProjectGeneration
        let debounce = addProjectListingDebounce
        addProjectListingTask = Task { [weak self] in
            if debounce > .zero {
                try? await Task.sleep(for: debounce)
            }
            guard !Task.isCancelled, let self else { return }
            // The fetch outlives later keystrokes so its listing still lands in the cache.
            Task { await self.loadAddProjectListing(directory, generation: generation) }
        }
    }

    private func loadAddProjectListing(_ directory: String, generation: Int) async {
        guard generation == addProjectGeneration,
              addProjectInputs.listings[directory] == nil,
              !addProjectInputs.missingDirectories.contains(directory),
              !addProjectListingsInFlight.contains(directory)
        else { return }
        addProjectListingsInFlight.insert(directory)
        addProjectInputs.listErrors[directory] = nil
        publishAddProjectSheet()
        let result: Result<DirectoryListing, Error>
        do {
            result = .success(try await filesystemRepository.listDirectory(path: directory))
        } catch {
            result = .failure(error)
        }
        guard generation == addProjectGeneration else { return }
        addProjectListingsInFlight.remove(directory)
        switch result {
        case let .success(listing):
            storeAddProjectListing(listing, for: directory)
        case .failure(FilesystemError.directoryNotFound):
            addProjectInputs.missingDirectories.insert(directory)
        case let .failure(error):
            DiagnosticLog.warning(.connection, "add project: folder listing failed", error: error)
            addProjectInputs.listErrors[directory] = AddProjectSheetState.listErrorMessage
        }
        publishAddProjectSheet()
    }

    private func storeAddProjectListing(_ listing: DirectoryListing, for directory: String) {
        addProjectInputs.listings[directory] = listing
        if ServerPathPolicy.isWindows(directory) {
            let root = ServerPathPolicy.root(directory)
            if !addProjectInputs.listedRoots.contains(root) {
                addProjectInputs.listedRoots.append(root)
            }
        }
    }

    private func resetAddProjectLoads() {
        addProjectListingsInFlight = []
        addProjectSummaryQueue = []
        addProjectSummariesInFlight = []
        addProjectSummaryFailures = []
    }

    /// Starts queued row listings up to `folderSummaryConcurrency` at once. A listing that
    /// lands fills the browser's cache, so entering that folder needs no new request.
    private func startAddProjectSummaries() {
        let generation = addProjectGeneration
        while addProjectSummariesInFlight.count < AddProjectSheetState.folderSummaryConcurrency,
              !addProjectSummaryQueue.isEmpty {
            let path = addProjectSummaryQueue.removeFirst()
            guard addProjectInputs.listings[path] == nil else { continue }
            addProjectSummariesInFlight.insert(path)
            let filesystemRepository = filesystemRepository
            Task { [weak self] in
                let listing = try? await filesystemRepository.listDirectory(path: path)
                guard let self, generation == self.addProjectGeneration else { return }
                self.addProjectSummariesInFlight.remove(path)
                if let listing {
                    if self.addProjectInputs.listings[path] == nil {
                        self.storeAddProjectListing(listing, for: path)
                        self.publishAddProjectSheet()
                    }
                } else {
                    self.addProjectSummaryFailures.insert(path)
                }
                self.startAddProjectSummaries()
            }
        }
    }

    private func publishAddProjectSheet() {
        guard isAddProjectPresented else { return }
        let sheet = AddProjectSheetState.make(addProjectInputs)
        if sheet != addProjectSheet {
            addProjectSheet = sheet
        }
        switch state {
        case let .empty(showAdd, current, createError):
            guard !showAdd || current != sheet else { return }
            state = .empty(showAddProjectSheet: true, addProjectSheet: sheet, projectSessionCreateError: createError)
        case let .loaded(sections, showAdd, current, createError):
            guard !showAdd || current != sheet else { return }
            state = .loaded(
                sections: sections,
                showAddProjectSheet: true,
                addProjectSheet: sheet,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    private func startLiveObservations(worktrees: [String]) {
        if liveObservationsRunning(for: worktrees) { return }
        stopLiveObservations()
        observedWorktrees = worktrees
        liveObservationTasks =
            worktrees.map { worktree in
                Task {
                    await observeWorktree(worktree)
                }
            }
        worktreeEventTask?.cancel()
        if let foregroundEventStream {
            worktreeEventTask = Task { [weak self] in
                for await event in foregroundEventStream.events() {
                    if event.type == "worktree.updated", let projectID = event.namedProjectID {
                        await self?.refetchWorktreeMark(projectID)
                    } else if event.triggersHomeRefresh {
                        self?.scheduleWorktreeExtrasRefresh()
                    }
                }
            }
        }
    }

    private func reloadWorktreeMarks(_ worktrees: [String]) {
        worktreeMarkTask?.cancel()
        worktreeMarks.onConnection(serverBaseURL)
        worktreeMarks.setAddedPaths(worktrees)
        worktreeInventoryCache.setAddedPaths(worktrees, server: serverBaseURL)
        branchLookups.refresh()
        loadServerProjectInfo()
        worktreeMarkTask = Task { @MainActor [weak self] in
            guard let self else { return }
            for worktree in worktrees {
                guard !Task.isCancelled else { return }
                let update = await self.worktreeMarks.load(addedPath: worktree)
                guard !Task.isCancelled else { return }
                self.applyWorktreeMark(update)
            }
        }
    }

    private func refetchWorktreeMark(_ projectID: String) async {
        guard worktreeMarks.knows(projectID) else { return }
        for update in await worktreeMarks.refetch(projectID: projectID) {
            applyWorktreeMark(update)
        }
    }

    /// v1: sessions in worktrees nobody added refresh on the same session events (and debounce)
    /// as the added paths' own lists.
    private func scheduleWorktreeExtrasRefresh() {
        worktreeExtrasRefreshTask?.cancel()
        worktreeExtrasRefreshTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
            guard !Task.isCancelled, let self else { return }
            for update in await self.worktreeMarks.refreshExtras() {
                guard !Task.isCancelled else { return }
                self.applyWorktreeMark(update)
            }
        }
    }

    /// Server project names and VCS for display names; optional, a failure keeps folder names.
    private func loadServerProjectInfo() {
        serverProjectsTask?.cancel()
        let projectRepository = projectRepository
        serverProjectsTask = Task { @MainActor [weak self] in
            guard let projects = try? await projectRepository.listProjects() else { return }
            guard !Task.isCancelled, let self else { return }
            var info: [String: (name: String?, isRepository: Bool)] = [:]
            for project in projects where info[project.id] == nil {
                info[project.id] = (project.name, project.isRepository)
            }
            self.serverProjectInfo = info
        }
    }

    /// Pull to refresh asks every worktree for its branch again.
    private func refreshBranches() {
        branchLookups.refresh()
        lookUpBranchesForResolvedProjects()
    }

    /// Branch names load behind the session list for every worktree of a resolved project.
    private func lookUpBranchesForResolvedProjects() {
        for project in homeProjects where project.memberPaths.contains(where: { projectResolutions[$0] != nil }) {
            branchLookups.lookUp(project.worktrees.map(\.directory))
        }
    }

    private func applyWorktreeMark(_ update: WorktreeMarkUpdate) {
        if serverCanCreateWorktrees != worktreeRepository.canCreateWorktrees {
            serverCanCreateWorktrees = worktreeRepository.canCreateWorktrees
        }
        worktreeInventories[update.addedPath] = update.inventory
        worktreeInventoryCache.record(update.inventory, addedPath: update.addedPath, server: serverBaseURL)
        if let projectID = update.projectID {
            worktreeProjectIDs[update.addedPath] = projectID
        }
        if let resolution = update.resolution, projectResolutions[update.addedPath] != resolution {
            projectResolutions[update.addedPath] = resolution
            sessionCache.rememberProjectResolution(worktree: update.addedPath, resolution: resolution)
        }
        if homeExtraDirectories[update.addedPath] ?? [] != update.extraDirectories {
            homeExtraDirectories[update.addedPath] = update.extraDirectories.isEmpty ? nil : update.extraDirectories
        }
        // A failed reload keeps the worktrees Home already knows.
        if update.inventory != .failed, worktreeDirectories[update.addedPath] != update.directories {
            worktreeDirectories[update.addedPath] = update.directories
        }
        if case .loaded = update.inventory {
            rememberWorktreeLayout(update.addedPath)
        }
        // The inventory may have grown (v2 lists again after its refresh): ask for new worktrees.
        lookUpBranchesForResolvedProjects()
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == update.addedPath }) else { return }
        let updated = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: worktreeMarks.applyExtras(
                addedPath: update.addedPath,
                current: section.allSessions,
                extras: update.extras
            ),
            loadError: section.loadError,
            sessionsFromCache: section.sessionsFromCache,
            projectID: worktreeProjectIDs[update.addedPath] ?? section.projectID,
            worktreeInventory: update.inventory,
            hasMoreSessions: section.hasMoreSessions,
            oldestWindowUpdatedAtEpochMs: section.oldestWindowUpdatedAtEpochMs
        )
        state = .loaded(
            sections: sections.map { $0.worktree == update.addedPath ? updated : $0 },
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    private func stopLiveObservations() {
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        observedWorktrees = []
        worktreeMarkTask?.cancel()
        worktreeMarkTask = nil
        worktreeEventTask?.cancel()
        worktreeEventTask = nil
        worktreeExtrasRefreshTask?.cancel()
        worktreeExtrasRefreshTask = nil
        liveSessionsByWorktree = [:]
        liveStatusesByWorktree = [:]
        liveReadyIdsByWorktree = [:]
        latestRowBuild = [:]
        projectLoadsAwaitingRows = []
        ancestryTasks.values.forEach { $0.cancel() }
        ancestryTasks = [:]
        ancestryPending = [:]
        loadMoreTask?.cancel()
        loadMoreTask = nil
        isLoadingMoreSessions = false
    }

    private func liveObservationsRunning(for worktrees: [String]) -> Bool {
        !liveObservationTasks.isEmpty && observedWorktrees == worktrees
    }

    private func observeWorktree(_ worktree: String) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await snapshot in self.sessionLiveStore.observeSessions(directory: worktree) {
                    self.liveSessionsByWorktree[worktree] = snapshot
                    self.applyLiveUpdate(worktree: worktree)
                    // Straight from the session list: the combined update waits on all three streams.
                    self.noteProjectLoad(worktree: worktree, snapshot: snapshot)
                }
            }
            group.addTask { @MainActor in
                for await snapshot in self.sessionStatusLiveStore.observeActivity(directory: worktree) {
                    self.liveStatusesByWorktree[worktree] = snapshot
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
            group.addTask { @MainActor in
                for await readyIds in self.sessionReadyStore.observeReadyIds(directory: worktree) {
                    self.liveReadyIdsByWorktree[worktree] = readyIds
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
        }
    }

    // MARK: - First project load

    private func beginProjectLoad(_ worktree: String, showImmediately: Bool) {
        projectLoads[worktree]?.timers.forEach { $0.cancel() }
        projectLoadAttempt += 1
        let attempt = projectLoadAttempt
        var tracker = ProjectLoadTracker(attempt: attempt)
        if showImmediately {
            tracker.shownAt = .now
            setProjectLoadState(worktree, .loading(isSlow: false))
        } else {
            setProjectLoadState(worktree, nil)
        }
        let timing = projectLoadTiming
        tracker.timers = [
            projectLoadTimer(worktree, attempt: attempt, after: timing.grace) { $0.exposeProjectLoad(worktree) },
            projectLoadTimer(worktree, attempt: attempt, after: timing.slowThreshold) { $0.markProjectLoadSlow(worktree) },
            projectLoadTimer(worktree, attempt: attempt, after: timing.timeout) { $0.failProjectLoad(worktree) },
        ]
        projectLoads[worktree] = tracker
    }

    private func projectLoadTimer(
        _ worktree: String,
        attempt: Int,
        after delay: Duration,
        fire: @escaping @MainActor (ProjectListHomeViewModel) -> Void
    ) -> Task<Void, Never> {
        Task { @MainActor [weak self] in
            if delay > .zero {
                try? await Task.sleep(for: delay)
            } else {
                await Task.yield()
            }
            guard !Task.isCancelled, let self, self.projectLoads[worktree]?.attempt == attempt else { return }
            fire(self)
        }
    }

    private func exposeProjectLoad(_ worktree: String) {
        guard var tracker = projectLoads[worktree], tracker.isPending, tracker.shownAt == nil else { return }
        tracker.shownAt = .now
        projectLoads[worktree] = tracker
        setProjectLoadState(worktree, .loading(isSlow: tracker.isSlow))
    }

    private func markProjectLoadSlow(_ worktree: String) {
        guard var tracker = projectLoads[worktree], tracker.isPending else { return }
        tracker.isSlow = true
        projectLoads[worktree] = tracker
        if tracker.shownAt != nil {
            setProjectLoadState(worktree, .loading(isSlow: true))
        }
    }

    private func failProjectLoad(_ worktree: String) {
        guard var tracker = projectLoads[worktree], tracker.isPending else { return }
        tracker.timers.forEach { $0.cancel() }
        tracker.timers = []
        tracker.failed = true
        projectLoads[worktree] = tracker
        setProjectLoadState(worktree, .failed(message: ProjectLoadState.failedMessage))
    }

    /// The first server list landed. Loading that was on screen stays for its minimum time.
    private func completeProjectLoad(_ worktree: String) {
        guard var tracker = projectLoads[worktree], !tracker.completing else { return }
        tracker.timers.forEach { $0.cancel() }
        tracker.timers = []
        let remaining = tracker.failed ? nil : tracker.shownAt.map { projectLoadTiming.minimumVisible - $0.duration(to: .now) }
        guard let remaining, remaining > .zero else {
            clearProjectLoad(worktree)
            return
        }
        tracker.completing = true
        tracker.timers = [
            projectLoadTimer(worktree, attempt: tracker.attempt, after: remaining) { $0.clearProjectLoad(worktree) },
        ]
        projectLoads[worktree] = tracker
    }

    private func noteProjectLoad(worktree: String, snapshot: SessionListSnapshot) {
        guard let tracker = projectLoads[worktree], !tracker.completing else { return }
        if snapshot.loadFailed {
            projectLoadsAwaitingRows.remove(worktree)
            failProjectLoad(worktree)
        } else if !snapshot.isFromCache {
            // The skeleton clears once these rows are on screen.
            projectLoadsAwaitingRows.insert(worktree)
            // Rows land with the load even when status or ready ids have not emitted yet.
            if liveStatusesByWorktree[worktree] == nil || liveReadyIdsByWorktree[worktree] == nil {
                scheduleRowBuild(
                    worktree: worktree,
                    snapshot: snapshot,
                    readyIds: liveReadyIdsByWorktree[worktree] ?? []
                )
            }
        }
    }

    private func clearProjectLoad(_ worktree: String) {
        projectLoads.removeValue(forKey: worktree)?.timers.forEach { $0.cancel() }
        setProjectLoadState(worktree, nil)
    }

    private func clearProjectLoads() {
        projectLoads.values.forEach { $0.timers.forEach { $0.cancel() } }
        projectLoads = [:]
        if !projectLoadStates.isEmpty {
            projectLoadStates = [:]
        }
    }

    private func setProjectLoadState(_ worktree: String, _ loadState: ProjectLoadState?) {
        guard projectLoadStates[worktree] != loadState else { return }
        projectLoadStates[worktree] = loadState
    }

    private func applyLiveUpdate(worktree: String) {
        guard let snapshot = liveSessionsByWorktree[worktree] else { return }
        guard liveStatusesByWorktree[worktree] != nil else { return }
        guard let readyIds = liveReadyIdsByWorktree[worktree] else { return }
        scheduleRowBuild(worktree: worktree, snapshot: snapshot, readyIds: readyIds)
    }

    /// Builds the section's rows off the main actor and applies them unless a newer build for
    /// the worktree started meanwhile.
    private func scheduleRowBuild(worktree: String, snapshot: SessionListSnapshot, readyIds: Set<String>) {
        rowBuildSerial += 1
        let serial = rowBuildSerial
        latestRowBuild[worktree] = serial
        let input = rowsInput(worktree: worktree, sessions: snapshot.sessions, readyIds: readyIds)
        Task { [weak self] in
            let result = await Task.detached(priority: .userInitiated) { HomeSessionRows.build(input) }.value
            guard let self, self.latestRowBuild[worktree] == serial else { return }
            self.applyRows(result.items, worktree: worktree, snapshot: snapshot)
            if !result.unresolvedBusyIDs.isEmpty {
                self.resolveBusyRoots(result.unresolvedBusyIDs, worktree: worktree)
            }
        }
    }

    private func rowsInput(worktree: String, sessions: [Session], readyIds: Set<String>) -> HomeSessionRowsInput {
        HomeSessionRowsInput(
            worktree: worktree,
            sessions: sessions,
            activity: liveStatusesByWorktree[worktree],
            readyIDs: readyIds,
            ancestry: sessionLiveStore.ancestry.snapshot(),
            projectID: rowScopeProjectID(worktree),
            readsServerUnread: activeConnectionManager.capabilities.supportsSessionViewed
        )
    }

    /// The repository project an added path lists whole (worktrees of one repository merge).
    private func rowScopeProjectID(_ worktree: String) -> String? {
        guard let resolution = projectResolutions[worktree], resolution.isMergeable else { return nil }
        return resolution.id
    }

    /// Looks up the roots of busy sessions Home cannot place yet, then rebuilds the section when
    /// that taught anything. One lookup per worktree at a time; ids asked for meanwhile wait for it.
    private func resolveBusyRoots(_ ids: [String], worktree: String) {
        guard ancestryTasks[worktree] == nil else {
            ancestryPending[worktree, default: []].formUnion(ids)
            return
        }
        let ancestry = sessionLiveStore.ancestry
        let loadedRoots = Set(liveSessionsByWorktree[worktree]?.sessions.map(\.id) ?? [])
        ancestryTasks[worktree] = Task { [weak self] in
            // Same priority as the row build it feeds: these rows are on screen.
            let resolution = await Task.detached(priority: .userInitiated) {
                await ancestry.resolveRoots(ids: ids, directory: worktree, loadedRoots: loadedRoots)
            }.value
            guard !Task.isCancelled, let self else { return }
            self.ancestryTasks[worktree] = nil
            let waited = self.ancestryPending.removeValue(forKey: worktree) ?? []
            if resolution.learned || !waited.isEmpty {
                self.applyLiveUpdate(worktree: worktree)
            }
        }
    }

    private func applyRows(_ items: [SessionListItem], worktree: String, snapshot: SessionListSnapshot) {
        defer {
            if projectLoadsAwaitingRows.remove(worktree) != nil {
                completeProjectLoad(worktree)
            }
        }
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let updatedSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: worktreeMarks.withLive(addedPath: worktree, primary: items),
            loadError: section.loadError,
            sessionsFromCache: snapshot.isFromCache,
            projectID: worktreeProjectIDs[worktree] ?? section.projectID,
            worktreeInventory: worktreeInventories[worktree] ?? section.worktreeInventory,
            hasMoreSessions: snapshot.hasMore,
            oldestWindowUpdatedAtEpochMs: snapshot.sessions.compactMap(\.updatedAtEpochMs).min()
        )
        guard updatedSection != section else { return }
        let updatedSections =
            sections.map { current in
                current.worktree == worktree ? updatedSection : current
            }
        state = .loaded(
            sections: updatedSections,
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    // MARK: - Load more

    /// Near the end of Home's list: grows the window of every directory in the scope that has
    /// more. `scopeId` is the selected filter (All, a project, or a worktree scope).
    func loadMoreSessions(scopeId: String) {
        guard loadMoreTask == nil else { return }
        let directories = observedDirectories(inScope: scopeId).filter(windowCanLoadMore)
        guard !directories.isEmpty else { return }
        isLoadingMoreSessions = true
        let store = sessionLiveStore
        loadMoreTask = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                for directory in directories {
                    group.addTask { await store.loadMore(directory: directory) }
                }
            }
            guard !Task.isCancelled, let self else { return }
            self.loadMoreTask = nil
            self.isLoadingMoreSessions = false
        }
    }

    /// The loading footer under Home's list: a load more runs and the scope still has more.
    func showsLoadMoreFooter(scopeId: String) -> Bool {
        guard isLoadingMoreSessions else { return false }
        return observedDirectories(inScope: scopeId).contains { liveSessionsByWorktree[$0]?.hasMore == true }
    }

    /// Whether a directory of the scope has older sessions to load. A window whose refresh failed
    /// stays out until a refresh succeeds, so paging never retries in a loop.
    func canLoadMoreSessions(scopeId: String) -> Bool {
        observedDirectories(inScope: scopeId).contains(where: windowCanLoadMore)
    }

    private func windowCanLoadMore(_ directory: String) -> Bool {
        guard let snapshot = liveSessionsByWorktree[directory] else { return false }
        return snapshot.hasMore && !snapshot.loadFailed
    }

    private func observedDirectories(inScope scopeId: String) -> [String] {
        let projects = homeProjects
        guard let key = HomeScope.resolve(id: scopeId, projects: projects)?.projectKey,
              let project = projects.first(where: { $0.key == key })
        else { return observedWorktrees }
        return observedWorktrees.filter { project.memberPaths.contains($0) }
    }

    private func setProjectSessionCreateError(_ message: String?) {
        switch state {
        case let .empty(showAdd, addProjectSheet, _):
            state = .empty(showAddProjectSheet: showAdd, addProjectSheet: addProjectSheet, projectSessionCreateError: message)
        case let .loaded(sections, showAdd, addProjectSheet, _):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: showAdd,
                addProjectSheet: addProjectSheet,
                projectSessionCreateError: message
            )
        default:
            break
        }
    }

}

/// Bookkeeping for one project's first load; ``ProjectLoadState`` is what the UI sees.
private struct ProjectLoadTracker {
    let attempt: Int
    /// When loading became visible; nil while inside the grace delay.
    var shownAt: ContinuousClock.Instant?
    var isSlow = false
    var failed = false
    /// Loaded; only the minimum-visible hold is left.
    var completing = false
    var timers: [Task<Void, Never>] = []

    var isPending: Bool { !failed && !completing }
}
