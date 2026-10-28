import Foundation

/// Harness + model choices of the active T3 environment for Session Chat.
protocol T3HarnessRepository: Sendable {
    /// The provider catalogue; `force` refetches instead of using the cache.
    func catalog(force: Bool) async throws -> T3HarnessCatalog
    /// The selection a new thread in `directory` would launch with when the
    /// user picks nothing. Throws when no harness is usable.
    func newThreadSelection(directory: String?, preferred: T3ModelSelection?) async throws -> T3ModelSelection
    /// Opens the environment socket early so the chat shows connecting / connected.
    func warmConnection()
}

struct DefaultT3HarnessRepository: T3HarnessRepository {
    private let client: any T3EngineClient

    init(client: any T3EngineClient) {
        self.client = client
    }

    func catalog(force: Bool) async throws -> T3HarnessCatalog {
        try await client.fetchHarnessCatalog(force: force)
    }

    func newThreadSelection(directory: String?, preferred: T3ModelSelection?) async throws -> T3ModelSelection {
        async let shellLoad = client.fetchShell(force: false)
        async let catalogLoad = client.fetchHarnessCatalog(force: false)
        let (shell, catalog) = try await (shellLoad, catalogLoad)
        let project = T3ProjectSelection.project(for: directory, in: shell)
        guard let selection = T3ModelSelectionResolver.resolve(
            catalog: catalog,
            projectDefault: project?.defaultModelSelection,
            preferred: preferred
        ) else {
            throw T3ClientError.rpc(T3ModelSelectionResolver.noHarnessMessage)
        }
        return selection
    }

    func warmConnection() {
        client.warmConnection()
    }
}
