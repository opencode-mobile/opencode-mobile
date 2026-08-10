import Foundation

/// Raw message snapshots that feed `DisplayMessagePipeline`. Production wires
/// `MessageSync`; tests use a controllable source so pacing and preparation can
/// be exercised without transport.
protocol DisplayMessageSnapshotSource: AnyObject, Sendable {
    func observeMessages(sessionID: String, directory: String?) -> AsyncStream<MessageListUpdate>
}

/// Markdown preparation used by the display pipeline. Production uses
/// `MessageContentPreparer`; tests can suspend completion and record mode.
protocol DisplayMessagePreparing: AnyObject, Sendable {
    func prepareMessages(
        _ messages: [Message],
        previousByID: [String: DisplayMessage],
        mode: MessagePreparationMode
    ) async -> [DisplayMessage]

    func prepareMessage(
        _ message: Message,
        previous: DisplayMessage?,
        mode: MessagePreparationMode
    ) async -> DisplayMessage

    func prepareTextBlock(
        blockID: String,
        plain: String,
        isStreaming: Bool,
        sessionKey: String,
        mode: MessagePreparationMode
    ) async -> NativeMarkdownDocument?

    /// Format-first streaming parse. TextMate on → empty code spans; TextMate off →
    /// semantic fallback. Does not touch the preparer LRU or TextMate.
    func prepareStructureOnlyTextBlock(
        blockID: String,
        plain: String,
        sessionKey: String
    ) async -> NativeMarkdownDocument?
}

/// Monotonic clock and sleep used for streaming quiet-window / max-batch pacing.
protocol DisplayMessagePipelineClock: AnyObject, Sendable {
    var now: Duration { get }
    func sleep(for duration: Duration) async
}

nonisolated final class SystemDisplayMessagePipelineClock: DisplayMessagePipelineClock, @unchecked Sendable {
    private let startedAt = ContinuousClock.now

    var now: Duration {
        ContinuousClock.now - startedAt
    }

    func sleep(for duration: Duration) async {
        try? await Task.sleep(for: duration)
    }
}

extension MessageSync: DisplayMessageSnapshotSource {}

extension MessageContentPreparer: DisplayMessagePreparing {}
