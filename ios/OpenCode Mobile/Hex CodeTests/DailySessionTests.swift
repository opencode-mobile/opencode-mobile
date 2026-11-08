import Foundation
import Testing
@testable import Hex_Code

/// The daily session rule (app plan, "The daily session" and the M2 cases under "Testing").
struct DailySessionDecisionTests {
    private let project = "/Users/example/Projects/hexcode"
    private let base = Date(timeIntervalSince1970: 1_793_880_000) // 2026-11-05 12:00 UTC

    private func session(_ id: String, _ title: String, directory: String? = nil, minutesAgo: Double = 0) -> SessionListing {
        SessionListing(id: id, title: title, directory: directory ?? project, updatedAt: base - minutesAgo * 60)
    }

    @Test func noSessionTodayIsCreatedWithTheDateAsTitle() {
        let decision = DailySession.decide(today: "2026-11-05", directory: project, lastOpenedDay: "2026-11-05", sessions: [])
        #expect(decision == .init(action: .create(title: "2026-11-05"), showsNewDayNotice: false))
    }

    @Test func oneTodayIsReused() {
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project,
            lastOpenedDay: "2026-11-05",
            sessions: [session("ses_a", "2026-11-05")]
        )
        #expect(decision == .init(action: .reuse(id: "ses_a"), showsNewDayNotice: false))
    }

    @Test func twoTodayTakesTheFirstWhichIsTheNewest() {
        // The server lists newest update first.
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project,
            lastOpenedDay: "2026-11-05",
            sessions: [session("ses_new", "2026-11-05", minutesAgo: 2), session("ses_old", "2026-11-05", minutesAgo: 30)]
        )
        #expect(decision.action == .reuse(id: "ses_new"))
    }

    @Test func yesterdaysIsLeftAndANewOneIsCreatedWithTheNotice() {
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project,
            lastOpenedDay: "2026-11-04",
            sessions: [session("ses_yesterday", "2026-11-04", minutesAgo: 600)]
        )
        #expect(decision == .init(action: .create(title: "2026-11-05"), showsNewDayNotice: true))
    }

    @Test func theFirstDayAfterFirstRunHasNoNotice() {
        let decision = DailySession.decide(today: "2026-11-05", directory: project, lastOpenedDay: nil, sessions: [])
        #expect(decision.showsNewDayNotice == false)
    }

    @Test func todaysSessionGoneMissingIsCreatedAgainWithoutTheNotice() {
        let decision = DailySession.decide(today: "2026-11-05", directory: project, lastOpenedDay: "2026-11-05", sessions: [])
        #expect(decision == .init(action: .create(title: "2026-11-05"), showsNewDayNotice: false))
    }

    @Test func todaysSessionFromElsewhereOnANewDayIsReusedWithoutTheNotice() {
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project,
            lastOpenedDay: "2026-11-04",
            sessions: [session("ses_a", "2026-11-05")]
        )
        #expect(decision == .init(action: .reuse(id: "ses_a"), showsNewDayNotice: false))
    }

    @Test func onlyTheExactTitleAndDirectoryCount() {
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project,
            lastOpenedDay: "2026-11-05",
            sessions: [
                session("ses_other", "2026-11-05", directory: "/Users/example/notes"),
                session("ses_slash", "2026-11-05", directory: project + "/"),
                session("ses_named", "2026-11-05 login"),
                session("ses_mine", "2026-11-05", minutesAgo: 60),
            ]
        )
        #expect(decision.action == .reuse(id: "ses_mine"))
    }

    @Test func theTitleIsTheLocalDate() {
        let lateEvening = Date(timeIntervalSince1970: 1_793_919_600) // 2026-11-05 23:00 UTC
        #expect(DailySession.title(for: lateEvening, timeZone: TimeZone(identifier: "UTC")!) == "2026-11-05")
        #expect(DailySession.title(for: lateEvening, timeZone: TimeZone(identifier: "Europe/Berlin")!) == "2026-11-06")
        #expect(DailySession.title(for: lateEvening, timeZone: TimeZone(identifier: "America/Los_Angeles")!) == "2026-11-05")
    }
}

/// A fake server for the daily session and the live view, recording every call in order.
@MainActor
final class FakeSessionServer: DailySessionService, LiveSessionAPI, @unchecked Sendable {
    enum Call: Equatable {
        case list(search: String, cursor: String?)
        case create(title: String, model: ModelChoice)
        case rules(String)
        case snapshot(String)
        case generate(String)
        case model(String, ModelChoice)
    }

    struct Failure: Error {}

