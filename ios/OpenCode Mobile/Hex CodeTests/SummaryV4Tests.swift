import Foundation
import Testing
@testable import Hex_Code

/// Reading a version 4 reply (app plan, "Reading the reply", "Clipping"). The fixtures are real
/// model output from status-summary-proposal.md §5.
@MainActor
struct SummaryReplyTests {
    private func card(_ reply: String) -> StatusCard? {
        guard case let .card(card) = StatusSummary.parse(reply) else { return nil }
        return card
    }

    private func plain(_ text: String) -> NowLine { NowLine(text: text) }
    private func bullet(_ text: String) -> NowLine { NowLine(text: text, isBullet: true) }

    // MARK: Real replies

    @Test func aLeadLineWithBulletsAndALink() {
        let reply = """
        ## Prototype: palette session dates

        Now: Four palette variants built and published: `0` current, `A` date buckets, `B` time gutter, `C` date filters; A recommended.

        - Source: `dialog.prototype.html` (untracked, next to `dialog.tsx`)
        - Published to this laptop only; Mac mini and Tailscale are down

        Next: Fold variant A into `dialog.tsx`
        Link: Open prototype | http://Ivans-MacBook-Pro.local:8787/2026-11-06/1343-command-palette--session-date-improvements/
        """
        let card = card(reply)
        #expect(card?.title == "Prototype: palette session dates")
        #expect(card?.lines == [
            plain("Four palette variants built and published: `0` current, `A` date buckets, `B` time gutter, `C` date filters; A recommended."),
            bullet("Source: `dialog.prototype.html` (untracked, next to `dialog.tsx`)"),
            bullet("Published to this laptop only; Mac mini and Tailscale are down"),
        ])
        #expect(card?.next == "Fold variant A into `dialog.tsx`")
        #expect(card?.links.map(\.label) == ["Open prototype"])
    }

    @Test func aListAnswerWithNextNoneAndALink() {
        let reply = """
        ## Safer remove_item ideas
        Now: Four suggested safety improvements for `remove_item` without code changes.
        - Validate `qty` is non-negative
        - Treat missing item as zero
        - Clamp stock at zero
        - Return a copy of stock
        Next: none
        Link: Python unittest docs | https://docs.python.org/3/library/unittest.html
        """
        let card = card(reply)
        #expect(card?.lines.count == 5)
        #expect(card?.lines.filter(\.isBullet).map(\.text) == ["Validate `qty` is non-negative", "Treat missing item as zero", "Clamp stock at zero", "Return a copy of stock"])
        #expect(card?.next == nil)
        #expect(card?.links.count == 1)
    }

    @Test func longBulletsAreKeptWhole() {
        let reply = """
        ## Validation for mathutils.py
        Now: All four `mathutils.py` functions now validate inputs, and 11 tests pass in `test_validation.py` and `test_mathutils.py`.
        - `clamp`, `average`, `percent`, and `scale` raise `TypeError` on non-numeric input
        - `clamp` raises `ValueError` when low > high; `average` on an empty list; `percent` when whole == 0
        Next: Commit these changes
        Link: none
        """
        let card = card(reply)
        #expect(card?.lines[2] == bullet("`clamp` raises `ValueError` when low > high; `average` on an empty list; `percent` when whole == 0"))
        #expect(card?.next == "Commit these changes")
        // "Link: none" isn't a link.
        #expect(card?.links == [])
    }

    @Test func noNextLineAtAll() {
        let reply = """
        ## Example.com and README
        Now: Page title is "Example Domain" with no anchor link; README links to the Python unittest docs.
        Link: Python unittest docs | https://docs.python.org/3/library/unittest.html
        """
        let card = card(reply)
        #expect(card?.lines.count == 1)
        #expect(card?.next == nil)
        #expect(card?.links.map(\.url) == ["https://docs.python.org/3/library/unittest.html"])
    }

    @Test func aStrayBulletAndTwoLinks() {
        let reply = """
        ## Session summary
        Now: `remove_item` raises ValueError for missing or negative stock; 4 tests pass; example.com title is "Example Domain".
        - README links to unittest docs
        Next: Commit it
        Link: unittest docs | https://docs.python.org/3/library/unittest.html
        Link: Example Domain | https://example.com
        """
        let card = card(reply)
        #expect(card?.lines.map(\.isBullet) == [false, true])
        #expect(card?.next == "Commit it")
        #expect(card?.links.count == 2)
    }

    @Test func noneWithACapitalAndLinkNonePipeNone() {
        let reply = """
        ## Said done
        Now: Replied "Done." after counting 3 files in `/private/tmp/hex-m2/ios-project` (`README.md`, `math.ts`, `opencode.json`).
        Next: None
        Link: None | None
        """
        let card = card(reply)
        #expect(card?.next == nil)
        #expect(card?.links == [])
    }

    @Test func aShortAnswer() {
        let card = card("## Python version and tests\nNow: Python 3.14.6 is installed and all 4 inventory tests pass.\nNext: Commit the changes")
        #expect(card?.lines == [plain("Python 3.14.6 is installed and all 4 inventory tests pass.")])
        #expect(card?.next == "Commit the changes")
    }

    // MARK: Rules

    @Test func nowEndsAtNextLinkOrATitle() {
        let titled = card("## T\nNow: One.\nTwo.\n## Another title\nThree.\nNext: Go\nFour.")
        #expect(titled?.lines == [plain("One."), plain("Two.")])
        let linked = card("## T\nNow: One.\nLink: A | https://a.example\nTwo.")
        #expect(linked?.lines == [plain("One.")])
    }

    @Test func anEmptyNowLabelStartsWithTheNextLine() {
        let card = card("## T\nNow:\nFirst line.\n- A point")
        #expect(card?.lines == [plain("First line."), bullet("A point")])
    }

    @Test func anEmptyNowIsAFallback() {
        #expect(StatusSummary.parse("## T\nNow:\nNext: Go") == .fallback("## T Now: Next: Go"))
    }

    @Test func everyBulletMarker() {
        let card = card("## T\nNow: Lead.\n- dash\n* star\n• dot\n1. one\n2) two")
        #expect(card?.lines.map(\.isBullet) == [false, true, true, true, true])
        #expect(card?.lines.dropFirst().map(\.text) == ["dash", "star", "dot", "one"])
        #expect(StatusSummary.nowLine("12. twelve") == bullet("twelve"))
        #expect(StatusSummary.nowLine("2) two") == bullet("two"))
        // Bold at the start isn't a bullet.
        #expect(StatusSummary.nowLine("**Bold** start") == plain("**Bold** start"))
    }

    @Test func theNoNextWords() {
        for word in ["", "none", "None.", "NONE", "n/a", "N/A.", "-", "—", "(none)", "(None)."] {
            #expect(card("## T\nNow: X.\nNext: \(word)")?.next == nil, "\(word)")
        }
        #expect(card("## T\nNow: X.\nNext: None of these, try B")?.next == "None of these, try B")
    }

    @Test func linkLinesNeedAWebURL() {
        let card = card("## T\nNow: X.\nLink: None\nLink: none | none\nLink: Notes | file:///tmp/a\nLink: A | https://a.example")
        #expect(card?.links.map(\.url) == ["https://a.example"])
    }

    @Test func atMostFourBulletsThenAtMostFiveLines() {
        let five = card("## T\nNow: Lead.\n- 1\n- 2\n- 3\n- 4\n- 5\nAfter.")
        #expect(five?.lines.map(\.text) == ["Lead.", "1", "2", "3", "4"])
        let plainLines = card("## T\nNow: 1\n2\n3\n4\n5\n6\n7")
        #expect(plainLines?.lines.count == 5)
        let mixed = card("## T\nNow: Lead.\n- 1\n- 2\n- 3\n- 4\n- 5\nAfter.\nMore.")
        // The fifth bullet goes first; then lines after the fifth.
        #expect(mixed?.lines.map(\.text) == ["Lead.", "1", "2", "3", "4"])
        let plainAfter = card("## T\nNow: Lead.\n- 1\n- 2\n- 3\n- 4\n- 5")
        #expect(plainAfter?.lines.filter(\.isBullet).count == 4)
    }

    @Test func nowIsNotCutByCharacters() {
        let long = String(repeating: "word ", count: 90).trimmingCharacters(in: .whitespaces) // 449
        #expect(card("## T\nNow: \(long)")?.now == long)
        let bullet = "- " + String(repeating: "b", count: 80)
        #expect(card("## T\nNow: Lead.\n\(bullet)")?.lines.last?.text.count == 80)
    }

    @Test func linksInEachLineBecomeTiles() {
        let card = card("## T\nNow: See https://a.example/x.\n- And [docs](https://b.example)\nNext: Open https://c.example")
        #expect(card?.lines.map(\.text) == ["See a.example.", "And docs"])
        #expect(card?.links.map(\.url) == ["https://a.example/x", "https://b.example", "https://c.example"])
    }

    @Test func theBranchesTextIsKeptWhole() {
        let card = card(TodaySessionDemo.branchesReply)
        #expect(card?.now.hasSuffix("No mobile app source in…") == true)
        #expect(card?.lines.count == 1)
    }

    // MARK: Saved cards

    @Test func aCardSavedBeforeLinesReadsAsOneLine() throws {
        let old = #"{"title":"T","now":"N","next":"X","links":[]}"#
        let card = try JSONDecoder().decode(StatusCard.self, from: Data(old.utf8))
        #expect(card == StatusCard(title: "T", lines: [NowLine(text: "N")], next: "X"))
    }

    @Test func linesSurviveSaving() throws {
        let card = StatusCard(title: "T", lines: [NowLine(text: "Lead"), NowLine(text: "A", isBullet: true)], next: nil)
        #expect(try JSONDecoder().decode(StatusCard.self, from: JSONEncoder().encode(card)) == card)
    }

    // MARK: The label above Now

    @Test func theLabelWhileWorkingOrWaiting() {
        #expect(CardLabel.text(title: "Login fix", state: .plain) == "Login fix")
        #expect(CardLabel.text(title: "Login fix", state: .working) == "Still working · Login fix")
        #expect(CardLabel.text(title: "Login fix", state: .waiting) == "Waiting for you · Login fix")
        #expect(CardLabel.text(title: "", state: .working) == "Still working")
        #expect(CardLabel.text(title: "", state: .waiting) == "Waiting for you")
    }
}

