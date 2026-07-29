import Foundation
import OSLog

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
    let isStreaming: Bool
    let textMateEnabled: Bool
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

final class MessageContentPreparer: @unchecked Sendable {
    static let shared = MessageContentPreparer()

    /// While streaming, skip markdown tokenization above this size and let the
    /// bubble render plain text. Full parse still runs on the final frame.
    nonisolated static let streamingPlainTextCharacterThreshold = 8_000

    /// When opening a history whose markdown-candidate text exceeds this size,
    /// prepare a plain-text frame first so enrichment can start without TextMate.
    nonisolated static let historyPlainFirstMarkdownCharacterThreshold = 4_000

    /// Experimental: keep Session Chat on Loading until the newest markdown
    /// candidate is enriched. Plain-first still runs internally but is not
    /// published. Flip to `false` to restore immediate plain Ready.
    nonisolated static let holdReadyUntilNewestMarkdown = true

    /// Newest-first enrich publishes once the open viewport is ready so iOS
    /// matches Android's perceived speed without per-bubble UI frames.
    nonisolated static let historyEnrichViewportMessageCount = 3
    nonisolated static let historyEnrichViewportCharacterBudget = 12_000

    nonisolated private let documentCache = MessageMarkdownDocumentCache(maxEntries: 32)
    nonisolated private let textMateLogbook: any TextMateLogbookRecording

    nonisolated init(textMateLogbook: any TextMateLogbookRecording = TextMateLogbook.shared) {
        self.textMateLogbook = textMateLogbook
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
        return await Task.detached(priority: .userInitiated) { [documentCache, textMateLogbook] in
            let startedAt = Date()
            var prepared: [DisplayMessage] = []
            prepared.reserveCapacity(messages.count)
            var parsedCount = 0
            var reusedCount = 0
            var streamingChars = 0
            for message in messages {
                let previous = previousByID[message.id]
                let display = Self.prepareMessageOnWorker(
                    message,
                    previous: previous,
                    mode: mode,
                    sessionKey: "",
                    documentCache: documentCache,
                    textMateLogbook: textMateLogbook,
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
                "prepareMessages count=\(messages.count) parsed=\(parsedCount) reused=\(reusedCount) markdown=\(prepared.count { $0.renderedMarkdown != nil }) streamingChars=\(streamingChars) mode=\(mode.rawValue, privacy: .public) ms=\(elapsedMs)"
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
        return await Task.detached(priority: .userInitiated) { [documentCache, textMateLogbook] in
            Self.prepareMessageOnWorker(
                message,
                previous: previous,
                mode: mode,
                sessionKey: "",
                documentCache: documentCache,
                textMateLogbook: textMateLogbook,
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
        return await Task.detached(priority: .userInitiated) { [documentCache, textMateLogbook] in
            if mode == .streaming,
               plain.count >= MessageContentPreparer.streamingPlainTextCharacterThreshold
            {
                return nil as NativeMarkdownDocument?
            }
            guard OpenCodeRenderedMarkdownDetector.shouldRender(plain) else {
                return nil as NativeMarkdownDocument?
            }
            return Self.parseDocument(
                sessionKey: sessionKey,
                blockID: blockID,
                text: plain,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                documentCache: documentCache,
                textMateLogbook: textMateLogbook
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
        textMateEnabled: Bool,
        highlightEpoch: Int
    ) -> DisplayMessage {
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
            if message.text.count < MessageContentPreparer.streamingPlainTextCharacterThreshold,
               let reusable = reusableDocument(
                for: message,
                previous: previous,
                mode: mode,
                highlightEpoch: highlightEpoch
               )
            {
                return DisplayMessage(
                    message: message,
                    renderedMarkdown: reusable,
                    highlightEpoch: highlightEpoch
                )
            }
            return DisplayMessage(
                message: message,
                renderedMarkdown: nil,
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
            document = parseDocumentSafely(
                sessionKey: sessionKey,
                blockID: blockID,
                text: message.text,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled,
                documentCache: documentCache,
                textMateLogbook: textMateLogbook
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

    /// Synchronous TextMate on the worker thread — callers already hopped off MainActor.
    /// Parser bridge failures already degrade to a plain-text document inside
    /// `OpenCodeRenderedMarkdownParser`; this seam keeps the call site aligned with
    /// Android's safe-plain fallback contract.
    nonisolated private static func parseDocumentSafely(
        sessionKey: String,
        blockID: String,
        text: String,
        isStreaming: Bool,
        textMateEnabled: Bool,
        documentCache: MessageMarkdownDocumentCache,
        textMateLogbook: any TextMateLogbookRecording
    ) -> NativeMarkdownDocument? {
        parseDocument(
            sessionKey: sessionKey,
            blockID: blockID,
            text: text,
            isStreaming: isStreaming,
            textMateEnabled: textMateEnabled,
            documentCache: documentCache,
            textMateLogbook: textMateLogbook
        )
    }

    nonisolated private static func parseDocument(
        sessionKey: String,
        blockID: String,
        text: String,
        isStreaming: Bool,
        textMateEnabled: Bool,
        documentCache: MessageMarkdownDocumentCache,
        textMateLogbook: any TextMateLogbookRecording
    ) -> NativeMarkdownDocument? {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(text) else {
            return nil
        }
        let revision = ContentRevision.of(text)
        let cacheKey = MessageMarkdownCacheKey(
            sessionKey: sessionKey,
            blockID: blockID,
            textHash: revision.textHash,
            textLength: revision.textLength,
            isStreaming: isStreaming,
            textMateEnabled: textMateEnabled
        )
        if let cached = documentCache.value(for: cacheKey) { return cached }

        let document: NativeMarkdownDocument
        if textMateEnabled && !isStreaming {
            let parsed = OpenCodeRenderedMarkdownParser.parseObserved(
                text,
                isStreaming: false,
                textMateEnabled: true
            )
            textMateLogbook.submit(parsed.textMateObservations)
            document = parsed.document
        } else {
            document = OpenCodeRenderedMarkdownParser.parse(
                text,
                isStreaming: isStreaming,
                textMateEnabled: textMateEnabled
            )
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
