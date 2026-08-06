import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ProjectListHomeViewModelTests {
    @Test func refresh_probeFailed() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(shouldFailProbe: true)
        )
        await viewModel.refresh()
        guard case .probeFailed = viewModel.state else {
            Issue.record("Expected probeFailed")
            return
        }
    }

    @Test func refresh_staysEmpty_whenNoAddedProjects() async {
        let store = FakeConnectionProfileStore()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(bootWorktree: "/boot"),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").isEmpty)
        guard case .empty = viewModel.state else {
            Issue.record("Expected empty without auto-seeded projects")
            return
        }
    }

    @Test func refresh_limitsPreviewSessionsToThree() async {
        let sessions = (1 ... 7).map {
            Session(id: "\($0)", title: "S\($0)", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: Int64($0))
        }
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        // Disk-first paints empty cache immediately; wait for live SWR to fill sections.
        let populated = await waitUntil {
            if case let .loaded(sections, _, _, _, _, _) = viewModel.state {
                return sections.first?.totalSessionCount == 7
            }
            return false
        }
        #expect(populated)
        guard case let .loaded(sections, _, _, _, focusedWorktree, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.first?.previewSessions.count == 3)
        #expect(sections.first?.totalSessionCount == 7)
        #expect(focusedWorktree == "/repo")
    }

    @Test func refresh_excludesArchivedSessionsFromActiveLists() async {
        let sessions = [
            Session(id: "active", title: "Active", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: 2),
            Session(
                id: "archived",
                title: "Archived",
                slug: nil,
                projectID: nil,
                directory: "/repo",
                parentID: nil,
                updatedAtEpochMs: 3,
                archivedAtEpochMs: 1
            ),
        ]
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        let populated = await waitUntil {
            if case let .loaded(sections, _, _, _, _, _) = viewModel.state {
                return sections.first?.allSessions.map(\.id) == ["active"]
            }
            return false
        }
        #expect(populated)
        guard case let .loaded(sections, _, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.first?.allSessions.map(\.id) == ["active"])
        #expect(sections.first?.totalSessionCount == 1)
    }

    @Test func refresh_preservesAddedWorktreeOrder() async {
        let viewModel = makeViewModel(
            projectRepository: SlowFirstProjectRepository(),
            sessionRepository: SlowFirstSessionRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/slow", "/fast"])
        )
        await viewModel.refresh()
        guard case let .loaded(sections, _, _, _, focusedWorktree, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/slow", "/fast"])
        #expect(focusedWorktree == "/slow")
    }

    @Test func focusProject_updatesHomeFocusOnly() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one", "/two"])
        )
        await viewModel.refresh()
        viewModel.focusProject(worktree: "/two")
        guard case let .loaded(sections, _, _, _, focusedWorktree, createError) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/one", "/two"])
        #expect(focusedWorktree == "/two")
        #expect(createError == nil)
    }

    @Test func addProject_focusesNewProject() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(projects: [
            Project(id: "two", worktree: "/two", displayName: "two"),
        ])
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.addProject(worktree: "/two")
        guard case let .loaded(_, _, _, _, focusedWorktree, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(focusedWorktree == "/two")
    }

    @Test func requestRemoveProject_setsPendingRemoval() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one", "/two"])
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        #expect(viewModel.pendingProjectRemoval?.worktree == "/one")
        #expect(viewModel.pendingProjectRemoval?.displayName == "one")
    }

    @Test func dismissProjectRemoval_clearsPending() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        viewModel.dismissProjectRemoval()
        #expect(viewModel.pendingProjectRemoval == nil)
    }

    @Test func confirmRemoveProject_dropsWorktreeAndShowsSnackbar() async {
        let store = FakeConnectionProfileStore(initial: ["/one", "/two"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        await viewModel.confirmRemoveProject()

        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/two"])
        #expect(viewModel.pendingProjectRemoval == nil)
        guard case let .loaded(sections, _, _, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/two"])
        #expect(viewModel.removalSnackbar?.text == "Removed \"one\" from home")

        viewModel.consumeProjectRemovalSnackbar()
        #expect(viewModel.removalSnackbar == nil)
    }

    @Test func confirmRemoveProject_lastProject_showsEmpty() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        viewModel.requestRemoveProject(worktree: "/one")
        await viewModel.confirmRemoveProject()

        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").isEmpty)
        guard case .empty = viewModel.state else {
            Issue.record("Expected empty")
            return
        }
        #expect(viewModel.removalSnackbar?.text == "Removed \"one\" from home")
    }

    @Test func showAddProjectSheet_startsAtHomeAndListsDirectories() async {
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: [
                "/home": [
                    DirectoryNode(name: "projects", absolutePath: "/home/projects", isDirectory: true),
                    DirectoryNode(name: ".cache", absolutePath: "/home/.cache", isDirectory: true),
                    DirectoryNode(name: "readme", absolutePath: "/home/readme", isDirectory: false),
                ],
            ]
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            filesystemRepository: filesystem,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/one"])
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        guard case let .loaded(_, show, navigator, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show)
        #expect(navigator?.currentPath == "/home")
        #expect(navigator?.entries.map(\.name) == ["projects"])
        #expect(navigator?.canGoUp == true)
    }

    @Test func selectCurrentDirectory_validatesAndFocuses() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository()
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": []]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.selectCurrentDirectory()
        guard case let .loaded(_, show, _, _, focusedWorktree, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(focusedWorktree == "/home")
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").contains("/home"))
        #expect(projectRepository.validatedDirectories.contains("/home"))
    }

    @Test func selectCurrentDirectory_persistsSelectedPathEvenWhenCurrentResolvesGlobal() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(
            projectsByDirectory: [
                "/home": Project(id: "global", worktree: "/", displayName: "/"),
            ]
        )
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/home", directory: "/home", worktree: "/"),
            listings: ["/home": []]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.selectCurrentDirectory()
        guard case let .loaded(_, show, _, _, focusedWorktree, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(focusedWorktree == "/home")
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/home"])
    }

    @Test func selectCurrentDirectory_nestedPathPersistsSelectedDirectory() async {
        let store = FakeConnectionProfileStore(initial: ["/one"])
        let projectRepository = FakeProjectRepository(
            projectsByDirectory: [
                "/repo/android": Project(id: "repo", worktree: "/repo", displayName: "repo"),
            ]
        )
        let filesystem = FakeFilesystemRepository(
            paths: ServerPaths(home: "/repo/android", directory: "/repo/android", worktree: "/"),
            listings: ["/repo/android": []]
        )
        let viewModel = makeViewModel(
            projectRepository: projectRepository,
            filesystemRepository: filesystem,
            connectionProfileStore: store
        )
        await viewModel.refresh()
        await viewModel.showAddProjectSheet()
        await viewModel.selectCurrentDirectory()
        guard case let .loaded(_, show, _, _, focusedWorktree, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(show == false)
        #expect(focusedWorktree == "/repo/android")
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/") == ["/one", "/repo/android"])
    }

    @Test func createSession_usesFocusedWorktreeAndClearsError() async {
        let sessionRepository = FakeSessionRepository()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        await viewModel.createSession(in: "/repo")
        #expect(sessionRepository.createdDirectories == ["/repo"])
        #expect(viewModel.pendingSessionOpen?.worktree == "/repo")
        guard case let .loaded(_, _, _, _, _, createError) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(createError == nil)
    }

    @Test func createSession_failureStoresInlineError() async {
        let sessionRepository = FakeSessionRepository(createError: TestError.message("nope"))
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: sessionRepository,
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        await viewModel.createSession(in: "/repo")
        guard case let .loaded(_, _, _, _, _, createError) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(createError == "nope")
    }

    @Test func createSession_copiesResolvedPreferenceBeforeOpeningChat() async {
        let store = HomeSelectionStore()
        let identity = ModelSelectionConnectionIdentity(
            serverBaseURL: "http://127.0.0.1:4096/",
            username: "opencode"
        )
        await store.saveNewSessionPreference(
            connectionIdentity: identity,
            worktree: "/repo",
            selection: SessionModelSelection(
                providerID: "openai",
                modelID: "gpt-4",
                variant: "high",
                savedAtEpochMs: 1_000
            )
        )
        let option = ModelSelectionOption(
            providerID: "openai",
            providerName: "OpenAI",
            modelID: "gpt-4",
            modelName: "GPT-4",
            variants: ["high"],
            contextLimitTokens: 128_000
        )
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"]),
            newSessionModelSelectionResolver: NewSessionModelSelectionResolver(
                store: store,
                modelRepository: HomeModelRepository(catalog: ModelSelectionCatalog(options: [option], serverDefault: option))
            )
        )
        await viewModel.refresh()

        await viewModel.createSession(in: "/repo")

        #expect(viewModel.pendingSessionOpen?.id == "created")
        #expect(await store.loadSession(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "created"
        )?.modelID == "gpt-4")
    }
}

