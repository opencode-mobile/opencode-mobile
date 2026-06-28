import Foundation

func stableMarkdownSourceHash(_ markdown: String) -> Int {
    var hash = 0x811c9dc5
    for byte in markdown.utf8 {
        hash = hash ^ Int(byte)
        hash = hash &* 0x01000193
    }
    return hash
}

final class MessageContentPreparer: @unchecked Sendable {
    static let shared = MessageContentPreparer()

    private let cacheLock = NSLock()
    private var cache: [CacheKey: NativeMarkdownDocument] = [:]
    private var cacheOrder: [CacheKey] = []
    private let maxCacheEntries = 32

    init() {}

    func prepareMessages(
        _ messages: [Message],
        previousByID: [String: DisplayMessage] = [:]
    ) async -> [DisplayMessage] {
        var prepared: [DisplayMessage] = []
        prepared.reserveCapacity(messages.count)
        for message in messages {
            prepared.append(await prepareMessage(message, previous: previousByID[message.id]))
        }
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

    func prepareMessage(_ message: Message, previous: DisplayMessage? = nil) async -> DisplayMessage {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(message.text) else {
            return DisplayMessage(message: message, renderedMarkdown: nil)
        }
        let document: NativeMarkdownDocument?
        if let reusable = reusableDocument(for: message, previous: previous) {
            document = reusable
        } else {
            document = await parseDocument(
                messageID: message.id,
                text: message.text,
                isStreaming: messageIsStreaming(message)
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

    private func parseDocument(
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

        let parsed = await Task.detached(priority: .userInitiated) {
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

    private func reusableDocument(for message: Message, previous: DisplayMessage?) -> NativeMarkdownDocument? {
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
        return nil
    }

    private func touch(_ key: CacheKey) {
        cacheOrder.removeAll { $0 == key }
        cacheOrder.append(key)
    }

    private struct CacheKey: Hashable {
        let messageID: String
        let textHash: Int
        let textLength: Int
        let isStreaming: Bool
    }
}
