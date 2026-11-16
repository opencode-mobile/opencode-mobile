import Foundation
import Testing
@testable import OpenCode_Mobile

/// #1033: a message timeline flush is encoded one message at a time and keeps the newest messages
/// within a byte budget, whole.
struct SessionCacheMessageBudgetTests {
    @Test func encodeBuildsOneMessageAtATimeAndKeepsTheNewestThatFit() throws {
        let messages = Self.transcript(count: 40, outputBytes: 200_000)
        var pieces: [Int] = []

        let payload = try SessionCacheCodec.encodeMessages(
            messages,
            budgetBytes: 1_000_000,
            onMessageEncoded: { pieces.append($0) }
        )

        // Every block built is one message's JSON, never the whole 8 MB transcript.
        #expect((pieces.max() ?? 0) < 250_000)
        #expect(payload.utf8.count < 1_000_200)
        let decoded = try #require(SessionCacheCodec.decodeMessages(payload))
        // Only the kept messages were encoded, and at most one more to learn it did not fit.
        #expect((0 ... 1).contains(pieces.count - decoded.count))
        // The newest messages, whole: nothing inside a kept message is cut.
        #expect(decoded == Array(messages.suffix(decoded.count)))
    }

    @Test func encodeUnderTheBudgetRoundTripsEveryMessage() throws {
        let messages = Self.transcript(count: 3, outputBytes: 100)
        let payload = try SessionCacheCodec.encodeMessages(messages)
        #expect(SessionCacheCodec.decodeMessages(payload) == messages)
        #expect(payload.contains("\"schemaVersion\":1"))
    }

    @Test func encodeDoesNotEncodeAMessageWhoseTextAloneIsOverTheBudget() throws {
        // One huge tool output must not build its JSON just to learn it does not fit.
        let messages = Self.transcript(count: 3, outputBytes: 50_000)
        var pieces: [Int] = []
        let payload = try SessionCacheCodec.encodeMessages(messages, budgetBytes: 40_000, onMessageEncoded: { pieces.append($0) })
        #expect(pieces.isEmpty)
        #expect(SessionCacheCodec.decodeMessages(payload) == [])
    }

    @Test func encodeDoesNotEncodeAMessageWhoseToolInputAloneIsOverTheBudget() throws {
        // A large write carries its content in the input, not the output.
        let write = Message(
            id: "msg-write",
            role: .assistant,
            text: "",
            parts: [
                .tool(
                    ToolPart(
                        id: "prt-write",
                        toolName: "write",
                        status: .completed,
                        input: ["filePath": .string("src/Big.kt"), "content": .string(String(repeating: "x", count: 50_000))]
                    )
                ),
            ],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
        var pieces: [Int] = []
        let payload = try SessionCacheCodec.encodeMessages([write], budgetBytes: 40_000, onMessageEncoded: { pieces.append($0) })
        #expect(pieces.isEmpty)
        #expect(SessionCacheCodec.decodeMessages(payload) == [])
    }

    @Test func encodeKeepsNoMessagesWhenTheNewestIsOverTheBudget() throws {
        // The floor is under the budget, the encoded JSON over it: still not kept.
        let messages = Self.transcript(count: 3, outputBytes: 50_000)
        let payload = try SessionCacheCodec.encodeMessages(messages, budgetBytes: 50_050)
        #expect(SessionCacheCodec.decodeMessages(payload) == [])
    }

    private static func transcript(count: Int, outputBytes: Int) -> [Message] {
        (1 ... count).map { index in
            Message(
                id: "msg-\(index)",
                role: .assistant,
                text: "",
                parts: [
                    .text(TextPart(text: "Step \(index)", isStreaming: false)),
                    .tool(
                        ToolPart(
                            id: "prt-\(index)",
                            toolName: "write",
                            status: .completed,
                            input: ["filePath": .string("src/File\(index).kt")],
                            output: String(String(repeating: "line \(index)\n", count: outputBytes / 7).prefix(outputBytes))
                        )
                    ),
                ],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            )
        }
    }
}
