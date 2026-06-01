import Foundation

struct ModelSelectionCatalog: Equatable, Sendable {
    let options: [ModelSelectionOption]
    let serverDefault: ModelSelectionOption?

    var isEmpty: Bool { options.isEmpty }

    func option(providerID: String, modelID: String) -> ModelSelectionOption? {
        options.first { $0.providerID == providerID && $0.modelID == modelID }
    }
}

struct ModelSelectionOption: Equatable, Sendable, Identifiable {
    var id: String { "\(providerID):\(modelID)" }

    let providerID: String
    let providerName: String
    let modelID: String
    let modelName: String
    let variants: [String]
    let contextLimitTokens: Int64?

    init(
        providerID: String,
        providerName: String,
        modelID: String,
        modelName: String,
        variants: [String],
        contextLimitTokens: Int64? = nil
    ) {
        self.providerID = providerID
        self.providerName = providerName
        self.modelID = modelID
        self.modelName = modelName
        self.variants = variants
        self.contextLimitTokens = contextLimitTokens
    }

    var modelLabel: String { modelName.isEmpty ? modelID : modelName }
    var providerLabel: String { providerName.isEmpty ? providerID : providerName }
    var displayLabel: String { "\(modelLabel) · \(providerLabel)" }
    var supportsVariants: Bool { !variants.isEmpty }

    func matchesFilter(_ query: String) -> Bool {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return true }

        let haystacks = [displayLabel, modelID, providerID, modelName, providerName]
        return haystacks.contains { $0.localizedCaseInsensitiveContains(trimmed) }
    }
}

struct ModelSelection: Equatable, Sendable {
    static let defaultVariantLabel = "Default"

    let option: ModelSelectionOption
    var selectedVariant: String?

    var modelLabel: String { option.modelLabel }
    var modelDisplayLabel: String { option.displayLabel }
    var variantLabel: String { selectedVariant ?? Self.defaultVariantLabel }
}

enum ModelSelectionError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)

    var localizedDescription: String {
        switch self {
        case let .transport(message):
            message
        case let .http(_, message):
            message ?? "Request failed"
        case let .decoding(message):
            message
        }
    }
}
