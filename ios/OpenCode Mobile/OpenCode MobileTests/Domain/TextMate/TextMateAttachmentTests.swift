import Foundation
import Testing
@testable import OpenCode_Mobile

/// Fence identity, pure attachment, and stale-result rejection (`attachingTextMate`), plus how
/// `MessageContentPreparer` drives the highlighter for a settled message.
@MainActor
struct TextMateAttachmentTests {
    private static let document = NativeMarkdownDocument(
        sourceHash: 17,
        blocks: [
            .paragraph([.text("before")]),
            .codeBlock(language: "swift", code: "let value = 1", spans: []),
            .paragraph([.text("between")]),
            .codeBlock(language: "json", code: "[1]", spans: []),
        ]
    )

    @Test func fenceIdentityIsDeterministic() {
        let revision = TextMateHighlightRevision(value: 4)
        let first = Self.document.textMateFences(ownerKey: "message", revision: revision, policyEpoch: 2)
        let second = Self.document.textMateFences(ownerKey: "message", revision: revision, policyEpoch: 2)
        let other = Self.document.textMateFences(ownerKey: "other", revision: revision, policyEpoch: 2)

        #expect(first.map(\.key) == second.map(\.key))
        #expect(first.map(\.blockIndex) == [1, 3])
        #expect(first.map(\.fenceIndex) == [0, 1])
        #expect(first[0].key != first[1].key)
        #expect(first[0].key != other[0].key)
        #expect(first[0].key == .deterministic(ownerKey: "message", blockIndex: 1, fenceIndex: 0))
        // The owner key is length-prefixed, so "a:1" + block 2 never collides with "a" + block 1.
        #expect(TextMateCodeBlockKey.deterministic(ownerKey: "a:1", blockIndex: 2, fenceIndex: 0)
            != TextMateCodeBlockKey.deterministic(ownerKey: "a", blockIndex: 1, fenceIndex: 2))
    }

    @Test func attachmentIsPureAndKeepsTheSourceHash() {
        let fence = Self.fence()
        let spans = [NativeCodeSpan(start: 0, end: 3, role: .keyword)]
        guard case .attached(let attached) = Self.document.attachingTextMate(fence: fence, result: Self.result(for: fence, spans: spans))
        else {
            Issue.record("A current result should attach.")
            return
        }
        guard case .codeBlock(_, _, let originalSpans) = Self.document.blocks[1],
              case .codeBlock(let language, let code, let attachedSpans) = attached.blocks[1]
        else {
            Issue.record("Expected code blocks.")
            return
        }
        #expect(originalSpans.isEmpty)
        #expect(attachedSpans == spans)
        #expect(language == "swift")
        #expect(code == "let value = 1")
        #expect(attached.renderTreeVersion == Self.document.renderTreeVersion + 1)
        #expect(attached.sourceHash == Self.document.sourceHash)
        // Other fences are untouched.
        guard case .codeBlock(let otherLanguage, let otherCode, let otherSpans) = attached.blocks[3] else {
            Issue.record("Expected the second fence.")
            return
        }
        #expect(otherLanguage == "json" && otherCode == "[1]" && otherSpans.isEmpty)
    }

    @Test func aChangedDocumentIsRejected() {
        let fence = Self.fence()
        let changed = NativeMarkdownDocument(sourceHash: 18, blocks: Self.document.blocks)
        #expect(Self.rejection(changed, fence, Self.result(for: fence)) == .documentChanged)
    }

    @Test func aMissingFenceIsRejected() {
        let fence = Self.fence()
        let noCode = NativeMarkdownDocument(sourceHash: 17, blocks: [.paragraph([.text("before")]), .paragraph([.text("now prose")])])
        let shorter = NativeMarkdownDocument(sourceHash: 17, blocks: [.paragraph([.text("before")])])
        #expect(Self.rejection(noCode, fence, Self.result(for: fence)) == .fenceMissing)
        #expect(Self.rejection(shorter, fence, Self.result(for: fence)) == .fenceMissing)
    }

    @Test func aChangedIdentityIsRejected() {
        let fence = Self.fence()
        let result = Self.result(for: fence, key: TextMateCodeBlockKey(value: "someone else"))
        #expect(Self.rejection(Self.document, fence, result) == .identityChanged)
    }

    @Test func aNewerRevisionIsRejected() {
        let fence = Self.fence()
        let result = Self.result(for: fence, revision: TextMateHighlightRevision(value: fence.revision.value + 1))
        #expect(Self.rejection(Self.document, fence, result) == .revisionChanged)
    }

    @Test func aChangedPolicyIsRejected() {
        let fence = Self.fence()
        let result = Self.result(for: fence, policyEpoch: fence.policyEpoch + 1)
        #expect(Self.rejection(Self.document, fence, result) == .policyChanged)
    }

    @Test func aChangedSourceIsRejected() {
        let fence = Self.fence()
        // The result colored other text.
        #expect(Self.rejection(Self.document, fence, Self.result(for: fence, source: "let stale = 1")) == .sourceChanged)
        // The document's fence changed under the same hash.
        let edited = NativeMarkdownDocument(
            sourceHash: 17,
            blocks: [.paragraph([.text("before")]), .codeBlock(language: "swift", code: "let value = 2", spans: [])]
        )
        #expect(Self.rejection(edited, fence, Self.result(for: fence)) == .sourceChanged)
    }

    @Test func cancelledAndFailedResultsAreNotAttached() {
        let fence = Self.fence()
        for outcome in ["Cancelled", "Failed"] {
            #expect(Self.rejection(Self.document, fence, Self.result(for: fence, outcome: outcome)) == .resultNotAttachable, "\(outcome)")
        }
        for outcome in ["Highlighted", "NoSpans", "Partial", "Unsupported"] {
            guard case .attached = Self.document.attachingTextMate(fence: fence, result: Self.result(for: fence, outcome: outcome)) else {
                Issue.record("\(outcome) should attach")
                continue
            }
        }
    }

    // MARK: Preparer

    @Test func preparerUsesInjectedEngineOffMainAndMonotonicRevisions() async {
        let engine = ScriptedTextMateEngine()
        let preparer = MessageContentPreparer(textMateEngine: engine)

        _ = await preparer.prepareMessage(Self.message(id: "one", text: "```swift\nlet first = 1\n```"))
        _ = await preparer.prepareMessage(Self.message(id: "two", text: "```swift\nlet second = 2\n```"))

        let calls = engine.calls
        #expect(calls.count == 2)
        #expect(calls[0].request.revision.value < calls[1].request.revision.value)
        #expect(calls.map(\.onMain) == [false, false])
        #expect(engine.maxActive == 1)
    }

    @Test func finalPrepareKeepsCallingWhileTheFenceIsPartial() async {
        let code = "let a = 1\nlet b = 2\nlet c = 3"
        let engine = ScriptedTextMateEngine(outcomes: [
            ("Partial", [NativeCodeSpan(start: 0, end: 3, role: .keyword)]),
            ("Partial", [NativeCodeSpan(start: 0, end: 3, role: .keyword), NativeCodeSpan(start: 10, end: 13, role: .keyword)]),
        ])
        let preparer = MessageContentPreparer(textMateEngine: engine)
        let message = Self.message(id: "one", text: "```swift\n\(code)\n```")

        let display = await preparer.prepareMessage(message)

        #expect(engine.calls.count == 3)
        #expect(Set(engine.calls.map(\.request.revision)).count == 1)
        #expect(Self.codeSpans(display) == ScriptedTextMateEngine.fullSpans)
        // Complete, so it is cached: the same text prepares again without the engine.
        _ = await preparer.prepareMessage(message)
        #expect(engine.calls.count == 3)
    }

    @Test func aStalledPartialFenceStopsAndIsNotCached() async {
        let stuck = [NativeCodeSpan(start: 0, end: 3, role: .keyword)]
        let engine = ScriptedTextMateEngine(outcomes: Array(repeating: ("Partial", stuck), count: 6))
        let preparer = MessageContentPreparer(textMateEngine: engine)
        let message = Self.message(id: "one", text: "```swift\nlet a = 1\nlet b = 2\n```")

        let display = await preparer.prepareMessage(message)

        // The first call, then two in a row that colored nothing new.
        #expect(engine.calls.count == 3)
        #expect(Self.codeSpans(display) == stuck)
        _ = await preparer.prepareMessage(message)
        #expect(engine.calls.count == 6)
    }

    @Test func cancelledOrFailedFencesAreNotCached() async {
        for outcome in ["Cancelled", "Failed"] {
            let engine = ScriptedTextMateEngine(outcomes: [(outcome, [])])
            let preparer = MessageContentPreparer(textMateEngine: engine)
            let message = Self.message(id: "one", text: "```swift\nlet a = 1\n```\n\n```json\n[1]\n```")

            let first = await preparer.prepareMessage(message)
            #expect(engine.calls.count == 2, "\(outcome)")
            #expect(Self.codeSpans(first)?.isEmpty == true, "\(outcome)")

            let second = await preparer.prepareMessage(message)
            #expect(engine.calls.count == 4, "\(outcome)")
            #expect(Self.codeSpans(second) == ScriptedTextMateEngine.fullSpans, "\(outcome)")
            _ = await preparer.prepareMessage(message)
            #expect(engine.calls.count == 4, "\(outcome)")
        }
    }

    // MARK: Helpers

    private static func fence() -> TextMateMarkdownFence {
        document.textMateFences(ownerKey: "message", revision: TextMateHighlightRevision(value: 4), policyEpoch: 2)[0]
    }

    private static func result(
        for fence: TextMateMarkdownFence,
        key: TextMateCodeBlockKey? = nil,
        revision: TextMateHighlightRevision? = nil,
        policyEpoch: Int64? = nil,
        source: String? = nil,
        outcome: String = "Highlighted",
        spans: [NativeCodeSpan] = [NativeCodeSpan(start: 0, end: 3, role: .keyword)]
    ) -> TextMateHighlightResult {
        TextMateHighlightResult(
            key: key ?? fence.key,
            revision: revision ?? fence.revision,
            source: source ?? fence.source,
            canonicalLanguage: "swift",
            policyEpoch: policyEpoch ?? fence.policyEpoch,
            spans: spans,
            outcome: outcome,
            diagnostics: []
        )
    }

    private static func rejection(
        _ document: NativeMarkdownDocument,
        _ fence: TextMateMarkdownFence,
        _ result: TextMateHighlightResult
    ) -> TextMateAttachmentResult.RejectionReason? {
        if case .rejected(let reason) = document.attachingTextMate(fence: fence, result: result) { return reason }
        return nil
    }

    private static func message(id: String, text: String) -> Message {
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

    /// The first code block's spans.
    private static func codeSpans(_ display: DisplayMessage) -> [NativeCodeSpan]? {
        for block in display.renderedMarkdown?.blocks ?? [] {
            if case .codeBlock(_, _, let spans) = block { return spans }
        }
        return nil
    }
}

