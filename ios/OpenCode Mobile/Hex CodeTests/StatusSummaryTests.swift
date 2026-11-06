import Foundation
import Testing
@testable import Hex_Code

/// Reading the status summary reply, clipping, the empty card and the ↻ label (app plan, "The status
/// summary").
struct StatusSummaryTests {
    private let fullReply = """
    ## Login screen rewrite
    Now: Wiring the new token refresh into the API client.
    Next: Run the auth tests, then fix whatever breaks.
    - [x] Remove old session helper
    - [x] New login form
    - [ ] Token refresh
    - [ ] Auth tests
    """

    @Test func theFullShapeIsACard() {
        let expected = StatusCard(
            title: "Login screen rewrite",
            now: "Wiring the new token refresh into the API client.",
            next: "Run the auth tests, then fix whatever breaks.",
            steps: [
                .init(text: "Remove old session helper", done: true),
                .init(text: "New login form", done: true),
                .init(text: "Token refresh", done: false),
                .init(text: "Auth tests", done: false),
            ]
        )
        #expect(StatusSummary.parse(fullReply) == .card(expected))
        #expect(expected.doneCount == 2)
    }

    @Test func noStepsIsACardWithoutProgress() {
        let reply = "## Login screen rewrite\nNow: Reading the auth tests.\nNext: Fix the first failure."
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.steps.isEmpty)
        #expect(card.next == "Fix the first failure.")
    }

    @Test func noNextIsACardWithoutNext() {
        let reply = "## Login screen rewrite\nNow: Reading the auth tests.\n- [ ] Auth tests"
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.next == nil)
        #expect(card.steps == [.init(text: "Auth tests", done: false)])
    }

    @Test func codeFencesAndBlankLinesAreIgnored() {
        let reply = "```markdown\n\n" + fullReply + "\n\n```\n"
        #expect(StatusSummary.parse(reply) == StatusSummary.parse(fullReply))
    }

    @Test func textBeforeTheTitleIsIgnored() {
        let reply = "Sure, here is the summary:\n\n" + fullReply
        #expect(StatusSummary.parse(reply) == StatusSummary.parse(fullReply))
    }

    @Test func linesAreTrimmedAndTheFirstOfEachWins() {
        let reply = "   ## First title  \n## Second title\n  Now:   Doing it.  \nNow: Something else\n"
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.title == "First title")
        #expect(card.now == "Doing it.")
    }

    @Test func nowAndNextMatchInAnyCaseAndCapitalXIsDone() {
        let reply = "## Task\nNOW: Doing it.\nnext: After that.\n- [X] One\n- [x] Two\n- [ ] Three"
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.now == "Doing it.")
        #expect(card.next == "After that.")
        #expect(card.steps.map(\.done) == [true, true, false])
    }

    @Test func stepsAreClippedTo40() {
        let long = String(repeating: "step ", count: 20)
        guard case let .card(card) = StatusSummary.parse("## Task\nNow: Doing it.\n- [ ] \(long)") else {
            Issue.record("expected a card")
            return
        }
        #expect(card.steps[0].text.count <= 40)
        #expect(card.steps[0].text.hasSuffix("…"))
    }

    @Test func theFallbackCollapsesRunsOfSpaces() {
        #expect(StatusSummary.parse("Working   on\tthe\n\n  tests   now") == .fallback("Working on the tests now"))
    }

    @Test func noNowLineIsAFallback() {
        let reply = "## Login screen rewrite\nNext: Run the auth tests.\n- [x] New login form"
        #expect(StatusSummary.parse(reply) == .fallback("## Login screen rewrite Next: Run the auth tests. - [x] New login form"))
    }

    @Test func noTitleIsAFallback() {
        let reply = "Now: Wiring the new token refresh.\nNext: Run the auth tests."
        guard case .fallback = StatusSummary.parse(reply) else {
            Issue.record("expected a fallback")
            return
        }
    }

    @Test func anEmptyTitleOrNowDoesNotCount() {
        guard case .fallback = StatusSummary.parse("## \nNow: Doing it.") else {
            Issue.record("expected a fallback")
            return
        }
        guard case .fallback = StatusSummary.parse("## Title\nNow:   ") else {
            Issue.record("expected a fallback")
            return
        }
    }

    @Test func plainProseIsAFallbackOfAtMost300Characters() {
        let prose = String(repeating: "I am going through the auth tests one at a time. ", count: 10)
        guard case let .fallback(text) = StatusSummary.parse("```\n" + prose + "\n```") else {
            Issue.record("expected a fallback")
            return
        }
        #expect(text.count <= 300)
        #expect(text.hasSuffix("…"))
        #expect(text.hasPrefix("I am going through"))
    }

    @Test func titleNowAndNextAreClipped() {
        let long = String(repeating: "word ", count: 40)
        let reply = "## \(long)\nNow: \(long)\nNext: \(long)"
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.title.count <= 40)
        #expect(card.now.count <= 90)
        #expect(card.next.map(\.count).map { $0 <= 60 } == true)
        #expect(card.title.hasSuffix("…"))
    }

    @Test func atMostEightSteps() {
        let steps = (1 ... 10).map { "- [x] Step \($0)" }.joined(separator: "\n")
        guard case let .card(card) = StatusSummary.parse("## Title\nNow: Doing it.\n" + steps) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.steps.count == 8)
        #expect(card.steps.last?.text == "Step 8")
    }

    // MARK: Clipping, as the prototype's clip()

    @Test func clipKeepsShortTextAsItIs() {
        #expect(StatusSummary.clip("Short", 10) == "Short")
        #expect(StatusSummary.clip("Exactly10!", 10) == "Exactly10!")
    }

    @Test func clipCutsAtTheLastSpacePastHalfWay() {
        // cut = first 19 characters "Wiring the new toke"; its last space (14) is past 10.
        #expect(StatusSummary.clip("Wiring the new token refresh", 20) == "Wiring the new…")
    }

    @Test func clipCutsMidWordWhenTheSpaceIsTooEarly() {
        // cut = "Hi abcdefghi"; its last space (2) is not past 6.5.
        #expect(StatusSummary.clip("Hi abcdefghijklmnop", 13) == "Hi abcdefghi…")
    }

    @Test func clipDropsTrailingPunctuation() {
        // cut = "Done with this, an"; the last space is at 15, so "Done with this," then the comma goes.
        #expect(StatusSummary.clip("Done with this, and then more", 19) == "Done with this…")
    }

    // MARK: The empty session

    @Test func theEmptyCardTitleIsTheLongDateInEnglishDayFirst() {
        let utc = TimeZone(identifier: "UTC")!
        let thursday = Date(timeIntervalSince1970: 1_793_880_000) // 2026-11-05 12:00 UTC
        let card = StatusCard.empty(on: thursday, timeZone: utc)
        #expect(card.title == "Thursday 5 November")
        #expect(card.now == "New session. Nothing running yet.")
        #expect(card.next == "Say what to do.")
        #expect(card.steps.isEmpty)
        // The phone's region doesn't change it (the formatter ignores Locale.current).
        #expect(StatusCard.longDate(thursday + 86400 * 3, timeZone: utc) == "Sunday 8 November")
        #expect(StatusCard.longDate(thursday, timeZone: TimeZone(identifier: "Pacific/Kiritimati")!) == "Friday 6 November")
    }

    // MARK: ↻

    @Test func refreshLabelRules() {
        let made = Date(timeIntervalSince1970: 1_000_000)
        func label(after seconds: TimeInterval, updating: Bool = false, failed: Bool = false) -> SummaryRefreshLabel {
            SummaryRefreshLabel.make(updatedAt: made, at: made + seconds, isUpdating: updating, failed: failed)
        }
        #expect(label(after: 0) == .now)
        #expect(label(after: 44) == .now)
        #expect(label(after: 45) == .minutesAgo(1))
        #expect(label(after: 180) == .minutesAgo(3))
        #expect(label(after: 209) == .minutesAgo(3))
        #expect(label(after: 210) == .minutesAgo(4))
        #expect(label(after: 300, updating: true) == .updating)
        #expect(label(after: 300, failed: true) == .failed)
        #expect(label(after: 300, updating: true, failed: true) == .updating)
        #expect(SummaryRefreshLabel.make(updatedAt: nil, at: made, isUpdating: false, failed: false) == .none)
    }

    @Test func refreshLabelText() {
        #expect(SummaryRefreshLabel.now.text == "now")
        #expect(SummaryRefreshLabel.minutesAgo(3).text == "3m ago")
        #expect(SummaryRefreshLabel.updating.text == "updating…")
        #expect(SummaryRefreshLabel.failed.text == "Couldn’t update")
    }

    @Test func thePromptAndRulesAreVersioned() {
        #expect(SessionPrompts.Summary.version == 1)
        #expect(SessionPrompts.Summary.text.hasPrefix("Summarise this session for a small phone screen."))
        #expect(SessionPrompts.Summary.text.split(separator: "\n").count == 7)
        #expect(SessionPrompts.MessageRules.version == 2)
        #expect(SessionPrompts.MessageRules.text == """
        Messages come from a voice app and may arrive while you work.
        - No label: more detail for the current task. Fold it into the plan.
        - "[Correction]": the user changed a detail mid-task. Keep the work already done, change only what the correction affects (finished steps too), say so in one line, then carry on with the rest.
        - The goal set with /goal is the plan for today. Keep it unless told otherwise.
        """)
        #expect(SessionPrompts.MessageRules.entryID == "message-rules")
        #expect(SessionPrompts.MessageRules.text.split(separator: "\n").count == 4)
    }
}

/// Refreshing the summary: never two requests at once.
@MainActor
struct StatusSummaryModelTests {
    /// Holds each request until the test lets it go.
    private final class HeldGenerator: SummaryGenerating, @unchecked Sendable {
        var requests = 0
        var prompts: [String] = []
        var reply: Result<String, Error> = .success("## Task\nNow: Doing it.")
        private var waiting: [CheckedContinuation<Void, Never>] = []

        func generateSummary(prompt: String) async throws -> String {
            await MainActor.run {
                requests += 1
                prompts.append(prompt)
            }
            await withCheckedContinuation { continuation in
                Task { @MainActor in self.waiting.append(continuation) }
            }
            return try await MainActor.run { try reply.get() }
        }

        @MainActor
        func releaseAll() {
            waiting.forEach { $0.resume() }
            waiting = []
        }

        @MainActor
        var isWaiting: Bool { !waiting.isEmpty }
    }

    private struct Failure: Error {}

    private func waitUntil(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 1000 where !condition() {
            await Task.yield()
        }
    }

    @Test func aBlankReplyIsAFailedUpdate() async {
        let generator = HeldGenerator()
        let model = StatusSummaryModel(generator: generator)
        async let first: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await first
        let card = model.summary
        generator.reply = .success("  \n ")
        async let second: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await second
        #expect(model.summary == card)
        #expect(model.failed)
    }

    @Test func anEmptyReplyWhileWorkingKeepsTheLastCardWithoutTheNote() async {
        let generator = HeldGenerator()
        let model = StatusSummaryModel(generator: generator)
        async let first: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await first
        let card = model.summary
        let updated = model.updatedAt
        model.isWorking = { true }
        generator.reply = .success("")
        async let second: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await second
        #expect(model.summary == card)
        #expect(model.updatedAt == updated)
        #expect(!model.failed)
        // An error while working is still a failed update.
        generator.reply = .failure(Failure())
        async let third: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await third
        #expect(model.failed)
    }

    @Test func aRequestPastTheTimeoutFails() async {
        let generator = HeldGenerator()
        let model = StatusSummaryModel(generator: generator, timeout: .milliseconds(50))
        await model.refresh()
        #expect(model.failed)
        #expect(!model.isUpdating)
        generator.releaseAll()
    }

    @Test func twoRefreshesAtOnceMakeOneRequest() async {
        let generator = HeldGenerator()
        let clock = Date(timeIntervalSince1970: 1_000_000)
        let model = StatusSummaryModel(generator: generator, now: { clock })
        async let first: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        // Asked for while one runs: dropped at once.
        await model.refresh()
        #expect(model.isUpdating)
        #expect(model.refreshLabel(at: clock) == .updating)
        generator.releaseAll()
        await first
        #expect(generator.requests == 1)
        #expect(generator.prompts == [SessionPrompts.Summary.text])
        #expect(!model.isUpdating)
        #expect(model.summary == .card(StatusCard(title: "Task", now: "Doing it.")))
        #expect(model.refreshLabel(at: clock) == .now)
    }

    @Test func aLaterRefreshMakesANewRequest() async {
        let generator = HeldGenerator()
        let model = StatusSummaryModel(generator: generator)
        async let first: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await first
        async let second: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await second
        #expect(generator.requests == 2)
    }

    @Test func aFailedRequestKeepsTheLastCard() async {
        let generator = HeldGenerator()
        let model = StatusSummaryModel(generator: generator)
        async let first: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await first
        let card = model.summary

        generator.reply = .failure(Failure())
        async let second: Void = model.refresh()
        await waitUntil { generator.isWaiting }
        generator.releaseAll()
        await second
        #expect(model.summary == card)
        #expect(model.failed)
        #expect(model.refreshLabel(at: Date()) == .failed)
    }

    @Test func theEmptySessionMakesNoRequest() async {
        let generator = HeldGenerator()
        let model = StatusSummaryModel(generator: generator)
        model.showEmpty()
        #expect(generator.requests == 0)
        guard case let .card(card) = model.summary else {
            Issue.record("expected the empty card")
            return
        }
        #expect(card.now == "New session. Nothing running yet.")
    }
}
