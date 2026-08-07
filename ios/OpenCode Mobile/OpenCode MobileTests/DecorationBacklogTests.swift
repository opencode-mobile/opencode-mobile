import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct DecorationBacklogTests {
    @Test func dropsLowestPriorityUnderCountPressure() {
        let backlog = DecorationBacklog()
        for index in 0..<DecorationBacklogPolicy.pendingCountCapacity {
            #expect(
                backlog.tryEnqueue(
                    job(id: "history-\(index)", text: String(repeating: "x", count: 10), priority: 0)
                )
            )
        }
        #expect(backlog.size == DecorationBacklogPolicy.pendingCountCapacity)
        #expect(
            backlog.tryEnqueue(
                job(id: "streaming", text: String(repeating: "x", count: 10), priority: 2)
            )
        )
        #expect(backlog.size == DecorationBacklogPolicy.pendingCountCapacity)

        var ids: Set<String> = []
        while let next = backlog.poll() {
            ids.insert(next.messageID)
        }
        #expect(ids.contains("streaming"))
        #expect(!ids.contains("history-0"))
    }

    @Test func dropsLowestPriorityUnderCharCostPressure() {
        let backlog = DecorationBacklog()
        let large = DecorationBacklogPolicy.pendingCharCostBudget / 2 + 1
        #expect(
            backlog.tryEnqueue(
                job(id: "history-large", text: String(repeating: "x", count: large), priority: 0)
            )
        )
        #expect(
            backlog.tryEnqueue(
                job(id: "streaming-large", text: String(repeating: "x", count: large), priority: 2)
            )
        )
        #expect(backlog.size == 1)
        let kept = backlog.poll()
        #expect(kept?.messageID == "streaming-large")
        #expect(backlog.poll() == nil)
    }

    @Test func replaceStreamingKeepsLatestCandidateOnly() {
        let backlog = DecorationBacklog()
        #expect(backlog.replaceStreamingJob(job(id: "msg-1", text: "abcd", priority: 2)))
        #expect(backlog.replaceStreamingJob(job(id: "msg-1", text: "abcde", priority: 2)))
        #expect(backlog.size == 1)
        let next = backlog.poll()
        #expect(next?.plainText == "abcde")
        #expect(next?.textLength == 5)
        #expect(next?.blockID == PresentationBlockId.messageFallback("msg-1"))
    }

    @Test func replaceStreamingCoalescesByBlockID() {
        let backlog = DecorationBacklog()
        let first = job(id: "msg-1", text: "one", priority: 2, blockID: "p:a")
        let second = job(id: "msg-1", text: "two", priority: 2, blockID: "p:a")
        let other = job(id: "msg-2", text: "other", priority: 2, blockID: "p:b")
        #expect(backlog.replaceStreamingJob(first))
        #expect(backlog.replaceStreamingJob(other))
        #expect(backlog.replaceStreamingJob(second))
        #expect(backlog.size == 2)
        let polled = [backlog.poll(), backlog.poll()].compactMap { $0 }
        #expect(polled.map(\.plainText).sorted() == ["other", "two"])
    }

    private func job(
        id: String,
        text: String,
        priority: Int,
        blockID: String? = nil
    ) -> DecorationJob {
        DecorationJob(
            generation: 1,
            sessionKey: "ses-1",
            messageID: id,
            blockID: blockID ?? PresentationBlockId.messageFallback(id),
            contentRevision: ContentRevision.of(text),
            plainText: text,
            isStreaming: priority == 2,
            messages: [
                Message(
                    id: id,
                    role: .assistant,
                    text: text,
                    parts: [.text(TextPart(text: text, isStreaming: priority == 2))],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ],
            priority: priority
        )
    }
}