/// Returns scripted outcomes in order, then `Highlighted` with `fullSpans`; records each call.
nonisolated private final class ScriptedTextMateEngine: TextMateHighlighting, @unchecked Sendable {
    static let fullSpans = [NativeCodeSpan(start: 0, end: 3, role: .keyword)]

    struct Call {
        let request: TextMateHighlightRequest
        let onMain: Bool
    }

    private let lock = NSLock()
    private var outcomes: [(String, [NativeCodeSpan])]
    private var recorded: [Call] = []
    private var active = 0
    private var maxActiveSeen = 0

    init(outcomes: [(String, [NativeCodeSpan])] = []) {
        self.outcomes = outcomes
    }

    var calls: [Call] { lock.withLock { recorded } }
    var maxActive: Int { lock.withLock { maxActiveSeen } }

    func highlight(_ request: TextMateHighlightRequest) async throws -> TextMateHighlightResult {
        let onMain = Thread.isMainThread
        let (outcome, spans) = lock.withLock {
            active += 1
            maxActiveSeen = max(maxActiveSeen, active)
            recorded.append(Call(request: request, onMain: onMain))
            return outcomes.isEmpty ? ("Highlighted", Self.fullSpans) : outcomes.removeFirst()
        }
        await Task.yield()
        lock.withLock { active -= 1 }
        return TextMateHighlightResult(
            key: request.key,
            revision: request.revision,
            source: request.source,
            canonicalLanguage: request.language,
            policyEpoch: request.policyEpoch,
            spans: outcome == "Cancelled" || outcome == "Failed" ? [] : spans,
            outcome: outcome,
            diagnostics: []
        )
    }
}