    var listed: [SessionListing] = []
    /// Pages to return in turn, instead of `listed`.
    var pages: [SessionListingPage] = []
    var calls: [Call] = []
    var failsList = false
    /// Thrown by the list, in place of `Failure`.
    var listError: (any Error)?
    var failsCreate = false
    var failsRules = false
    var snapshot = LiveSnapshot(busySince: nil, hasRun: false, children: [], pending: [], inbox: [])
    var reply = "## Task\nNow: Doing it."
    /// How long each generate takes; none by default.
    var generateDelay: Duration?
    private var created = 0

    init() {}

    var callsWithoutGenerate: [Call] {
        calls.filter { if case .generate = $0 { false } else { true } }
    }

    func sessionsPage(directory: String, search: String, limit: Int, cursor: String?) async throws -> SessionListingPage {
        calls.append(.list(search: search, cursor: cursor))
        if let listError { throw listError }
        if failsList { throw Failure() }
        if !pages.isEmpty { return pages.removeFirst() }
        return SessionListingPage(sessions: listed, next: listed.isEmpty ? nil : "next")
    }

    func createSession(title: String, directory: String, model: ModelChoice) async throws -> SessionListing {
        calls.append(.create(title: title, model: model))
        if failsCreate { throw Failure() }
        created += 1
        let session = SessionListing(id: "ses_new\(created)", title: title, directory: directory, updatedAt: Date())
        listed.insert(session, at: 0)
        return session
    }

    func putMessageRules(sessionID: String) async throws {
        calls.append(.rules(sessionID))
        if failsRules { throw Failure() }
    }

    func snapshot(rootID: String) async throws -> LiveSnapshot {
        calls.append(.snapshot(rootID))
        return snapshot
    }

    func generate(sessionID: String, prompt: String) async throws -> String {
        calls.append(.generate(sessionID))
        if let generateDelay { try await Task.sleep(for: generateDelay) }
        return reply
    }

    func switchModel(sessionID: String, model: ModelChoice) async throws {
        calls.append(.model(sessionID, model))
        updated += 1
    }

    /// The session's `time.updated`; not recorded as a call.
    var updated: Int64 = 1000

    func sessionUpdated(sessionID: String) async throws -> Int64 {
        updated
    }

    var prompts: [(text: String, delivery: Delivery)] = []
    var promptImages: [[OutgoingImage]] = []
    var promptFiles: [[OutgoingFile]] = []
    var cancelled: [String] = []

    func prompt(sessionID: String, text: String, images: [OutgoingImage], files: [OutgoingFile], delivery: Delivery) async throws -> String {
        prompts.append((text, delivery))
        promptImages.append(images)
        promptFiles.append(files)
        return "msg_\(prompts.count)"
    }

    func cancelInbox(sessionID: String, itemID: String) async throws {
        cancelled.append(itemID)
    }
}

@MainActor
final class FakeMemory: DailySessionMemory {
    var lastOpenedDay: String?
    var lastSessionID: String?
    init(_ day: String?, session: String? = nil) {
        lastOpenedDay = day
        lastSessionID = session
    }
}

