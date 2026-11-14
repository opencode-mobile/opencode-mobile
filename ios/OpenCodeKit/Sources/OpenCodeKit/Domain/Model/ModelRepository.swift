import Foundation

public protocol ModelRepository: Sendable {
    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog
}

public struct DefaultModelRepository: ModelRepository, Sendable {
    private let api: ModelsAPIClient
    private let adapter: ModelDomainAdapter
    private let activeConnectionManager: (any ServerProtocolResolving)?

    public init(
        api: ModelsAPIClient,
        adapter: ModelDomainAdapter,
        activeConnectionManager: (any ServerProtocolResolving)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    public func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        do {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                let providers = try await api.listProvidersV2(directory: directory)
                let models = try await api.listModelsV2(directory: directory)
                let defaultModel = try? await api.getDefaultModelV2(directory: directory)
                return adapter.catalog(providers: providers, models: models, defaultModel: defaultModel)
            }
            return adapter.catalog(from: try await api.listProviders(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }
}
