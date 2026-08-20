import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct DisplayMessagePipelinePacingTests {
    @Test func streamingMaxBatchUsesConfiguredDuration() {
        #expect(streamingPrepareDebounceNs == 80_000_000)
        #expect(streamingPrepareMaxBatchDuration == .milliseconds(160))
    }

    @Test func largeStreamingMessagesSkipMarkdownPreparation() async {
        let text = String(repeating: "# Heading\n\nSome prose. ", count: 500)
        #expect(text.count >= MessageContentPreparer.streamingPlainTextCharacterThreshold)

        let streamingMessage = Message(
            id: "msg-stream",
            role: .assistant,
            text: text,
            parts: [.text(TextPart(text: text, isStreaming: true))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )

        let preparer = MessageContentPreparer()
        let streaming = await preparer.prepareMessage(streamingMessage, mode: .streaming)
        #expect(streaming.renderedMarkdown == nil)
        #expect(streaming.isStreaming)

        let finalMessage = Message(
            id: "msg-stream",
            role: .assistant,
            text: text,
            parts: [.text(TextPart(text: text, isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        let finished = await preparer.prepareMessage(finalMessage, mode: .final)
        #expect(finished.renderedMarkdown != nil)
        #expect(!finished.isStreaming)
    }

    @Test func largeHistoryTriggersPlainFirstPublishGate() {
        let text = String(repeating: "# Heading\n\nSome prose with `code`. ", count: 200)
        #expect(text.count >= MessageContentPreparer.historyPlainFirstMarkdownCharacterThreshold)
        let messages = [
            Message(
                id: "msg-1",
                role: .assistant,
                text: text,
                parts: [.text(TextPart(text: text, isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        #expect(shouldPlainFirstHistoryPublish(messages: messages, previousByID: [:]))
        let alreadyPrepared = DisplayMessage(message: messages[0], renderedMarkdown: nil)
        // Gate still allows plain-first when previous frames had no markdown docs.
        #expect(
            shouldPlainFirstHistoryPublish(
                messages: messages,
                previousByID: ["msg-1": alreadyPrepared]
            )
        )
        let cachedDoc = NativeMarkdownDocument(
            sourceHash: 1,
            blocks: [.paragraph([.text("cached")])]
        )
        let fullyCached = DisplayMessage(message: messages[0], renderedMarkdown: cachedDoc)
        #expect(
            hasCompletePreparedMarkdownCache(
                messages: messages,
                previousByID: ["msg-1": fullyCached]
            )
        )
        #expect(
            !shouldPlainFirstHistoryPublish(
                messages: messages,
                previousByID: ["msg-1": fullyCached]
            )
        )
    }
}
