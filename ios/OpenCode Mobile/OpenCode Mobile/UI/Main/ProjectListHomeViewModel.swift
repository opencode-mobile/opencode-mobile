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
}

struct AddProjectDirectoryItem: Identifiable, Equatable {
    var id: String { absolutePath }
    let name: String
    let absolutePath: String
}

struct AddProjectNavigatorState: Equatable {
    var currentPath: String
    var canGoUp: Bool
    var entries: [AddProjectDirectoryItem]
    var isLoading: Bool
    var isValidating: Bool
    var errorMessage: String?

    static let empty = AddProjectNavigatorState(
        currentPath: "",
        canGoUp: false,
        entries: [],
        isLoading: false,
        isValidating: false,
        errorMessage: nil
    )
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
        navigator: AddProjectNavigatorState?,
        projectSessionCreateError: String?
    )
    case loaded(
        sections: [ProjectSectionItem],
        showAddProjectSheet: Bool,
        navigator: AddProjectNavigatorState?,
        projectSessionCreateError: String?
    )
}

@MainActor
@Observable
final class ProjectListHomeViewModel {
    private(set) var state: ProjectListHomeState = .probing
    /// Soft pull-to-refresh indicator; content stays on Loaded/Empty.
    private var userRefreshing = false
    private var catchUpRefreshing = false
    private(set) var isRefreshing = false
    private var catchUpChromeTask: Task<Void, Never>?
    /// Sheet presentation is tracked here so SwiftUI `.sheet` can observe it directly.
    private(set) var isAddProjectPresented = false
    private(set) var addProjectNavigator = AddProjectNavigatorState.empty
    private(set) var pendingProjectRemoval: ProjectRemovalItem?
    /// Shared home snackbar: project removal plus archive / delete failures.
    private(set) var removalSnackbar: OpenCodeSnackbarMessage?
    var sessionActionsTarget: HomeSessionActionTarget? { sessionActionsRegion.target }
    var sessionActionsSheetVisible: Bool { sessionActionsRegion.sheetVisible }
    /// Stays false until a message fetch confirms the session is forkable.
    var sessionActionsCanFork: Bool { sessionActionsRegion.canFork }
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
    private let newSessionModelSelectionResolver: NewSessionModelSelectionResolver?
    private let sessionLiveStore: any SessionLiveStore
    private let sessionStatusLiveStore: any SessionStatusLiveStore
    private let permissionLiveStore: any PermissionLiveStore
    private let questionLiveStore: any QuestionLiveStore
    private let sessionTodoLiveStore: any SessionTodoLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let foregroundEventStream: any ForegroundEventStream
    private let liveUpdates: any LiveUpdates
    private let sessionCache: any SessionCache
    private let eventAdapter = EventDomainAdapter()
    private let sessionAdapter = SessionDomainAdapter()
    private var connectionStateObservationTask: Task<Void, Never>?
    private(set) var liveUpdatesConnectionStatus: SessionConnectionStatus = .offline
    private(set) var serversChrome = HomeServersChrome()
    private var serverBaseURL: String {
        activeConnectionManager.connection?.baseURL.absoluteString ?? ""
    }
    private var liveObservationTasks: [Task<Void, Never>] = []
    private var liveSessionsByWorktree: [String: SessionListSnapshot] = [:]
    private var liveStatusesByWorktree: [String: [String: SessionStatus]] = [:]
    private var liveReadyIdsByWorktree: [String: Set<String>] = [:]
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
        permissionLiveStore: any PermissionLiveStore,
        questionLiveStore: any QuestionLiveStore,
        sessionTodoLiveStore: any SessionTodoLiveStore,
        sessionReadyStore: any SessionReadyStore,
        foregroundEventStream: any ForegroundEventStream,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver? = nil,
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        liveUpdates: any LiveUpdates = FixedLiveUpdates()
    ) {
        self.projectRepository = projectRepository
        self.filesystemRepository = filesystemRepository
        self.sessionRepository = sessionRepository
        self.messageRepository = messageRepository
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.sessionLiveStore = sessionLiveStore
        self.sessionStatusLiveStore = sessionStatusLiveStore
        self.permissionLiveStore = permissionLiveStore
        self.questionLiveStore = questionLiveStore
        self.sessionTodoLiveStore = sessionTodoLiveStore
        self.sessionReadyStore = sessionReadyStore
        self.foregroundEventStream = foregroundEventStream
        self.liveUpdates = liveUpdates
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.sessionCache = sessionCache
        self.sessionActionsRegion = HomeSessionActions(
            sessionRepository: sessionRepository,
            messageRepository: messageRepository
        )
        lastHomeHiddenAtEpochMs = HomeDisplayPreferences.shared.lastHomeHiddenAtEpochMs()
        sessionActionsRegion.onLifecycleError = { [weak self] message in
            self?.removalSnackbar = OpenCodeSnackbarMessage(text: message, variant: .error)
        }
        sessionReadyStore.bindProfile(profileID: activeConnectionManager.currentProfile()?.id)
        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                self?.stopLiveObservations()
                self?.sessionReadyStore.bindProfile(profileID: self?.activeConnectionManager.currentProfile()?.id)
                self?.sessionLiveStore.clearCache()
                self?.sessionStatusLiveStore.clearCache()
                self?.permissionLiveStore.clearCache()
                self?.questionLiveStore.clearCache()
                self?.sessionTodoLiveStore.clearCache()
                self?.messageRepository.clearMessageCache()
                await self?.refresh()
            }
        }
        connectionSessionCoordinator.onProfileCleared { [weak self] in
            Task { @MainActor in
                self?.sessionReadyStore.bindProfile(profileID: nil)
                self?.sessionLiveStore.clearCache()
                self?.sessionStatusLiveStore.clearCache()
                self?.permissionLiveStore.clearCache()
                self?.questionLiveStore.clearCache()
                self?.sessionTodoLiveStore.clearCache()
                self?.messageRepository.clearMessageCache()
                self?.stopLiveObservations()
                self?.state = .disconnected
                self?.serversChrome = HomeServersChrome()
            }
        }
        startObservingConnectionState()
        startObservingCatchUpChrome()
    }

    private func startObservingCatchUpChrome() {
        catchUpChromeTask?.cancel()
        catchUpChromeTask = Task {
            for await catchingUp in messageRepository.observeCatchUpChrome() {
                catchUpRefreshing = catchingUp
                publishRefreshing()
            }
        }
    }

    private func publishRefreshing() {
        let next = userRefreshing || catchUpRefreshing
        if isRefreshing != next {
            isRefreshing = next
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
        await openDirectoryNavigator()
    }

    func dismissAddProjectSheet() {
        isAddProjectPresented = false
        addProjectNavigator = .empty
        switch state {
        case let .empty(_, _, createError):
            state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, createError):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                navigator: nil,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    func navigateAddProjectUp() async {
        guard let navigator = currentNavigator(),
              let parent = DirectoryListingPolicy.parentPath(navigator.currentPath)
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectGoUp)
        await loadDirectoryNavigator(path: parent)
    }

    func navigateAddProjectInto(path: String) async {
        logAddProjectAction(ProductAnalytics.Action.addProjectEnterDirectory)
        await loadDirectoryNavigator(path: path)
    }

    func retryAddProjectNavigator() async {
        guard let navigator = currentNavigator() else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectRetryNavigator)
        await loadDirectoryNavigator(path: navigator.currentPath)
    }

    func selectCurrentDirectory() async {
        guard let navigator = currentNavigator(),
              !navigator.currentPath.isEmpty,
              !navigator.isLoading,
              !navigator.isValidating
        else { return }
        logAddProjectAction(ProductAnalytics.Action.addProjectSelectCurrent)
        await addProject(worktree: navigator.currentPath)
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
        updateNavigator { current in
            var next = current
            next.isValidating = true
            next.errorMessage = nil
            return next
        }
        do {
            // Validate the path is usable, then persist the selected directory (web
            // open-project behavior). Non-git dirs resolve /project/current to "/".
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            await connectionProfileStore.addWorktree(serverBaseURL: serverBaseURL, worktree: worktree)
            dismissAddProjectSheet()
            await runHomeLoad(skipProbe: true)
            pendingSelectedFilterId = worktree
        } catch {
            updateNavigator { current in
                var next = current
                next.isValidating = false
                next.errorMessage = error.localizedDescription
                return next
            }
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
    }

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
            stopLiveObservations()
            state = .disconnected
            return
        }

        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: serverBaseURL)

        // Disk-first home: local worktrees + Session Cache paint .loaded
        // without .probing/.loading. Live store then SWR-refreshes.
        if !preserveContent, !worktrees.isEmpty {
            let sections = await loadSectionsFromCache(worktrees: worktrees)
            state = .loaded(
                sections: sections,
                showAddProjectSheet: false,
                navigator: nil,
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
            state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: nil)
            if !skipProbe {
                do {
                    try await projectRepository.probeServer()
                } catch {
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
                if case let .empty(showAdd, navigator, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        navigator: navigator,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: nil)
                }
                return
            }

            let sections = await loadSections(worktrees: worktrees)
            if sections.isEmpty {
                stopLiveObservations()
                if case let .empty(showAdd, navigator, createError) = previous, preserveContent {
                    state = .empty(
                        showAddProjectSheet: showAdd,
                        navigator: navigator,
                        projectSessionCreateError: createError
                    )
                } else {
                    state = .empty(showAddProjectSheet: false, navigator: nil, projectSessionCreateError: nil)
                }
                return
            }
            if case let .loaded(_, showAdd, navigator, createError) = previous, preserveContent {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: showAdd,
                    navigator: navigator,
                    projectSessionCreateError: createError
                )
            } else {
                state = .loaded(
                    sections: sections,
                    showAddProjectSheet: false,
                    navigator: nil,
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
        let sessions =
            (seeded ?? [])
                .activeSessions()
                .rootSessions()
                .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
        let items =
            sessions.map { session in
                sessionAdapter.listItem(from: session, worktree: worktree, statusLabel: nil)
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

    private func loadSection(worktree: String) async -> ProjectSectionItem {
        do {
            _ = try await projectRepository.getCurrentProject(directory: worktree)
            let sessions =
                try await sessionRepository.listSessions(directory: worktree)
                    .activeSessions()
                    .rootSessions()
                    .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
            let statusBySessionID =
                Dictionary(
                    uniqueKeysWithValues: (try await sessionRepository.getAllSessionStatuses(directory: worktree))
                        .map { ($0.sessionID, $0) }
                )
            let items =
                sessions.map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statusBySessionID[session.id].flatMap(eventAdapter.statusLabel)
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

    private func openDirectoryNavigator() async {
        setNavigator(
            AddProjectNavigatorState(
                currentPath: "",
                canGoUp: false,
                entries: [],
                isLoading: true,
                isValidating: false,
                errorMessage: nil
            )
        )
        do {
            let paths = try await filesystemRepository.getPaths()
            await loadDirectoryNavigator(path: paths.home)
        } catch {
            setNavigator(
                AddProjectNavigatorState(
                    currentPath: "",
                    canGoUp: false,
                    entries: [],
                    isLoading: false,
                    isValidating: false,
                    errorMessage: error.localizedDescription
                )
            )
        }
    }

    private func loadDirectoryNavigator(path: String) async {
        updateNavigator { current in
            var next = current
            next.currentPath = path
            next.canGoUp = DirectoryListingPolicy.parentPath(path) != nil
            next.isLoading = true
            next.isValidating = false
            next.errorMessage = nil
            return next
        }
        do {
            let entries = try await filesystemRepository.listDirectory(path: path).map {
                AddProjectDirectoryItem(name: $0.name, absolutePath: $0.absolutePath)
            }
            updateNavigator { current in
                var next = current
                next.currentPath = path
                next.canGoUp = DirectoryListingPolicy.parentPath(path) != nil
                next.entries = entries
                next.isLoading = false
                next.isValidating = false
                next.errorMessage = nil
                return next
            }
        } catch {
            updateNavigator { current in
                var next = current
                next.currentPath = path
                next.canGoUp = DirectoryListingPolicy.parentPath(path) != nil
                next.entries = []
                next.isLoading = false
                next.isValidating = false
                next.errorMessage = error.localizedDescription
                return next
            }
        }
    }

    private func currentNavigator() -> AddProjectNavigatorState? {
        isAddProjectPresented ? addProjectNavigator : nil
    }

    private func setNavigator(_ navigator: AddProjectNavigatorState) {
        addProjectNavigator = navigator
        isAddProjectPresented = true
        switch state {
        case let .empty(_, _, createError):
            state = .empty(showAddProjectSheet: true, navigator: navigator, projectSessionCreateError: createError)
        case let .loaded(sections, _, _, createError):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: true,
                navigator: navigator,
                projectSessionCreateError: createError
            )
        default:
            break
        }
    }

    private func updateNavigator(_ transform: (AddProjectNavigatorState) -> AddProjectNavigatorState) {
        setNavigator(transform(addProjectNavigator))
    }

    private func startLiveObservations(worktrees: [String]) {
        stopLiveObservations()
        liveObservationTasks =
            worktrees.map { worktree in
                Task {
                    await observeWorktree(worktree)
                }
            }
    }

    private func stopLiveObservations() {
        liveObservationTasks.forEach { $0.cancel() }
        liveObservationTasks = []
        liveSessionsByWorktree = [:]
        liveStatusesByWorktree = [:]
        liveReadyIdsByWorktree = [:]
    }

    private func observeWorktree(_ worktree: String) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await snapshot in self.sessionLiveStore.observeSessions(directory: worktree) {
                    self.liveSessionsByWorktree[worktree] = snapshot
                    self.applyLiveUpdate(worktree: worktree)
                }
            }
            group.addTask { @MainActor in
                for await statuses in self.sessionStatusLiveStore.observeStatuses(directory: worktree) {
                    self.liveStatusesByWorktree[worktree] = statuses
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

    private func applyLiveUpdate(worktree: String) {
        guard let snapshot = liveSessionsByWorktree[worktree] else { return }
        let statuses = liveStatusesByWorktree[worktree] ?? [:]
        let readyIds = liveReadyIdsByWorktree[worktree] ?? []
        updateSectionFromLiveData(
            worktree: worktree,
            sessions: snapshot.sessions,
            statuses: statuses,
            readyIds: readyIds,
            sessionsFromCache: snapshot.isFromCache
        )
    }

    private func updateSectionFromLiveData(
        worktree: String,
        sessions: [Session],
        statuses: [String: SessionStatus],
        readyIds: Set<String>,
        sessionsFromCache: Bool
    ) {
        guard case let .loaded(sections, showAdd, navigator, createError) = state else { return }
        guard let section = sections.first(where: { $0.worktree == worktree }) else { return }
        let items =
            sessions
                .activeSessions()
                .rootSessions()
                .sorted { ($0.updatedAtEpochMs ?? 0) > ($1.updatedAtEpochMs ?? 0) }
                .map { session in
                    sessionAdapter.listItem(
                        from: session,
                        worktree: worktree,
                        statusLabel: statuses[session.id].flatMap(eventAdapter.statusLabel),
                        isReady: readyIds.contains(session.id)
                    )
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
            sessionsFromCache: sessionsFromCache
        )
        let updatedSections =
            sections.map { current in
                current.worktree == worktree ? updatedSection : current
            }
        state = .loaded(
            sections: updatedSections,
            showAddProjectSheet: showAdd,
            navigator: navigator,
            projectSessionCreateError: createError
        )
    }

    private func setProjectSessionCreateError(_ message: String?) {
        switch state {
        case let .empty(showAdd, navigator, _):
            state = .empty(showAddProjectSheet: showAdd, navigator: navigator, projectSessionCreateError: message)
        case let .loaded(sections, showAdd, navigator, _):
            state = .loaded(
                sections: sections,
                showAddProjectSheet: showAdd,
                navigator: navigator,
                projectSessionCreateError: message
            )
        default:
            break
        }
    }

}
