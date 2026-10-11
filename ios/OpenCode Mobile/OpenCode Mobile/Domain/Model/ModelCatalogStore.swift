import Foundation
import Observation

/// One model catalog and selectable agent list per Connection Profile and worktree, shared by
/// every Session Chat. A chat opened on a loaded catalog shows its model at once while a quiet
/// refresh runs; a failed or empty refresh keeps the last usable answer.
@MainActor
@Observable
final class ModelCatalogStore {
    struct Entry: Equatable {
        /// Last non-empty catalog, or `nil` until one arrives.
        var catalog: ModelSelectionCatalog?
        var selectableAgents: [SelectableAgent]?
        var fetchedAt: Date?
    }

    private var entries: [Key: Entry] = [:]
    @ObservationIgnored private var lastSelections: [SelectionKey: SessionModelSelection] = [:]
    @ObservationIgnored private var catalogFetches: [Key: Fetch<ModelSelectionCatalog>] = [:]
    @ObservationIgnored private var agentFetches: [Key: Fetch<[SelectableAgent]>] = [:]
    /// Highest fetch id stored per key, so an older answer never overwrites a newer one.
    @ObservationIgnored private var storedCatalogFetchIDs: [Key: Int] = [:]
    @ObservationIgnored private var storedAgentFetchIDs: [Key: Int] = [:]
    @ObservationIgnored private var nextFetchID = 0
    /// Bumped on every clear; answers that land after a profile change are discarded.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private let modelRepository: any ModelRepository
    @ObservationIgnored private let agentRepository: any AgentRepository
    @ObservationIgnored private let activeConnectionManager: ActiveConnectionManager
    @ObservationIgnored private let now: () -> Date

    init(
        modelRepository: any ModelRepository,
        agentRepository: any AgentRepository,
        activeConnectionManager: ActiveConnectionManager,
        now: @escaping () -> Date = Date.init
    ) {
        self.modelRepository = modelRepository
        self.agentRepository = agentRepository
        self.activeConnectionManager = activeConnectionManager
        self.now = now
    }

    func peek(worktree: String) -> Entry? {
        key(worktree: worktree).flatMap { entries[$0] }
    }

    /// Fetches the catalog for `worktree`. Callers join a fetch already in flight for the same
    /// key; `force` asks for an answer started after this call unless a forced fetch is running.
    func refreshCatalog(worktree: String, force: Bool = false) async throws -> ModelSelectionCatalog {
        guard let key = key(worktree: worktree) else {
            return try await modelRepository.listModelOptions(directory: worktree)
        }
        let fetch: Fetch<ModelSelectionCatalog>
        if let running = catalogFetches[key], !force || running.isForced {
            fetch = running
        } else {
            let id = takeFetchID()
            let generation = generation
            let repository = modelRepository
            fetch = Fetch(id: id, isForced: force, task: Task { [weak self] in
                let result: Result<ModelSelectionCatalog, Error>
                do {
                    result = .success(try await repository.listModelOptions(directory: key.worktree))
                } catch {
                    result = .failure(error)
                }
                self?.finishCatalogFetch(key: key, id: id, generation: generation, result: result)
                return result
            })
            catalogFetches[key] = fetch
        }
        return try await fetch.task.value.get()
    }

    func refreshSelectableAgents(worktree: String, force: Bool = false) async throws -> [SelectableAgent] {
        guard let key = key(worktree: worktree) else {
            return try await agentRepository.listSelectableAgents(directory: worktree)
        }
        let fetch: Fetch<[SelectableAgent]>
        if let running = agentFetches[key], !force || running.isForced {
            fetch = running
        } else {
            let id = takeFetchID()
            let generation = generation
            let repository = agentRepository
            fetch = Fetch(id: id, isForced: force, task: Task { [weak self] in
                let result: Result<[SelectableAgent], Error>
                do {
                    result = .success(try await repository.listSelectableAgents(directory: key.worktree))
                } catch {
                    result = .failure(error)
                }
                self?.finishAgentFetch(key: key, id: id, generation: generation, result: result)
                return result
            })
            agentFetches[key] = fetch
        }
        return try await fetch.task.value.get()
    }

    /// The selection a Session Chat last resolved or the user last picked, kept in memory so
    /// the next chat for the session can show it before persisted state or history load.
    func lastSelection(worktree: String, sessionID: String) -> SessionModelSelection? {
        key(worktree: worktree).flatMap { lastSelections[SelectionKey(key: $0, sessionID: sessionID)] }
    }

    func recordSelection(_ selection: SessionModelSelection, worktree: String, sessionID: String) {
        guard let key = key(worktree: worktree) else { return }
        let selectionKey = SelectionKey(key: key, sessionID: sessionID)
        if let existing = lastSelections[selectionKey]?.savedAtEpochMs,
           let incoming = selection.savedAtEpochMs,
           existing > incoming {
            return
        }
        lastSelections[selectionKey] = selection
    }

    /// Profile switch, save, removal, or Settings "Clear cache".
    func clearCache() {
        generation += 1
        entries = [:]
        lastSelections = [:]
        catalogFetches = [:]
        agentFetches = [:]
        storedCatalogFetchIDs = [:]
        storedAgentFetchIDs = [:]
    }

    private func finishCatalogFetch(
        key: Key,
        id: Int,
        generation: Int,
        result: Result<ModelSelectionCatalog, Error>
    ) {
        guard generation == self.generation else { return }
        if catalogFetches[key]?.id == id {
            catalogFetches[key] = nil
        }
        // An empty answer (a server still loading providers) keeps the last usable catalog.
        guard case let .success(catalog) = result, !catalog.options.isEmpty else { return }
        guard id > storedCatalogFetchIDs[key, default: 0] else { return }
        storedCatalogFetchIDs[key] = id
        var entry = entries[key] ?? Entry()
        entry.catalog = catalog
        entry.fetchedAt = now()
        entries[key] = entry
    }

    private func finishAgentFetch(
        key: Key,
        id: Int,
        generation: Int,
        result: Result<[SelectableAgent], Error>
    ) {
        guard generation == self.generation else { return }
        if agentFetches[key]?.id == id {
            agentFetches[key] = nil
        }
        guard case let .success(agents) = result else { return }
        var entry = entries[key] ?? Entry()
        guard !agents.isEmpty || entry.selectableAgents == nil else { return }
        guard id > storedAgentFetchIDs[key, default: 0] else { return }
        storedAgentFetchIDs[key] = id
        entry.selectableAgents = agents
        entries[key] = entry
    }

    private func takeFetchID() -> Int {
        nextFetchID += 1
        return nextFetchID
    }

    private func key(worktree: String) -> Key? {
        guard let profile = activeConnectionManager.currentProfile() else { return nil }
        let username = profile.username.trimmingCharacters(in: .whitespacesAndNewlines)
        return Key(
            profileID: profile.id,
            baseURL: profile.normalizedBaseURL,
            username: username.isEmpty ? ConnectionProfile.defaultUsername : username,
            worktree: worktree
        )
    }

    private struct Key: Hashable {
        let profileID: String
        let baseURL: String
        let username: String
        let worktree: String
    }

    private struct SelectionKey: Hashable {
        let key: Key
        let sessionID: String
    }

    private struct Fetch<Value: Sendable> {
        let id: Int
        let isForced: Bool
        let task: Task<Result<Value, Error>, Never>
    }
}
