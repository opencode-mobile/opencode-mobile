import Foundation

protocol ModelRepository: Sendable {
    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog
}

struct DefaultModelRepository: ModelRepository, Sendable {
    private let api: ModelsAPIClient
    private let adapter: ModelDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?
    private let projectRepository: (any ProjectRepository)?

    init(
        api: ModelsAPIClient,
        adapter: ModelDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil,
        projectRepository: (any ProjectRepository)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
        self.projectRepository = projectRepository
    }

    func listModelOptions(directory: String?) async throws -> ModelSelectionCatalog {
        do {
            if await isV2() {
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

    private func isV2() async -> Bool {
        if let protocolValue = activeConnectionManager?.serverProtocol {
            return protocolValue == .v2
        }
        guard let projectRepository else { return false }
        _ = try? await projectRepository.probeServer()
        return activeConnectionManager?.serverProtocol == .v2
    }
}
