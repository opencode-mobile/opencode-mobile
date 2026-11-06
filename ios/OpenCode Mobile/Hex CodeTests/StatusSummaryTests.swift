import Foundation
import SwiftUI
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
            next: "Run the auth tests, then fix whatever breaks."
        )
        #expect(StatusSummary.parse(fullReply) == .card(expected))
    }

    @Test func linesAfterNextAreIgnored() {
        let withSteps = "## Login screen rewrite\nNow: Reading the auth tests.\nNext: Fix the first failure.\n- [x] Done\n- [ ] Not yet\nSome chatter after the card."
        let without = "## Login screen rewrite\nNow: Reading the auth tests.\nNext: Fix the first failure."
        #expect(StatusSummary.parse(withSteps) == StatusSummary.parse(without))
    }

    @Test func savedCardsWithStepsStillDecode() throws {
        let old = #"{"title":"T","now":"N","next":"X","steps":[{"text":"A","done":true}]}"#
        let card = try JSONDecoder().decode(StatusCard.self, from: Data(old.utf8))
        #expect(card == StatusCard(title: "T", now: "N", next: "X"))
    }

    @Test func noNextIsACardWithoutNext() {
        let reply = "## Login screen rewrite\nNow: Reading the auth tests.\n- [ ] Auth tests"
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.next == nil)
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

    @Test func nowAndNextMatchInAnyCase() {
        let reply = "## Task\nNOW: Doing it.\nnext: After that."
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.now == "Doing it.")
        #expect(card.next == "After that.")
    }

    @Test func theFallbackCollapsesRunsOfSpaces() {
        #expect(StatusSummary.parse("Working   on\tthe\n\n  tests   now") == .fallback("Working on the tests now"))
    }

    @Test func linkLinesBecomeLinksNotText() {
        let reply = "## Repo overview\nNow: OpenCode is an open-source AI coding agent.\nLink: Docs | https://opencode.ai/docs\nNext: Read the docs."
        #expect(StatusSummary.parse(reply) == .card(StatusCard(
            title: "Repo overview",
            now: "OpenCode is an open-source AI coding agent.",
            next: "Read the docs.",
            links: [CardLink(label: "Docs", url: "https://opencode.ai/docs")]
        )))
        // Nor do they reach a fallback's text.
        #expect(StatusSummary.parse("Plain words.\nLink: Docs | https://opencode.ai") == .fallback("Plain words."))
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

    @Test func titleNowAndNextAreNeverCut() {
        let long = String(repeating: "word ", count: 40).trimmingCharacters(in: .whitespaces)
        let reply = "## \(long)\nNow: \(long)\nNext: \(long)"
        guard case let .card(card) = StatusSummary.parse(reply) else {
            Issue.record("expected a card")
            return
        }
        #expect(card.title == long)
        #expect(card.now == long)
        #expect(card.next == long)
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
        #expect(SessionPrompts.Summary.version == 4)
        #expect(SessionPrompts.Summary.text.hasPrefix("Say where this session stands, for the user glancing at their phone instead of reading the chat."))
        #expect(SessionPrompts.Summary.text.contains("Next: <what the user might say next, at most 60 characters, or \"none\">"))
        #expect(SessionPrompts.Summary.text.contains("At most 300 characters in all. Shorter is better: this is read at a glance."))
        #expect(SessionPrompts.Summary.text.hasSuffix("Never put a URL in Now or Next.\nUse no other formatting."))
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

/// The Now sentence's size by length, then down to nowFit while the card doesn't fit (app plan,
/// "Now size").
struct NowSizeTests {
    @Test func lengthBoundaries() {
        #expect(NowSize.forLength(0) == .display)
        #expect(NowSize.forLength(90) == .display)
        #expect(NowSize.forLength(91) == .large)
        #expect(NowSize.forLength(150) == .large)
        #expect(NowSize.forLength(151) == .medium)
        #expect(NowSize.forLength(220) == .medium)
        #expect(NowSize.forLength(221) == .small)
        #expect(NowSize.forLength(300) == .small)
    }

    @Test func charactersAreWhatTheUserSees() {
        // 90 flags are 90 characters, though many more UTF-16 units.
        #expect(NowSize.forText(String(repeating: "🇬🇧", count: 90)) == .display)
        #expect(NowSize.forText(String(repeating: "🇬🇧", count: 91)) == .large)
    }

    private func line(_ count: Int, bullet: Bool = false) -> NowLine {
        NowLine(text: String(repeating: "a", count: count), isBullet: bullet)
    }

    @Test func eachLineAfterTheFirstCountsTwentyMore() {
        // 80 + 20 = 100: past display.
        #expect(NowSize.forLines([line(80)]) == .display)
        #expect(NowSize.forLines([line(40), line(40)]) == .large)
        // 3 lines of 40 + 2 × 20 = 160.
        #expect(NowSize.forLines([line(40), line(40), line(40)]) == .medium)
    }

    @Test func bulletsAreAtMostNowMedium() {
        #expect(NowSize.forLines([line(20), line(10, bullet: true), line(10, bullet: true)]) == .medium)
        #expect(NowSize.forLines([line(100), line(60, bullet: true), line(60, bullet: true)]) == .small)
    }

    @Test func formattingSymbolsDontCount() {
        let code = NowLine(text: "`" + String(repeating: "a", count: 90) + "`")
        #expect(NowSize.forLines([code]) == .display)
    }

    @Test func itStepsDownUntilTheCardFitsThenDropsLines() {
        let lines = [line(60), line(30, bullet: true), line(30, bullet: true)] // 120 + 40 = 160, bullets: medium
        let layouts = NowSize.layouts(for: lines)
        #expect(layouts == [
            NowLayout(size: .medium, lineCount: 3),
            NowLayout(size: .small, lineCount: 3),
            NowLayout(size: .fit, lineCount: 3),
            NowLayout(size: .fit, lineCount: 2),
            NowLayout(size: .fit, lineCount: 1),
        ] + [8, 6, 4, 3, 2, 1].map { NowLayout(size: .fit, lineCount: 1, rowLimit: $0) })
        #expect(NowSize.choose(for: lines) { _ in true } == NowLayout(size: .medium, lineCount: 3))
        #expect(NowSize.choose(for: lines) { $0.size == .fit } == NowLayout(size: .fit, lineCount: 3))
        #expect(NowSize.choose(for: lines) { $0.lineCount <= 2 } == NowLayout(size: .fit, lineCount: 2))
        // Then one line in fewer rows; nothing fits: one row.
        #expect(NowSize.choose(for: lines) { ($0.rowLimit ?? 99) <= 4 } == NowLayout(size: .fit, lineCount: 1, rowLimit: 4))
        #expect(NowSize.choose(for: lines) { _ in false } == NowLayout(size: .fit, lineCount: 1, rowLimit: 1))
    }

    @Test func droppedLinesEndTheLastKeptInAnEllipsis() {
        let lines = [NowLine(text: "Four ways to make it safer:"), NowLine(text: "Validate `qty`.", isBullet: true), NowLine(text: "Clamp", isBullet: true)]
        #expect(NowLayout(size: .fit, lineCount: 2).shown(lines) == [lines[0], NowLine(text: "Validate `qty`…", isBullet: true)])
        #expect(NowLayout(size: .fit, lineCount: 1).shown(lines) == [NowLine(text: "Four ways to make it safer…")])
        #expect(NowLayout(size: .small, lineCount: 3).shown(lines) == lines)
    }

    @Test func eachSizeUsesItsToken() {
        #expect(NowSize.display.style == .display)
        #expect(NowSize.large.style == HexTextStyle(size: 25, weight: .semibold, tracking: -0.022, lineHeight: 1.22))
        #expect(NowSize.medium.style == HexTextStyle(size: 21, weight: .medium, tracking: -0.016, lineHeight: 1.28))
        #expect(NowSize.small.style == HexTextStyle(size: 19, weight: .medium, tracking: -0.012, lineHeight: 1.32))
        #expect(NowSize.fit.style == HexTextStyle(size: 17, weight: .medium, tracking: -0.008, lineHeight: 1.36))
    }
}
