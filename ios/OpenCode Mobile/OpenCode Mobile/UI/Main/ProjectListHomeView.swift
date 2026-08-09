import Factory
import SwiftUI

struct ProjectListHomeView: View {
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showSettings = false
    @State private var navigationPath = NavigationPath()
    @State private var connectionSetupPresentation: ConnectionSetupPresentation?
    @State private var connectionSetupViewModel: ConnectionSetupViewModel?
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @State private var showNewSessionProjectPicker = false
    @State private var selectedSessionFilterId = SessionListFilterTab.allId
    @Binding private var presentConnectionSetupOnAppear: Bool

    init(
        viewModel: ProjectListHomeViewModel,
        presentConnectionSetupOnAppear: Binding<Bool> = .constant(false)
    ) {
        _viewModel = State(initialValue: viewModel)
        _presentConnectionSetupOnAppear = presentConnectionSetupOnAppear
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            VStack(spacing: 0) {
                OpenCodeTopBar(
                    title: homeTopBarTitle,
                    subtitleAccessory: showsLiveUpdatesStatus
                        ? .statusDot(viewModel.liveUpdatesConnectionState.openCodeSessionConnectionStatus)
                        : nil,
                    actions: [
                        .icon(.settings, accessibilityLabel: "Settings", action: { showSettings = true }),
                    ]
                )
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)
            // Custom OpenCodeTopBar owns chrome; keep the system bar out of this stack
            // so pushes do not animate in the Liquid Glass back button.
            .toolbar(.hidden, for: .navigationBar)
            .overlay(alignment: .bottom) {
                OpenCodeSnackbarHost(
                    message: viewModel.removalSnackbar,
                    onDismissed: { viewModel.consumeProjectRemovalSnackbar() }
                )
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsView(onConnectionSetup: presentConnectionSetup)
            }
            .navigationDestination(for: SessionListItem.self) { session in
                SessionChatView(session: session)
            }
            .task {
                let decision = ConnectionSetupOnAppear.consume(presentConnectionSetupOnAppear)
                presentConnectionSetupOnAppear = decision.pending
                if decision.shouldPresent {
                    presentConnectionSetup()
                }
                await viewModel.refresh()
            }
            .onChange(of: viewModel.pendingSessionOpen?.id) { _, _ in
                if let session = viewModel.pendingSessionOpen {
                    openSession(session)
                    viewModel.consumePendingSessionOpen()
                }
            }
            .openCodeBottomSheet(
                isPresented: addProjectPresentedBinding,
                title: "Add project",
                alwaysFixedHeight: true,
                pinnedContent: {
                    let navigator = viewModel.addProjectNavigator
                    OpenCodeBottomSheetEntry(
                        title: "Go up",
                        enabled: navigator.canGoUp && !navigator.isLoading && !navigator.isValidating,
                        startDecorator: .icon(.arrowUpward),
                        action: { Task { await viewModel.navigateAddProjectUp() } }
                    )
                    OpenCodeBottomSheetEntry(
                        title: navigator.currentPath.isEmpty ? "Current directory" : navigator.currentPath,
                        enabled: !navigator.currentPath.isEmpty && !navigator.isLoading && !navigator.isValidating,
                        startDecorator: .icon(.folderCheck),
                        action: { Task { await viewModel.selectCurrentDirectory() } }
                    )
                }
            ) {
                let navigator = viewModel.addProjectNavigator
                if navigator.isLoading || navigator.isValidating {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(24)
                } else if let errorMessage = navigator.errorMessage {
                    VStack(spacing: 12) {
                        Text(errorMessage)
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .multilineTextAlignment(.center)
                        OpenCodeButton(
                            variant: .fullWidthText(title: "Retry"),
                            action: { Task { await viewModel.retryAddProjectNavigator() } }
                        )
                    }
                    .padding(20)
                } else if navigator.entries.isEmpty {
                    OpenCodeBottomSheetEntry(title: "No subdirectories", enabled: false, action: {})
                } else {
                    ForEach(navigator.entries) { entry in
                        OpenCodeBottomSheetEntry(
                            title: entry.name,
                            startDecorator: .icon(.subdirectoryArrowRight),
                            action: { Task { await viewModel.navigateAddProjectInto(path: entry.absolutePath) } }
                        )
                    }
                }
            }
            .openCodeBottomSheet(
                item: seeAllSheetBinding,
                title: viewModel.seeAllSessionsSheet?.projectName ?? "Sessions"
            ) { sheet in
                ForEach(sheet.sessions) { session in
                    OpenCodeBottomSheetEntry(
                        title: seeAllSessionEntryTitle(session),
                        action: {
                            viewModel.dismissSeeAllSessions()
                            openSession(session)
                        }
                    )
                }
            }
            .openCodeBottomSheet(
                isPresented: $showNewSessionProjectPicker,
                title: "New session"
            ) {
                let projects = newSessionPickerProjects
                if projects.isEmpty {
                    OpenCodeBottomSheetEntry(title: "No projects", enabled: false, action: {})
                } else {
                    ForEach(projects, id: \.worktree) { project in
                        OpenCodeBottomSheetEntry(
                            title: project.displayName,
                            startDecorator: .icon(.folder),
                            action: {
                                showNewSessionProjectPicker = false
                                Task { await viewModel.createSession(in: project.worktree) }
                            }
                        )
                    }
                }
            }
            .openCodeBottomSheet(
                isPresented: sessionActionsSheetBinding,
                title: "Session actions"
            ) {
                OpenCodeBottomSheetEntry(
                    title: "Rename session",
                    startDecorator: .icon(.edit),
                    action: { viewModel.openSessionRename() }
                )
                if viewModel.sessionActionsCanFork {
                    OpenCodeBottomSheetEntry(
                        title: "Fork session",
                        startDecorator: .icon(.contentCopy),
                        action: { viewModel.openSessionFork() }
                    )
                }
                OpenCodeBottomSheetEntry(
                    title: "Archive",
                    enabled: !viewModel.sessionActionInFlight,
                    startDecorator: .icon(.archive),
                    action: { Task { await viewModel.archiveSessionAction() } }
                )
                OpenCodeBottomSheetEntry(
                    title: "Delete",
                    enabled: !viewModel.sessionActionInFlight,
                    destructive: true,
                    startDecorator: .icon(.close),
                    action: { viewModel.requestDeleteSessionAction() }
                )
            }
            .fullScreenCover(item: $connectionSetupPresentation) { presentation in
                ConnectionSetupView(
                    viewModel: connectionSetupViewModel ?? Container.makeConnectionSetupViewModel(initialProfile: presentation.initialProfile),
                    onDismiss: {
                        connectionSetupPresentation = nil
                        connectionSetupViewModel = nil
                    }
                )
                .openCodeTheme()
            }
            .openCodeDialog(
                isPresented: projectRemovalBinding,
                title: "Remove from home?",
                subtitle: pendingProjectRemoval.map {
                    "Remove \"\($0.displayName)\" from home? Its sessions stay on the server."
                },
                primaryOption: OpenCodeDialogOption(
                    title: "Remove",
                    destructive: true
                ) {
                    Task { await viewModel.confirmRemoveProject() }
                },
                secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                    viewModel.dismissProjectRemoval()
                }
            )
            .openCodeDialog(
                isPresented: sessionRenameDialogBinding,
                title: "Rename session",
                primaryOption: OpenCodeDialogOption(
                    title: "Save",
                    enabled: !viewModel.sessionRenameDialog.isSubmitting
                ) {
                    Task { await viewModel.confirmSessionRename() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.sessionRenameDialog.isSubmitting,
                    action: viewModel.cancelSessionRename
                ),
                textField: OpenCodeDialogTextField(
                    text: Binding(
                        get: { viewModel.sessionRenameDialog.draftTitle },
                        set: viewModel.updateSessionRenameDraft
                    ),
                    hint: "Title",
                    enabled: !viewModel.sessionRenameDialog.isSubmitting,
                    errorMessage: viewModel.sessionRenameDialog.errorMessage
                )
            )
            .openCodeDialog(
                isPresented: sessionForkDialogBinding,
                title: "Fork session",
                primaryOption: OpenCodeDialogOption(
                    title: "Fork",
                    enabled: !viewModel.sessionForkDialog.isSubmitting
                ) {
                    Task { await viewModel.confirmSessionFork() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.sessionForkDialog.isSubmitting,
                    action: viewModel.cancelSessionFork
                ),
                textField: OpenCodeDialogTextField(
                    text: Binding(
                        get: { viewModel.sessionForkDialog.draftTitle },
                        set: viewModel.updateSessionForkDraft
                    ),
                    hint: "Title",
                    enabled: !viewModel.sessionForkDialog.isSubmitting,
                    errorMessage: viewModel.sessionForkDialog.errorMessage
                )
            )
            .openCodeDialog(
                isPresented: sessionForkResultBinding,
                title: "Session forked",
                subtitle: "Forked as “\(viewModel.sessionForkResult.forkedTitle)”.",
                primaryOption: OpenCodeDialogOption(title: "Open") {
                    viewModel.chooseSessionForkOpen()
                },
                secondaryOption: OpenCodeDialogOption(title: "Stay") {
                    viewModel.chooseSessionForkStay()
                }
            )
            .openCodeDialog(
                isPresented: sessionDeleteConfirmBinding,
                title: "Delete session",
                subtitle: viewModel.sessionActionsTarget.map { "Delete session “\($0.title)”?" },
                primaryOption: OpenCodeDialogOption(
                    title: "Delete",
                    enabled: !viewModel.sessionActionInFlight,
                    destructive: true
                ) {
                    Task { await viewModel.confirmDeleteSessionAction() }
                },
                secondaryOption: OpenCodeDialogOption(
                    title: "Cancel",
                    enabled: !viewModel.sessionActionInFlight,
                    action: viewModel.cancelDeleteSessionAction
                )
            )
        }
    }

