import Factory
import SwiftUI

struct ProjectListHomeView: View {
    @State private var viewModel: ProjectListHomeViewModel
    @State private var showComponentGallery = false
    @State private var selectedSession: SessionListItem?
    @State private var connectionSetupPresentation: ConnectionSetupPresentation?

    init(viewModel: ProjectListHomeViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "OpenCode Mobile",
                actions: [
                    .icon(.edit, accessibilityLabel: "Edit connection", action: { presentConnectionSetup() }),
                    .icon(.widgets, accessibilityLabel: "Developer components", action: { showComponentGallery = true }),
                ],
                showDivider: true
            )
            if showsLiveUpdatesStatus {
                LiveUpdatesConnectionBanner(state: viewModel.liveUpdatesConnectionState)
            }
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundPrimary)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
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
        .sheet(isPresented: addProjectSheetBinding) {
            addProjectSheet
        }
        .sheet(item: seeAllSheetBinding) { sheet in
            seeAllSheet(sheet)
        }
        .openCodeBottomSheet(item: $connectionSetupPresentation, title: "Connect to server") { presentation in
            ConnectionSetupSheetView(
                viewModel: Container.makeConnectionSetupViewModel(initialProfile: presentation.initialProfile),
                onDismiss: { connectionSetupPresentation = nil }
            )
        }
    }

    private func presentConnectionSetup() {
        if let profile = Container.shared.activeConnectionManager().currentProfile() {
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile)
            return
        }
        Task {
            let profile = await Container.shared.connectionProfileStore().activeProfile()
            connectionSetupPresentation = ConnectionSetupPresentation(initialProfile: profile)
        }
    }

    private var showsLiveUpdatesStatus: Bool {
        switch viewModel.state {
        case .probing, .loading:
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
                .foregroundStyle(OpenCodeColors.textSecondary)
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
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                browseProjectsListItem
                if sections.isEmpty {
                    Text("Add a project to see recent sessions.")
                        .font(OpenCodeTypography.body)
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .padding(.horizontal, 16)
                }
                ForEach(sections) { section in
                    projectSection(section)
                }
                ProjectFocusSection(
                    sections: sections,
                    focusedWorktree: focusedWorktree,
                    createError: createError,
                    onFocusProject: { viewModel.focusProject(worktree: $0) },
                    onBrowseProjects: { Task { await viewModel.showAddProjectSheet() } },
                    onSessionSelected: { selectedSession = $0 },
                    onShowAll: { viewModel.showSeeAllSessions(worktree: $0) },
                    onNewSession: { worktree in Task { await viewModel.createSession(in: worktree) } }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            }
        }
        .background(OpenCodeColors.backgroundPrimary)
    }

    private var browseProjectsListItem: some View {
        OpenCodeListItem(
            title: "Browse projects",
            startDecorator: .icon(.add, accessibilityLabel: "Browse projects"),
            action: { Task { await viewModel.showAddProjectSheet() } }
        )
    }

    private func projectSection(_ section: ProjectSectionItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeListItem(
                title: section.displayName,
                subtitle: section.pathSubtitle,
                startDecorator: .letter(section.letter, section.letterAccent)
            )
            if let error = section.loadError {
                Text(error)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textSecondary)
                    .padding(.leading, 16)
            }
            if section.previewSessions.isEmpty, section.loadError == nil {
                OpenCodeListItem(
                    title: "New Session",
                    action: { Task { await viewModel.createSession(in: section.worktree) } }
                )
            } else {
                ForEach(section.previewSessions) { session in
                    OpenCodeListItem(
                        title: session.title,
                        subtitle: session.statusLabel,
                        action: { selectedSession = session }
                    )
                }
            }
            if section.totalSessionCount > section.previewSessions.count {
                OpenCodeListItem(
                    title: "See all...",
                    action: { viewModel.showSeeAllSessions(worktree: section.worktree) }
                )
            }
        }
    }

    private var addProjectSheetBinding: Binding<Bool> {
        Binding(
            get: {
                switch viewModel.state {
                case let .empty(show, _, _), let .loaded(_, show, _, _, _, _):
                    show
                default:
                    false
                }
            },
            set: { isPresented in
                if !isPresented { viewModel.dismissAddProjectSheet() }
            }
        )
    }

    @ViewBuilder
    private var addProjectSheet: some View {
        let candidates: [AddProjectCandidateItem] = {
            switch viewModel.state {
            case let .empty(_, items, _), let .loaded(_, _, items, _, _, _):
                items
            default:
                []
            }
        }()
        NavigationStack {
            List {
                if candidates.isEmpty {
                    Text("No more projects on this server")
                        .foregroundStyle(OpenCodeColors.textSecondary)
                        .listRowSeparator(.hidden)
                } else {
                    ForEach(candidates) { candidate in
                        Button(candidate.displayName) {
                            Task {
                                await viewModel.addProject(worktree: candidate.worktree)
                            }
                        }
                        .listRowSeparator(.hidden)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .navigationTitle("Browse projects")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { viewModel.dismissAddProjectSheet() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var seeAllSheetBinding: Binding<SeeAllSessionsSheetState?> {
        Binding(
            get: {
                if case let .loaded(_, _, _, sheet, _, _) = viewModel.state { sheet }
                else { nil }
            },
            set: { _ in viewModel.dismissSeeAllSessions() }
        )
    }

    private func seeAllSheet(_ sheet: SeeAllSessionsSheetState) -> some View {
        NavigationStack {
            List(sheet.sessions) { session in
                Button(session.title) {
                    viewModel.dismissSeeAllSessions()
                    selectedSession = session
                }
                .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .navigationTitle(sheet.projectName)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { viewModel.dismissSeeAllSessions() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct ProjectFocusSection: View {
    let sections: [ProjectSectionItem]
    let focusedWorktree: String?
    let createError: String?
    let onFocusProject: (String) -> Void
    let onBrowseProjects: () -> Void
    let onSessionSelected: (SessionListItem) -> Void
    let onShowAll: (String) -> Void
    let onNewSession: (String) -> Void

    private let horizontalSpacing: CGFloat = 12

    private var focusedSection: ProjectSectionItem? {
        if let focusedWorktree,
           let section = sections.first(where: { $0.worktree == focusedWorktree }) {
            return section
        }
        return sections.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            projectTiles

            if let focusedSection {
                projectHeader(focusedSection)
                Divider().overlay(OpenCodeColors.borderSubtle)
                sessionPreview(focusedSection)
                Divider().overlay(OpenCodeColors.borderSubtle)
                footer(focusedSection)
            }
        }
        .background(OpenCodeColors.backgroundSecondary, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
        )
    }

    private var projectTiles: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: horizontalSpacing) {
                ForEach(sections) { section in
                    ProjectFocusTile(
                        section: section,
                        focused: section.worktree == focusedSection?.worktree,
                        action: { onFocusProject(section.worktree) }
                    )
                }
                AddProjectFocusTile(action: onBrowseProjects)
            }
            .padding(.horizontal, horizontalSpacing)
            .padding(.vertical, 20)
        }
    }

    private func projectHeader(_ section: ProjectSectionItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 4) {
                OpenCodeIconImage(icon: .folder)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.textInteractive)
                    .accessibilityHidden(true)
                Text(section.displayName)
                    .font(OpenCodeTypography.heading.weight(.bold))
                    .foregroundStyle(OpenCodeColors.textPrimary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(compactHomePath(section.pathSubtitle))
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, horizontalSpacing)
        .padding(.bottom, 16)
    }

    private func sessionPreview(_ section: ProjectSectionItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let error = section.loadError {
                Text(error)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.statusError)
                    .lineLimit(2)
                    .padding(.horizontal, horizontalSpacing)
                    .padding(.vertical, 14)
            } else if !section.previewSessions.isEmpty {
                ForEach(section.previewSessions) { session in
                    OpenCodeListItem(
                        title: session.title,
                        subtitle: session.projectFocusSubtitle,
                        action: { onSessionSelected(session) }
                    )
                }
            }
        }
    }

    private func footer(_ section: ProjectSectionItem) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 8) {
                OpenCodeButton(
                    variant: .iconSquare(.list, accessibilityLabel: "Show all"),
                    action: { onShowAll(section.worktree) }
                )
                OpenCodeButton(
                    variant: .fullWidthText(title: "New session"),
                    action: { onNewSession(section.worktree) }
                )
            }
            if let createError {
                Text(createError)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.statusError)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 12)
        .padding(.horizontal, horizontalSpacing)
        .padding(.bottom, 16)
    }
}

