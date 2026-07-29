import Foundation
import OpenCodeMarkdownCore

nonisolated struct TextMateCodeBlockKey: Codable, Hashable, Sendable {
    let value: String

    static func deterministic(ownerKey: String, blockIndex: Int, fenceIndex: Int) -> Self {
        Self(value: "\(ownerKey.utf16.count):\(ownerKey):\(blockIndex):\(fenceIndex)")
    }
}

nonisolated struct TextMateHighlightRevision: Codable, Hashable, Sendable {
    let value: Int64
}

nonisolated enum TextMateHighlightIntent: String, Codable, Sendable {
    case interactiveFinal = "InteractiveFinal"
    case visibleHistory = "VisibleHistory"
    case backgroundHistory = "BackgroundHistory"
}

nonisolated struct TextMateHighlightRequest: Encodable, Sendable {
    let key: TextMateCodeBlockKey
    let revision: TextMateHighlightRevision
    let language: String?
    let source: String
    let intent: TextMateHighlightIntent
    let policyEpoch: Int64
    let messageCharacters: Int
    let fenceIndex: Int
    let fenceCount: Int
}

nonisolated struct TextMateHighlightResult: Decodable, Sendable {
    let key: TextMateCodeBlockKey
    let revision: TextMateHighlightRevision
    let source: String
    let canonicalLanguage: String?
    let policyEpoch: Int64
    let spans: [NativeCodeSpan]
    let outcome: String
    let diagnostics: [NativeMarkdownDiagnostic]
    let observation: NativeTextMateParseObservation
}

nonisolated protocol TextMateHighlighting: Sendable {
    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult
}

/// iOS's asynchronous platform-to-KMP adapter. Actor isolation is the sole
/// mutable owner; the synchronous KMP call therefore cannot overlap or execute
/// on MainActor.
actor KMPTextMateHighlightingEngine: TextMateHighlighting {
    static let shared = KMPTextMateHighlightingEngine()

    private var bridge: OpenCodeTextMateHighlightingEngineJson?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(bridge: OpenCodeTextMateHighlightingEngineJson? = nil) {
        self.bridge = bridge
    }

    func highlight(_ request: TextMateHighlightRequest) throws -> TextMateHighlightResult {
        let activeBridge: OpenCodeTextMateHighlightingEngineJson
        if let bridge {
            activeBridge = bridge
        } else {
            let created = OpenCodeTextMateHighlightingEngineJson.companion.createDefault()
            bridge = created
            activeBridge = created
        }
        let requestData = try encoder.encode(request)
        guard let requestJSON = String(data: requestData, encoding: .utf8) else {
            throw TextMateHighlightingAdapterError.invalidUTF8
        }
        let responseJSON = activeBridge.highlightJson(requestJson: requestJSON)
        return try decoder.decode(TextMateHighlightResult.self, from: Data(responseJSON.utf8))
    }
}

nonisolated private enum TextMateHighlightingAdapterError: Error {
    case invalidUTF8
}

nonisolated struct TextMateMarkdownFence: Sendable {
    let documentSourceHash: Int
    let blockIndex: Int
    let fenceIndex: Int
    let key: TextMateCodeBlockKey
    let revision: TextMateHighlightRevision
    let language: String?
    let source: String
    let policyEpoch: Int64

    func request(
        intent: TextMateHighlightIntent,
        messageCharacters: Int,
        fenceCount: Int
    ) -> TextMateHighlightRequest {
        TextMateHighlightRequest(
            key: key,
            revision: revision,
            language: language,
            source: source,
            intent: intent,
            policyEpoch: policyEpoch,
            messageCharacters: messageCharacters,
            fenceIndex: fenceIndex,
            fenceCount: fenceCount
        )
    }
}

nonisolated enum TextMateAttachmentResult: Sendable {
    case attached(NativeMarkdownDocument)
    case rejected(RejectionReason)

    enum RejectionReason: Equatable, Sendable {
        case documentChanged
        case fenceMissing
        case identityChanged
        case revisionChanged
        case policyChanged
        case sourceChanged
        case resultNotAttachable
    }
}

nonisolated extension NativeMarkdownDocument {
    func textMateFences(
        ownerKey: String,
        revision: TextMateHighlightRevision,
        policyEpoch: Int64
    ) -> [TextMateMarkdownFence] {
        var fences: [TextMateMarkdownFence] = []
        for (blockIndex, block) in blocks.enumerated() {
            guard case .codeBlock(let language, let code, _) = block else { continue }
            let fenceIndex = fences.count
            fences.append(
                TextMateMarkdownFence(
                    documentSourceHash: sourceHash,
                    blockIndex: blockIndex,
                    fenceIndex: fenceIndex,
                    key: .deterministic(
                        ownerKey: ownerKey,
                        blockIndex: blockIndex,
                        fenceIndex: fenceIndex
                    ),
                    revision: revision,
                    language: language,
                    source: code,
                    policyEpoch: policyEpoch
                )
            )
        }
        return fences
    }

    /// Pure attachment with exact-source and complete identity validation.
    func attachingTextMate(
        fence: TextMateMarkdownFence,
        result: TextMateHighlightResult
    ) -> TextMateAttachmentResult {
        guard sourceHash == fence.documentSourceHash else {
            return .rejected(.documentChanged)
        }
        guard blocks.indices.contains(fence.blockIndex),
              case .codeBlock(let language, let code, _) = blocks[fence.blockIndex]
        else {
            return .rejected(.fenceMissing)
        }
        guard result.key == fence.key else { return .rejected(.identityChanged) }
        guard result.revision == fence.revision else { return .rejected(.revisionChanged) }
        guard result.policyEpoch == fence.policyEpoch else { return .rejected(.policyChanged) }
        guard result.source == fence.source, code == fence.source else {
            return .rejected(.sourceChanged)
        }
        guard !["Failed", "Cancelled", "BudgetExceeded"].contains(result.outcome) else {
            return .rejected(.resultNotAttachable)
        }

        var updatedBlocks = blocks
        updatedBlocks[fence.blockIndex] = .codeBlock(
            language: language,
            code: code,
            spans: result.spans
        )
        let retainedDiagnostics = diagnostics.filter {
            !["UnsupportedLanguage", "HighlighterFallback", "HighlighterBackendFallback"].contains($0.kind)
        }
        return .attached(
            NativeMarkdownDocument(
                renderTreeVersion: renderTreeVersion,
                sourceHash: sourceHash,
                blocks: updatedBlocks,
                diagnostics: retainedDiagnostics + result.diagnostics
            )
        )
    }
}
