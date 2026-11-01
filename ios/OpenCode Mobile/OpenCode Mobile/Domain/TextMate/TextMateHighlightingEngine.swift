import Foundation

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

nonisolated enum TextMateMemoryPressure: String, Sendable {
    case normal = "Normal"
    case moderate = "Moderate"
    case critical = "Critical"
}

nonisolated struct TextMateHighlightRequest: Sendable {
    let key: TextMateCodeBlockKey
    let revision: TextMateHighlightRevision
    let language: String?
    let source: String
    let intent: TextMateHighlightIntent
    let policyEpoch: Int64
}

/// `outcome` is `Highlighted`, `NoSpans`, `Partial` (deadline passed: highlighted prefix, plain
/// rest), `Unsupported` (no highlight-core lexer: plain text), `Cancelled`, or `Failed`.
nonisolated struct TextMateHighlightResult: Sendable {
    let key: TextMateCodeBlockKey
    let revision: TextMateHighlightRevision
    let source: String
    let canonicalLanguage: String?
    let policyEpoch: Int64
    let spans: [NativeCodeSpan]
    let outcome: String
    let diagnostics: [NativeMarkdownDiagnostic]
}

/// Highlights one code fence at a time for Session Chat and the File Viewer;
/// `HighlightCoreHighlightingEngine` is the implementation. Callers own revisions and
/// stale-result rejection (`attachingTextMate`).
nonisolated protocol TextMateHighlighting: Sendable {
    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult
    func trim(_ level: TextMateMemoryPressure) async
}

extension TextMateHighlighting {
    func trim(_ level: TextMateMemoryPressure) async {}
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

    func request(intent: TextMateHighlightIntent) -> TextMateHighlightRequest {
        TextMateHighlightRequest(
            key: key,
            revision: revision,
            language: language,
            source: source,
            intent: intent,
            policyEpoch: policyEpoch
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
        guard !["Failed", "Cancelled"].contains(result.outcome) else {
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
                // Keep sourceHash stable for fence identity; bump paint identity so
                // row equality / projector return-previous notice span upgrades.
                renderTreeVersion: renderTreeVersion + 1,
                sourceHash: sourceHash,
                blocks: updatedBlocks,
                diagnostics: retainedDiagnostics + result.diagnostics
            )
        )
    }
}