/// The card's basis and the "nothing new" rule, the You line and mid-run cards (app plan,
/// "Nothing new", "Just sent", "Mid-run card").
@MainActor
struct SummaryRefreshRuleTests {
    private let start = Date(timeIntervalSince1970: 1_793_880_000)
    private let utc = TimeZone(identifier: "UTC")!

    private func make(_ server: FakeSessionServer, store: MemorySummaryStore) -> (TodaySession, FakeTransport) {
        let transport = FakeTransport()
        let session = TodaySession(
            setup: DailySessionSetup(service: server, memory: FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(store: store, timeZone: utc, now: { [start] in start }),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: HexToaster(),
            api: server,
            stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
            now: { [start] in start },
            timeZone: utc
        )
        return (session, transport)
    }

    private func server(busy: Bool = false) -> FakeSessionServer {
        let server = FakeSessionServer()
        server.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: "/p", updatedAt: start, busySince: Date(timeIntervalSince1970: 1))]
        server.reply = "## Task\nNow: Doing it.\nNext: Commit it"
        server.updated = 1000
        server.snapshot = LiveSnapshot(busySince: busy ? start : nil, hasRun: true, children: [], updated: 1000, pending: [], inbox: [])
        return server
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 300 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    private func generates(_ server: FakeSessionServer) -> Int {
        server.calls.filter { if case .generate = $0 { true } else { false } }.count
    }

