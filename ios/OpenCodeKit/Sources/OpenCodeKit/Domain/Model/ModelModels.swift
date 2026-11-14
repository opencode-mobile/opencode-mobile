import Foundation

public struct ModelSelectionCatalog: Equatable, Sendable {
    public let options: [ModelSelectionOption]
    public let serverDefault: ModelSelectionOption?

    public var isEmpty: Bool { options.isEmpty }

    public var providers: [ModelSelectionProvider] {
        options.reduce(into: []) { providers, option in
            if let index = providers.firstIndex(where: { $0.id == option.providerID }) {
                providers[index].models.append(option)
            } else {
                providers.append(
                    ModelSelectionProvider(
                        id: option.providerID,
                        label: option.providerLabel,
                        models: [option]
                    )
                )
            }
        }
    }

    public func option(providerID: String, modelID: String) -> ModelSelectionOption? {
        options.first { $0.providerID == providerID && $0.modelID == modelID }
    }

    public init(
        options: [ModelSelectionOption],
        serverDefault: ModelSelectionOption?
    ) {
        self.options = options
        self.serverDefault = serverDefault
    }
}

public struct ModelSelectionProvider: Equatable, Sendable, Identifiable {
    public let id: String
    public let label: String
    public var models: [ModelSelectionOption]

    public init(
        id: String,
        label: String,
        models: [ModelSelectionOption]
    ) {
        self.id = id
        self.label = label
        self.models = models
    }
}

/// Variant naming rules shared by the catalog adapters and the composer.
public nonisolated enum ModelVariants {
    /// The v2 server records `"default"` on messages and sessions when no variant was chosen.
    public static let serverDefault = "default"

    private static let effortRank = ["none", "minimal", "low", "medium", "high", "xhigh", "max"]

    /// A recorded variant that means "no variant": empty, or the server's `"default"` when the
    /// model has no variant literally named that.
    public static func isDefault(_ variant: String?, available: [String]) -> Bool {
        guard let variant, !variant.isEmpty else { return true }
        return variant == serverDefault && !available.contains(variant)
    }

    /// Orders variants by reasoning effort (low → medium → high), then by name. Used when the
    /// server gives no order (v1 returns variants as an object).
    public static func sortedByEffort<S: Sequence>(_ variants: S) -> [String] where S.Element == String {
        func rank(_ name: String) -> Int {
            effortRank.firstIndex(of: name.lowercased()) ?? effortRank.count
        }
        return variants.sorted { left, right in
            let leftRank = rank(left)
            let rightRank = rank(right)
            if leftRank != rightRank { return leftRank < rightRank }
            return left.localizedCaseInsensitiveCompare(right) == .orderedAscending
        }
    }
}

public struct ModelSelectionOption: Equatable, Sendable, Identifiable {
    public var id: String { "\(providerID):\(modelID)" }

    public let providerID: String
    public let providerName: String
    public let modelID: String
    public let modelName: String
    public let variants: [String]
    public let contextLimitTokens: Int64?
    public let supportsImageInput: Bool
    public let supportsPdfInput: Bool

    public init(
        providerID: String,
        providerName: String,
        modelID: String,
        modelName: String,
        variants: [String],
        contextLimitTokens: Int64? = nil,
        supportsImageInput: Bool = true,
        supportsPdfInput: Bool = true
    ) {
        self.providerID = providerID
        self.providerName = providerName
        self.modelID = modelID
        self.modelName = modelName
        self.variants = variants
        self.contextLimitTokens = contextLimitTokens
        self.supportsImageInput = supportsImageInput
        self.supportsPdfInput = supportsPdfInput
    }

    public var modelLabel: String { TitleCaseIdentifier.displayName(for: modelName.isEmpty ? modelID : modelName) }
    public var providerLabel: String { providerName.isEmpty ? providerID : providerName }
    public var displayLabel: String { "\(modelLabel) · \(providerLabel)" }
    public var supportsVariants: Bool { !variants.isEmpty }
    public var canAttach: Bool { supportsImageInput || supportsPdfInput }
    public var canAttachPhotos: Bool { supportsImageInput }
    public var canAttachFiles: Bool { canAttach }

    /// Whether the model reads this mime itself, as an image or PDF.
    public func readsNatively(mime: String) -> Bool {
        switch mime.lowercased() {
        case "image/png", "image/jpeg", "image/gif", "image/webp": supportsImageInput
        case "application/pdf": supportsPdfInput
        default: false
        }
    }

    public func matchesFilter(_ query: String) -> Bool {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return true }

        let haystacks = [displayLabel, modelID, providerID, modelName, providerName]
        return haystacks.contains { $0.localizedCaseInsensitiveContains(trimmed) }
    }
}

public struct ModelSelection: Equatable, Sendable {
    public static let defaultVariantLabel = "Default"

    public let option: ModelSelectionOption
    public var selectedVariant: String?

    public var modelLabel: String { option.modelLabel }
    public var modelDisplayLabel: String { option.displayLabel }
    public var variantLabel: String { TitleCaseIdentifier.displayName(for: selectedVariant ?? Self.defaultVariantLabel) }

    public init(
        option: ModelSelectionOption,
        selectedVariant: String? = nil
    ) {
        self.option = option
        self.selectedVariant = selectedVariant
    }
}

public enum ModelSelectionError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)

    public var localizedDescription: String {
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
