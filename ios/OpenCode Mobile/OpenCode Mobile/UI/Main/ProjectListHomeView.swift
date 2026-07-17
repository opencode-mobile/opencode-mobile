import Factory
import SwiftUI

struct ProjectListHomeView: View {
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showSettings = false
    @State private var selectedSession: SessionListItem?
    @State private var connectionSetupPresentation: ConnectionSetupPresentation?
    @State private var connectionSetupViewModel: ConnectionSetupViewModel?

    init(viewModel: ProjectListHomeViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "OpenCode Mobile",
                subtitleAccessory: showsLiveUpdatesStatus
                    ? .statusDot(viewModel.liveUpdatesConnectionState.openCodeSessionConnectionStatus)
                    : nil,
                actions: [
                    .icon(.settings, accessibilityLabel: "Settings", action: { showSettings = true }),
                ],
                showDivider: true
            )
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(isPresented: $showSettings) {
            SettingsView(onConnectionSetup: presentConnectionSetup)
        }
        .navigationDestination(item: $selectedSession) { session in
            SessionChatView(session: session)
        }
        .task {
            await viewModel.refresh()
        }
        .onChange(of: viewModel.pendingSessionOpen?.id) { _, _ in
            if let session = viewModel.pendingSessionOpen {
                selectedSession = session
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
            isPresented: seeAllPresentedBinding,
            title: viewModel.seeAllSessionsSheet?.projectName ?? "Sessions"
        ) {
            if let sheet = viewModel.seeAllSessionsSheet {
                ForEach(sheet.sessions) { session in
                    OpenCodeBottomSheetEntry(
                        title: seeAllSessionEntryTitle(session),
                        action: {
                            viewModel.dismissSeeAllSessions()
                            selectedSession = session
                        }
                    )
                }
            }
        }
        .openCodeBottomSheet(
            item: $connectionSetupPresentation,
            title: "Connect to server",
            contentMode: .form
        ) { presentation in
            ConnectionSetupSheetView(
                viewModel: connectionSetupViewModel ?? Container.makeConnectionSetupViewModel(initialProfile: presentation.initialProfile),
                onDismiss: {
                    connectionSetupPresentation = nil
                    connectionSetupViewModel = nil
                }
            )
        }
    }

    private var addProjectPresentedBinding: Binding<Bool> {
        Binding(
            get: { viewModel.isAddProjectPresented },
            set: { presented in
                if !presented { viewModel.dismissAddProjectSheet() }
            }
        )
    }

    private var seeAllPresentedBinding: Binding<Bool> {
        Binding(
            get: { viewModel.seeAllSessionsSheet != nil },
            set: { presented in
                if !presented { viewModel.dismissSeeAllSessions() }
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

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .probing, .loading:
            Spacer()
            ProgressView()
            Spacer()
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

    private func homeProjectsList(
        sections: [ProjectSectionItem],
        focusedWorktree: String?,
        createError: String?
    ) -> some View {
        let crossProjectSessions = sections.crossProjectSessionListSessions()
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if !crossProjectSessions.isEmpty {
                    SessionList(
                        sessions: crossProjectSessions,
                        onSessionSelected: { session in
                            selectedSession = SessionListItem(
                                id: session.id,
                                title: session.title,
                                worktree: session.worktree
                            )
                        }
                    )
                }

                ProjectSessionSelector(
                    projects: sections.map(\.projectSessionSelectorProject),
                    selectedWorktree: focusedWorktree,
                    createError: createError,
                    onProjectSelected: { viewModel.focusProject(worktree: $0) },
                    onBrowseProjects: { Task { await viewModel.showAddProjectSheet() } },
                    onSessionSelected: { project, session in
                        selectedSession = SessionListItem(id: session.id, title: session.title, worktree: project.worktree)
                    },
                    onShowAllSessions: { viewModel.showSeeAllSessions(worktree: $0) },
                    onNewSession: { worktree in Task { await viewModel.createSession(in: worktree) } }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(OpenCodeColors.backgroundBase)
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

    init(
        id: String,
        title: String,
        worktree: String,
        statusLabel: String? = nil,
        updatedAtLabel: String? = nil,
        updatedAtEpochMs: Int64? = nil
    ) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.statusLabel = statusLabel
        self.updatedAtLabel = updatedAtLabel
        self.updatedAtEpochMs = updatedAtEpochMs
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

    func sessionListStatus() -> SessionListStatus {
        switch statusLabel {
        case "Working", "Retrying":
            .running
        default:
            .idle
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
                        caption: session.updatedAtEpochMs.map { RelativeTimeFormatting.format(epochMs: $0) } ?? ""
                    )
                )
            }
        }
        .sorted { $0.0 > $1.0 }
        .map(\.1)
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
    NavigationStack {
        ProjectListHomeView(viewModel: previewProjectListHomeViewModel())
    }
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
        eventStream: previewEventStream
    )
    return ProjectListHomeViewModel(
        projectRepository: PreviewProjectRepository(),
        filesystemRepository: PreviewFilesystemRepository(),
        sessionRepository: PreviewSessionRepository(),
        connectionProfileStore: store,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
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
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
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
