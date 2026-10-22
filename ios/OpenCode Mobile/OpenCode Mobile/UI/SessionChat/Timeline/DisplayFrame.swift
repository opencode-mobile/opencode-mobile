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
        collapseConsecutiveToolCalls: Bool = false,
        chipTimeline: Bool = false,
        stepRail: Bool = false,
        activityCard: Bool = false,
        childSessions: [SessionChatChildSessionCandidate] = []
    ) -> DisplayFrame {
        // pthread_main_np avoids the async-context warning on Thread.isMainThread.
        lastBuildWasOnMainThread = pthread_main_np() != 0
        let ordered = linkChildSessions(orderedDisplayMessages(displayMessages), children: childSessions)

        var bodies: [DisplayMessageBody] = []
        bodies.reserveCapacity(ordered.count)
        for display in ordered {
            let message = display.message
            var blocks = SessionChatTimelineBlocks.buildOrFallback(
                role: message.role,
                parts: message.parts,
                messageId: message.id,
                fallbackText: message.text,
                collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
                chipTimeline: chipTimeline,
                stepRail: stepRail,
                activityCard: activityCard
            )
            blocks = SessionChatTimelineBlocks.collapseCommandInvocations(
                role: message.role,
                blocks: blocks,
                commands: serverCommands
            )
            blocks = SessionChatTimelineBlocks.ensureLiveRow(
                message: message,
                isStreaming: display.isStreaming,
                blocks: blocks
            )
            guard !blocks.isEmpty || message.failure != nil else { continue }
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
        bodies = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: collapseConsecutiveToolCalls,
            chipTimeline: chipTimeline,
            stepRail: stepRail,
            activityCard: activityCard
        )
        if chipTimeline || stepRail || activityCard {
            // Merged runs rebuilt their paragraphs; pick up per-block cache hits again.
            bodies = bodies.map { body in
                DisplayMessageBody(
                    message: body.message,
                    isStreaming: body.isStreaming,
                    blocks: attachCachedBlockMarkdown(
                        body.blocks,
                        sessionKey: sessionKey,
                        isStreaming: body.isStreaming
                    ),
                    renderedMarkdown: body.renderedMarkdown
                )
            }
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
            guard let content = result[index].textContent, content.renderedMarkdown == nil else { continue }
            let revision = ContentRevision.of(content.text)
            guard let document = cache.get(
                sessionKey: sessionKey,
                blockID: content.blockID,
                contentRevision: revision,
                isStreaming: isStreaming
            ) else { continue }
            result[index] = result[index].withRenderedMarkdown(document)
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
        let textIndices = blocks.indices.filter { blocks[$0].textContent != nil }
        guard textIndices.count == 1, let index = textIndices.first else { return blocks }
        guard let content = blocks[index].textContent else { return blocks }
        if content.renderedMarkdown != nil { return blocks }
        if isCollapsedAwayFromMessageText(message: message, soleText: content.text) { return blocks }
        var result = blocks
        result[index] = blocks[index].withRenderedMarkdown(markdown)
        return result
    }

    static func matchingMarkdown(
        _ markdown: NativeMarkdownDocument?,
        message: Message,
        blocks: [SessionChatTimelineBlock]
    ) -> NativeMarkdownDocument? {
        guard let markdown else { return nil }
        let texts = blocks.compactMap { $0.textContent?.text }
        guard texts.count == 1, let text = texts.first else { return markdown }
        return isCollapsedAwayFromMessageText(message: message, soleText: text) ? nil : markdown
    }

    static func isCollapsedAwayFromMessageText(message: Message, soleText: String) -> Bool {
        message.role == .user && soleText != message.text
    }

    /// Oldest-to-newest by `time.created`, without moving a user the store already
    /// placed before an assistant (v2 inbox user rows can be persisted after the reply starts).
    private static func linkChildSessions(
        _ displayMessages: [DisplayMessage],
        children: [SessionChatChildSessionCandidate]
    ) -> [DisplayMessage] {
        let linked = SessionChatChildSessionLinker.link(displayMessages.map(\.message), children: children)
        return zip(displayMessages, linked).map { display, message in
            guard message != display.message else { return display }
            return DisplayMessage(
                message: message,
                renderedMarkdown: display.renderedMarkdown,
                awaitingTextMateUpgrade: display.awaitingTextMateUpgrade,
                highlightEpoch: display.highlightEpoch
            )
        }
    }

    static func orderedDisplayMessages(_ displayMessages: [DisplayMessage]) -> [DisplayMessage] {
        let inputIndex = Dictionary(
            uniqueKeysWithValues: displayMessages.enumerated().map { ($0.element.id, $0.offset) }
        )
        var ordered =
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

        var i = 0
        while i < ordered.count {
            let current = ordered[i]
            guard current.message.role == .assistant else {
                i += 1
                continue
            }
            let assistantInput = inputIndex[current.id] ?? i
            var displaced: [DisplayMessage] = []
            var rest: [DisplayMessage] = []
            for message in ordered[(i + 1)...] {
                let messageInput = inputIndex[message.id] ?? .max
                if message.message.role == .user && messageInput < assistantInput {
                    displaced.append(message)
                } else {
                    rest.append(message)
                }
            }
            guard !displaced.isEmpty else {
                i += 1
                continue
            }
            displaced.sort { (inputIndex[$0.id] ?? 0) < (inputIndex[$1.id] ?? 0) }
            ordered = Array(ordered[..<i]) + displaced + [current] + rest
            i += displaced.count + 1
        }
        return ordered
    }
}
