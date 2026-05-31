import Foundation

protocol ModelRepository: Sendable {
    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog
}

struct DefaultModelRepository: ModelRepository, Sendable {
    private let api: ModelsAPIClient
    private let adapter: ModelDomainAdapter

    init(api: ModelsAPIClient, adapter: ModelDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        do {
            return adapter.catalog(from: try await api.listProviders(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }
}