private struct ProjectFocusTile: View {
    let section: ProjectSectionItem
    let focused: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                Text(String(section.letter).uppercased())
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(focused ? OpenCodeColors.textInteractive : section.letterAccent.badgeTint)
                    .frame(width: 48, height: 48)
                    .background(focused ? OpenCodeColors.backgroundSecondary : section.letterAccent.badgeFill)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(focused ? OpenCodeColors.textInteractive : Color.clear, lineWidth: 3)
                    )
                if focused {
                    Circle()
                        .fill(OpenCodeColors.textPrimary)
                        .frame(width: 12, height: 12)
                        .offset(x: 3, y: -3)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(section.displayName), \(compactHomePath(section.pathSubtitle))")
        .accessibilityValue(focused ? "Focused" : "Not focused")
    }
}

private struct AddProjectFocusTile: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            OpenCodeIconImage(icon: .add)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(OpenCodeColors.textSecondary)
                .frame(width: 48, height: 48)
                .background(OpenCodeColors.surfaceInset, in: RoundedRectangle(cornerRadius: 12))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(
                            OpenCodeColors.borderDefault,
                            style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                        )
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Browse projects")
    }
}

private func compactHomePath(_ path: String) -> String {
    let home = NSHomeDirectory()
    if path == home { return "~" }
    if path.hasPrefix(home + "/") {
        return "~" + path.dropFirst(home.count)
    }
    return path
}

