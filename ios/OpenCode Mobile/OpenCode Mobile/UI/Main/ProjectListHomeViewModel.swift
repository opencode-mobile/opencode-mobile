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
    /// Archived and settled sessions listed after the active ones; T3 only.
    var archivedSessions: [SessionListItem] = []
}

/// Where a home session row sits in its lifecycle; drives the long-press actions.
enum HomeSessionLifecycle: Equatable {
    case active
    case archived
    case settled
    case snoozed
}

/// A subfolder row in the Add project sheet.
struct AddProjectFolderItem: Identifiable, Equatable {
    var id: String { path }
    let name: String
    let path: String
    /// Already added to home on this server.
    let isAdded: Bool
}

/// A chip that jumps the path field to a root the server cannot enumerate (Home, `/`, a
/// mounted volume, a Windows drive or share).
struct AddProjectRoot: Identifiable, Equatable {
    var id: String { path }
    let label: String
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
    static let pickFolderTitle = "Pick a folder"
    static let alreadyAddedTitle = "Already on home"
    static let homeFolderTitle = "Add home folder"
    static let noSubfoldersMessage = "No subfolders"
    static let truncatedListingHint = "Showing the first \(DirectoryListingPolicy.maxVisibleDirectories) folders. Type more of the name to narrow the list."
    /// Listing failures never show server text, which can carry paths or HTML.
    static let listErrorMessage = "Check the path and try again."

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
    var truncationHint: String?
    var isLoading = false
    var listError: String?
    /// Shown when the listing loaded but no row matches.
    var emptyMessage: String?
    var roots: [AddProjectRoot] = []
    var suggestions: [AddProjectSuggestion] = []
    var showSuggestions = false
    /// Header over the folder rows while suggestions are shown above them.
    var folderSectionTitle: String?
    var upTarget: String?
    var addTarget: String?
    var addTitle = pickFolderTitle
    var addEnabled = false
    var isValidating = false
    var fieldError: String?

    static let empty = AddProjectSheetState()

    static func emptyFilterMessage(directory: String, filter: String) -> String {
        "Nothing in \(ServerPathPolicy.name(directory)) starts with \u{201C}\(filter)\u{201D}"
    }

