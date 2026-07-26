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

final class MessageContentPreparer: @unchecked Sendable {
    static let shared = MessageContentPreparer()

    /// While streaming, skip markdown tokenization above this size and let the
    /// bubble render plain text. Full parse still runs on the final frame.
    static let streamingPlainTextCharacterThreshold = 8_000

    /// When opening a history whose markdown-candidate text exceeds this size,
    /// publish a plain-text frame first so the timeline is usable before TextMate.
    static let historyPlainFirstMarkdownCharacterThreshold = 4_000

    private let cacheLock = NSLock()
    private var cache: [CacheKey: NativeMarkdownDocument] = [:]
    private var cacheOrder: [CacheKey] = []
    private let maxCacheEntries = 32

    init() {}

    func prepareMessages(
        _ messages: [Message],
        previousByID: [String: DisplayMessage] = [:],
        reuseStreamingDocumentAsFinalPlaceholder: Bool = false,
        skipMarkdown: Bool = false
    ) async -> [DisplayMessage] {
        let startedAt = Date()
        var prepared: [DisplayMessage] = []
        prepared.reserveCapacity(messages.count)
        var parsedCount = 0
        var reusedCount = 0
        var streamingChars = 0
        for message in messages {
            let previous = previousByID[message.id]
            let display = await prepareMessage(
                message,
                previous: previous,
                reuseStreamingDocumentAsFinalPlaceholder: reuseStreamingDocumentAsFinalPlaceholder,
                skipMarkdown: skipMarkdown
            )
            prepared.append(display)
            if messageIsStreaming(message) {
                streamingChars = max(streamingChars, message.text.count)
            }
            guard display.renderedMarkdown != nil else { continue }
            if wasReused(display: display, previous: previous) {
                reusedCount += 1
            } else {
                parsedCount += 1
            }
        }
        let elapsedMs = max(0, Int(Date().timeIntervalSince(startedAt) * 1_000))
        OpenCodeLog.messageContent.info(
            "prepareMessages count=\(messages.count) parsed=\(parsedCount) reused=\(reusedCount) markdown=\(prepared.count { $0.renderedMarkdown != nil }) streamingChars=\(streamingChars) skipMarkdown=\(skipMarkdown) ms=\(elapsedMs)"
        )
        return prepared
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
            )
        )
    }

    func prepareMessage(
        _ message: Message,
        previous: DisplayMessage? = nil,
        reuseStreamingDocumentAsFinalPlaceholder: Bool = false,
        skipMarkdown: Bool = false
    ) async -> DisplayMessage {
        let isStreaming = messageIsStreaming(message)
        // Large live replies: print plain text until the message finishes so
        // SwiftUI is not rebuilding a growing markdown tree every publish.
        if isStreaming,
           !reuseStreamingDocumentAsFinalPlaceholder,
           message.text.count >= Self.streamingPlainTextCharacterThreshold
        {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        if skipMarkdown {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        let document: NativeMarkdownDocument?
        if let reusable = reusableDocument(
            for: message,
            previous: previous,
            reuseStreamingDocumentAsFinalPlaceholder: reuseStreamingDocumentAsFinalPlaceholder
        ) {
            document = reusable
        } else if reuseStreamingDocumentAsFinalPlaceholder {
            // Keep the last streaming render for one frame; full TextMate runs on the follow-up.
            document = previous?.renderedMarkdown
        } else {
            document = await parseDocument(
                messageID: message.id,
                text: message.text,
                isStreaming: isStreaming
            )
        }
        return DisplayMessage(message: message, renderedMarkdown: document)
    }

    private func messageIsStreaming(_ message: Message) -> Bool {
        message.parts.contains { part in
            if case .text(let textPart) = part {
                return textPart.isStreaming
            }
            return false
        }
    }

    /// Must stay off MainActor: default actor isolation is MainActor, and history
    /// enrich depends on this running inside `Task.detached` without hopping back.
    nonisolated private func parseDocument(
        messageID: String,
        text: String,
        isStreaming: Bool
    ) async -> NativeMarkdownDocument? {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(text) else {
            return nil
        }
        let cacheKey = CacheKey(
            messageID: messageID,
            textHash: stableMarkdownSourceHash(text),
            textLength: text.count,
            isStreaming: isStreaming
        )
        cacheLock.lock()
        if let cached = cache[cacheKey] {
            touch(cacheKey)
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()

        let parsed = await Task.detached(priority: .utility) {
            OpenCodeRenderedMarkdownParser.parse(text, isStreaming: isStreaming)
        }.value

        cacheLock.lock()
        cache[cacheKey] = parsed
        touch(cacheKey)
        while cacheOrder.count > maxCacheEntries {
            let oldestKey = cacheOrder.removeFirst()
            cache.removeValue(forKey: oldestKey)
        }
        cacheLock.unlock()
        return parsed
    }

    private func reusableDocument(
        for message: Message,
        previous: DisplayMessage?,
        reuseStreamingDocumentAsFinalPlaceholder: Bool
    ) -> NativeMarkdownDocument? {
        let isStreaming = messageIsStreaming(message)
        if let existing = previous?.renderedMarkdown,
           !isStreaming,
           previous?.isStreaming != true {
            return existing
        }
        if isStreaming,
           let previous,
           previous.isStreaming,
           previous.text == message.text {
            return previous.renderedMarkdown
        }
        // Stream → final: keep the cheap streaming render until TextMate finishes.
        if reuseStreamingDocumentAsFinalPlaceholder,
           !isStreaming,
           let previous,
           previous.isStreaming,
           previous.text == message.text {
            return previous.renderedMarkdown
        }
        return nil
    }

    private func wasReused(display: DisplayMessage, previous: DisplayMessage?) -> Bool {
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

    nonisolated private func touch(_ key: CacheKey) {
        cacheOrder.removeAll { $0 == key }
        cacheOrder.append(key)
    }

    private struct CacheKey: Hashable, Sendable {
        let messageID: String
        let textHash: Int
        let textLength: Int
        let isStreaming: Bool
    }
}
