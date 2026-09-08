import Foundation
import Testing
@testable import OpenCode_Mobile

struct ThoughtPartChromeTests {
    @Test func paintLabel_matchesStreamingSettledAndHidden() {
        #expect(ThoughtPartChrome.paintLabel(ReasoningPart(text: "", isStreaming: true)) == "hidden")
        #expect(ThoughtPartChrome.paintLabel(ReasoningPart(text: "done", isStreaming: false)) == "thought")
        #expect(ThoughtPartChrome.paintLabel(ReasoningPart(text: "", isStreaming: false)) == "hidden")
        #expect(
            ThoughtPartChrome.paintLabel(
                ReasoningPart(text: "", isStreaming: false, timeEndEpochMs: 2)
            ) == "hidden"
        )
        #expect(ThoughtPartChrome.paintLabel(ReasoningPart(text: "working", isStreaming: true)) == "thinking")
    }

    @Test func summarize_marksStreamingThought() {
        let summary = ThoughtPartChrome.summarize(
            messages: [
                Message(id: "u1", role: .user, text: "Hi", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(
                    id: "a1",
                    role: .assistant,
                    text: "",
                    parts: [.reasoning(ReasoningPart(id: "r1", text: "", isStreaming: true))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: 2
                ),
            ],
            paintedCount: 1
        )
        #expect(summary.contains("store=2"))
        #expect(summary.contains("painted=1"))
        #expect(summary.contains("thinking=0"))
        #expect(summary.contains("hidden=1"))
        #expect(summary.contains("last=hidden"))
        #expect(summary.contains("streaming=1"))
    }

    @Test func summarize_hidesBlankCompletedReasoning() {
        let summary = ThoughtPartChrome.summarize(
            messages: [
                Message(id: "u1", role: .user, text: "Hi", model: nil, mode: nil, sentAtEpochMs: 1),
                Message(
                    id: "a1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .reasoning(
                            ReasoningPart(id: "r1", text: "", isStreaming: false, timeEndEpochMs: 2)
                        ),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: 2
                ),
            ],
            paintedCount: 0
        )
        #expect(summary.contains("thinking=0"))
        #expect(summary.contains("hidden=1"))
        #expect(summary.contains("omitted=1"))
        #expect(summary.contains("last=hidden"))
        #expect(summary.contains("end=1"))
    }
}