    /// Builds the sheet from what the Screen Model has loaded for this presentation.
    static func make(_ inputs: AddProjectSheetInputs) -> AddProjectSheetState {
        var sheet = AddProjectSheetState()
        sheet.fieldText = inputs.fieldText
        sheet.homePath = inputs.home
        sheet.isValidating = inputs.isValidating
        sheet.fieldError = inputs.fieldError
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
            .suggestions(from: inputs.serverProjects, excluding: inputs.added)
            .map { project in
                AddProjectSuggestion(
                    name: project.displayName,
                    path: project.worktree,
                    activityLabel: project.activityAtEpochMs.map { RelativeTimeFormatting.format(epochMs: $0) }
                )
            }

        guard let directory = parts.directory else { return sheet }
        let filter = parts.filter
        sheet.upTarget = filter.isEmpty ? ServerPathPolicy.parentPath(directory) : directory
        let listing = inputs.listings[directory]
        if let error = inputs.listErrors[directory] {
            sheet.listError = error
        } else if listing == nil {
            sheet.isLoading = true
        }
        sheet.showSuggestions = directory == inputs.home && filter.isEmpty && !sheet.suggestions.isEmpty
        if sheet.showSuggestions {
            sheet.folderSectionTitle = "In \(ServerPathPolicy.name(directory))"
        }

        if let listing {
            let visible = DirectoryListingPolicy.visibleEntries(listing, filter: filter)
            sheet.entries = visible.rows
                .map { node in
                    AddProjectFolderItem(
                        name: node.name,
                        path: node.absolutePath,
                        isAdded: added.contains(ServerPathPolicy.canonical(node.absolutePath))
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
                let windows = ServerPathPolicy.isWindows(directory)
                sheet.addTarget = listing.first { node in
                    windows ? node.name.caseInsensitiveCompare(filter) == .orderedSame : node.name == filter
                }?.absolutePath
            }
        }

        if let target = sheet.addTarget {
            if added.contains(ServerPathPolicy.canonical(target)) {
                sheet.addTitle = alreadyAddedTitle
            } else {
                sheet.addTitle = ServerPathPolicy.canonical(target) == inputs.home
                    ? homeFolderTitle
                    : "Add \(ServerPathPolicy.name(target))"
                sheet.addEnabled = !inputs.isValidating
            }
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
        var chips: [(label: String, path: String)] = [("Home", inputs.home)]
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
            chips += ([homeRoot] + others).map { (rootLabel($0), $0) }
        } else if ServerPathPolicy.root(inputs.home) == "/" {
            chips.append(("/", "/"))
            chips += inputs.volumes.map { ($0.name, $0.absolutePath) }
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
    var pathsError: String?
    var added: [String] = []
    /// Successful full listings (every child folder, hidden ones included, uncapped) by
    /// canonical folder, kept for the sheet's lifetime.
    var listings: [String: [DirectoryNode]] = [:]
    var listErrors: [String: String] = [:]
    var serverProjects: [Project] = []
    /// Folders in `/Volumes` on a POSIX server.
    var volumes: [DirectoryNode] = []
    /// Roots of folders the field listed this session (Windows drives and shares).
    var listedRoots: [String] = []
    var isValidating = false
    var fieldError: String?
}

struct ProjectRemovalItem: Equatable {
    let worktree: String
    let displayName: String
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
    var errorMessage: String?
}

struct HomeSessionForkResultState: Equatable {
    var isVisible = false
    var forkedTitle = ""
}

struct HomeServersChrome: Equatable {
    var title = "Sessions"
    var isSwitcher = false
    var active: ConnectionProfile?
    var others: [ConnectionProfile] = []
    var sheetVisible = false
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
    private let addProjectListingDebounce: Duration
    private(set) var pendingProjectRemoval: ProjectRemovalItem?
    /// Shared home snackbar: project removal plus archive / delete failures.
    private(set) var removalSnackbar: OpenCodeSnackbarMessage?
    var sessionActionsTarget: HomeSessionActionTarget? { sessionActionsRegion.target }
    var sessionActionsSheetVisible: Bool { sessionActionsRegion.sheetVisible }
    /// Stays false until a message fetch confirms the session is forkable.
    var sessionActionsCanFork: Bool { sessionActionsRegion.canFork }
    var sessionActionsCanArchive: Bool { sessionActionsRegion.canArchive }
    var sessionActionsLifecycle: HomeSessionLifecycle { sessionActionsRegion.lifecycle }
    var sessionActionsShowsSettle: Bool { sessionActionsRegion.showsSettle }
    var sessionActionsSnoozeBlockReason: String? { sessionActionsRegion.snoozeBlockReason }
    var snoozeSheetVisible: Bool { sessionActionsRegion.snoozeVisible }
    var snoozeAmount: String { sessionActionsRegion.snoozeAmount }
    var snoozeUnit: SnoozeUnit { sessionActionsRegion.snoozeUnit }
    var snoozeError: String? { sessionActionsRegion.snoozeError }
    var showsHomeSettle: Bool {
        SessionCapabilities.forEngine(activeConnectionManager.currentProfile()?.engine ?? .opencode).showsSettle
    }
    var showsTerminal: Bool {
        activeConnectionManager.capabilities.supportsTerminal
    }
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
    private let permissionLiveStore: any PermissionLiveStore
    private let liveUpdates: any LiveUpdates
    private let sessionCache: any SessionCache
    private let sessionAdapter = SessionDomainAdapter()
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionStatus: SessionConnectionStatus = .offline
    private(set) var serversChrome = HomeServersChrome()
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var observedWorktrees: [String] = []
    private var archivedByWorktree: [String: [SessionListItem]] = [:]
    private var archivedReloadTask: Task<Void, Never>?
    private var archivedGeneration = 0
    private var liveSessionsByWorktree: [String: SessionListSnapshot] = [:]
    private var liveStatusesByWorktree: [String: SessionActivitySnapshot] = [:]
    private var liveReadyIdsByWorktree: [String: Set<String>] = [:]
    private var livePermissionSessionIDsByWorktree: [String: Set<String>] = [:]
    private let sessionActionsRegion: HomeSessionActions
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
        permissionLiveStore: (any PermissionLiveStore)? = nil,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        liveUpdates: any LiveUpdates = FixedLiveUpdates(),
        addProjectListingDebounce: Duration = .milliseconds(250)
    ) {
        self.addProjectListingDebounce = addProjectListingDebounce
        self.projectRepository = projectRepository
        self.filesystemRepository = filesystemRepository
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.permissionLiveStore = permissionLiveStore ?? HomeEmptyPermissionLiveStore()
        self.liveUpdates = liveUpdates
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.sessionCache = sessionCache
        self.sessionActionsRegion = HomeSessionActions(
            sessionRepository: sessionRepository,
            messageRepository: messageRepository,
            capabilities: { [activeConnectionManager] in
                SessionCapabilities.forEngine(activeConnectionManager.currentProfile()?.engine ?? .opencode)
            }
        )
        lastHomeHiddenAtEpochMs = HomeDisplayPreferences.shared.lastHomeHiddenAtEpochMs()
        sessionActionsRegion.onLifecycleError = { [weak self] message in
            self?.removalSnackbar = OpenCodeSnackbarMessage(text: message, variant: .error)
        }
        sessionReadyStore.bindProfile(profileID: activeConnectionManager.currentProfile()?.id)
        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                self?.stopLiveObservations()
                await self?.refresh()
            }
        }
        connectionSessionCoordinator.onProfileCleared { [weak self] in
            Task { @MainActor in
                self?.stopLiveObservations()
                self?.state = .disconnected
                self?.serversChrome = HomeServersChrome()
            }
        }
        startObservingConnectionState()
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

    /// Settle, snooze, and wake change which list a thread belongs to.
    /// `refresh` returns immediately while live observations are running, and that
    /// path never loads settled threads, so the row would stay on the ladder.
    private func reloadAfterSessionLifecycle() async {
        await sessionLiveStore.invalidate(directory: nil)
        await refreshArchivedSessions()
    }

    private var showsArchivedSessions: Bool {
        SessionCapabilities.forEngine(activeConnectionManager.currentProfile()?.engine ?? .opencode).showsArchivedSessions
    }

    func showServersSheet() {
        serversChrome.sheetVisible = true
    }

    func dismissServersSheet() {
        serversChrome.sheetVisible = false
    }

    func switchProfile(id: String) async {
        await connectionSessionCoordinator.switchProfile(id: id)
        dismissServersSheet()
    }

    func refreshServersChrome() async {
        let profiles = await connectionProfileStore.listProfiles()
        let active = await connectionProfileStore.activeProfile()
            ?? activeConnectionManager.currentProfile()
        serversChrome = HomeServersChrome(
            title: active?.displayName ?? "Sessions",
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
            await sessionLiveStore.invalidate(directory: nil)
            await sessionStatusLiveStore.invalidate(directory: nil)
            await refreshArchivedSessions()
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
        addProjectListingsInFlight = []
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
        addProjectListingsInFlight = []
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

    func selectAddProjectSuggestion(path: String) async {
        guard isAddProjectPresented, !addProjectInputs.isValidating else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectSuggestion)
        await addProject(worktree: path)
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
            removalSnackbar = OpenCodeSnackbarMessage(
                text: "\"\(ProjectDomainAdapter.displayName(from: worktree))\" is already on home"
            )
            return
        }
        addProjectInputs.isValidating = true
        addProjectInputs.fieldError = nil
        publishAddProjectSheet()
        do {
            // Validate the path is usable, then persist the selected directory (web
            // open-project behavior). Non-git dirs resolve /project/current to "/".
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL, worktree: worktree)
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            pendingSelectedFilterId = worktree
        } catch {
            addProjectInputs.isValidating = false
            addProjectInputs.fieldError = error.localizedDescription
            publishAddProjectSheet()
        }
    }

