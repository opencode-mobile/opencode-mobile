import Foundation

nonisolated func stableMarkdownSourceHash(_ markdown: String) -> Int {
    var hash = 0x811c9dc5
    for byte in markdown.utf8 {
        hash = hash ^ Int(byte)
        hash = hash &* 0x01000193
    }
    return hash
}

/// Preparer LRU key: sessionKey + blockID + contentRevision + isStreaming (no generation).
nonisolated private struct MessageMarkdownCacheKey: Hashable, Sendable {
    let sessionKey: String
    let blockID: String
    let textHash: Int
    let textLength: Int
    let exactSource: String
    let isStreaming: Bool
    let textMateEnabled: Bool
    let policyEpoch: Int
}

nonisolated private final class TextMateHighlightRevisionSource: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64 = 0

    func next() -> TextMateHighlightRevision {
        lock.lock()
        defer { lock.unlock() }
        value &+= 1
        return TextMateHighlightRevision(value: value)
    }
}

nonisolated private final class MessageMarkdownDocumentCache: @unchecked Sendable {
    private let lock = NSLock()
    private let maxEntries: Int
    private var values: [MessageMarkdownCacheKey: NativeMarkdownDocument] = [:]
    private var order: [MessageMarkdownCacheKey] = []

    init(maxEntries: Int) {
        self.maxEntries = maxEntries
    }

    func value(for key: MessageMarkdownCacheKey) -> NativeMarkdownDocument? {
        lock.lock()
        defer { lock.unlock() }
        guard let value = values[key] else { return nil }
        touch(key)
        return value
    }

    func insertIfAbsent(
        _ document: NativeMarkdownDocument,
        for key: MessageMarkdownCacheKey
    ) -> NativeMarkdownDocument {
        lock.lock()
        defer { lock.unlock() }
        if let existing = values[key] {
            touch(key)
            return existing
        }
        values[key] = document
        touch(key)
        while order.count > maxEntries {
            values.removeValue(forKey: order.removeFirst())
        }
        return document
    }

    private func touch(_ key: MessageMarkdownCacheKey) {
        order.removeAll { $0 == key }
        order.append(key)
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        values.removeAll()
        order.removeAll()
    }
}

