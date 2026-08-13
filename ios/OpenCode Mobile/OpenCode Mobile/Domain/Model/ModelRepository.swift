import Foundation

protocol ModelRepository: Sendable {
    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog
}

struct DefaultModelRepository: ModelRepository, Sendable {
    private let api: ModelsAPIClient
    private let adapter: ModelDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: ModelsAPIClient,
        adapter: ModelDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        do {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                return adapter.catalog(
                    providers: try await api.listProvidersV2(directory: directory),
                    models: try await api.listModelsV2(directory: directory)
                )
            }
            return adapter.catalog(from: try await api.listProviders(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }
}