struct SessionListItem: Identifiable, Hashable {
    let id: String
    let title: String
    let worktree: String
    let statusLabel: String?
    let updatedAtLabel: String?

    init(id: String, title: String, worktree: String, statusLabel: String? = nil, updatedAtLabel: String? = nil) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.statusLabel = statusLabel
        self.updatedAtLabel = updatedAtLabel
    }
}

private extension SessionListItem {
    var projectFocusSubtitle: String? {
        updatedAtLabel ?? statusLabel
    }
}

extension SeeAllSessionsSheetState: Identifiable {
    var id: String { worktree }
}

#Preview {
    NavigationStack {
        ProjectListHomeView(viewModel: previewProjectListHomeViewModel())
    }
    .openCodeTheme()
}

#Preview("Project Focus Overflow") {
    ProjectFocusSection(
        sections: previewProjectFocusSections(sessionCount: 5),
        focusedWorktree: "/Users/demo/opencode-mobile",
        createError: nil,
        onFocusProject: { _ in },
        onBrowseProjects: {},
        onSessionSelected: { _ in },
        onShowAll: { _ in },
        onNewSession: { _ in }
    )
    .padding(.vertical, 24)
    .openCodeTheme()
}

#Preview("Project Focus Empty") {
    ProjectFocusSection(
        sections: [
            ProjectSectionItem(
                id: "/Users/demo/empty",
                worktree: "/Users/demo/empty",
                displayName: "empty",
                pathSubtitle: "/Users/demo/empty",
                letter: "E",
                letterAccent: .teal,
                previewSessions: [],
                allSessions: [],
                totalSessionCount: 0,
                loadError: nil
            ),
        ],
        focusedWorktree: "/Users/demo/empty",
        createError: nil,
        onFocusProject: { _ in },
        onBrowseProjects: {},
        onSessionSelected: { _ in },
        onShowAll: { _ in },
        onNewSession: { _ in }
    )
    .padding(.vertical, 24)
    .openCodeTheme()
}

#Preview("Project Focus Add Tile") {
    ProjectFocusSection(
        sections: [],
        focusedWorktree: nil,
        createError: nil,
        onFocusProject: { _ in },
        onBrowseProjects: {},
        onSessionSelected: { _ in },
        onShowAll: { _ in },
        onNewSession: { _ in }
    )
    .padding(.vertical, 24)
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

private func previewProjectFocusSections(sessionCount: Int) -> [ProjectSectionItem] {
    let sessions = (1 ... sessionCount).map { index in
        SessionListItem(
            id: "\(index)",
            title: ["Refactor Navigation", "Bug: Header Clipping", "Add Dark Mode Assets", "Review Session List", "Polish Connection Flow"][index - 1],
            worktree: "/Users/demo/opencode-mobile",
            updatedAtLabel: ["Oct 24 • 2:30 PM", "Oct 23 • 11:45 AM", "Oct 22 • 4:15 PM", "Oct 21 • 10:05 AM", "Oct 20 • 9:20 AM"][index - 1]
        )
    }
    return [
        ProjectSectionItem(
            id: "/Users/demo/opencode-mobile",
            worktree: "/Users/demo/opencode-mobile",
            displayName: "opencode-mobile",
            pathSubtitle: "/Users/demo/opencode-mobile",
            letter: "O",
            letterAccent: .violet,
            previewSessions: Array(sessions.prefix(3)),
            allSessions: sessions,
            totalSessionCount: sessions.count,
            loadError: nil
        ),
        ProjectSectionItem(
            id: "/Users/demo/playground",
            worktree: "/Users/demo/playground",
            displayName: "playground",
            pathSubtitle: "/Users/demo/playground",
            letter: "P",
            letterAccent: .amber,
            previewSessions: [],
            allSessions: [],
            totalSessionCount: 0,
            loadError: nil
        ),
    ]
}

private struct PreviewConnectionProfileStore: ConnectionProfileStore {
    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }
    func saveProfile(_ profile: ConnectionProfile) async {}
    func addedWorktrees(serverBaseURL: String) async -> [String] { ["/demo"] }
    func addWorktree(serverBaseURL: String, worktree: String) async {}
    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}
}

private struct PreviewSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: directory, parentID: nil, updatedAtEpochMs: 1)]
    }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool { fatalError() }
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