    private var sessionActionsSheetBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionActionsSheetVisible },
            set: { visible in
                if !visible { viewModel.dismissSessionActions() }
            }
        )
    }

    private var sessionRenameDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionRenameDialog.isVisible },
            set: { visible in
                if !visible { viewModel.cancelSessionRename() }
            }
        )
    }

    private var sessionForkDialogBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionForkDialog.isVisible },
            set: { visible in
                if !visible { viewModel.cancelSessionFork() }
            }
        )
    }

    private var sessionForkResultBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionForkResult.isVisible },
            set: { visible in
                if !visible { viewModel.chooseSessionForkStay() }
            }
        )
    }

    private var sessionDeleteConfirmBinding: Binding<Bool> {
        Binding(
            get: { viewModel.sessionDeleteConfirmVisible },
            set: { visible in
                if !visible { viewModel.cancelDeleteSessionAction() }
            }
        )
    }

    private var projectRemovalBinding: Binding<Bool> {
        Binding(
            get: { viewModel.pendingProjectRemoval != nil },
            set: { visible in
                if !visible {
                    viewModel.dismissProjectRemoval()
                }
            }
        )
    }

    private var pendingProjectRemoval: ProjectRemovalItem? {
        viewModel.pendingProjectRemoval
    }

    private var newSessionPickerProjects: [(worktree: String, displayName: String)] {
        switch viewModel.state {
        case let .loaded(sections, _, _, _, _, _):
            sections.map { ($0.worktree, $0.displayName) }
        default:
            []
        }
    }

    private func openSession(_ session: SessionListItem) {
        navigationPath.append(session)
    }

    private var addProjectPresentedBinding: Binding<Bool> {
        Binding(
            get: { viewModel.isAddProjectPresented },
            set: { presented in
                if !presented { viewModel.dismissAddProjectSheet() }
            }
        )
    }

    private var seeAllSheetBinding: Binding<SeeAllSessionsSheetState?> {
        Binding(
            get: { viewModel.seeAllSessionsSheet },
            set: { value in
                if value == nil {
                    viewModel.dismissSeeAllSessions()
                }
            }
        )
    }

    private func presentConnectionSetup() {
        if let profile = Container.shared.activeConnectionManager().currentProfile() {
            connectionSetupViewModel = Container.makeConnectionSetupViewModel(initialProfile: profile)
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile)
            return
        }
        Task {
            let profile = await Container.shared.connectionProfileStore().activeProfile()
            connectionSetupViewModel = Container.makeConnectionSetupViewModel(initialProfile: profile)
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile)
        }
    }

    private var showsLiveUpdatesStatus: Bool {
        switch viewModel.state {
        case .disconnected, .probing, .loading:
            false
        default:
            true
        }
    }

    private var homeTopBarTitle: String {
        homeDisplayPreferences.showSessionListOnHome ? "Sessions" : "OpenCode Mobile"
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .probing, .loading:
            OpenCodeStatusStack(
                title: "Loading sessions",
                subtitle: "Checking server…",
                accessibilityIdentifier: "homeLoading"
            )
        case .disconnected:
            ConnectionOnboardingView(onConnectClick: presentConnectionSetup)
        case let .probeFailed(message):
            probeFailed(message)
        case let .empty(_, _, createError):
            homeProjectsList(sections: [], focusedWorktree: nil, createError: createError)
        case let .loaded(sections, _, _, _, focusedWorktree, createError):
            homeProjectsList(sections: sections, focusedWorktree: focusedWorktree, createError: createError)
        }
    }

    private func probeFailed(_ message: String) -> some View {
        VStack(spacing: 12) {
            Text("Unable to connect")
                .font(OpenCodeTypography.heading)
            Text(message)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
                .multilineTextAlignment(.center)
            OpenCodeButton(variant: .fullWidthText(title: "Retry")) {
                Task { await viewModel.retryProbe() }
            }
            OpenCodeButton(variant: .fullWidthText(title: "Edit connection")) {
                presentConnectionSetup()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(24)
    }

    @ViewBuilder
    private func homeProjectsList(
        sections: [ProjectSectionItem],
        focusedWorktree: String?,
        createError: String?
    ) -> some View {
        if homeDisplayPreferences.showSessionListOnHome {
            let sessions = sections.crossProjectSessionListSessions()
            let filterTabs = sections.sessionListFilterTabs(sessionCount: sessions.count)
            let selectedFilterId =
                filterTabs.contains(where: { $0.id == selectedSessionFilterId })
                    ? selectedSessionFilterId
                    : SessionListFilterTab.allId
            SessionList(
                sessions: sessions,
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                createError: createError,
                onFilterSelected: { selectedSessionFilterId = $0 },
                onSessionSelected: { session in
                    openSession(
                        SessionListItem(
                            id: session.id,
                            title: session.title,
                            worktree: session.worktree
                        )
                    )
                },
                onSessionLongPress: { session in
                    viewModel.onSessionLongPress(
                        sessionId: session.id,
                        title: session.title,
                        worktree: session.worktree
                    )
                },
                onAddProject: { Task { await viewModel.showAddProjectSheet() } },
                onNewSession: { showNewSessionProjectPicker = true },
                onRefresh: { await viewModel.pullToRefresh() },
                onRemoveProject: { worktree in viewModel.requestRemoveProject(worktree: worktree) },
                showsEmptyContent: sections.isEmpty,
                emptyContent: { NoProjectsEmptyState() }
            )
        } else {
            GeometryReader { proxy in
                if sections.isEmpty {
                    ScrollView {
                        NoProjectsEmptyState()
                            .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                    }
                    .background(OpenCodeColors.backgroundBase)
                    .refreshable { await viewModel.pullToRefresh() }
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        ProjectSessionSelector(
                            projects: [],
                            selectedWorktree: nil,
                            createError: createError,
                            onProjectSelected: { _ in },
                            onBrowseProjects: { Task { await viewModel.showAddProjectSheet() } },
                            onSessionSelected: { _, _ in },
                            onShowAllSessions: { _ in },
                            onNewSession: { _ in }
                        )
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)
                    }
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 16) {
                            ProjectSessionSelector(
                                projects: sections.map(\.projectSessionSelectorProject),
                                selectedWorktree: focusedWorktree,
                                createError: createError,
                                onProjectSelected: { viewModel.focusProject(worktree: $0) },
                                onBrowseProjects: { Task { await viewModel.showAddProjectSheet() } },
                                onSessionSelected: { project, session in
                                    openSession(
                                        SessionListItem(id: session.id, title: session.title, worktree: project.worktree)
                                    )
                                },
                                onShowAllSessions: { viewModel.showSeeAllSessions(worktree: $0) },
                                onNewSession: { worktree in Task { await viewModel.createSession(in: worktree) } }
                            )
                            .padding(.horizontal, 16)
                            .padding(.bottom, 16)
                        }
                        .frame(
                            maxWidth: .infinity,
                            minHeight: proxy.size.height,
                            alignment: .bottom
                        )
                    }
                    .background(OpenCodeColors.backgroundBase)
                    .refreshable { await viewModel.pullToRefresh() }
                }
            }
            .background(OpenCodeColors.backgroundBase)
        }
    }
}

