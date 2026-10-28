import Foundation

struct ModelSelectionCatalog: Equatable, Sendable {
    let options: [ModelSelectionOption]
    let serverDefault: ModelSelectionOption?

    var isEmpty: Bool { options.isEmpty }

    var providers: [ModelSelectionProvider] {
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

    func option(providerID: String, modelID: String) -> ModelSelectionOption? {
        options.first { $0.providerID == providerID && $0.modelID == modelID }
    }
}

struct ModelSelectionProvider: Equatable, Sendable, Identifiable {
    let id: String
    let label: String
    var models: [ModelSelectionOption]
}

/// Variant naming rules shared by the catalog adapters and the composer.
nonisolated enum ModelVariants {
    /// The v2 server records `"default"` on messages and sessions when no variant was chosen.
    static let serverDefault = "default"

    private static let effortRank = ["none", "minimal", "low", "medium", "high", "xhigh", "max"]

    /// A recorded variant that means "no variant": empty, or the server's `"default"` when the
    /// model has no variant literally named that.
    static func isDefault(_ variant: String?, available: [String]) -> Bool {
        guard let variant, !variant.isEmpty else { return true }
        return variant == serverDefault && !available.contains(variant)
    }

    /// Orders variants by reasoning effort (low → medium → high), then by name. Used when the
    /// server gives no order (v1 returns variants as an object).
    static func sortedByEffort<S: Sequence>(_ variants: S) -> [String] where S.Element == String {
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

struct ModelSelectionOption: Equatable, Sendable, Identifiable {
    var id: String { "\(providerID):\(modelID)" }

    let providerID: String
    let providerName: String
    let modelID: String
    let modelName: String
    let variants: [String]
    let contextLimitTokens: Int64?
    let supportsImageInput: Bool
    let supportsPdfInput: Bool
    /// Show `modelName` verbatim instead of title-casing it (catalogues with human names).
    let usesVerbatimModelName: Bool

    init(
        providerID: String,
        providerName: String,
        modelID: String,
        modelName: String,
        variants: [String],
        contextLimitTokens: Int64? = nil,
        supportsImageInput: Bool = true,
        supportsPdfInput: Bool = true,
        usesVerbatimModelName: Bool = false
    ) {
        self.providerID = providerID
        self.providerName = providerName
        self.modelID = modelID
        self.modelName = modelName
        self.variants = variants
        self.contextLimitTokens = contextLimitTokens
        self.supportsImageInput = supportsImageInput
        self.supportsPdfInput = supportsPdfInput
        self.usesVerbatimModelName = usesVerbatimModelName
    }

    var modelLabel: String {
        let raw = modelName.isEmpty ? modelID : modelName
        return usesVerbatimModelName ? raw : TitleCaseIdentifier.displayName(for: raw)
    }
    var providerLabel: String { providerName.isEmpty ? providerID : providerName }
    var displayLabel: String { "\(modelLabel) · \(providerLabel)" }
    var supportsVariants: Bool { !variants.isEmpty }
    var canAttach: Bool { supportsImageInput || supportsPdfInput }
    var canAttachPhotos: Bool { supportsImageInput }
    var canAttachFiles: Bool { canAttach }

    func acceptsAttachmentKind(_ kind: AttachmentKind) -> Bool {
        switch kind {
        case .image: supportsImageInput
        case .pdf: supportsPdfInput
        case .text, .file: true
        }
    }

    /// An uploaded file never reaches the model's input, so any model takes it.
    func accepts(_ attachment: Attachment) -> Bool {
        attachment.upload != .inline || acceptsAttachmentKind(attachment.kind)
    }

    /// Whether the model reads this mime itself, as an image or PDF.
    func readsNatively(mime: String) -> Bool {
        switch mime.lowercased() {
        case "image/png", "image/jpeg", "image/gif", "image/webp": supportsImageInput
        case "application/pdf": supportsPdfInput
        default: false
        }
    }

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
    var variantLabel: String { TitleCaseIdentifier.displayName(for: selectedVariant ?? Self.defaultVariantLabel) }
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
