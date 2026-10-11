import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct ModelCatalogStoreTests {
    @Test func concurrentRefreshes_shareOneFetch() async throws {
        let models = GatedCatalogRepository(isOpen: false)
        let store = makeStore(models: models)

        let first = Task { try await store.refreshCatalog(worktree: "/repo") }
        let second = Task { try await store.refreshCatalog(worktree: "/repo") }
        _ = await waitUntil { models.calls == 1 }
        try? await Task.sleep(for: .milliseconds(20))
        models.open()

        #expect(try await first.value == Self.catalog)
        #expect(try await second.value == Self.catalog)
        #expect(models.calls == 1)
        #expect(store.peek(worktree: "/repo")?.catalog == Self.catalog)
    }

    @Test func forcedRefresh_startsAfterAnUnforcedFetchButJoinsAForcedOne() async throws {
        let models = GatedCatalogRepository(isOpen: false)
        let store = makeStore(models: models)

        let unforced = Task { try await store.refreshCatalog(worktree: "/repo") }
        _ = await waitUntil { models.calls == 1 }
        let forced = Task { try await store.refreshCatalog(worktree: "/repo", force: true) }
        _ = await waitUntil { models.calls == 2 }
        let joined = Task { try await store.refreshCatalog(worktree: "/repo", force: true) }
        try? await Task.sleep(for: .milliseconds(20))
        models.open()

        _ = try await unforced.value
        _ = try await forced.value
        _ = try await joined.value
        #expect(models.calls == 2)
    }

    @Test func failedOrEmptyRefresh_keepsTheLastCatalog() async throws {
        let models = GatedCatalogRepository()
        let store = makeStore(models: models)
        _ = try await store.refreshCatalog(worktree: "/repo")

        models.setFailure(true)
        await #expect(throws: ModelSelectionError.self) {
            try await store.refreshCatalog(worktree: "/repo")
        }
        #expect(store.peek(worktree: "/repo")?.catalog == Self.catalog)

        models.setFailure(false)
        models.setCatalog(ModelSelectionCatalog(options: [], serverDefault: nil))
        let empty = try await store.refreshCatalog(worktree: "/repo")
        #expect(empty.isEmpty)
        #expect(store.peek(worktree: "/repo")?.catalog == Self.catalog)
    }

    @Test func entries_areKeyedByWorktree() async throws {
        let models = GatedCatalogRepository()
        let store = makeStore(models: models)

        _ = try await store.refreshCatalog(worktree: "/a")

        #expect(store.peek(worktree: "/a")?.catalog == Self.catalog)
        #expect(store.peek(worktree: "/b") == nil)
        _ = try await store.refreshCatalog(worktree: "/b")
        #expect(models.directories == ["/a", "/b"])
    }

    @Test func entries_areKeyedByConnectionProfile() async throws {
        let models = GatedCatalogRepository()
        let connection = Self.connection(id: "p1", baseURL: "http://127.0.0.1:4096/")
        let store = makeStore(models: models, connection: connection)
        _ = try await store.refreshCatalog(worktree: "/repo")
        store.recordSelection(Self.selection(savedAt: 1), worktree: "/repo", sessionID: "ses-1")

        connection.apply(ConnectionProfile(baseURL: "http://10.0.0.2:4096/", username: "opencode", password: "", id: "p2"))
        #expect(store.peek(worktree: "/repo") == nil)
        #expect(store.lastSelection(worktree: "/repo", sessionID: "ses-1") == nil)

        connection.apply(ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "", id: "p1"))
        #expect(store.peek(worktree: "/repo")?.catalog == Self.catalog)
    }

    @Test func clearCache_dropsCatalogsAndLastSelections() async throws {
        let store = makeStore(models: GatedCatalogRepository())
        _ = try await store.refreshCatalog(worktree: "/repo")
        _ = try await store.refreshSelectableAgents(worktree: "/repo")
        store.recordSelection(Self.selection(savedAt: 1), worktree: "/repo", sessionID: "ses-1")

        store.clearCache()

        #expect(store.peek(worktree: "/repo") == nil)
        #expect(store.lastSelection(worktree: "/repo", sessionID: "ses-1") == nil)
    }

    @Test func answerLandingAfterProfileChange_isDiscarded() async throws {
        let models = GatedCatalogRepository(isOpen: false)
        let store = makeStore(models: models)

        let refresh = Task { try await store.refreshCatalog(worktree: "/repo") }
        _ = await waitUntil { models.calls == 1 }
        store.clearCache()
        models.open()
        _ = try await refresh.value

        #expect(store.peek(worktree: "/repo") == nil)
        // The next caller starts a new fetch instead of joining the discarded one.
        _ = try await store.refreshCatalog(worktree: "/repo")
        #expect(models.calls == 2)
        #expect(store.peek(worktree: "/repo")?.catalog == Self.catalog)
    }

    @Test func selectableAgents_keepTheLastNonEmptyList() async throws {
        let agents = ListAgentRepository(agents: [SelectableAgent(id: "review", displayName: "Review")])
        let store = makeStore(models: GatedCatalogRepository(), agents: agents)
        _ = try await store.refreshSelectableAgents(worktree: "/repo")

        agents.agents = []
        _ = try await store.refreshSelectableAgents(worktree: "/repo")

        #expect(store.peek(worktree: "/repo")?.selectableAgents?.map(\.id) == ["review"])
    }

    @Test func recordSelection_keepsTheNewerSelection() {
        let store = makeStore(models: GatedCatalogRepository())
        store.recordSelection(Self.selection(modelID: "newer", savedAt: 2), worktree: "/repo", sessionID: "ses-1")
        store.recordSelection(Self.selection(modelID: "older", savedAt: 1), worktree: "/repo", sessionID: "ses-1")

        #expect(store.lastSelection(worktree: "/repo", sessionID: "ses-1")?.modelID == "newer")
        #expect(store.lastSelection(worktree: "/repo", sessionID: "ses-2") == nil)
        #expect(store.lastSelection(worktree: "/other", sessionID: "ses-1") == nil)
    }

    private static let catalog = ModelCatalogStoreTestsCatalog.value

    private static func selection(modelID: String = "claude-sonnet", savedAt: Int64) -> SessionModelSelection {
        SessionModelSelection(providerID: "anthropic", modelID: modelID, variant: nil, savedAtEpochMs: savedAt)
    }

    private static func connection(
        id: String = "p1",
        baseURL: String = "http://127.0.0.1:4096/"
    ) -> ActiveConnectionManager {
        let connection = ActiveConnectionManager()
        connection.apply(ConnectionProfile(baseURL: baseURL, username: "opencode", password: "", id: id))
        return connection
    }

    private func makeStore(
        models: GatedCatalogRepository,
        agents: ListAgentRepository = ListAgentRepository(agents: SelectableAgent.fallback),
        connection: ActiveConnectionManager? = nil
    ) -> ModelCatalogStore {
        ModelCatalogStore(
            modelRepository: models,
            agentRepository: agents,
            activeConnectionManager: connection ?? Self.connection()
        )
    }

    private func waitUntil(timeoutMs: Int = 2_000, predicate: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return predicate()
    }
}