private func seeAllSessionEntryTitle(_ session: SessionListItem) -> String {
    if let statusLabel = session.statusLabel {
        "\(session.title) · \(statusLabel)"
    } else {
        session.title
    }
}

struct SessionListItem: Identifiable, Hashable {
    let id: String
    let title: String
    let worktree: String
    let statusLabel: String?
    let updatedAtLabel: String?
    let updatedAtEpochMs: Int64?
    let isReady: Bool

    init(
        id: String,
        title: String,
        worktree: String,
        statusLabel: String? = nil,
        updatedAtLabel: String? = nil,
        updatedAtEpochMs: Int64? = nil,
        isReady: Bool = false
    ) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.statusLabel = statusLabel
        self.updatedAtLabel = updatedAtLabel
        self.updatedAtEpochMs = updatedAtEpochMs
        self.isReady = isReady
    }
}

private extension ProjectSectionItem {
    var projectSessionSelectorProject: ProjectSessionSelectorProject {
        ProjectSessionSelectorProject(
            worktree: worktree,
            displayName: displayName,
            pathSubtitle: pathSubtitle,
            letter: letter,
            letterAccent: letterAccent,
            previewSessions: previewSessions.map(\.projectSessionSelectorSession),
            totalSessionCount: totalSessionCount,
            loadError: loadError
        )
    }
}

