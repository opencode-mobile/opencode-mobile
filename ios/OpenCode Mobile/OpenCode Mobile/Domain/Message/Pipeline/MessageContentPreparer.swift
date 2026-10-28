import FactoryKit
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
    static let shared = MessageContentPreparer(textMateEngine: Container.codeHighlighter)

    /// Legacy size gate still used by disposable streaming decoration fixtures.
    /// Format-first text-lane parse no longer skips on size.
    nonisolated static let streamingPlainTextCharacterThreshold = 8_000

    /// When opening a history whose markdown-candidate text exceeds this size,
    /// prepare a plain-text frame first so enrichment can start before highlighting.
    nonisolated static let historyPlainFirstMarkdownCharacterThreshold = 4_000

    /// Legacy flag: format-first history is always published immediately.
    /// Enrichment still attaches highlight-core colors newest-first afterward.
    nonisolated static let holdReadyUntilNewestMarkdown = false

    /// Newest-first enrich publishes once the open viewport is ready so iOS
    /// matches Android's perceived speed without per-bubble UI frames.
    nonisolated static let historyEnrichViewportMessageCount = 3
    nonisolated static let historyEnrichViewportCharacterBudget = 12_000

    nonisolated private let documentCache = MessageMarkdownDocumentCache(maxEntries: 32)
    nonisolated private let textMateEngine: any TextMateHighlighting
    nonisolated private let highlightRevisions = TextMateHighlightRevisionSource()

    nonisolated init(textMateEngine: any TextMateHighlighting = HighlightCoreHighlightingEngine.shared) {
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
        // Match Android `Dispatchers.Default`: one worker hop for the batch,
        // not a MainActor round-trip between every message.
        return await Task.detached(
            priority: .userInitiated
        ) { [documentCache, textMateEngine, highlightRevisions] in
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
                    textMateEngine: textMateEngine,
                    highlightRevisions: highlightRevisions
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
        return await Task.detached(
            priority: .userInitiated
        ) { [documentCache, textMateEngine, highlightRevisions] in
            await Self.prepareMessageOnWorker(
                message,
                previous: previous,
                mode: mode,
                sessionKey: "",
                documentCache: documentCache,
                textMateEngine: textMateEngine,
                highlightRevisions: highlightRevisions
            )
        }.value
    }

    /// Format-first streaming paint: block structure with empty code spans when markdown is
    /// detected; highlight-core colors attach once the message is final. Never writes the
    /// preparer LRU.
    nonisolated func prepareStructureOnlyTextBlock(
        blockID: String,
        plain: String,
        sessionKey: String = ""
    ) async -> NativeMarkdownDocument? {
        return await Task.detached(priority: .userInitiated) {
            guard OpenCodeRenderedMarkdownDetector.shouldRender(plain) else {
                return nil as NativeMarkdownDocument?
            }
            return OpenCodeRenderedMarkdownParser.parse(plain, structureOnly: true)
        }.value
    }

    nonisolated private static func prepareMessageOnWorker(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode,
        sessionKey: String,
        documentCache: MessageMarkdownDocumentCache,
        textMateEngine: any TextMateHighlighting,
        highlightRevisions: TextMateHighlightRevisionSource
    ) async -> DisplayMessage {
        let isStreaming = messageIsStreaming(message)
        if mode == .streaming,
           message.text.count >= MessageContentPreparer.streamingPlainTextCharacterThreshold
        {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        if mode == .historyPlainFirst {
            if let reusable = reusableDocument(
                for: message,
                previous: previous,
                mode: mode
            ) {
                return DisplayMessage(
                    message: message,
                    renderedMarkdown: reusable,
                    awaitingTextMateUpgrade: previous?.awaitingTextMateUpgrade == true
                )
            }
            guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
                return DisplayMessage(message: message, renderedMarkdown: nil)
            }
            let document = OpenCodeRenderedMarkdownParser.parse(message.text, structureOnly: true)
            return DisplayMessage(
                message: message,
                renderedMarkdown: document,
                awaitingTextMateUpgrade: true
            )
        }
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        let reused = reusableDocument(
            for: message,
            previous: previous,
            mode: mode
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
                intent: mode.textMateHighlightIntent,
                documentCache: documentCache,
                textMateEngine: textMateEngine,
                highlightRevisions: highlightRevisions
            )
        }
        let awaitingTextMateUpgrade: Bool
        if mode == .finalPlaceholder, document != nil {
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
            awaitingTextMateUpgrade: awaitingTextMateUpgrade
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

    /// The structure-only document stays usable if the asynchronous highlight-core attach is
    /// cancelled or rejected.
    nonisolated private static func parseDocumentSafely(
        sessionKey: String,
        blockID: String,
        text: String,
        isStreaming: Bool,
        intent: TextMateHighlightIntent,
        documentCache: MessageMarkdownDocumentCache,
        textMateEngine: any TextMateHighlighting,
        highlightRevisions: TextMateHighlightRevisionSource
    ) async -> NativeMarkdownDocument? {
        await parseDocument(
            sessionKey: sessionKey,
            blockID: blockID,
            text: text,
            isStreaming: isStreaming,
            intent: intent,
            documentCache: documentCache,
            textMateEngine: textMateEngine,
            highlightRevisions: highlightRevisions
        )
    }

    nonisolated private static func parseDocument(
        sessionKey: String,
        blockID: String,
        text: String,
        isStreaming: Bool,
        intent: TextMateHighlightIntent,
        documentCache: MessageMarkdownDocumentCache,
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
            isStreaming: isStreaming
        )
        if let cached = documentCache.value(for: cacheKey) { return cached }

        // Structure first; highlight-core colors attach fence by fence once the text is final.
        let structureDocument = OpenCodeRenderedMarkdownParser.parse(text, structureOnly: true)
        let document: NativeMarkdownDocument
        if isStreaming {
            document = structureDocument
        } else {
            let highlightRevision = highlightRevisions.next()
            let fences = structureDocument.textMateFences(
                ownerKey: "\(sessionKey.utf16.count):\(sessionKey):\(blockID)",
                revision: highlightRevision,
                policyEpoch: 0
            )
            var upgraded = structureDocument
            for fence in fences {
                guard !Task.isCancelled else { return structureDocument }
                do {
                    let result = try await textMateEngine.highlight(fence.request(intent: intent))
                    if case .attached(let attached) = upgraded.attachingTextMate(
                        fence: fence,
                        result: result
                    ) {
                        upgraded = attached
                    }
                } catch {
                    return structureDocument
                }
            }
            document = upgraded
        }
        return documentCache.insertIfAbsent(document, for: cacheKey)
    }

    nonisolated private static func reusableDocument(
        for message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode
    ) -> NativeMarkdownDocument? {
        let isStreaming = messageIsStreaming(message)
        // Final must not settle-reuse a FinalPlaceholder that still holds a
        // structure-only streaming parse (`awaitingTextMateUpgrade`).
        if let existing = previous?.renderedMarkdown,
           !isStreaming,
           previous?.isStreaming != true,
           previous?.awaitingTextMateUpgrade != true,
           previous?.text == message.text {
            return existing
        }
        if isStreaming,
           let previous,
           previous.isStreaming,
           previous.text == message.text {
            return previous.renderedMarkdown
        }
        if mode == .finalPlaceholder,
           !isStreaming,
           let previous,
           previous.isStreaming,
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