    func createSession(in worktree: String) async {
        guard liveUpdates.chrome.canMutate else {
            logCreateSession(outcome: ProductAnalytics.Outcome.blocked)
            setProjectSessionCreateError("Reconnect to create a session")
            return
        }
        setProjectSessionCreateError(nil)
        logCreateSession(outcome: "started")
        revealNewestToken += 1
        pendingSessionOpen = SessionListItem(
            id: OptimisticSessionCreate.placeholderSessionID,
            title: "New Session",
            worktree: worktree,
            optimisticNavigationInstanceID: UUID()
        )
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
    private(set) var revealNewestToken = 0
    private var lastHomeHiddenAtEpochMs: Int64?

    func consumePendingSessionOpen() {
        pendingSessionOpen = nil
    }

    func consumePendingSelectedFilter() {
        pendingSelectedFilterId = nil
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
        // A settle from the session screen leaves home covered. Reveal reloads the lists.
        if showsArchivedSessions {
            Task { await reloadAfterSessionLifecycle() }
        }
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
        let byWorktree = Dictionary(uniqueKeysWithValues: sections.map { ($0.worktree, $0) })
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

    func requestRemoveProject(worktree: String) {
        guard case let .loaded(sections, _, _, _) = state,
              let section = sections.first(where: { $0.worktree == worktree })
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
        pendingProjectRemoval = ProjectRemovalItem(worktree: worktree, displayName: section.displayName)
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
        await connectionProfileStore.removeWorktree(serverBaseURL: serverBaseURL, worktree: pending.worktree)
        await sessionCache.purgeWorktree(serverBaseURL: serverBaseURL, worktree: pending.worktree)
        pendingProjectRemoval = nil
        await runHomeLoad(skipProbe: true)
        removalSnackbar = OpenCodeSnackbarMessage(text: "Removed \"\(pending.displayName)\" from home")
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
        let details = sessionActionDetails(sessionId: sessionId, worktree: worktree)
        sessionActionsRegion.begin(
            sessionId: sessionId,
            title: title,
            worktree: worktree,
            canArchive: activeConnectionManager.capabilities.supportsSessionArchive,
            lifecycle: details.lifecycle,
            showsSettle: showsHomeSettle,
            snoozeBlockReason: details.snoozeBlockReason
        )
    }

    func onSettleGesture(sessionId: String, title: String, worktree: String, gesture: HomeSettleGesture) async {
        let details = sessionActionDetails(sessionId: sessionId, worktree: worktree)
        sessionActionsRegion.begin(
            sessionId: sessionId,
            title: title,
            worktree: worktree,
            canArchive: activeConnectionManager.capabilities.supportsSessionArchive,
            lifecycle: details.lifecycle,
            showsSettle: showsHomeSettle,
            snoozeBlockReason: details.snoozeBlockReason,
            presentSheet: false
        )
        let restored: Bool
        switch gesture {
        case .settle:
            restored = await sessionActionsRegion.chooseSettle()
        case .snooze:
            sessionActionsRegion.openSnooze()
            restored = false
        case .wake:
            restored = await sessionActionsRegion.chooseWake()
        case .unsettle:
            restored = await sessionActionsRegion.chooseUnsettle()
        }
        if restored { await reloadAfterSessionLifecycle() }
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
            stopLiveObservations()
            state = .disconnected
            return
        }

        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)

        // Disk-first home: local worktrees + Session Cache paint .loaded
        // without .probing/.loading. Live store then SWR-refreshes.
        if !preserveContent, !worktrees.isEmpty {
            if case .loaded = state, liveObservationsRunning(for: worktrees) {
                return
            }
            let sections = await loadSectionsFromCache(worktrees: worktrees)
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                addProjectSheet: nil,
                projectSessionCreateError: nil
            )
            if !skipProbe {
                do {
                    try await projectRepository.probeServer()
                } catch {
                    // Keep cached home; connection chrome reflects reachability.
                }
            }
            startLiveObservations(worktrees: worktrees)
            return
        }

