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

    @Test func twoTodayTakesTheNewest() {
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project,
            lastOpenedDay: "2026-11-05",
            sessions: [session("ses_old", "2026-11-05", minutesAgo: 30), session("ses_new", "2026-11-05", minutesAgo: 2)]
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

    @Test func onlyTheChosenProjectAndExactTitleCount() {
        let decision = DailySession.decide(
            today: "2026-11-05",
            directory: project + "/",
            lastOpenedDay: "2026-11-05",
            sessions: [
                session("ses_other", "2026-11-05", directory: "/Users/example/notes"),
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

/// The setup steps, in order, against a fake server.
@MainActor
struct DailySessionSetupTests {
    final class FakeService: DailySessionService, @unchecked Sendable {
        enum Call: Equatable {
            case list
            case create(String)
            case rules(String)
            case stream(String)
        }

        var listed: [SessionListing] = []
        var calls: [Call] = []
        var failsList = false
        var failsRules = false
        private var created = 0

        struct Failure: Error {}

        func sessions(directory: String) async throws -> [SessionListing] {
            calls.append(.list)
            if failsList { throw Failure() }
            return listed
        }

        func createSession(title: String, directory: String) async throws -> String {
            created += 1
            let id = "ses_new\(created)"
            calls.append(.create(title))
            listed.append(SessionListing(id: id, title: title, directory: directory, updatedAt: Date()))
            return id
        }

        func addMessageRules(sessionID: String) async throws {
            calls.append(.rules(sessionID))
            if failsRules { throw Failure() }
        }

        func openStream(sessionID: String) async {
            calls.append(.stream(sessionID))
        }
    }

    final class Memory: DailySessionMemory {
        var lastOpenedDay: String?
        init(_ day: String?) { lastOpenedDay = day }
    }

    private let project = "/Users/example/Projects/hexcode"

    @Test func noSessionTodayIsCreatedRulesAddedThenTheStreamOpens() async {
        let service = FakeService()
        let memory = Memory("2026-11-05")
        let setup = DailySessionSetup(service: service, memory: memory)
        let outcome = await setup.open(today: "2026-11-05", directory: project)
        #expect(outcome == .opened(sessionID: "ses_new1", created: true, showsNewDayNotice: false))
        #expect(service.calls == [.list, .create("2026-11-05"), .rules("ses_new1"), .stream("ses_new1")])
    }

    @Test func oneTodayIsReusedAndNothingIsCreated() async {
        let service = FakeService()
        service.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: project, updatedAt: Date())]
        let setup = DailySessionSetup(service: service, memory: Memory("2026-11-05"))
        let outcome = await setup.open(today: "2026-11-05", directory: project)
        #expect(outcome == .opened(sessionID: "ses_a", created: false, showsNewDayNotice: false))
        #expect(service.calls == [.list, .stream("ses_a")])
    }

    @Test func aNewDayLeavesYesterdayAndShowsTheNotice() async {
        let service = FakeService()
        service.listed = [SessionListing(id: "ses_y", title: "2026-11-04", directory: project, updatedAt: Date())]
        let memory = Memory("2026-11-04")
        let setup = DailySessionSetup(service: service, memory: memory)
        let outcome = await setup.open(today: "2026-11-05", directory: project)
        #expect(outcome == .opened(sessionID: "ses_new1", created: true, showsNewDayNotice: true))
        #expect(!service.calls.contains(.rules("ses_y")))
        #expect(memory.lastOpenedDay == "2026-11-05")
    }

    @Test func theDateChangingInTheBackgroundMakesANewSessionOnReturn() async {
        let service = FakeService()
        let memory = Memory(nil)
        let setup = DailySessionSetup(service: service, memory: memory)
        // First open after first run: no notice.
        #expect(await setup.open(today: "2026-11-05", directory: project) == .opened(sessionID: "ses_new1", created: true, showsNewDayNotice: false))
        // Back in front the same day: reused.
        #expect(await setup.open(today: "2026-11-05", directory: project) == .opened(sessionID: "ses_new1", created: false, showsNewDayNotice: false))
        // Back in front after midnight: a new one, with the notice.
        #expect(await setup.open(today: "2026-11-06", directory: project) == .opened(sessionID: "ses_new2", created: true, showsNewDayNotice: true))
    }

    @Test func aFailedListIsAFailureAndNothingIsSaved() async {
        let service = FakeService()
        service.failsList = true
        let memory = Memory("2026-11-04")
        let setup = DailySessionSetup(service: service, memory: memory)
        #expect(await setup.open(today: "2026-11-05", directory: project) == .failed)
        #expect(memory.lastOpenedDay == "2026-11-04")
        #expect(service.calls == [.list])
    }

    @Test func failedRulesAreAddedOnTryAgainWithoutASecondSession() async {
        let service = FakeService()
        service.failsRules = true
        let memory = Memory("2026-11-04")
        let setup = DailySessionSetup(service: service, memory: memory)
        #expect(await setup.open(today: "2026-11-05", directory: project) == .failed)
        #expect(memory.lastOpenedDay == "2026-11-04")

        service.failsRules = false
        let outcome = await setup.open(today: "2026-11-05", directory: project)
        #expect(outcome == .opened(sessionID: "ses_new1", created: true, showsNewDayNotice: true))
        #expect(service.calls == [
            .list, .create("2026-11-05"), .rules("ses_new1"),
            .list, .rules("ses_new1"), .stream("ses_new1"),
        ])
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

    @Test func workingIsTimedFromTheTurnsStart() {
        let state = AgentState.working(since: start).topBar(at: start + 252.9)
        #expect(state == HexTopBarState(tone: .working, label: "Working", timer: "4:12"))
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

/// Today's session on home: setup, the empty card and the new-day notice.
@MainActor
struct TodaySessionTests {
    private struct Generator: SummaryGenerating {
        func generateSummary(prompt: String) async throws -> String { "## Task\nNow: Doing it." }
    }

    private func make(_ service: DailySessionSetupTests.FakeService, lastDay: String?) -> (TodaySession, HexToaster) {
        let toasts = HexToaster()
        let session = TodaySession(
            setup: DailySessionSetup(service: service, memory: DailySessionSetupTests.Memory(lastDay)),
            summary: StatusSummaryModel(generator: Generator()),
            directory: { "/p" },
            toasts: toasts,
            now: { Date(timeIntervalSince1970: 1_793_880_000) },
            timeZone: TimeZone(identifier: "UTC")!
        )
        return (session, toasts)
    }

    @Test func aNewDayShowsTheEmptyCardAndTheNotice() async {
        let (session, toasts) = make(DailySessionSetupTests.FakeService(), lastDay: "2026-11-04")
        await session.open()
        #expect(session.setup == .ready(sessionID: "ses_new1"))
        #expect(toasts.current?.tone == .sun)
        #expect(toasts.current?.title == "New day, new session")
        #expect(toasts.current?.detail == "Yesterday’s is kept on your computer.")
        #expect(toasts.current?.duration == 3.6)
        guard case let .card(card) = session.summary.summary else {
            Issue.record("expected the empty card")
            return
        }
        #expect(card.next == "Say what to do.")
    }

    @Test func anExistingSessionRefreshesTheSummary() async {
        let service = DailySessionSetupTests.FakeService()
        service.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: "/p", updatedAt: Date())]
        let (session, toasts) = make(service, lastDay: "2026-11-05")
        await session.open()
        #expect(toasts.current == nil)
        #expect(session.summary.summary == .card(StatusCard(title: "Task", now: "Doing it.")))
    }

    @Test func aFailedSetupCanBeTriedAgain() async {
        let service = DailySessionSetupTests.FakeService()
        service.failsList = true
        let (session, _) = make(service, lastDay: nil)
        await session.open()
        #expect(session.setup == .failed(retrying: false))
        service.failsList = false
        await session.retry()
        #expect(session.setup == .ready(sessionID: "ses_new1"))
    }
}