nonisolated final class MessageContentPreparer: @unchecked Sendable {
    static let shared = MessageContentPreparer()

    /// Legacy size gate still used by disposable streaming decoration fixtures.
    /// Format-first text-lane parse no longer skips on size.
    nonisolated static let streamingPlainTextCharacterThreshold = 8_000

    /// When opening a history whose markdown-candidate text exceeds this size,
    /// prepare a plain-text frame first so enrichment can start without TextMate.
    nonisolated static let historyPlainFirstMarkdownCharacterThreshold = 4_000

    /// Legacy flag: format-first history is always published immediately.
    /// Enrichment still upgrades TextMate colors newest-first afterward.
    nonisolated static let holdReadyUntilNewestMarkdown = false

    /// Newest-first enrich publishes once the open viewport is ready so iOS
    /// matches Android's perceived speed without per-bubble UI frames.
    nonisolated static let historyEnrichViewportMessageCount = 3
    nonisolated static let historyEnrichViewportCharacterBudget = 12_000

    nonisolated private let documentCache = MessageMarkdownDocumentCache(maxEntries: 32)
    nonisolated private let textMateLogbook: any TextMateLogbookRecording
    nonisolated private let textMateEngine: any TextMateHighlighting
    nonisolated private let highlightRevisions = TextMateHighlightRevisionSource()

    nonisolated init(
        textMateLogbook: any TextMateLogbookRecording = TextMateLogbook.shared,
        textMateEngine: any TextMateHighlighting = KMPTextMateHighlightingEngine.shared
    ) {
        self.textMateLogbook = textMateLogbook
        self.textMateEngine = textMateEngine
    }

    nonisolated func clearDocumentCache() {
        documentCache.clear()
    }

    nonisolated func prepareMessages(
        _ messages: [Message],
        previousByID: [String: DisplayMessage] = [:],
        mode: MessagePreparationMode = .final
    ) async -> [DisplayMessage] {
        let textMateEnabled = MarkdownHighlightPreferences.currentTextMateEnabled()
        let highlightEpoch = MarkdownHighlightPreferences.currentEpoch()
        // Match Android `Dispatchers.Default`: one worker hop for the batch,
        // not a MainActor round-trip between every message.
        return await Task.detached(
            priority: .userInitiated
        ) { [documentCache, textMateLogbook, textMateEngine, highlightRevisions] in
            let startedAt = Date()
            var prepared: [DisplayMessage] = []
            prepared.reserveCapacity(messages.count)
            var parsedCount = 0
            var reusedCount = 0
            var streamingChars = 0
            for message in messages {
                let previous = previousByID[message.id]
                let display = await Self.prepareMessageOnWorker(
                    message,
                    previous: previous,
                    mode: mode,
                    sessionKey: "",
                    documentCache: documentCache,
                    textMateLogbook: textMateLogbook,
                    textMateEngine: textMateEngine,
                    highlightRevisions: highlightRevisions,
                    textMateEnabled: textMateEnabled,
                    highlightEpoch: highlightEpoch
                )
                prepared.append(display)
                if Self.messageIsStreaming(message) {
                    streamingChars = max(streamingChars, message.text.count)
                }
                guard display.renderedMarkdown != nil else { continue }
                if Self.wasReused(display: display, previous: previous) {
                    reusedCount += 1
                } else {
                    parsedCount += 1
                }
            }
            let elapsedMs = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
            OpenCodeLog.messageContent.info(
                "prepareMessages count=\(messages.count) parsed=\(parsedCount) reused=\(reusedCount) markdown=\(prepared.count { $0.renderedMarkdown != nil }) streamingChars=\(streamingChars) mode=\(mode.rawValue) ms=\(elapsedMs)"
            )
            return prepared
        }.value
    }

    func prepareOptimisticUserMessage(id: String, text: String) async -> DisplayMessage {
        await prepareMessage(
            Message(
                id: id,
                role: .user,
                text: text,
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            mode: .final
        )
    }

    nonisolated func prepareMessage(
        _ message: Message,
        previous: DisplayMessage? = nil,
        mode: MessagePreparationMode = .final
    ) async -> DisplayMessage {
        let textMateEnabled = MarkdownHighlightPreferences.currentTextMateEnabled()
        let highlightEpoch = MarkdownHighlightPreferences.currentEpoch()
        return await Task.detached(
            priority: .userInitiated
        ) { [documentCache, textMateLogbook, textMateEngine, highlightRevisions] in
            await Self.prepareMessageOnWorker(
                message,
                previous: previous,
                mode: mode,
                sessionKey: "",
                documentCache: documentCache,
                textMateLogbook: textMateLogbook,
                textMateEngine: textMateEngine,
                highlightRevisions: highlightRevisions,
                textMateEnabled: textMateEnabled,
                highlightEpoch: highlightEpoch
            )
        }.value
    }

    /// Parse a single text block and write the preparer LRU (no generation).
    nonisolated func prepareTextBlock(
        blockID: String,
        plain: String,
        isStreaming: Bool,
        sessionKey: String = "",
        mode: MessagePreparationMode = .final
    ) async -> NativeMarkdownDocument? {
        let textMateEnabled = MarkdownHighlightPreferences.currentTextMateEnabled()
        let policyEpoch = MarkdownHighlightPreferences.currentEpoch()
        return await Task.detached(
            priority: .userInitiated
        ) { [documentCache, textMateLogbook, textMateEngine, highlightRevisions] in
            if mode == .streaming,
               plain.count >= MessageContentPreparer.streamingPlainTextCharacterThreshold
            {
                return nil as NativeMarkdownDocument?
            }
            guard OpenCodeRenderedMarkdownDetector.shouldRender(plain) else {
                return nil as NativeMarkdownDocument?
            }
            return await Self.parseDocument(
                sessionKey: sessionKey,
                blockID: blockID,
                text: plain,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                policyEpoch: policyEpoch,
                intent: mode.textMateHighlightIntent,
                documentCache: documentCache,
                textMateLogbook: textMateLogbook,
                textMateEngine: textMateEngine,
                highlightRevisions: highlightRevisions
            )
        }.value
    }

    /// Format-first streaming paint: always structure when markdown is detected.
    /// TextMate on → empty spans (colors later); TextMate off → full semantic.
    /// Never writes the preparer LRU.
    nonisolated func prepareStructureOnlyTextBlock(
        blockID: String,
        plain: String,
        sessionKey: String = ""
    ) async -> NativeMarkdownDocument? {
        let textMateEnabled = MarkdownHighlightPreferences.currentTextMateEnabled()
        return await Task.detached(priority: .userInitiated) {
            guard OpenCodeRenderedMarkdownDetector.shouldRender(plain) else {
                return nil as NativeMarkdownDocument?
            }
            return OpenCodeRenderedMarkdownParser.parse(
                plain,
                isStreaming: true,
                textMateEnabled: false,
                structureOnly: textMateEnabled
            )
        }.value
    }

    nonisolated private static func prepareMessageOnWorker(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode,
        sessionKey: String,
        documentCache: MessageMarkdownDocumentCache,
        textMateLogbook: any TextMateLogbookRecording,
        textMateEngine: any TextMateHighlighting,
        highlightRevisions: TextMateHighlightRevisionSource,
        textMateEnabled: Bool,
        highlightEpoch: Int
    ) async -> DisplayMessage {
        let isStreaming = messageIsStreaming(message)
        if mode == .streaming,
           message.text.count >= MessageContentPreparer.streamingPlainTextCharacterThreshold
        {
            return DisplayMessage(
                message: message,
                renderedMarkdown: nil,
                highlightEpoch: highlightEpoch
            )
        }
        if mode == .historyPlainFirst {
            if let reusable = reusableDocument(
                for: message,
                previous: previous,
                mode: mode,
                highlightEpoch: highlightEpoch
            ) {
                return DisplayMessage(
                    message: message,
                    renderedMarkdown: reusable,
                    awaitingTextMateUpgrade: previous?.awaitingTextMateUpgrade == true,
                    highlightEpoch: highlightEpoch
                )
            }
            guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
                return DisplayMessage(
                    message: message,
                    renderedMarkdown: nil,
                    highlightEpoch: highlightEpoch
                )
            }
            let document = OpenCodeRenderedMarkdownParser.parse(
                message.text,
                isStreaming: false,
                textMateEnabled: false,
                structureOnly: textMateEnabled
            )
            return DisplayMessage(
                message: message,
                renderedMarkdown: document,
                awaitingTextMateUpgrade: textMateEnabled,
                highlightEpoch: highlightEpoch
            )
        }
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
            return DisplayMessage(
                message: message,
                renderedMarkdown: nil,
                highlightEpoch: highlightEpoch
            )
        }
        let reused = reusableDocument(
            for: message,
            previous: previous,
            mode: mode,
            highlightEpoch: highlightEpoch
        )
        let document: NativeMarkdownDocument?
        if let reused {
            document = reused
        } else if mode == .finalPlaceholder {
            document = previous?.renderedMarkdown
        } else {
            let blockID =
                StreamingTextBlockIdentity.resolvePrimary(message: message)?.blockID
                ?? PresentationBlockId.messageFallback(message.id)
            document = await parseDocumentSafely(
                sessionKey: sessionKey,
                blockID: blockID,
                text: message.text,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                policyEpoch: highlightEpoch,
                intent: mode.textMateHighlightIntent,
                documentCache: documentCache,
                textMateLogbook: textMateLogbook,
                textMateEngine: textMateEngine,
                highlightRevisions: highlightRevisions
            )
        }
        let awaitingTextMateUpgrade: Bool
        if !textMateEnabled {
            awaitingTextMateUpgrade = false
        } else if mode == .finalPlaceholder, document != nil {
            awaitingTextMateUpgrade =
                previous?.awaitingTextMateUpgrade == true || previous?.isStreaming == true
        } else if reused != nil {
            awaitingTextMateUpgrade = previous?.awaitingTextMateUpgrade == true
        } else {
            awaitingTextMateUpgrade = false
        }
        return DisplayMessage(
            message: message,
            renderedMarkdown: document,
            awaitingTextMateUpgrade: awaitingTextMateUpgrade,
            highlightEpoch: highlightEpoch
        )
    }

    nonisolated private static func messageIsStreaming(_ message: Message) -> Bool {
        message.parts.contains { part in
            if case .text(let textPart) = part {
                return textPart.isStreaming
            }
            return false
        }
    }

    /// Structural fallback stays usable if the asynchronous TextMate upgrade is
    /// cancelled, rejected, or cannot cross the KMP bridge.
    nonisolated private static func parseDocumentSafely(
        sessionKey: String,
        blockID: String,
        text: String,
        isStreaming: Bool,
        textMateEnabled: Bool,
        policyEpoch: Int,
        intent: TextMateHighlightIntent,
        documentCache: MessageMarkdownDocumentCache,
        textMateLogbook: any TextMateLogbookRecording,
        textMateEngine: any TextMateHighlighting,
        highlightRevisions: TextMateHighlightRevisionSource
    ) async -> NativeMarkdownDocument? {
        await parseDocument(
            sessionKey: sessionKey,
            blockID: blockID,
            text: text,
            isStreaming: isStreaming,
            textMateEnabled: textMateEnabled,
            policyEpoch: policyEpoch,
            intent: intent,
            documentCache: documentCache,
            textMateLogbook: textMateLogbook,
            textMateEngine: textMateEngine,
            highlightRevisions: highlightRevisions
        )
    }

    nonisolated private static func parseDocument(
        sessionKey: String,
        blockID: String,
        text: String,
        isStreaming: Bool,
        textMateEnabled: Bool,
        policyEpoch: Int,
        intent: TextMateHighlightIntent,
        documentCache: MessageMarkdownDocumentCache,
        textMateLogbook: any TextMateLogbookRecording,
        textMateEngine: any TextMateHighlighting,
        highlightRevisions: TextMateHighlightRevisionSource
    ) async -> NativeMarkdownDocument? {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(text) else {
            return nil
        }
        let revision = ContentRevision.of(text)
        let cacheKey = MessageMarkdownCacheKey(
            sessionKey: sessionKey,
            blockID: blockID,
            textHash: revision.textHash,
            textLength: revision.textLength,
            exactSource: text,
            isStreaming: isStreaming,
            textMateEnabled: textMateEnabled,
            policyEpoch: policyEpoch
        )
        if let cached = documentCache.value(for: cacheKey) { return cached }

        let fallbackDocument = OpenCodeRenderedMarkdownParser.parse(
            text,
            isStreaming: isStreaming,
            textMateEnabled: false
        )
        let document: NativeMarkdownDocument
        if !textMateEnabled || isStreaming {
            document = fallbackDocument
        } else {
            let highlightRevision = highlightRevisions.next()
            let fences = fallbackDocument.textMateFences(
                ownerKey: "\(sessionKey.utf16.count):\(sessionKey):\(blockID)",
                revision: highlightRevision,
                policyEpoch: Int64(policyEpoch)
            )
            var upgraded = fallbackDocument
            for fence in fences {
                guard !Task.isCancelled else { return fallbackDocument }
                do {
                    let result = try await textMateEngine.highlight(
                        fence.request(
                            intent: intent,
                            messageCharacters: text.utf16.count,
                            fenceCount: fences.count
                        )
                    )
                    textMateLogbook.submit([result.observation])
                    if case .attached(let attached) = upgraded.attachingTextMate(
                        fence: fence,
                        result: result
                    ) {
                        upgraded = attached
                    }
                } catch is CancellationError {
                    return fallbackDocument
                } catch {
                    return fallbackDocument
                }
            }
            document = upgraded
        }
        return documentCache.insertIfAbsent(document, for: cacheKey)
    }

    nonisolated private static func reusableDocument(
        for message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode,
        highlightEpoch: Int
    ) -> NativeMarkdownDocument? {
        let isStreaming = messageIsStreaming(message)
        // Final must not settle-reuse a FinalPlaceholder that still holds
        // streaming semantic-fallback spans (`awaitingTextMateUpgrade`).
        if let existing = previous?.renderedMarkdown,
           !isStreaming,
           previous?.isStreaming != true,
           previous?.awaitingTextMateUpgrade != true,
           previous?.highlightEpoch == highlightEpoch,
           previous?.text == message.text {
            return existing
        }
        if isStreaming,
           let previous,
           previous.isStreaming,
           previous.highlightEpoch == highlightEpoch,
           previous.text == message.text {
            return previous.renderedMarkdown
        }
        if mode == .finalPlaceholder,
           !isStreaming,
           let previous,
           previous.isStreaming,
           previous.highlightEpoch == highlightEpoch,
           previous.text == message.text {
            return previous.renderedMarkdown
        }
        return nil
    }

    nonisolated private static func wasReused(display: DisplayMessage, previous: DisplayMessage?) -> Bool {
        guard let previous,
              let previousDocument = previous.renderedMarkdown,
              let document = display.renderedMarkdown
        else {
            return false
        }
        return previousDocument.sourceHash == document.sourceHash
            && previous.text == display.text
            && previous.isStreaming == display.isStreaming
    }
}

nonisolated private extension MessagePreparationMode {
    var textMateHighlightIntent: TextMateHighlightIntent {
        switch self {
        case .final:
            .interactiveFinal
        case .historyEnrichment:
            .visibleHistory
        default:
            .backgroundHistory
        }
    }
}