    /// Opens the session, connects the stream, and waits for the read-back.
    private func launch(_ server: FakeSessionServer, store: MemorySummaryStore) async -> (TodaySession, FakeTransport) {
        let (session, transport) = make(server, store: store)
        await session.open()
        await eventually { transport.connections == 1 }
        let snapshots = server.calls.filter { $0 == .snapshot("ses_a") }.count
        transport.send(EventLines.connected)
        await eventually { server.calls.filter { $0 == .snapshot("ses_a") }.count > snapshots && !session.summary.isUpdating }
        try? await Task.sleep(for: .milliseconds(30))
        return (session, transport)
    }

    @Test func aSecondLaunchWithNothingNewMakesNoRequest() async {
        let server = server()
        let store = MemorySummaryStore()
        let (first, _) = await launch(server, store: store)
        #expect(generates(server) == 1)
        #expect(store.saved?.basis == SummaryBasis(updated: 1000, busy: false))
        #expect(first.summary.summary != nil)
        let (second, _) = await launch(server, store: store)
        #expect(generates(server) == 1)
        #expect(second.summary.summary == first.summary.summary)
    }

    @Test func somethingNewMakesARequest() async {
        let server = server()
        let store = MemorySummaryStore()
        _ = await launch(server, store: store)
        server.updated = 1500
        server.snapshot.updated = 1500
        _ = await launch(server, store: store)
        #expect(generates(server) == 2)
        #expect(store.saved?.basis?.updated == 1500)
    }

    @Test func busyOnOpenMakesNoRequest() async {
        let server = server(busy: true)
        let store = MemorySummaryStore()
        let (session, _) = await launch(server, store: store)
        #expect(generates(server) == 0)
        // With no card yet: the working card.
        #expect(session.summary.summary == .card(.working(on: start, timeZone: utc)))
    }