private extension SessionListItem {
    var projectSessionSelectorSession: ProjectSessionSelectorSession {
        ProjectSessionSelectorSession(
            id: id,
            title: title,
            subtitle: updatedAtLabel ?? statusLabel
        )
    }

    func sessionListStatus(hasPendingPermission: Bool = false) -> SessionListStatus {
        _ = hasPendingPermission // Reserved for a future home Needs approval pill.
        switch statusLabel {
        case "Working", "Retrying":
            return SessionListStatus.running
        default:
            return isReady ? SessionListStatus.ready : SessionListStatus.idle
        }
    }
}

private extension Array where Element == ProjectSectionItem {
    func crossProjectSessionListSessions() -> [SessionListSession] {
        flatMap { section in
            section.allSessions.map { session in
                (
                    session.updatedAtEpochMs ?? 0,
                    SessionListSession(
                        id: session.id,
                        title: session.title,
                        worktree: section.worktree,
                        letter: section.letter,
                        letterAccent: section.letterAccent,
                        subtitle: section.pathSubtitle,
                        status: session.sessionListStatus(),
                        caption: session.updatedAtEpochMs.map { RelativeTimeFormatting.format(epochMs: $0) } ?? "",
                        updatedAtEpochMs: session.updatedAtEpochMs
                    )
                )
            }
        }
        .sorted { $0.0 > $1.0 }
        .map(\.1)
    }