/// Answers once open; records each call's directory and can fail instead.
private final class GatedCatalogRepository: ModelRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var isOpen: Bool
    private var shouldFail = false
    private var catalog = ModelCatalogStoreTestsCatalog.value
    private var _directories: [String] = []

    init(isOpen: Bool = true) {
        self.isOpen = isOpen
    }

    var calls: Int { withLock { _directories.count } }
    var directories: [String] { withLock { _directories } }

    func open() { withLock { isOpen = true } }
    func setFailure(_ fail: Bool) { withLock { shouldFail = fail } }
    func setCatalog(_ catalog: ModelSelectionCatalog) { withLock { self.catalog = catalog } }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        withLock { _directories.append(directory ?? "") }
        while !withLock({ isOpen }) {
            try? await Task.sleep(for: .milliseconds(5))
        }
        let (fail, catalog) = withLock { (shouldFail, self.catalog) }
        if fail { throw ModelSelectionError.transport("offline") }
        return catalog
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

private enum ModelCatalogStoreTestsCatalog {
    static let value: ModelSelectionCatalog = {
        let option = ModelSelectionOption(
            providerID: "anthropic",
            providerName: "Anthropic",
            modelID: "claude-sonnet",
            modelName: "Claude Sonnet",
            variants: []
        )
        return ModelSelectionCatalog(options: [option], serverDefault: option)
    }()
}

private final class ListAgentRepository: AgentRepository, @unchecked Sendable {
    var agents: [SelectableAgent]

    init(agents: [SelectableAgent]) {
        self.agents = agents
    }

    func listSelectableAgents(directory: String?) async throws -> [SelectableAgent] {
        agents
    }

    func listMentionableAgents(directory: String?) async throws -> [MentionableAgent] {
        []
    }
}