/// The setup steps, in order, against a fake server.
@MainActor
struct DailySessionSetupTests {
    private let project = "/Users/example/Projects/hexcode"
    private let model = ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: "high")

    private func today(_ id: String, title: String = "2026-11-05") -> SessionListing {
        SessionListing(id: id, title: title, directory: project, updatedAt: Date())
    }

    @Test func noSessionTodayIsCreatedWithTheModelThenTheRulesArePut() async {
        let server = FakeSessionServer()
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-05"))
        let outcome = await setup.open(today: "2026-11-05", directory: project, model: model)
        guard case let .opened(session, created, notice) = outcome else {
            Issue.record("expected opened")
            return
        }
        #expect(session.id == "ses_new1" && created && !notice)
        #expect(server.calls == [.list(search: "2026-11-05", cursor: nil), .create(title: "2026-11-05", model: model), .rules("ses_new1")])
    }

    @Test func oneTodayIsReusedNothingCreatedAndTheRulesPutAgain() async {
        let server = FakeSessionServer()
        server.listed = [today("ses_a")]
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-05"))
        guard case let .opened(session, false, false) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("expected a reuse")
            return
        }
        #expect(session.id == "ses_a")
        #expect(server.calls == [.list(search: "2026-11-05", cursor: nil), .rules("ses_a")])
    }

    @Test func aNewDayLeavesYesterdayAndShowsTheNotice() async {
        let server = FakeSessionServer()
        server.listed = [today("ses_y", title: "2026-11-04")]
        let memory = FakeMemory("2026-11-04")
        let setup = DailySessionSetup(service: server, memory: memory)
        guard case let .opened(session, true, true) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("expected a new session with the notice")
            return
        }
        #expect(session.id == "ses_new1")
        #expect(!server.calls.contains(.rules("ses_y")))
        #expect(memory.lastOpenedDay == "2026-11-05")
    }

    @Test func theDateChangingInTheBackgroundMakesANewSessionOnReturn() async {
        let server = FakeSessionServer()
        let memory = FakeMemory(nil)
        let setup = DailySessionSetup(service: server, memory: memory)
        // First open after first run: no notice.
        guard case .opened(_, true, false) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("first open")
            return
        }
        // Back in front the same day: reused.
        guard case let .opened(again, false, false) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("same day")
            return
        }
        #expect(again.id == "ses_new1")
        // Back in front after midnight: a new one, with the notice.
        guard case let .opened(next, true, true) = await setup.open(today: "2026-11-06", directory: project, model: model) else {
            Issue.record("next day")
            return
        }
        #expect(next.id == "ses_new2")
    }

    @Test func aFailedListIsAFailureAndNothingIsSaved() async {
        let server = FakeSessionServer()
        server.failsList = true
        let memory = FakeMemory("2026-11-04")
        let setup = DailySessionSetup(service: server, memory: memory)
        #expect(await setup.open(today: "2026-11-05", directory: project, model: model) == .failed)
        #expect(memory.lastOpenedDay == "2026-11-04")
    }

    @Test func tryAgainAfterFailedRulesFindsTheSessionPutsTheRulesAndStillShowsTheNotice() async {
        let server = FakeSessionServer()
        server.failsRules = true
        let memory = FakeMemory("2026-11-04")
        let setup = DailySessionSetup(service: server, memory: memory)
        #expect(await setup.open(today: "2026-11-05", directory: project, model: model) == .failed)
        #expect(memory.lastOpenedDay == "2026-11-04")

        server.failsRules = false
        guard case let .opened(session, false, true) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("expected the same session with the notice")
            return
        }
        #expect(session.id == "ses_new1")
        #expect(server.calls.filter { if case .create = $0 { true } else { false } }.count == 1)
        #expect(server.calls.last == .rules("ses_new1"))
    }

    @Test func pagingFollowsTheCursorOnlyWhileFullPagesHaveNoMatch() async {
        let server = FakeSessionServer()
        let noise = (0 ..< DailySessionSetup.pageSize).map { today("ses_x\($0)", title: "2026-11-05 notes") }
        server.pages = [
            SessionListingPage(sessions: noise, next: "c1"),
            SessionListingPage(sessions: [today("ses_b")], next: "c2"),
        ]
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-05"))
        guard case let .opened(session, false, _) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("expected a reuse")
            return
        }
        #expect(session.id == "ses_b")
        #expect(server.calls.prefix(2) == [.list(search: "2026-11-05", cursor: nil), .list(search: "2026-11-05", cursor: "c1")])
    }

    @Test func aShortPageWithoutAMatchIsTheEnd() async {
        let server = FakeSessionServer()
        server.pages = [SessionListingPage(sessions: [today("ses_x", title: "2026-11-05 notes")], next: "c1")]
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-05"))
        guard case .opened(_, true, _) = await setup.open(today: "2026-11-05", directory: project, model: model) else {
            Issue.record("expected a new session")
            return
        }
        #expect(server.calls.filter { if case .list = $0 { true } else { false } }.count == 1)
    }
}

/// The home top bar's state (app plan, "Today's session on screen").
struct AgentStateTests {
    private let start = Date(timeIntervalSince1970: 1_000_000)

    @Test func streamDownBeatsEverything() {
        #expect(AgentState.resolve(streamUp: false, pendingInput: true, busySince: start) == .reconnecting)
        #expect(AgentState.resolve(streamUp: false, pendingInput: false, busySince: nil) == .reconnecting)
    }

    @Test func pendingInputBeatsBusy() {
        #expect(AgentState.resolve(streamUp: true, pendingInput: true, busySince: start) == .needsInput)
    }

    @Test func busyBeatsIdle() {
        #expect(AgentState.resolve(streamUp: true, pendingInput: false, busySince: start) == .working(since: start))
        #expect(AgentState.resolve(streamUp: true, pendingInput: false, busySince: nil) == .idle)
    }