    func sessionListFilterTabs(sessionCount: Int) -> [SessionListFilterTab] {
        var tabs = [
            SessionListFilterTab(
                id: SessionListFilterTab.allId,
                title: "All",
                count: sessionCount
            ),
        ]
        tabs.append(
            contentsOf: map { section in
                SessionListFilterTab(
                    id: section.worktree,
                    title: section.displayName,
                    count: section.allSessions.count
                )
            }
        )
        return tabs
    }
}

private extension EventStreamConnectionState {
    var openCodeSessionConnectionStatus: OpenCodeSessionConnectionStatus {
        switch self {
        case .connected:
            .live
        case .connecting, .reconnecting:
            .connecting
        case .idle, .disconnected:
            .offline
        }
    }
}

#Preview {
    ProjectListHomeView(viewModel: previewProjectListHomeViewModel())
        .openCodeTheme()
}

@MainActor
private func previewProjectListHomeViewModel() -> ProjectListHomeViewModel {
    let manager = ActiveConnectionManager()
    manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""))
    let store = PreviewConnectionProfileStore()
    let coordinator = ConnectionSessionCoordinator(
        activeConnectionManager: manager,
        connectionProfileStore: store
    )
    let previewEventStream = PreviewForegroundEventStream()
    let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
        sessionRepository: PreviewSessionRepository(),
        eventStream: previewEventStream
    )
    let sessionLiveStore = DefaultSessionLiveStore(
        sessionRepository: PreviewSessionRepository(),
        eventStream: previewEventStream,
        sessionCache: NoOpSessionCache.shared
    )
    let sessionReadyStore = DefaultSessionReadyStore(
        sessionStatusLiveStore: sessionStatusLiveStore,
        eventStream: previewEventStream
    )
    return ProjectListHomeViewModel(
        projectRepository: PreviewProjectRepository(),
        filesystemRepository: PreviewFilesystemRepository(),
        sessionRepository: PreviewSessionRepository(),
        messageRepository: PreviewMessageRepository(),
        connectionProfileStore: store,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
        sessionReadyStore: sessionReadyStore,
        foregroundEventStream: previewEventStream,
        connectionSessionCoordinator: coordinator
    )
}

