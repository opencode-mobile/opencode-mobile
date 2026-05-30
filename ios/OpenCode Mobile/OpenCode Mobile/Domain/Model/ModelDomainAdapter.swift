import Foundation

struct ModelDomainAdapter: Sendable {
    func catalog(from dto: ConfigProvidersResponseDTO) -> ModelSelectionCatalog {
        let options = dto.providers.flatMap { provider in
            provider.models
                .filter { isUsable($0.value) }
                .map { key, model in
                    ModelSelectionOption(
                        providerID: provider.id,
                        providerName: provider.name ?? provider.id,
                        modelID: model.id ?? key,
                        modelName: model.name ?? model.id ?? key,
                        variants: variantKeys(from: model)
                    )
                }
                .sorted { $0.modelLabel.localizedCaseInsensitiveCompare($1.modelLabel) == .orderedAscending }
        }

        let serverDefault = defaultOption(from: dto.default, in: options)
        return ModelSelectionCatalog(options: options, serverDefault: serverDefault)
    }

    func mapError(_ error: Error) -> ModelSelectionError {
        if let modelError = error as? ModelSelectionError {
            return modelError
        }
        if let apiError = error as? ModelsAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        if error is DecodingError {
            return .decoding(error.localizedDescription)
        }
        return .transport(error.localizedDescription)
    }

    private func isUsable(_ model: ProviderModelDTO) -> Bool {
        if model.disabled == true {
            return false
        }
        if model.status?.lowercased() == "deprecated" {
            return false
        }
        return true
    }

    private func variantKeys(from model: ProviderModelDTO) -> [String] {
        guard let variants = model.variants else { return [] }
        return variants.keys.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    private func defaultOption(from defaults: [String: String], in options: [ModelSelectionOption]) -> ModelSelectionOption? {
        for providerID in defaults.keys.sorted() {
            guard let modelID = defaults[providerID],
                  let option = options.first(where: { $0.providerID == providerID && $0.modelID == modelID })
            else { continue }
            return option
        }
        return nil
    }
}