@MainActor
private func makeViewModel(
    projectRepository: any ProjectRepository,
    filesystemRepository: any FilesystemRepository = FakeFilesystemRepository(),
    sessionRepository: any SessionRepository = FakeSessionRepository(),
    connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
    eventStream: FakeForegroundEventStream? = nil,
    newSessionModelSelectionResolver: NewSessionModelSelectionResolver? = nil
) -> ProjectListHomeViewModel {
    let eventStream = eventStream ?? FakeForegroundEventStream()
    // Mutations (createSession) require Connected; default fakes start Idle.
    eventStream.setConnectionState(.connected)
    let manager = ActiveConnectionManager()
    manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""))
    let coordinator = ConnectionSessionCoordinator(
        activeConnectionManager: manager,
        connectionProfileStore: connectionProfileStore
    )
    let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream
    )
    let sessionLiveStore = DefaultSessionLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream,
        sessionCache: NoOpSessionCache.shared
    )
    let sessionReadyStore = DefaultSessionReadyStore(
        sessionStatusLiveStore: sessionStatusLiveStore,
        eventStream: eventStream
    )
    return ProjectListHomeViewModel(
        projectRepository: projectRepository,
        filesystemRepository: filesystemRepository,
        sessionRepository: sessionRepository,
        connectionProfileStore: connectionProfileStore,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
        sessionReadyStore: sessionReadyStore,
        foregroundEventStream: eventStream,
        connectionSessionCoordinator: coordinator,
        newSessionModelSelectionResolver: newSessionModelSelectionResolver
    )
}

