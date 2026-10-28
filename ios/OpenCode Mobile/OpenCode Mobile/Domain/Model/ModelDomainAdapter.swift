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
                        variants: variantKeys(from: model),
                        contextLimitTokens: model.limit?.context,
                        supportsImageInput: model.capabilities?.input?.image ?? true,
                        supportsPdfInput: model.capabilities?.input?.pdf ?? true
                    )
                }
                .sorted { $0.modelLabel.localizedCaseInsensitiveCompare($1.modelLabel) == .orderedAscending }
        }

        let serverDefault = defaultOption(from: dto.default, in: options)
        return ModelSelectionCatalog(options: options, serverDefault: serverDefault)
    }

    func catalog(
        providers: [V2ProviderInfoDTO],
        models: [V2ModelInfoDTO],
        defaultModel: V2ModelInfoDTO? = nil
    ) -> ModelSelectionCatalog {
        let providerNames = Dictionary(providers.map { ($0.id, $0.name ?? $0.id) }, uniquingKeysWith: { _, latest in latest })
        let disabledProviders = Set(providers.filter { $0.disabled == true }.map(\.id))
        let options: [ModelSelectionOption] = models.compactMap { model in
            guard isUsable(model), !disabledProviders.contains(model.providerID) else { return nil }
            let inputs = (model.capabilities?.input ?? []).map { $0.lowercased() }
            // v2 lists variants in the model's own order (low → medium → high).
            var seenVariants = Set<String>()
            let variants = model.variants.compactMap { variant -> String? in
                guard let id = variant.id, !id.isEmpty, seenVariants.insert(id).inserted else { return nil }
                return id
            }
            return ModelSelectionOption(
                providerID: model.providerID,
                providerName: providerNames[model.providerID] ?? model.providerID,
                modelID: model.id,
                modelName: model.name ?? model.id,
                variants: variants,
                contextLimitTokens: model.limit?.context,
                supportsImageInput: inputs.isEmpty || inputs.contains("image"),
                supportsPdfInput: inputs.isEmpty || inputs.contains("pdf")
            )
        }
        .sorted { $0.modelLabel.localizedCaseInsensitiveCompare($1.modelLabel) == .orderedAscending }
        return ModelSelectionCatalog(options: options, serverDefault: defaultOption(from: defaultModel, in: options))
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

    private func isUsable(_ model: V2ModelInfoDTO) -> Bool {
        if model.enabled == false {
            return false
        }
        if model.status?.lowercased() == "deprecated" {
            return false
        }
        return true
    }

    private func variantKeys(from model: ProviderModelDTO) -> [String] {
        guard let variants = model.variants else { return [] }
        return ModelVariants.sortedByEffort(variants.keys)
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

    private func defaultOption(from defaultModel: V2ModelInfoDTO?, in options: [ModelSelectionOption]) -> ModelSelectionOption? {
        guard let defaultModel else { return nil }
        var ids = Set<String>()
        if !defaultModel.id.isEmpty { ids.insert(defaultModel.id) }
        if let modelID = defaultModel.modelID, !modelID.isEmpty { ids.insert(modelID) }
        return options.first { option in
            option.providerID == defaultModel.providerID && ids.contains(option.modelID)
        }
    }
}