    @Test func workingIsTimedFromTheRunsStart() {
        let state = AgentState.working(since: start).topBar(at: start + 252.9)
        #expect(state == HexTopBarState(tone: .working, label: "Working", timer: "4:12", spoken: "Working, 4 minutes"))
        #expect(AgentState.clock(7) == "0:07")
        #expect(AgentState.clock(4503) == "75:03")
        #expect(AgentState.clock(-3) == "0:00")
    }

    @Test func labels() {
        #expect(AgentState.reconnecting.topBar(at: start) == HexTopBarState(tone: .reconnecting, label: "Reconnecting…"))
        #expect(AgentState.needsInput.topBar(at: start) == HexTopBarState(tone: .needsYou, label: "Needs input"))
        #expect(AgentState.idle.topBar(at: start) == HexTopBarState(tone: .idle, label: "Idle"))
    }
}

/// M6: a second session on the same day is numbered, and the finder takes the highest number (app
/// plan, "The daily session" › Finding it, More than one match; "Testing" › Finder).
struct NumberedSessionTests {
    private let project = "/Users/example/Projects/hexcode"
    private let base = Date(timeIntervalSince1970: 1_793_966_400) // 2026-11-07 12:00 UTC

    private func session(_ id: String, _ title: String, directory: String? = nil, minutesAgo: Double = 0) -> SessionListing {
        SessionListing(id: id, title: title, directory: directory ?? project, updatedAt: base - minutesAgo * 60)
    }

    @Test func numbersOfTitles() {
        #expect(DailySession.number(ofTitle: "2026-11-07", today: "2026-11-07") == 1)
        #expect(DailySession.number(ofTitle: "2026-11-07 (2)", today: "2026-11-07") == 2)
        #expect(DailySession.number(ofTitle: "2026-11-07 (12)", today: "2026-11-07") == 12)
        #expect(DailySession.number(ofTitle: "2026-11-07 (1)", today: "2026-11-07") == nil)
        #expect(DailySession.number(ofTitle: "2026-11-07 (0)", today: "2026-11-07") == nil)
        #expect(DailySession.number(ofTitle: "Notes 2026-11-07", today: "2026-11-07") == nil)
        #expect(DailySession.number(ofTitle: "2026-11-07 (2) notes", today: "2026-11-07") == nil)
        #expect(DailySession.number(ofTitle: "2026-11-07 (x)", today: "2026-11-07") == nil)
        #expect(DailySession.number(ofTitle: "2026-11-07(2)", today: "2026-11-07") == nil)
        #expect(DailySession.number(ofTitle: "2026-11-07 (-2)", today: "2026-11-07") == nil)
    }

    @Test func theHighestNumberWinsWhateverTheUpdateOrder() {
        let sessions = [
            session("ses_2", "2026-11-07 (2)", minutesAgo: 1),
            session("ses_1", "2026-11-07", minutesAgo: 2),
            session("ses_3", "2026-11-07 (3)", minutesAgo: 30),
        ]
        #expect(DailySession.match(today: "2026-11-07", directory: project, in: sessions)?.id == "ses_3")
        #expect(DailySession.match(today: "2026-11-07", directory: project, in: sessions.reversed())?.id == "ses_3")
    }

    @Test func notesAndOneInBracketsAreIgnored() {
        let sessions = [
            session("ses_notes", "Notes 2026-11-07"),
            session("ses_one", "2026-11-07 (1)"),
            session("ses_plain", "2026-11-07", minutesAgo: 5),
        ]
        #expect(DailySession.match(today: "2026-11-07", directory: project, in: sessions)?.id == "ses_plain")
    }

    @Test func twoOfTheSameNumberTakeTheMostRecentlyUpdated() {
        let sessions = [
            session("ses_new", "2026-11-07 (2)", minutesAgo: 1),
            session("ses_old", "2026-11-07 (2)", minutesAgo: 20),
        ]
        #expect(DailySession.match(today: "2026-11-07", directory: project, in: sessions)?.id == "ses_new")
    }

    @Test func anotherDirectoryStringIsIgnored() {
        let sessions = [
            session("ses_slash", "2026-11-07 (3)", directory: project + "/"),
            session("ses_mine", "2026-11-07 (2)"),
        ]
        #expect(DailySession.match(today: "2026-11-07", directory: project, in: sessions)?.id == "ses_mine")
    }

    @Test func theNextTitle() {
        #expect(DailySession.nextTitle(today: "2026-11-07", after: nil) == "2026-11-07")
        #expect(DailySession.nextTitle(today: "2026-11-07", after: session("a", "2026-11-07")) == "2026-11-07 (2)")
        #expect(DailySession.nextTitle(today: "2026-11-07", after: session("a", "2026-11-07 (3)")) == "2026-11-07 (4)")
    }
}

