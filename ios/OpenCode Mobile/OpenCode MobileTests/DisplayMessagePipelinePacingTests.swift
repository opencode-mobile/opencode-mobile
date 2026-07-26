import Foundation
import Testing
@testable import OpenCode_Mobile

struct DisplayMessagePipelinePacingTests {
    @Test func streamingMaxBatchStaysFlatAsMessageGrows() {
        let small = streamingPrepareMaxBatch(forStreamingChars: 1_000)
        let medium = streamingPrepareMaxBatch(forStreamingChars: 10_000)
        let large = streamingPrepareMaxBatch(forStreamingChars: 30_000)
        let huge = streamingPrepareMaxBatch(forStreamingChars: 80_000)

        #expect(small == .milliseconds(48))
        #expect(medium == small)
        #expect(large == small)
        #expect(huge == small)
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
        let streaming = await preparer.prepareMessage(streamingMessage)
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
        let finished = await preparer.prepareMessage(finalMessage)
        #expect(finished.renderedMarkdown != nil)
        #expect(!finished.isStreaming)
    }

    @Test fun largeHistoryTriggersPlainFirstPublishGate() {
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
    }
}