    @Test func aCardMadeWhileBusyIsNeverCurrent() async {
        let server = server(busy: true)
        let store = MemorySummaryStore()
        let (session, _) = await launch(server, store: store)
        await session.summary.refresh() // ↻ always asks, busy or not
        #expect(generates(server) == 1)
        #expect(store.saved?.basis == SummaryBasis(updated: 1000, busy: true))
        // Mid-run: the reply's Next is dropped.
        guard case let .card(card)? = session.summary.summary else {
            Issue.record("expected a card")
            return
        }
        #expect(card.next == nil)
        server.snapshot.busySince = nil
        _ = await launch(server, store: store)
        #expect(generates(server) == 2)
    }

    @Test func refreshAlwaysAsks() async {
        let server = server()
        let store = MemorySummaryStore()
        let (session, _) = await launch(server, store: store)
        await session.summary.refresh()
        #expect(generates(server) == 2)
    }

    @Test func theAppsOwnModelSwitchMovesTheBasis() async {
        let server = server()
        let store = MemorySummaryStore()
        let (session, _) = await launch(server, store: store)
        await session.switchModel(ModelChoice(providerID: "opencode", modelID: "other", variant: nil))
        #expect(store.saved?.basis?.updated == 1001)
        server.snapshot.updated = 1001
        _ = await launch(server, store: store)
        #expect(generates(server) == 1)
    }

    @Test func aFailedReplyKeepsTheBasis() async {
        let server = server()
        let store = MemorySummaryStore()
        let (session, _) = await launch(server, store: store)
        server.reply = ""
        server.updated = 2000
        await session.summary.refresh()
        #expect(session.summary.failed)
        #expect(store.saved?.basis?.updated == 1000)
        #expect(session.summary.basis?.updated == 1000)
    }

    // MARK: Just sent

    @Test func theYouLineShowsFromDeliveryUntilTheCardAfterTheRun() async throws {
        let server = server()
        let store = MemorySummaryStore()
        let (session, transport) = await launch(server, store: store)
        let id = try await session.send("Fix the login test", delivery: .steer)
        #expect(session.sentMessage == nil)
        transport.send(EventLines.delivered("ses_a", id: id))
        await eventually { session.sentMessage != nil }
        #expect(session.sentMessage == "Fix the login test")
        transport.send(EventLines.started("ses_a"))
        await eventually { session.busySince != nil }
        // ↻ mid-run: a new card, the You line stays, and the reply's Next goes.
        await session.summary.refresh()
        #expect(session.sentMessage == "Fix the login test")
        guard case let .card(card)? = session.summary.summary else {
            Issue.record("expected a card")
            return
        }
        #expect(card.next == nil)
        // The run's end: its card replaces the You line.
        transport.send(EventLines.succeeded("ses_a"))
        await eventually { session.sentMessage == nil }
        #expect(session.sentMessage == nil)
        guard case let .card(after)? = session.summary.summary else {
            Issue.record("expected a card")
            return
        }
        #expect(after.next == "Commit it")
    }

    @Test func aMessageFromAnotherClientUsesItsInboxText() async {
        let server = server()
        let store = MemorySummaryStore()
        let (session, transport) = await launch(server, store: store)
        transport.send(EventLines.enqueued("ses_a", id: "msg_9"))
        transport.send(EventLines.delivered("ses_a", id: "msg_9"))
        await eventually { session.sentMessage != nil }
        #expect(session.sentMessage == "hi")
        // A later delivered message replaces it.
        transport.send(EventLines.line("session.inbox.enqueued", #"{"sessionID":"ses_a","inboxID":"msg_10","item":{"type":"user","payload":{"text":"Also the README"},"delivery":"queue"}}"#))
        transport.send(EventLines.delivered("ses_a", id: "msg_10"))
        await eventually { session.sentMessage == "Also the README" }
        #expect(session.sentMessage == "Also the README")
    }

    @Test func otherSessionsDeliveriesDontShow() async {
        let server = server()
        let store = MemorySummaryStore()
        let (session, transport) = await launch(server, store: store)
        transport.send(EventLines.enqueued("ses_other", id: "msg_9"))
        transport.send(EventLines.delivered("ses_other", id: "msg_9"))
        try? await Task.sleep(for: .milliseconds(50))
        #expect(session.sentMessage == nil)
    }
}

/// Tapping Next puts its text into the draft (app plan, "Tapping Next").
@MainActor
struct NextIntoDraftTests {
    @Test func nextBecomesATextChunk() {
        var draft = DictationDraft()
        draft.addText("  Commit it ")
        #expect(draft.chunks.count == 1)
        #expect(draft.text == "Commit it")
        #expect(draft.isComplete)
        draft.addText("Then push")
        #expect(draft.text == "Commit it Then push")
        draft.addText("   ")
        #expect(draft.chunks.count == 2)
    }
}