private struct HomeModelRepository: ModelRepository {
    let catalog: ModelSelectionCatalog

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog { catalog }
}

@MainActor
private final class HomeSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private var sessions: [String: SessionModelSelection] = [:]
    private var preferences: [String: SessionModelSelection] = [:]

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? { nil }
    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {}
    func clearAll() async {}

    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection? {
        sessions[key(connectionIdentity, worktree, sessionID)]
    }

    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        sessions[key(connectionIdentity, worktree, sessionID)] = selection
    }

    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        sessions.removeValue(forKey: key(connectionIdentity, worktree, sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        let prefix = [connectionIdentity.serverBaseURL, connectionIdentity.username].joined(separator: "|")
        sessions.keys
            .filter { $0.hasPrefix(prefix) && $0.hasSuffix("|\(sessionID)") }
            .forEach { sessions.removeValue(forKey: $0) }
    }

    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection? {
        preferences[key(connectionIdentity, worktree)]
    }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {
        preferences[key(connectionIdentity, worktree)] = selection
    }

    private func key(_ identity: ModelSelectionConnectionIdentity, _ values: String...) -> String {
        ([identity.serverBaseURL, identity.username] + values).joined(separator: "|")
    }
}

private final class FakeProjectRepository: ProjectRepository, @unchecked Sendable {
    let shouldFailProbe: Bool
    let bootWorktree: String
    let projects: [Project]
    let projectsByDirectory: [String: Project]
    private(set) var validatedDirectories: [String?] = []

