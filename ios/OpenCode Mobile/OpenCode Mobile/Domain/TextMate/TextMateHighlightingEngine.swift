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

nonisolated enum TextMateMemoryPressure: String, Sendable {
    case normal = "Normal"
    case moderate = "Moderate"
    case critical = "Critical"
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

nonisolated private struct SchedulerAdmitEnvelope: Decodable, Sendable {
    let ticketId: Int64?
    let completed: [TextMateHighlightResult]
}

nonisolated protocol TextMateHighlighting: Sendable {
    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult
    func trim(_ level: TextMateMemoryPressure) async
}

extension TextMateHighlighting {
    func trim(_ level: TextMateMemoryPressure) async {}
}

/// iOS's asynchronous platform-to-KMP adapter. Actor isolation is the sole
/// mutable owner; the shared Kotlin scheduler bridge owns queue policy while
/// this actor drives at most one active highlight job per pump turn.
actor KMPTextMateHighlightingEngine: TextMateHighlighting {
    static let shared = KMPTextMateHighlightingEngine()

    private var bridge: OpenCodeTextMateSchedulerBridge?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var waiters: [Int64: CheckedContinuation<TextMateHighlightResult, Error>] = [:]
    private var ticketIdentity: [Int64: (TextMateCodeBlockKey, TextMateHighlightRevision)] = [:]
    private var pumping = false

    init(bridge: OpenCodeTextMateSchedulerBridge? = nil) {
        self.bridge = bridge
    }

    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult {
        try await withCheckedThrowingContinuation { continuation in
            Task { await self.enqueue(request, continuation: continuation) }
        }
    }

    func trim(_ level: TextMateMemoryPressure) async {
        let completedJSON = activeBridge().trim(level: level.rawValue)
        deliverAll(resultsJSON: completedJSON)
    }

    func setLowPowerOrThermal(_ disabled: Bool) async {
        activeBridge().setLowPowerOrThermal(disabled: disabled)
    }

    private func enqueue(
        _ request: TextMateHighlightRequest,
        continuation: CheckedContinuation<TextMateHighlightResult, Error>
    ) {
        do {
            let requestJSON = try encode(request)
            let admitJSON = activeBridge().admit(requestJson: requestJSON)
            let admit = try decoder.decode(SchedulerAdmitEnvelope.self, from: Data(admitJSON.utf8))
            for completed in admit.completed {
                resume(result: completed)
            }
            if let ticketId = admit.ticketId {
                waiters[ticketId] = continuation
                ticketIdentity[ticketId] = (request.key, request.revision)
                pump()
            } else if let rejected = admit.completed.last(where: {
                $0.key == request.key && $0.revision == request.revision
            }) ?? admit.completed.last {
                continuation.resume(returning: rejected)
            } else {
                continuation.resume(throwing: TextMateHighlightingAdapterError.invalidUTF8)
            }
        } catch {
            continuation.resume(throwing: error)
        }
    }

    private func pump() {
        guard !pumping else { return }
        pumping = true
        defer { pumping = false }
        while let resultsJSON = activeBridge().pumpOnce() {
            deliverAll(resultsJSON: resultsJSON)
        }
    }

    private func deliverAll(resultsJSON: String) {
        guard let data = resultsJSON.data(using: .utf8),
              let results = try? decoder.decode([TextMateHighlightResult].self, from: data)
        else {
            return
        }
        for result in results {
            resume(result: result)
        }
    }

    private func resume(result: TextMateHighlightResult) {
        if let ticketId = ticketIdentity.first(where: {
            $0.value.0 == result.key && $0.value.1 == result.revision
        })?.key {
            ticketIdentity.removeValue(forKey: ticketId)
            if let continuation = waiters.removeValue(forKey: ticketId) {
                continuation.resume(returning: result)
            }
        }
    }

    private func activeBridge() -> OpenCodeTextMateSchedulerBridge {
        if let bridge { return bridge }
        let created = OpenCodeTextMateSchedulerBridge.companion.createDefault()
        bridge = created
        return created
    }

    private func encode(_ request: TextMateHighlightRequest) throws -> String {
        // Kotlin HighlightRequest treats omitted nullables as missing unless they
        // have defaults. Encode nil `language` explicitly as JSON null.
        let data = try encoder.encode(EncodableHighlightRequest(request))
        guard let json = String(data: data, encoding: .utf8) else {
            throw TextMateHighlightingAdapterError.invalidUTF8
        }
        return json
    }
}

nonisolated private struct EncodableHighlightRequest: Encodable {
    let key: TextMateCodeBlockKey
    let revision: TextMateHighlightRevision
    let language: String?
    let source: String
    let intent: TextMateHighlightIntent
    let policyEpoch: Int64
    let messageCharacters: Int
    let fenceIndex: Int
    let fenceCount: Int

    init(_ request: TextMateHighlightRequest) {
        key = request.key
        revision = request.revision
        language = request.language
        source = request.source
        intent = request.intent
        policyEpoch = request.policyEpoch
        messageCharacters = request.messageCharacters
        fenceIndex = request.fenceIndex
        fenceCount = request.fenceCount
    }

    private enum CodingKeys: String, CodingKey {
        case key, revision, language, source, intent, policyEpoch, messageCharacters, fenceIndex, fenceCount
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(key, forKey: .key)
        try container.encode(revision, forKey: .revision)
        if let language {
            try container.encode(language, forKey: .language)
        } else {
            try container.encodeNil(forKey: .language)
        }
        try container.encode(source, forKey: .source)
        try container.encode(intent, forKey: .intent)
        try container.encode(policyEpoch, forKey: .policyEpoch)
        try container.encode(messageCharacters, forKey: .messageCharacters)
        try container.encode(fenceIndex, forKey: .fenceIndex)
        try container.encode(fenceCount, forKey: .fenceCount)
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
