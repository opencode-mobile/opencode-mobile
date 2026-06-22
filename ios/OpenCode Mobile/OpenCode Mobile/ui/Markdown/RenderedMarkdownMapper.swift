import Foundation

final class RenderedMarkdownMapper {
    static let shared = RenderedMarkdownMapper()

    private let cacheLock = NSLock()
    private var cache: [CacheKey: NativeMarkdownDocument] = [:]
    private var cacheOrder: [CacheKey] = []
    private let maxCacheEntries = 32

    private init() {}

    func parse(
        messageID: String,
        text: String,
        isStreaming: Bool
    ) async -> NativeMarkdownDocument? {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(text) else {
            return nil
        }

        let cacheKey = CacheKey(messageID: messageID, text: text, isStreaming: isStreaming)
        cacheLock.lock()
        if let cached = cache[cacheKey] {
            touch(cacheKey)
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()

        let parsed = await Task.detached {
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

    private func touch(_ key: CacheKey) {
        cacheOrder.removeAll { $0 == key }
        cacheOrder.append(key)
    }

    func enrichRows(
        _ rows: [SessionChatRow],
        previousByID: [String: SessionChatRow]
    ) async -> [SessionChatRow] {
        var enriched: [SessionChatRow] = []
        enriched.reserveCapacity(rows.count)
        for row in rows {
            enriched.append(await enrichRow(row, previous: previousByID[row.id]))
        }
        return enriched
    }

    private func enrichRow(_ row: SessionChatRow, previous: SessionChatRow?) async -> SessionChatRow {
        guard OpenCodeRenderedMarkdownDetector.shouldRender(row.text) else {
            return row.withRenderedMarkdown(nil)
        }
        let document: NativeMarkdownDocument?
        if let reusable = reusableDocument(for: row, previous: previous) {
            document = reusable
        } else {
            document = await parse(messageID: row.id, text: row.text, isStreaming: row.isStreaming)
        }
        return row.withRenderedMarkdown(document)
    }

    private func reusableDocument(for row: SessionChatRow, previous: SessionChatRow?) -> NativeMarkdownDocument? {
        if let existing = row.renderedMarkdown,
           !row.isStreaming,
           previous?.isStreaming != true {
            return existing
        }
        if row.isStreaming,
           let previous,
           previous.isStreaming,
           previous.text == row.text {
            return previous.renderedMarkdown
        }
        return nil
    }

    private struct CacheKey: Hashable {
        let messageID: String
        let text: String
        let isStreaming: Bool

        init(messageID: String, text: String, isStreaming: Bool) {
            self.messageID = messageID
            self.text = text
            self.isStreaming = isStreaming
        }
    }
}