    init(
        shouldFailProbe: Bool = false,
        bootWorktree: String = "/boot",
        projects: [Project] = [],
        projectsByDirectory: [String: Project] = [:]
    ) {
        self.shouldFailProbe = shouldFailProbe
        self.bootWorktree = bootWorktree
        self.projects = projects
        self.projectsByDirectory = projectsByDirectory
    }

    func probeServer() async throws {
        if shouldFailProbe { throw ServerProbeError.transport("down") }
    }

    func listProjects() async throws -> [Project] { projects }

    func getCurrentProject(directory: String?) async throws -> Project {
        validatedDirectories.append(directory)
        if let directory, let project = projectsByDirectory[directory] {
            return project
        }
        return Project(id: "p1", worktree: directory ?? bootWorktree, displayName: "demo")
    }
}

private struct FakeFilesystemRepository: FilesystemRepository {
    let paths: ServerPaths
    let listings: [String: [DirectoryNode]]

    init(
        paths: ServerPaths = ServerPaths(home: "/home", directory: "/home", worktree: "/"),
        listings: [String: [DirectoryNode]] = [:]
    ) {
        self.paths = paths
        self.listings = listings
    }

    func getPaths() async throws -> ServerPaths { paths }

    func listDirectory(path: String) async throws -> [DirectoryNode] {
        DirectoryListingPolicy.prepare(listings[path] ?? [])
    }
}

private final class FakeConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private var added: [String]

    init(initial: [String] = []) {
        self.added = initial
    }

    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }

    func saveProfile(_ profile: ConnectionProfile) async {}

    func addedWorktrees(serverBaseURL: String) async -> [String] { added }

    func addWorktree(serverBaseURL: String, worktree: String) async {
        if !added.contains(worktree) { added.append(worktree) }
    }

    func removeWorktree(serverBaseURL: String, worktree: String) async {
        added.removeAll { $0 == worktree }
    }

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {
        var seen = Set<String>()
        added = worktrees.filter { seen.insert($0).inserted }
    }

    func clearAll() async {
        added.removeAll()
    }
}

private struct SlowFirstProjectRepository: ProjectRepository {
    func probeServer() async throws {}

    func listProjects() async throws -> [Project] { [] }

    func getCurrentProject(directory: String?) async throws -> Project {
        if directory == "/slow" {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return Project(
            id: directory ?? "p",
            worktree: directory ?? "/unknown",
            displayName: ProjectDomainAdapter.displayName(from: directory ?? "/unknown")
        )
    }
}

private struct SlowFirstSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        if directory == "/slow" {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return [
            Session(
                id: "1",
                title: "Session",
                slug: nil,
                projectID: nil,
                directory: directory,
                parentID: nil,
                updatedAtEpochMs: 1
            ),
        ]
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

private final class FakeSessionRepository: SessionRepository, @unchecked Sendable {
    let sessions: [Session]
    let createError: Error?
    private(set) var createdDirectories: [String?] = []

    init(
        sessions: [Session] = [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: nil, parentID: nil, updatedAtEpochMs: 1)],
        createError: Error? = nil
    ) {
        self.sessions = sessions
        self.createError = createError
    }

    func listSessions(directory: String?) async throws -> [Session] { sessions }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        createdDirectories.append(directory)
        if let createError { throw createError }
        return Session(id: "created", title: "New Session", slug: nil, projectID: nil, directory: directory, parentID: parentID, updatedAtEpochMs: 10)
    }
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

private enum TestError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .message(message):
            message
        }
    }
}

@MainActor
private func waitUntil(
    timeoutMs: Int = 2_000,
    predicate: @MainActor () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}
