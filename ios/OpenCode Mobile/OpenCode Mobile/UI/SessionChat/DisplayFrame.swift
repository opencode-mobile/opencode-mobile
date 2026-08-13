import Foundation

/// Content-addressed revision for a text block's plain source.
/// Matches Phase 4/5 decoration keying (`textHash` + `textLength`).
nonisolated struct ContentRevision: Hashable, Sendable, Equatable {
    let textHash: Int
    let textLength: Int

    nonisolated static func of(_ plain: String) -> ContentRevision {
        ContentRevision(
            textHash: stableMarkdownSourceHash(plain),
            textLength: plain.count
        )
    }
}

/// One message inside an internal display frame, with presentation blocks.
nonisolated struct DisplayMessageBody: Sendable {
    let message: Message
    let isStreaming: Bool
    let blocks: [SessionChatTimelineBlock]
    /// Temporary bridge: message-scoped markdown while pipeline still publishes it.
    let renderedMarkdown: NativeMarkdownDocument?

    var id: String { message.id }
}

/// Internal display handoff for the pure timeline projector.
/// Public observation remains `DisplayMessageListUpdate` / `DisplayMessage`.
nonisolated struct DisplayFrame: Sendable {
    let sessionKey: String
    let frameRevision: UInt64
    let sourceRevision: UInt64
    let messages: [DisplayMessageBody]
}

nonisolated enum DisplayFrameBuilder {
    /// Test/diagnostic: last `build` call's thread. Cleared only by tests.
    nonisolated(unsafe) static var lastBuildWasOnMainThread: Bool?

    /// Builds an internal frame from the public list-shaped display snapshot.
    /// Attaches message-root markdown onto the sole text block as a migration bridge.
    static func build(
        sessionKey: String,
        frameRevision: UInt64,
        sourceRevision: UInt64,
        displayMessages: [DisplayMessage],
        serverCommands: [ServerCommand] = [],
        collapseConsecutiveToolCalls: Bool = false
    ) -> DisplayFrame {
        // pthread_main_np avoids the async-context warning on Thread.isMainThread.
        lastBuildWasOnMainThread = pthread_main_np() != 0
        let sorted =
            displayMessages
                .enumerated()
                .sorted { left, right in
                    let leftTime = left.element.message.sentAtEpochMs
                    let rightTime = right.element.message.sentAtEpochMs
                    if let leftTime, let rightTime {
                        if leftTime != rightTime { return leftTime < rightTime }
                        return left.element.message.id < right.element.message.id
                    }
                    return left.offset < right.offset
                }
                .map(\.element)

        var bodies: [DisplayMessageBody] = []
        bodies.reserveCapacity(sorted.count)
        for display in sorted {
            let message = display.message
            var blocks = SessionChatTimelineBlocks.buildOrFallback(
                role: message.role,
                parts: message.parts,
                messageId: message.id,
                fallbackText: message.text,
                collapseConsecutiveToolCalls: collapseConsecutiveToolCalls
            )
            blocks = SessionChatTimelineBlocks.collapseCommandInvocations(
                role: message.role,
                blocks: blocks,
                commands: serverCommands
            )
            guard !blocks.isEmpty else { continue }
            blocks = attachCachedBlockMarkdown(
                blocks,
                sessionKey: sessionKey,
                isStreaming: display.isStreaming
            )
            let rootMarkdown = matchingMarkdown(display.renderedMarkdown, message: message, blocks: blocks)
            blocks = attachSoleTextMarkdown(blocks, rootMarkdown, message: message)
            bodies.append(
                DisplayMessageBody(
                    message: message,
                    isStreaming: display.isStreaming,
                    blocks: blocks,
                    renderedMarkdown: rootMarkdown
                )
            )
        }
        return DisplayFrame(
            sessionKey: sessionKey,
            frameRevision: frameRevision,
            sourceRevision: sourceRevision,
            messages: bodies
        )
    }

    /// Attach per-block docs from `BlockDecorationCache` when present.
    static func attachCachedBlockMarkdown(
        _ blocks: [SessionChatTimelineBlock],
        sessionKey: String,
        isStreaming: Bool,
        cache: BlockDecorationCache = .shared
    ) -> [SessionChatTimelineBlock] {
        var result = blocks
        for index in result.indices {
            guard case .text(let blockID, let text, let existing) = result[index] else { continue }
            if existing != nil { continue }
            let revision = ContentRevision.of(text)
            guard let document = cache.get(
                sessionKey: sessionKey,
                blockID: blockID,
                contentRevision: revision,
                isStreaming: isStreaming
            ) else { continue }
            result[index] = .text(blockID: blockID, text: text, renderedMarkdown: document)
        }
        return result
    }

    /// Bridge: when the pipeline still publishes message-root markdown, copy it
    /// onto the only text block so views can prefer per-block docs.
    ///
    /// Skip when slash-command collapse rewrote the sole text away from
    /// `Message.text` so the expanded template document is not pasted onto
    /// `[/name] args`. Streaming may keep a stale previous document whose hash
    /// no longer matches growing text — that remains intentional.
    static func attachSoleTextMarkdown(
        _ blocks: [SessionChatTimelineBlock],
        _ markdown: NativeMarkdownDocument?,
        message: Message
    ) -> [SessionChatTimelineBlock] {
        guard let markdown else { return blocks }
        let textIndices = blocks.indices.filter { index in
            if case .text = blocks[index] { return true }
            return false
        }
        guard textIndices.count == 1, let index = textIndices.first else { return blocks }
        guard case .text(let blockID, let text, let existing) = blocks[index] else { return blocks }
        if existing != nil { return blocks }
        if isCollapsedAwayFromMessageText(message: message, soleText: text) { return blocks }
        var result = blocks
        result[index] = .text(blockID: blockID, text: text, renderedMarkdown: markdown)
        return result
    }

    static func matchingMarkdown(
        _ markdown: NativeMarkdownDocument?,
        message: Message,
        blocks: [SessionChatTimelineBlock]
    ) -> NativeMarkdownDocument? {
        guard let markdown else { return nil }
        let texts = blocks.compactMap { block -> String? in
            if case .text(_, let text, _) = block { return text }
            return nil
        }
        guard texts.count == 1, let text = texts.first else { return markdown }
        return isCollapsedAwayFromMessageText(message: message, soleText: text) ? nil : markdown
    }

    static func isCollapsedAwayFromMessageText(message: Message, soleText: String) -> Bool {
        message.role == .user && soleText != message.text
    }
}