        if worktrees.isEmpty, !preserveContent {
            stopLiveObservations()
            state = .empty(showAddProjectSheet: false, addProjectSheet: nil, projectSessionCreateError: nil)
            if !skipProbe {
                do {
                    try await projectRepository.probeServer()
                } catch {
                    state = .probeFailed(error.localizedDescription)
                    return
                }
                // A probe may seed worktrees (T3 project roots); paint them now.
                let seeded = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)
                if !seeded.isEmpty {
                    await runHomeLoad(skipProbe: true)
                }
            }
            return
        }

        if !skipProbe {
            if !preserveContent {
                state = .probing
            }
            do {
                try await projectRepository.probeServer()
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
        } catch {
            if preserveContent { return }
            stopLiveObservations()
            state = .probeFailed(error.localizedDescription)
        }
    }

    private func loadSectionsFromCache(worktrees: [String]) async -> [ProjectSectionItem] {
        var sections: [ProjectSectionItem] = []
        sections.reserveCapacity(worktrees.count)
        for worktree in worktrees {
            sections.append(await sectionFromCache(worktree: worktree))
        }
        return sections
    }

    private func sectionFromCache(worktree: String) async -> ProjectSectionItem {
        let seeded = await sessionCache.seedSessions(worktree: worktree)
        let cachedActivity = await sessionCache.seedActivity(directory: worktree) ?? []
        let activityByID = Dictionary(uniqueKeysWithValues: cachedActivity.map { ($0.sessionId, $0) })
        let sessions =
            (seeded ?? [])
                .activeSessions()
                .rootSessions()
                .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
        let items =
            sessions.map { session in
                let cached = activityByID[session.id]
                return sessionAdapter.listItem(
                    from: session,
                    worktree: worktree,
                    statusLabel: cached?.activity.statusLabel,
                    statusIsStale: cached != nil && cached?.activity.statusLabel != nil
                )
            }
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
            sessionsFromCache: seeded != nil
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

    /// Archived and settled sessions for engines that list them; a failed load hides the rows.
    private func loadArchivedItems(worktree: String) async -> [SessionListItem] {
        guard SessionCapabilities.forEngine(activeConnectionManager.currentProfile()?.engine ?? .opencode).showsArchivedSessions
        else { return [] }
        let archived = (try? await sessionRepository.listArchivedSessions(directory: worktree)) ?? []
        return archived
            .rootSessions()
            .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
            .map { session in
                var item = sessionAdapter.listItem(from: session, worktree: worktree)
                item.lifecycle = session.isArchived ? .archived : (session.isSettled ? .settled : .active)
                return item
            }
    }

    private struct SessionActionDetails {
        var lifecycle: HomeSessionLifecycle
        var snoozeBlockReason: String?
    }

    /// Active rows can be snoozed; archived and settled rows come from the restore list.
    private func sessionActionDetails(sessionId: String, worktree: String) -> SessionActionDetails {
        guard case let .loaded(sections, _, _, _) = state,
              let section = sections.first(where: { $0.worktree == worktree })
        else { return SessionActionDetails(lifecycle: .active, snoozeBlockReason: nil) }
        if let active = section.allSessions.first(where: { $0.id == sessionId }) {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let snoozed = HomeSettleFeed.isSnoozed(
                snoozedUntilEpochMs: active.snoozedUntilEpochMs,
                nowEpochMs: now,
                isSettled: active.lifecycle == .settled
            )
            return SessionActionDetails(
                lifecycle: snoozed ? .snoozed : .active,
                snoozeBlockReason: active.snoozeBlockReason
            )
        }
        guard let row = section.archivedSessions.first(where: { $0.id == sessionId }) else {
            return SessionActionDetails(lifecycle: .active, snoozeBlockReason: nil)
        }
        return SessionActionDetails(lifecycle: row.lifecycle, snoozeBlockReason: nil)
    }

    func unarchiveSessionAction() async {
        guard sessionActionsTarget != nil, sessionActionsLifecycle == .archived, !sessionActionInFlight else { return }
        let restored = await sessionActionsRegion.chooseUnarchive()
        if restored { await reloadAfterSessionLifecycle() }
    }

    func unsettleSessionAction() async {
        guard sessionActionsTarget != nil, sessionActionsLifecycle == .settled, !sessionActionInFlight else { return }
        let restored = await sessionActionsRegion.chooseUnsettle()
        if restored { await reloadAfterSessionLifecycle() }
    }

    func settleSessionAction() async {
        guard sessionActionsTarget != nil, sessionActionsLifecycle == .active, !sessionActionInFlight else { return }
        let restored = await sessionActionsRegion.chooseSettle()
        if restored { await reloadAfterSessionLifecycle() }
    }

    func wakeSessionAction() async {
        guard sessionActionsTarget != nil, sessionActionsLifecycle == .snoozed, !sessionActionInFlight else { return }
        let restored = await sessionActionsRegion.chooseWake()
        if restored { await reloadAfterSessionLifecycle() }
    }

    func openSnooze() {
        sessionActionsRegion.openSnooze()
    }

    func dismissSnooze() {
        sessionActionsRegion.dismissSnooze()
    }

    func updateSnoozeAmount(_ text: String) {
        sessionActionsRegion.updateSnoozeAmount(text)
    }

    func cycleSnoozeUnit() {
        sessionActionsRegion.cycleSnoozeUnit()
    }

    func chooseSnoozePreset(untilEpochMs: Int64) async {
        let restored = await sessionActionsRegion.chooseSnoozePreset(untilEpochMs: untilEpochMs)
        if restored { await reloadAfterSessionLifecycle() }
    }

    func confirmCustomSnooze() async {
        let restored = await sessionActionsRegion.confirmCustomSnooze()
        if restored { await reloadAfterSessionLifecycle() }
    }

    private func loadSection(worktree: String) async -> ProjectSectionItem {
        do {
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            let sessions =
                try await sessionRepository.listSessions(directory: worktree)
                    .activeSessions()
                    .rootSessions()
                    .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
            let items =
                sessions.map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statusLabel(for: session.id, directory: worktree),
                        isReady: isReady(
                            sessionID: session.id,
                            directory: worktree,
                            readyIds: liveReadyIdsByWorktree[worktree] ?? []
                        ),
                        unreadOutcome: unreadOutcome(for: session, directory: worktree),
                        statusIsStale: statusIsStale(for: session.id, directory: worktree)
                    )
                }
            let name = ProjectDomainAdapter.displayName(from: worktree)
            var section = ProjectSectionItem(
                id: worktree,
                worktree: worktree,
                displayName: name,
                pathSubtitle: worktree,
                letter: letterCharacter(for: name),
                letterAccent: letterAccent(for: worktree),
                allSessions: items,
                loadError: nil
            )
            section.archivedSessions = await loadArchivedItems(worktree: worktree)
            return section
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
        addProjectInputs.home = home
        addProjectInputs.fieldText = ServerPathPolicy.fieldText(home)
        publishAddProjectSheet()

        let projectRepository = projectRepository
        let filesystemRepository = filesystemRepository
        // Suggestions and volumes are optional: failures hide them.
        async let projects = (try? await projectRepository.listProjects()) ?? []
        async let volumes: [DirectoryNode] = ServerPathPolicy.root(home) == "/" && !ServerPathPolicy.isWindows(home)
            ? (try? await filesystemRepository.listDirectory(path: "/Volumes")) ?? []
            : []
        if let directory = addProjectSheet.directory {
            await loadAddProjectListing(directory, generation: generation)
        }
        let loadedProjects = await projects
        let loadedVolumes = await volumes
        guard generation == addProjectGeneration else { return }
        addProjectInputs.serverProjects = loadedProjects
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
              !addProjectListingsInFlight.contains(directory)
        else { return }
        addProjectListingsInFlight.insert(directory)
        addProjectInputs.listErrors[directory] = nil
        publishAddProjectSheet()
        let result: Result<[DirectoryNode], Error>
        do {
            result = .success(try await filesystemRepository.listDirectory(path: directory))
        } catch {
            result = .failure(error)
        }
        guard generation == addProjectGeneration else { return }
        addProjectListingsInFlight.remove(directory)
        switch result {
        case let .success(nodes):
            addProjectInputs.listings[directory] = nodes
            if ServerPathPolicy.isWindows(directory) {
                let root = ServerPathPolicy.root(directory)
                if !addProjectInputs.listedRoots.contains(root) {
                    addProjectInputs.listedRoots.append(root)
                }
            }
        case let .failure(error):
            DiagnosticLog.warning(.connection, "add project: folder listing failed", error: error)
            addProjectInputs.listErrors[directory] = AddProjectSheetState.listErrorMessage
        }
        publishAddProjectSheet()
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
        archivedReloadTask?.cancel()
        archivedReloadTask = Task { await refreshArchivedSessions() }
        liveObservationTasks =
            worktrees.map { worktree in
                Task {
                    await observeWorktree(worktree)
                }
            }
    }

    private func stopLiveObservations() {
        archivedGeneration += 1
        archivedReloadTask?.cancel()
        archivedReloadTask = nil
        archivedByWorktree = [:]
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        observedWorktrees = []
        liveSessionsByWorktree = [:]
        liveStatusesByWorktree = [:]
        liveReadyIdsByWorktree = [:]
        livePermissionSessionIDsByWorktree = [:]
        permissionLiveStore.clearSessionIndex()
    }

    /// Settled and archived rows are not in the live session list. Keep the latest fetch.
    private func refreshArchivedSessions() async {
        let generation = archivedGeneration
        guard case let .loaded(sections, _, _, _) = state, showsArchivedSessions else { return }
        var fetched: [String: [SessionListItem]] = [:]
        for section in sections {
            fetched[section.worktree] = await loadArchivedItems(worktree: section.worktree)
        }
        guard generation == archivedGeneration else { return }
        for (worktree, items) in fetched {
            archivedByWorktree[worktree] = items
        }
        applyArchivedSessions()
    }

    private func applyArchivedSessions() {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        var changed = false
        let updated = sections.map { section -> ProjectSectionItem in
            guard let archived = archivedByWorktree[section.worktree], archived != section.archivedSessions else {
                return section
            }
            changed = true
            var copy = section
            copy.archivedSessions = archived
            return copy
        }
        guard changed else { return }
        state = .loaded(
            sections: updated,
            showAddProjectSheet: showAdd,
            addProjectSheet: addProjectSheet,
            projectSessionCreateError: createError
        )
    }

    private func liveObservationsRunning(for worktrees: [String]) -> Bool {
        !liveObservationTasks.isEmpty && observedWorktrees == worktrees
    }

    private func observeWorktree(_ worktree: String) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await snapshot in self.sessionLiveStore.observeSessions(directory: worktree) {
                    self.liveSessionsByWorktree[worktree] = snapshot
                    if self.observesHomePermissions {
                        self.permissionLiveStore.noteSessions(
                            directory: worktree,
                            sessionIDs: Set(snapshot.sessions.map(\.id))
                        )
                    }
                    self.applyLiveUpdate(worktree: worktree)
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
            if observesHomePermissions {
                group.addTask { @MainActor in
                    for await requests in self.permissionLiveStore.observePermissions(directory: worktree) {
                        self.livePermissionSessionIDsByWorktree[worktree] = Set(requests.map(\.sessionID))
                        self.applyLiveUpdate(worktree: worktree)
                    }
                }
            }
        }
    }

    /// OpenCode pending approvals are GET /api/permission/request. T3 already flags them on the shell.
    private var observesHomePermissions: Bool {
        activeConnectionManager.currentProfile()?.engine != .t3
    }

    private func applyLiveUpdate(worktree: String) {
        guard let snapshot = liveSessionsByWorktree[worktree] else { return }
        guard liveStatusesByWorktree[worktree] != nil else { return }
        guard let readyIds = liveReadyIdsByWorktree[worktree] else { return }
        updateSectionFromLiveData(
            worktree: worktree,
            sessions: snapshot.sessions,
            readyIds: readyIds,
            sessionsFromCache: snapshot.isFromCache
        )
    }

    private func updateSectionFromLiveData(
        worktree: String,
        sessions: [Session],
        readyIds: Set<String>,
        sessionsFromCache: Bool
    ) {
        guard case let .loaded(sections, showAdd, addProjectSheet, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let items =
            sessions
                .activeSessions()
                .rootSessions()
                .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
                .map { session in
                    var item = sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statusLabel(for: session.id, directory: worktree),
                        isReady: isReady(sessionID: session.id, directory: worktree, readyIds: readyIds),
                        unreadOutcome: unreadOutcome(for: session, directory: worktree),
                        statusIsStale: statusIsStale(for: session.id, directory: worktree)
                    )
                    if livePermissionSessionIDsByWorktree[worktree]?.contains(session.id) == true {
                        item.needsApproval = true
                    }
                    return item
                }
        let updatedSection = ProjectSectionItem(
            id: section.id,
            worktree: section.worktree,
            displayName: section.displayName,
            pathSubtitle: section.pathSubtitle,
            letter: section.letter,
            letterAccent: section.letterAccent,
            allSessions: items,
            loadError: section.loadError,
            sessionsFromCache: sessionsFromCache,
            archivedSessions: archivedByWorktree[worktree] ?? section.archivedSessions
        )
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

    private func statusLabel(for sessionID: String, directory: String) -> String? {
        liveStatusesByWorktree[directory]?.statusLabel(for: sessionID)
    }

    private func statusIsStale(for sessionID: String, directory: String) -> Bool {
        liveStatusesByWorktree[directory]?.statusIsStale(for: sessionID) == true
    }

    /// v1 only: the local per-device Ready ids. v2 reads unread state from the server instead.
    private func isReady(sessionID: String, directory: String, readyIds: Set<String>) -> Bool {
        guard !activeConnectionManager.capabilities.supportsSessionViewed else { return false }
        guard liveStatusesByWorktree[directory]?.seeded == true else { return false }
        return readyIds.contains(sessionID)
    }

    /// v2 only: Done / Failed / Stopped for a finished run nobody has viewed. Same seeding gate as
    /// Ready, so a session the status snapshot has not confirmed idle never flashes Done.
    /// A missing outcome (older v2 server) reads as succeeded.
    private func unreadOutcome(for session: Session, directory: String) -> SessionIdleOutcome? {
        guard activeConnectionManager.capabilities.supportsSessionViewed else { return nil }
        guard liveStatusesByWorktree[directory]?.seeded == true else { return nil }
        guard session.isServerUnread else { return nil }
        return session.idleOutcome ?? .succeeded
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

@MainActor
private final class HomeEmptyPermissionLiveStore: PermissionLiveStore {
    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }

    func clearCache() {}
}
