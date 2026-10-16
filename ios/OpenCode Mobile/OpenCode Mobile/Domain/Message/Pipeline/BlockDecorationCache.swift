import Foundation

/// Bounded per-block Markdown decoration cache (Phase 5).
/// Key: sessionKey + blockID + contentRevision + isStreaming (no generation).
nonisolated final class BlockDecorationCache: @unchecked Sendable {
    nonisolated static let shared = BlockDecorationCache(maxEntries: 32)

    nonisolated struct Key: Hashable, Sendable {
        let sessionKey: String
        let blockID: String
        let textHash: Int
        let textLength: Int
        let isStreaming: Bool

        init(
            sessionKey: String,
            blockID: String,
            contentRevision: ContentRevision,
            isStreaming: Bool
        ) {
            self.sessionKey = sessionKey
            self.blockID = blockID
            self.textHash = contentRevision.textHash
            self.textLength = contentRevision.textLength
            self.isStreaming = isStreaming
        }

        init(
            sessionKey: String,
            blockID: String,
            textHash: Int,
            textLength: Int,
            isStreaming: Bool
        ) {
            self.sessionKey = sessionKey
            self.blockID = blockID
            self.textHash = textHash
            self.textLength = textLength
            self.isStreaming = isStreaming
        }
    }

    private let lock = NSLock()
    private let maxEntries: Int
    private var values: [Key: NativeMarkdownDocument] = [:]
    private var order: [Key] = []

    nonisolated init(maxEntries: Int) {
        self.maxEntries = maxEntries
    }

    nonisolated func get(_ key: Key) -> NativeMarkdownDocument? {
        lock.lock()
        defer { lock.unlock() }
        guard let value = values[key] else { return nil }
        touch(key)
        return value
    }

    nonisolated func get(
        sessionKey: String,
        blockID: String,
        contentRevision: ContentRevision,
        isStreaming: Bool
    ) -> NativeMarkdownDocument? {
        get(
            Key(
                sessionKey: sessionKey,
                blockID: blockID,
                contentRevision: contentRevision,
                isStreaming: isStreaming
            )
        )
    }

    @discardableResult
    nonisolated func put(_ key: Key, document: NativeMarkdownDocument) -> NativeMarkdownDocument {
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

    @discardableResult
    nonisolated func put(
        sessionKey: String,
        blockID: String,
        contentRevision: ContentRevision,
        isStreaming: Bool,
        document: NativeMarkdownDocument
    ) -> NativeMarkdownDocument {
        put(
            Key(
                sessionKey: sessionKey,
                blockID: blockID,
                contentRevision: contentRevision,
                isStreaming: isStreaming
            ),
            document: document
        )
    }

    nonisolated func clear() {
        lock.lock()
        defer { lock.unlock() }
        values.removeAll()
        order.removeAll()
    }

    nonisolated var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return values.count
    }

    private nonisolated func touch(_ key: Key) {
        order.removeAll { $0 == key }
        order.append(key)
    }
}

/// Resolves text-block identity for decoration keys without MainActor UI builders.
nonisolated enum StreamingTextBlockIdentity {
    /// Block that currently has streaming text, falling back to message-scoped ID.
    static func resolveStreaming(message: Message) -> (blockID: String, plain: String)? {
        guard message.parts.contains(where: { part in
            if case .text(let textPart) = part { return textPart.isStreaming }
            return false
        }) else {
            return nil
        }
        if let run = textRun(in: message, requiringStreaming: true) {
            return run
        }
        return (
            PresentationBlockId.messageFallback(message.id),
            message.text
        )
    }

    /// Sole or primary text block for message-scoped bridge / LRU writes.
    static func resolvePrimary(message: Message) -> (blockID: String, plain: String)? {
        let runs = allTextRuns(in: message)
        if runs.isEmpty {
            let trimmed = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            return (PresentationBlockId.messageFallback(message.id), message.text)
        }
        if runs.count == 1 {
            return runs[0]
        }
        if let streaming = resolveStreaming(message: message) {
            return streaming
        }
        return runs[0]
    }

    static func isSoleTextBlock(message: Message) -> Bool {
        allTextRuns(in: message).count <= 1
    }

    private static func textRun(
        in message: Message,
        requiringStreaming: Bool
    ) -> (blockID: String, plain: String)? {
        let runs = detailedTextRuns(in: message)
        let selected: [(id: String, text: String, streaming: Bool)]?
        if requiringStreaming {
            selected = runs.last(where: { $0.contains(where: \.streaming) })
        } else {
            selected = runs.first
        }
        guard let selected else { return nil }
        let ids = selected.map(\.id)
        let plain = selected.map(\.text).joined(separator: "\n")
        return (PresentationBlockId.mergedText(ids), plain)
    }

    private static func allTextRuns(in message: Message) -> [(blockID: String, plain: String)] {
        detailedTextRuns(in: message).map { run in
            (
                PresentationBlockId.mergedText(run.map(\.id)),
                run.map(\.text).joined(separator: "\n")
            )
        }
    }

    private static func detailedTextRuns(
        in message: Message
    ) -> [[(id: String, text: String, streaming: Bool)]] {
        var runs: [[(id: String, text: String, streaming: Bool)]] = []
        var current: [(id: String, text: String, streaming: Bool)] = []
        var sawMissingID = false
        let allowToolCards = message.role == .assistant

        for part in message.parts {
            switch part {
            case .text(let textPart):
                let trimmed = textPart.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                guard let partId = textPart.id else {
                    sawMissingID = true
                    if !current.isEmpty {
                        runs.append(current)
                        current = []
                    }
                    continue
                }
                current.append((partId, trimmed, textPart.isStreaming))
            case .tool(let tool):
                // Match SessionChatTimelineBlocks: invisible tools do not split text runs.
                guard allowToolCards, isVisibleToolForDecoration(tool) else { continue }
                if !current.isEmpty {
                    runs.append(current)
                    current = []
                }
            case .file, .compaction, .reasoning, .notice:
                if !current.isEmpty {
                    runs.append(current)
                    current = []
                }
            }
        }
        if !current.isEmpty {
            runs.append(current)
        }
        if sawMissingID, runs.isEmpty {
            return []
        }
        return runs
    }

    /// Mirrors SessionChatTimelineBlocks visibility used when flushing text runs.
    private static func isVisibleToolForDecoration(_ tool: ToolPart) -> Bool {
        ToolPartIdentity.isVisibleOnTimeline(tool)
    }
}