private struct PreviewProjectRepository: ProjectRepository {
    func probeServer() async throws {}
    func listProjects() async throws -> [Project] { [] }
    func getCurrentProject(directory: String?) async throws -> Project {
        Project(id: "1", worktree: "/demo", displayName: "demo")
    }
}

private struct PreviewFilesystemRepository: FilesystemRepository {
    func getPaths() async throws -> ServerPaths {
        ServerPaths(home: "/demo", directory: "/demo", worktree: "/")
    }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        []
    }
}

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { ["/demo"] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func removeWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
    func clearAll() async {}
}

private struct PreviewSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: directory, parentID: nil, updatedAtEpochMs: 1)]
    }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}

private struct PreviewMessageRepository: MessageRepository {
    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func observeDisplayMessages(sessionID: String, directory: String?) -> AsyncStream<DisplayMessageListUpdate> {
        AsyncStream { $0.finish() }
    }

    func refreshMessages(sessionID: String, directory: String?) async throws -> [Message] { [] }
    func refreshDisplayMessages(sessionID: String, directory: String?) async throws -> [DisplayMessage] { [] }
    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage { fatalError() }
    func clearMessageCache() {}
    func listMessages(sessionID: String, directory: String?) async throws -> [Message] { [] }
    func getMessage(sessionID: String, messageID: String, directory: String?) async throws -> Message { fatalError() }
    func sendMessage(sessionID: String, text: String, directory: String?) async throws -> Message { fatalError() }
    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}
}

@MainActor
private final class PreviewForegroundEventStream: ForegroundEventStream {
    var connectionState: EventStreamConnectionState = .connecting

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        AsyncStream { continuation in
            continuation.yield(connectionState)
        }
    }

    func events() -> AsyncStream<OpencodeEvent> { AsyncStream { _ in } }
    func start() {}
    func stop() {}
}
