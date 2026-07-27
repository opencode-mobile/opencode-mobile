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

nonisolated private struct MessageMarkdownCacheKey: Hashable, Sendable {
    let messageID: String
    let textHash: Int
    let textLength: Int
    let isStreaming: Bool
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
}

final class MessageContentPreparer: @unchecked Sendable {
    static let shared = MessageContentPreparer()

    /// While streaming, skip markdown tokenization above this size and let the
    /// bubble render plain text. Full parse still runs on the final frame.
    nonisolated static let streamingPlainTextCharacterThreshold = 8_000

    /// When opening a history whose markdown-candidate text exceeds this size,
    /// publish a plain-text frame first so the timeline is usable before TextMate.
    nonisolated static let historyPlainFirstMarkdownCharacterThreshold = 4_000

    /// Newest-first enrich publishes once the open viewport is ready so iOS
    /// matches Android's perceived speed without per-bubble UI frames.
    nonisolated static let historyEnrichViewportMessageCount = 3
    nonisolated static let historyEnrichViewportCharacterBudget = 12_000

    nonisolated private let documentCache = MessageMarkdownDocumentCache(maxEntries: 32)

    init() {}

    nonisolated func prepareMessages(
        _ messages: [Message],
        previousByID: [String: DisplayMessage] = [:],
        mode: MessagePreparationMode = .final
    ) async -> [DisplayMessage] {
        // Match Android `Dispatchers.Default`: one worker hop for the batch,
        // not a MainActor round-trip between every message.
        await Task.detached(priority: .userInitiated) { [documentCache] in
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
                    documentCache: documentCache
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
        await Task.detached(priority: .userInitiated) { [documentCache] in
            Self.prepareMessageOnWorker(
                message,
                previous: previous,
                mode: mode,
                documentCache: documentCache
            )
        }.value
    }

    nonisolated private static func prepareMessageOnWorker(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode,
        documentCache: MessageMarkdownDocumentCache
    ) -> DisplayMessage {
        let isStreaming = messageIsStreaming(message)
        if mode == .streaming,
           message.text.count >= streamingPlainTextCharacterThreshold
        {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        if mode == .historyPlainFirst {
            if message.text.count < streamingPlainTextCharacterThreshold,
               let reusable = reusableDocument(
                for: message,
                previous: previous,
                mode: mode
               )
            {
                return DisplayMessage(message: message, renderedMarkdown: reusable)
            }
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        let document: NativeMarkdownDocument?
        if let reusable = reusableDocument(
            for: message,
            previous: previous,
            mode: mode
        ) {
            document = reusable
        } else if mode == .finalPlaceholder {
            document = previous?.renderedMarkdown
        } else {
            document = parseDocumentSafely(
                messageID: message.id,
                text: message.text,
                isStreaming: isStreaming,
                documentCache: documentCache
            )
        }
        return DisplayMessage(message: message, renderedMarkdown: document)
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
        messageID: String,
        text: String,
        isStreaming: Bool,
        documentCache: MessageMarkdownDocumentCache
    ) -> NativeMarkdownDocument? {
        parseDocument(
            messageID: messageID,
            text: text,
            isStreaming: isStreaming,
            documentCache: documentCache
        )
    }

    nonisolated private static func parseDocument(
        messageID: String,
        text: String,
        isStreaming: Bool,
        documentCache: MessageMarkdownDocumentCache
    ) -> NativeMarkdownDocument? {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(text) else {
            return nil
        }
        let cacheKey = MessageMarkdownCacheKey(
            messageID: messageID,
            textHash: stableMarkdownSourceHash(text),
            textLength: text.count,
            isStreaming: isStreaming
        )
        if let cached = documentCache.value(for: cacheKey) { return cached }

        let parsed = OpenCodeRenderedMarkdownParser.parse(text, isStreaming: isStreaming)
        return documentCache.insertIfAbsent(parsed, for: cacheKey)
    }

    nonisolated private static func reusableDocument(
        for message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode
    ) -> NativeMarkdownDocument? {
        let isStreaming = messageIsStreaming(message)
        if let existing = previous?.renderedMarkdown,
           !isStreaming,
           previous?.isStreaming != true,
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
