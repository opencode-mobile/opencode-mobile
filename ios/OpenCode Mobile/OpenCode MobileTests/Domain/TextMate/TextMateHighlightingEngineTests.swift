import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct TextMateHighlightingEngineTests {
    @Test func deterministicFenceIdentityAndExactSourceAttachment() {
        let document = NativeMarkdownDocument(
            sourceHash: 17,
            blocks: [
                .paragraph([.text("before")]),
                .codeBlock(language: "swift", code: "let value = 1", spans: []),
            ]
        )
        let revision = TextMateHighlightRevision(value: 4)
        let first = document.textMateFences(ownerKey: "message", revision: revision, policyEpoch: 2)
        let second = document.textMateFences(ownerKey: "message", revision: revision, policyEpoch: 2)
        let fence = first[0]
        let stale = result(
            for: fence,
            source: "let stale = 1",
            spans: [NativeCodeSpan(start: 0, end: 3, role: .keyword)]
        )

        #expect(first[0].key == second[0].key)
        if case .rejected(let reason) = document.attachingTextMate(fence: fence, result: stale) {
            #expect(reason == .sourceChanged)
        } else {
            Issue.record("Exact-source mismatch must be rejected.")
        }

        let current = result(
            for: fence,
            source: fence.source,
            spans: [NativeCodeSpan(start: 0, end: 3, role: .keyword)]
        )
        guard case .attached(let attached) = document.attachingTextMate(fence: fence, result: current)
        else {
            Issue.record("Current result should attach.")
            return
        }
        guard case .codeBlock(_, _, let originalSpans) = document.blocks[1],
              case .codeBlock(_, _, let attachedSpans) = attached.blocks[1]
        else {
            Issue.record("Expected code blocks.")
            return
        }
        #expect(originalSpans.isEmpty)
        #expect(attachedSpans == current.spans)
        #expect(attached.renderTreeVersion == document.renderTreeVersion + 1)
        #expect(attached.sourceHash == document.sourceHash)
    }

    @Test func preparerUsesInjectedEngineOffMainAndMonotonicRevisions() async {
        let preferences = MarkdownHighlightPreferences.shared
        let wasEnabled = preferences.textMateEnabled
        preferences.setTextMateEnabled(true)
        defer { preferences.setTextMateEnabled(wasEnabled) }

        let engine = RecordingTextMateEngine()
        let preparer = MessageContentPreparer(
            textMateLogbook: DiscardingTextMateLogbook(),
            textMateEngine: engine
        )
        let firstMarkdown = "```swift\nlet first = 1\n```"
        let secondMarkdown = "```swift\nlet second = 2\n```"

        _ = await preparer.prepareMessage(message(id: "one", text: firstMarkdown))
        _ = await preparer.prepareMessage(message(id: "two", text: secondMarkdown))

        let snapshot = await engine.snapshot()
        #expect(snapshot.requests.count == 2)
        #expect(snapshot.requests[0].revision.value < snapshot.requests[1].revision.value)
        #expect(snapshot.ranOnMain == [false, false])
        #expect(snapshot.maxActive == 1)
    }

    @Test func newerRevisionCancelsTheRunningJob() async throws {
        let engine = KMPTextMateHighlightingEngine()
        let chunk = """
        export function render(value: number): string {
          const label = `item ${value}`; // trailing comment
          return label.padStart(12, "-");
        }

        """
        let slowSource = String(repeating: chunk, count: 1_500)
        let first = Task {
            try await engine.highlight(request(key: "cancel", revision: 1, source: slowSource))
        }
        // Long enough for the first job to start running on the executor.
        try await Task.sleep(for: .milliseconds(200))

        let second = try await engine.highlight(request(key: "cancel", revision: 2, source: "let value = 1"))
        let superseded = try await first.value

        #expect(superseded.outcome == "Cancelled")
        // Set only when a running job, not a queued one, was cancelled.
        #expect(superseded.observation.cancelRequestedToStopNanoseconds != nil)
        #expect(second.outcome == "Highlighted")
    }

    @Test func normalTrimAfterMemoryWarningRestoresHistoryHighlighting() async throws {
        let engine = KMPTextMateHighlightingEngine()
        await engine.trim(.critical)

        let duringWarning = try await engine.highlight(
            request(key: "history", revision: 1, source: "let value = 1", intent: .visibleHistory)
        )
        #expect(duringWarning.outcome == "BudgetExceeded")
        #expect(duringWarning.observation.schedulingReason == "Trim")

        await engine.trim(.normal)

        let afterForeground = try await engine.highlight(
            request(key: "history", revision: 2, source: "let value = 1", intent: .backgroundHistory)
        )
        #expect(afterForeground.outcome == "Highlighted")
    }

    private func request(
        key: String,
        revision: Int64,
        source: String,
        intent: TextMateHighlightIntent = .interactiveFinal
    ) -> TextMateHighlightRequest {
        TextMateHighlightRequest(
            key: TextMateCodeBlockKey(value: key),
            revision: TextMateHighlightRevision(value: revision),
            language: "typescript",
            source: source,
            intent: intent,
            policyEpoch: 1,
            messageCharacters: source.utf16.count,
            fenceIndex: 0,
            fenceCount: 1
        )
    }

    private func message(id: String, text: String) -> Message {
        Message(
            id: id,
            role: .assistant,
            text: text,
            parts: [.text(TextPart(text: text, isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
    }

    private func result(
        for fence: TextMateMarkdownFence,
        source: String,
        spans: [NativeCodeSpan]
    ) -> TextMateHighlightResult {
        TextMateHighlightResult(
            key: fence.key,
            revision: fence.revision,
            source: source,
            canonicalLanguage: "Swift",
            policyEpoch: fence.policyEpoch,
            spans: spans,
            outcome: "Highlighted",
            diagnostics: [],
            observation: observation(for: fence, spanCount: spans.count)
        )
    }

    private func observation(
        for fence: TextMateMarkdownFence,
        spanCount: Int
    ) -> NativeTextMateParseObservation {
        NativeTextMateParseObservation(
            schemaVersion: 1,
            requestedLanguage: fence.language,
            canonicalLanguage: "Swift",
            outcome: "Highlighted",
            messageCharacters: fence.source.utf16.count,
            fenceCharacters: fence.source.utf16.count,
            fenceLines: 1,
            fenceIndex: fence.fenceIndex,
            fenceCount: 1,
            spanCount: spanCount,
            backend: "test",
            totalNanoseconds: 1,
            stages: [],
            diagnosticKinds: []
        )
    }
}

private actor RecordingTextMateEngine: TextMateHighlighting {
    struct Snapshot: Sendable {
        let requests: [TextMateHighlightRequest]
        let ranOnMain: [Bool]
        let maxActive: Int
    }

    private var requests: [TextMateHighlightRequest] = []
    private var ranOnMain: [Bool] = []
    private var active = 0
    private var maxActive = 0

    func highlight(_ request: TextMateHighlightRequest) throws -> TextMateHighlightResult {
        active += 1
        maxActive = max(maxActive, active)
        requests.append(request)
        ranOnMain.append(Thread.isMainThread)
        active -= 1
        return TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: "Swift",
            policyEpoch: request.policyEpoch,
            spans: [NativeCodeSpan(start: 0, end: min(3, request.source.utf16.count), role: .keyword)],
            outcome: "Highlighted",
            diagnostics: [],
            observation: NativeTextMateParseObservation(
                schemaVersion: 1,
                requestedLanguage: request.language,
                canonicalLanguage: "Swift",
                outcome: "Highlighted",
                messageCharacters: request.messageCharacters,
                fenceCharacters: request.source.utf16.count,
                fenceLines: 1,
                fenceIndex: request.fenceIndex,
                fenceCount: request.fenceCount,
                spanCount: 1,
                backend: "test",
                totalNanoseconds: 1,
                stages: [],
                diagnosticKinds: []
            )
        )
    }

    func snapshot() -> Snapshot {
        Snapshot(requests: requests, ranOnMain: ranOnMain, maxActive: maxActive)
    }
}

private struct DiscardingTextMateLogbook: TextMateLogbookRecording {
    func submit(_ observations: [NativeTextMateParseObservation]) {}
}