/// The finder's pages, and Start a new session (app plan, "Settings and finish (M6)").
@MainActor
struct NumberedSessionSetupTests {
    private let project = "/Users/example/Projects/hexcode"
    private let model = ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: "high")

    private func today(_ id: String, title: String = "2026-11-07") -> SessionListing {
        SessionListing(id: id, title: title, directory: project, updatedAt: Date())
    }

    @Test func aFullFirstPageWithTheHigherMatchOnPageTwoFindsIt() async {
        let server = FakeSessionServer()
        let first = [today("ses_1")] + (1 ..< DailySessionSetup.pageSize).map { today("ses_x\($0)", title: "2026-11-07 notes") }
        server.pages = [
            SessionListingPage(sessions: first, next: "c1"),
            SessionListingPage(sessions: [today("ses_2", title: "2026-11-07 (2)")], next: nil),
        ]
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-07"))
        guard case let .opened(session, false, _) = await setup.open(today: "2026-11-07", directory: project, model: model) else {
            Issue.record("expected a reuse")
            return
        }
        #expect(session.id == "ses_2")
        #expect(server.calls.prefix(2) == [.list(search: "2026-11-07", cursor: nil), .list(search: "2026-11-07", cursor: "c1")])
    }

    @Test func atMostTwentyFivePagesAreRead() async {
        let server = FakeSessionServer()
        let full = (0 ..< DailySessionSetup.pageSize).map { today("ses_x\($0)", title: "2026-11-07 notes") }
        server.pages = Array(repeating: SessionListingPage(sessions: full, next: "more"), count: 30)
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-07"))
        _ = await setup.open(today: "2026-11-07", directory: project, model: model)
        #expect(server.calls.filter { if case .list = $0 { true } else { false } }.count == DailySessionSetup.maxPages)
    }

    @Test func aNewSessionIsTitledWithTheNextNumberAndSavedAsTheLastID() async {
        let server = FakeSessionServer()
        server.listed = [today("ses_2", title: "2026-11-07 (2)"), today("ses_1")]
        let memory = FakeMemory("2026-11-07", session: "ses_2")
        let setup = DailySessionSetup(service: server, memory: memory)
        guard case let .opened(session) = await setup.startNew(today: "2026-11-07", directory: project, model: model) else {
            Issue.record("expected a new session")
            return
        }
        #expect(session.title == "2026-11-07 (3)")
        #expect(server.calls == [
            .list(search: "2026-11-07", cursor: nil),
            .create(title: "2026-11-07 (3)", model: model),
            .rules(session.id),
        ])
        #expect(memory.lastSessionID == session.id)
        #expect(memory.lastOpenedDay == "2026-11-07")
    }

    @Test func withNoSessionTodayTheNewOneHasThePlainDate() async {
        let server = FakeSessionServer()
        let memory = FakeMemory("2026-11-06", session: "ses_y")
        let setup = DailySessionSetup(service: server, memory: memory)
        guard case let .opened(session) = await setup.startNew(today: "2026-11-07", directory: project, model: model) else {
            Issue.record("expected a new session")
            return
        }
        #expect(session.title == "2026-11-07")
        // The last date only moves with a normal open.
        #expect(memory.lastOpenedDay == "2026-11-06")
    }

    @Test func creatingFailsLeavesEverythingAsItWas() async {
        let server = FakeSessionServer()
        server.listed = [today("ses_1")]
        server.failsCreate = true
        let memory = FakeMemory("2026-11-07", session: "ses_1")
        let setup = DailySessionSetup(service: server, memory: memory)
        #expect(await setup.startNew(today: "2026-11-07", directory: project, model: model) == .createFailed)
        #expect(memory.lastSessionID == "ses_1")
    }

    @Test func theRulesFailingThenTryAgainFindsTheNewSessionAndMakesNoThird() async {
        let server = FakeSessionServer()
        server.listed = [today("ses_1")]
        server.failsRules = true
        let setup = DailySessionSetup(service: server, memory: FakeMemory("2026-11-07", session: "ses_1"))
        #expect(await setup.startNew(today: "2026-11-07", directory: project, model: model) == .rulesFailed)
        server.failsRules = false
        guard case let .opened(session, false, false) = await setup.open(today: "2026-11-07", directory: project, model: model) else {
            Issue.record("expected the new session found")
            return
        }
        #expect(session.title == "2026-11-07 (2)")
        #expect(server.calls.filter { if case .create = $0 { true } else { false } }.count == 1)
    }
}
