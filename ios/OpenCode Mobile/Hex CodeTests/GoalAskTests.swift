import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// M5 (app plan, "Goal and Ask (M5)" › Testing): goal state, rounds, commands, the old kickoff, goal
// text, goal items, the summary on a round, Ask and its history.

/// Today's goal on a fake server, recording every call.
@MainActor
final class FakeGoals: GoalAPI, @unchecked Sendable {
    struct Failure: Error {}

    /// The goal each session has.
    var goals: [String: TodayGoal] = [:]
    private(set) var reads: [String] = []
    private(set) var commands: [String] = []
    private(set) var commandImages: [[String]] = []
    /// Thrown by the next command.
    var commandError: (any Error)?

    nonisolated init() {}

    func goal(sessionID: String, directory: String) async throws -> TodayGoal? {
        reads.append(sessionID)
        return goals[sessionID]
    }

    func goalCommand(sessionID: String, text: String, images: [OutgoingImage]) async throws {
        commands.append(text)
        commandImages.append(images.map(\.name))
        if let error = commandError { commandError = nil; throw error }
    }
}

extension EventLines {
    static func goal(_ session: String, id: String = "g1", objective: String = "Ship it", status: String = "active", rounds: Int = 0, reason: String? = nil) -> String {
        let reason = reason.map { #","reason":"\#($0)""# } ?? ""
        return line(
            "rpc.opencode.goal.changed",
            #"{"sessionID":"\#(session)","goal":{"id":"\#(id)","objective":"\#(objective)","status":"\#(status)","rounds":\#(rounds),"maxRounds":50,"stalls":0,"boot":"b1","updatedAt":1794007563460\#(reason)}}"#
        )
    }

    static func goalCleared(_ session: String) -> String {
        line("rpc.opencode.goal.changed", #"{"sessionID":"\#(session)","goal":null}"#)
    }

    /// A goal's kickoff (or, without `goalID`, a round's continuation), as the server enqueues it.
    static func goalItem(_ session: String, id: String, goalID: String? = "g1", type: String = "synthetic", delivery: String = "steer") -> String {
        let goal = goalID.map { #","goalID":"\#($0)""# } ?? ""
        return line(
            "session.inbox.enqueued",
            #"{"sessionID":"\#(session)","inboxID":"\#(id)","item":{"type":"\#(type)","delivery":"\#(delivery)","payload":{"text":"A goal is now active for this session.","description":"Goal: Ship it","metadata":{"source":"goal"\#(goal)}}}}"#
        )
    }
}

/// Today's session with a goal API, against fakes.
@MainActor
struct GoalHarness {
    let server = FakeSessionServer()
    let goals = FakeGoals()
    let transport = FakeTransport()
    let toasts = HexToaster()
    let session: TodaySession

    init(goal: TodayGoal? = nil, busy: Bool = false, inbox: [InboxEntry] = [], memory: FakeMemory? = nil, ask: AskModel? = nil) {
        server.snapshot = LiveSnapshot(busySince: busy ? Date() : nil, hasRun: true, children: [], pending: [], inbox: inbox)
        server.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: "/p", updatedAt: Date(), busySince: Date(timeIntervalSince1970: 1))]
        if let goal { goals.goals["ses_a"] = goal }
        session = TodaySession(
            setup: DailySessionSetup(service: server, memory: memory ?? FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: toasts,
            api: server,
            goals: goals,
            stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
            now: { Date(timeIntervalSince1970: 1_793_880_000) },
            timeZone: TimeZone(identifier: "UTC")!,
            ask: ask
        )
        session.sleep = { _ in try await Task.sleep(for: .milliseconds(30)) }
    }

    /// Opens today's session and its stream, and reads the state and the goal back.
    func open() async {
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        await eventually { server.calls.contains(.snapshot("ses_a")) && goals.reads.contains("ses_a") }
        try? await Task.sleep(for: .milliseconds(30))
    }

    func send(_ line: String) async {
        transport.send(line)
        try? await Task.sleep(for: .milliseconds(30))
    }

    func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 300 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    var generateCount: Int { server.calls.filter { if case .generate = $0 { true } else { false } }.count }
}

private func goal(_ status: TodayGoal.Status = .active, rounds: Int = 0, id: String = "g1", reason: String? = nil) -> TodayGoal {
    TodayGoal(id: id, objective: "Ship it", status: status, reason: reason, rounds: rounds)
}

// MARK: Goal state

struct GoalStateTests {
    @Test func theLineForEachStateBusyAndIdle() {
        #expect(GoalLineState.of(nil, busy: false) == .empty)
        #expect(GoalLineState.of(nil, busy: true) == nil)
        #expect(GoalLineState.of(goal(.active), busy: true) == .starting)
        #expect(GoalLineState.of(goal(.active, rounds: 3), busy: true) == .round(3))
        #expect(GoalLineState.of(goal(.active, rounds: 3), busy: false) == .waiting)
        #expect(GoalLineState.of(goal(.paused), busy: true) == .paused)
        #expect(GoalLineState.of(goal(.paused), busy: false) == .paused)
        #expect(GoalLineState.of(goal(.complete), busy: false) == .done)
        #expect(GoalLineState.of(goal(.complete), busy: true) == .done)
    }

    @Test func theLinesSuffixAndIcon() {
        #expect(GoalLineState.empty.suffix == nil)
        #expect(GoalLineState.starting.suffix == "starting")
        #expect(GoalLineState.round(1).suffix == "round 1")
        #expect(GoalLineState.round(50).suffix == "round 50")
        #expect(GoalLineState.waiting.suffix == "waiting")
        #expect(GoalLineState.paused.suffix == "paused")
        #expect(GoalLineState.done.suffix == "done")
        #expect(GoalLineState.round(2).iconLit)
        #expect(GoalLineState.done.iconLit)
        #expect(!GoalLineState.paused.iconLit)
        #expect(!GoalLineState.empty.iconLit)
    }

    @Test func roundsZeroOneAndFiftyInTheBox() {
        #expect(GoalBoxStatus.text(goal(rounds: 0), busy: true) == "active · starting")
        #expect(GoalBoxStatus.text(goal(rounds: 1), busy: true) == "active · round 1 of 50")
        #expect(GoalBoxStatus.text(goal(rounds: 50), busy: true) == "active · round 50 of 50")
        #expect(GoalBoxStatus.text(goal(rounds: 4), busy: false) == "active · waiting")
        #expect(GoalBoxStatus.text(goal(.paused), busy: false) == "paused")
        #expect(GoalBoxStatus.text(goal(.complete), busy: false) == "done")
    }

    @Test func theReasonShowsForPausedAndDoneOnly() {
        #expect(GoalBoxStatus.reason(goal(.active, reason: "Only one.txt exists")) == nil)
        #expect(GoalBoxStatus.reason(goal(.paused, reason: "Stopped by the user")) == "Stopped by the user")
        #expect(GoalBoxStatus.reason(goal(.complete, reason: "Verified all three files")) == "Verified all three files")
    }

    @Test func pauseIsOfferedOnlyWhenActiveResumeOnlyWhenPaused() {
        #expect(GoalSheetButtons.of(nil) == .none)
        #expect(GoalSheetButtons.of(goal(.active)).offersPause)
        #expect(!GoalSheetButtons.of(goal(.active)).offersResume)
        #expect(GoalSheetButtons.of(goal(.paused)).offersResume)
        #expect(!GoalSheetButtons.of(goal(.paused)).offersPause)
        #expect(GoalSheetButtons.of(goal(.complete)) == .done)
        #expect(!GoalSheetButtons.of(goal(.complete)).offersPause)
        #expect(!GoalSheetButtons.of(goal(.complete)).offersResume)
        #expect(!GoalSheetButtons.of(nil).offersClear)
    }

    @Test func theEventDecodesTheGoalOrNull() {
        #expect(SessionEvent.decode(EventLines.goal("ses_a", status: "paused", rounds: 2, reason: "Paused by the user")) == .goalChanged(
            sessionID: "ses_a",
            goal: TodayGoal(id: "g1", objective: "Ship it", status: .paused, reason: "Paused by the user", rounds: 2, updatedAt: 1_794_007_563_460)
        ))
        #expect(SessionEvent.decode(EventLines.goalCleared("ses_a")) == .goalChanged(sessionID: "ses_a", goal: nil))
        #expect(SessionEvent.types.contains("rpc.opencode.goal.changed"))
    }

    @Test func theKitReadsTheGoalStateAndInboxMetadata() throws {
        let json = #"{"id":"g1","objective":"Create the files","status":"complete","reason":"Verified","rounds":2,"maxRounds":50,"stalls":0,"boot":"b","updatedAt":1794007563460}"#
        let state = try JSONDecoder().decode(GoalStateV2.self, from: Data(json.utf8))
        #expect(TodayGoal(state) == TodayGoal(id: "g1", objective: "Create the files", status: .complete, reason: "Verified", rounds: 2, updatedAt: 1_794_007_563_460))
        let item = #"{"id":"inb_1","type":"synthetic","delivery":"steer","payload":{"text":"A goal…","metadata":{"source":"goal","goalID":"g1"}}}"#
        let decoded = try JSONDecoder().decode(InboxItemV2.self, from: Data(item.utf8))
        #expect(decoded.metadata == InboxMetadataV2(source: "goal", goalID: "g1"))
    }
}

@MainActor
struct GoalSessionTests {
    @Test func openAndEveryReconnectReadTheGoal() async {
        let h = GoalHarness(goal: goal(.paused, reason: "Stopped by the user"))
        await h.open()
        #expect(h.session.goal?.status == .paused)
        #expect(h.session.goalLine == .paused)
        h.goals.goals["ses_a"] = goal(.complete)
        h.transport.drop()
        await h.eventually { h.transport.connections == 2 }
        h.transport.send(EventLines.connected)
        await h.eventually { h.goals.reads.count == 2 }
        await h.eventually { h.session.goal?.status == .complete }
        #expect(h.session.goalLine == .done)
    }

    @Test func noGoalShowsSetTodaysGoalWhileIdleAndNoLineWhileBusy() async {
        let idle = GoalHarness()
        await idle.open()
        #expect(idle.session.goal == nil)
        #expect(idle.session.goalLine == .empty)
        let busy = GoalHarness(busy: true)
        await busy.open()
        #expect(busy.session.goalLine == nil)
    }

    @Test func anEventReplacesTheGoalAndNullClearsIt() async {
        let h = GoalHarness(busy: true)
        await h.open()
        await h.send(EventLines.goal("ses_a", rounds: 0))
        #expect(h.session.goalLine == .starting)
        await h.send(EventLines.goal("ses_a", rounds: 1))
        #expect(h.session.goalLine == .round(1))
        await h.send(EventLines.goalCleared("ses_a"))
        #expect(h.session.goal == nil)
        #expect(h.session.goalLine == nil)
    }

    @Test func anEventForAnotherSessionIsIgnored() async {
        let h = GoalHarness()
        await h.open()
        await h.send(EventLines.goal("ses_other"))
        #expect(h.session.goal == nil)
    }

    // MARK: Commands

    @Test func setReplacePauseResumeAndClearSendTheirText() async {
        let h = GoalHarness()
        await h.open()
        #expect(await h.session.setGoal("  Make login work offline  ") == .done)
        #expect(h.toasts.current?.title == "Goal set")
        #expect(h.toasts.current?.detail == "The agent keeps going until it’s done.")
        await h.send(EventLines.goal("ses_a"))
        #expect(await h.session.setGoal("Ship the release") == .done)
        #expect(h.toasts.current?.title == "Goal replaced")
        #expect(await h.session.pauseGoal() == .done)
        #expect(h.toasts.current?.title == "Goal paused")
        #expect(await h.session.resumeGoal() == .done)
        #expect(h.toasts.current?.title == "Goal resumed")
        #expect(await h.session.clearGoal() == .done)
        #expect(h.toasts.current?.title == "Goal cleared")
        #expect(h.goals.commands == ["Make login work offline", "Ship the release", "pause", "resume", "clear"])
    }

    @Test func setWhileBusySaysItStartsAtTheNextStep() async {
        let h = GoalHarness(busy: true)
        await h.open()
        h.session.openGoalSheet()
        #expect(await h.session.setGoal("Ship it") == .done)
        #expect(h.toasts.current?.detail == "The agent starts on it at its next step.")
        #expect(!h.session.showsGoalSheet)
        #expect(await h.session.clearGoal() == .done)
        #expect(h.toasts.current?.detail == "The agent finishes this turn.")
    }

    @Test func aStaleCommandReadsTheGoalAndSaysSo() async {
        let h = GoalHarness(goal: goal(.active))
        await h.open()
        h.goals.goals["ses_a"] = goal(.paused, reason: "Paused by the user")
        h.goals.commandError = GoalAPIError.stale
        h.session.openGoalSheet()
        #expect(await h.session.pauseGoal() == .stale)
        #expect(h.session.goal?.status == .paused)
        #expect(h.toasts.current?.title == "The goal had already changed")
        #expect(h.toasts.current?.tone == .info)
        #expect(h.goals.reads.count == 2)
    }

    @Test func anyOtherFailureSaysTryAgain() async {
        let h = GoalHarness()
        await h.open()
        h.session.openGoalSheet()
        h.goals.commandError = FakeGoals.Failure()
        #expect(await h.session.setGoal("Ship it") == .failed)
        #expect(h.session.showsGoalSheet)
        #expect(h.toasts.current?.title == "Couldn’t change the goal")
        #expect(h.toasts.current?.tone == .stop)
    }

    @Test func withoutAnEventWithinTwoSecondsTheAppReadsTheGoal() async {
        let h = GoalHarness()
        await h.open()
        #expect(h.goals.reads.count == 1)
        h.goals.goals["ses_a"] = goal(.active)
        _ = await h.session.setGoal("Ship it")
        await h.eventually { h.goals.reads.count == 2 }
        #expect(h.session.goal?.id == "g1")
    }

    @Test func anEventAfterTheCommandMeansNoRead() async {
        let h = GoalHarness()
        await h.open()
        h.session.sleep = { _ in try await Task.sleep(for: .milliseconds(100)) }
        _ = await h.session.setGoal("Ship it")
        await h.send(EventLines.goal("ses_a"))
        try? await Task.sleep(for: .milliseconds(200))
        #expect(h.goals.reads.count == 1)
    }

    // MARK: Old kickoff

    @Test func clearTakesBackTheOldKickoffStillWaiting() async {
        let h = GoalHarness(busy: true)
        await h.open()
        await h.send(EventLines.goal("ses_a", id: "g1"))
        await h.send(EventLines.goalItem("ses_a", id: "inb_kick", goalID: "g1"))
        await h.send(EventLines.enqueued("ses_a", id: "inb_msg"))
        #expect(await h.session.clearGoal() == .done)
        #expect(h.server.cancelled == ["inb_kick"])
    }

    @Test func replaceTakesBackOnlyTheOldGoalsKickoff() async {
        let h = GoalHarness(busy: true)
        await h.open()
        await h.send(EventLines.goal("ses_a", id: "g1"))
        await h.send(EventLines.goalItem("ses_a", id: "inb_old", goalID: "g1"))
        await h.send(EventLines.goalItem("ses_a", id: "inb_other", goalID: "g0"))
        #expect(await h.session.setGoal("Ship the release") == .done)
        #expect(h.server.cancelled == ["inb_old"])
    }

    @Test func aKickoffAlreadyDeliveredIsLeftAlone() async {
        let h = GoalHarness(busy: true)
        await h.open()
        await h.send(EventLines.goal("ses_a", id: "g1"))
        await h.send(EventLines.goalItem("ses_a", id: "inb_kick", goalID: "g1"))
        await h.send(EventLines.delivered("ses_a", id: "inb_kick"))
        #expect(await h.session.clearGoal() == .done)
        #expect(h.server.cancelled.isEmpty)
    }

    // MARK: Goal items

    @Test func goalItemsNeverWaitAndNeverSetTheYouLine() async {
        let h = GoalHarness(busy: true)
        await h.open()
        await h.send(EventLines.goalItem("ses_a", id: "inb_kick", type: "synthetic", delivery: "queue"))
        await h.send(EventLines.goalItem("ses_a", id: "inb_user_kick", type: "user", delivery: "queue"))
        await h.send(EventLines.goalItem("ses_a", id: "inb_round", goalID: nil, type: "synthetic", delivery: "queue"))
        #expect(h.session.waitingCount == 0)
        #expect(h.session.undeliveredMessages.isEmpty)
        for id in ["inb_kick", "inb_user_kick", "inb_round"] {
            await h.send(EventLines.delivered("ses_a", id: id))
        }
        #expect(h.session.sentMessage == nil)
    }

    @Test func aSubagentsNoteDoesntSetTheYouLine() async {
        let h = GoalHarness(busy: true)
        await h.open()
        await h.send(EventLines.line(
            "session.inbox.enqueued",
            #"{"sessionID":"ses_a","inboxID":"inb_note","item":{"type":"synthetic","delivery":"steer","payload":{"text":"<subagent id=\"x\">done</subagent>"}}}"#
        ))
        await h.send(EventLines.delivered("ses_a", id: "inb_note"))
        #expect(h.session.sentMessage == nil)
    }

    @Test func goalItemsInTheSnapshotDontWaitEither() async {
        let h = GoalHarness(busy: true, inbox: [
            InboxEntry(id: "inb_kick", delivery: "queue", text: "A goal is now active", kind: "user", source: "goal", goalID: "g1"),
            InboxEntry(id: "inb_msg", delivery: "queue", text: "hi"),
        ])
        await h.open()
        #expect(h.session.waitingMessages.map(\.id) == ["inb_msg"])
    }

    @Test func setAsGoalFromADraftMakesTheYouLine() async {
        let h = GoalHarness()
        await h.open()
        #expect(await h.session.setGoal("Make login work offline", fromDraft: true) == .done)
        #expect(h.session.sentMessage == "Make login work offline")
        #expect(h.session.sentIsGoal)
        // A message delivered later replaces it.
        await h.send(EventLines.enqueued("ses_a", id: "inb_msg"))
        await h.send(EventLines.delivered("ses_a", id: "inb_msg"))
        #expect(h.session.sentMessage == "hi")
        #expect(!h.session.sentIsGoal)
    }

    // MARK: Summary

    @Test func aHigherRoundAsksForOneMidRunRefreshTheSameRoundDoesnt() async {
        let h = GoalHarness(goal: goal(.active, rounds: 0), busy: true)
        await h.open()
        let before = h.generateCount
        await h.send(EventLines.goal("ses_a", rounds: 0))
        #expect(h.generateCount == before)
        await h.send(EventLines.goal("ses_a", rounds: 1))
        await h.eventually { h.generateCount == before + 1 }
        #expect(h.generateCount == before + 1)
        await h.send(EventLines.goal("ses_a", rounds: 1))
        await h.send(EventLines.goal("ses_a", status: "complete", rounds: 1))
        #expect(h.generateCount == before + 1)
        // Mid-run: the card drops Next.
        if case let .card(card)? = h.session.summary.summary { #expect(card.next == nil) }
    }

    @Test func aNewGoalStartsCountingAgain() async {
        let h = GoalHarness(goal: goal(.active, rounds: 4), busy: true)
        await h.open()
        let before = h.generateCount
        await h.send(EventLines.goal("ses_a", id: "g2", rounds: 0))
        await h.send(EventLines.goal("ses_a", id: "g2", rounds: 1))
        await h.eventually { h.generateCount == before + 1 }
        #expect(h.generateCount == before + 1)
    }

    // MARK: New day

    @Test func yesterdaysActiveGoalIsNamedInTheNewDayNotice() async {
        let h = GoalHarness(memory: FakeMemory("2026-11-04", session: "ses_y"))
        h.server.listed = []
        h.goals.goals["ses_y"] = goal(.active)
        await h.session.open()
        #expect(h.goals.reads.contains("ses_y"))
        #expect(h.toasts.current?.title == "New day, new session")
        #expect(h.toasts.current?.detail == "Yesterday’s is kept on your computer. Its goal keeps running there.")
    }

    @Test func yesterdaysPausedGoalLeavesTheNoticeAsItWas() async {
        let h = GoalHarness(memory: FakeMemory("2026-11-04", session: "ses_y"))
        h.server.listed = []
        h.goals.goals["ses_y"] = goal(.paused)
        await h.session.open()
        #expect(h.toasts.current?.detail == "Yesterday’s is kept on your computer.")
        // Today's session starts with no goal.
        #expect(h.session.goal == nil)
    }
}

// MARK: Goal text

struct GoalTextTests {
    @Test func aCommandWordIsRefusedInAnyCaseWithTrailingPunctuation() {
        #expect(GoalText.refusal("Pause") == .commandWord)
        #expect(GoalText.refusal("resume.") == .commandWord)
        #expect(GoalText.refusal("CLEAR!") == .commandWord)
        #expect(GoalText.refusal("  clear?  ") == .commandWord)
        #expect(GoalText.refusal("Pause the deploy") == nil)
    }

    @Test func overFourThousandCharactersIsRefused() {
        #expect(GoalText.refusal(String(repeating: "a", count: 4000)) == nil)
        #expect(GoalText.refusal(String(repeating: "a", count: 4001)) == .tooLong)
    }

    @Test func theObjectiveIsTrimmedWithNoPrefix() {
        #expect(GoalText.objective("  Ship it \n") == "Ship it")
        #expect(GoalCommand.set("Ship it").text == "Ship it")
        #expect(GoalCommand.pause.text == "pause")
        #expect(GoalCommand.resume.text == "resume")
        #expect(GoalCommand.clear.text == "clear")
    }

    @MainActor
    @Test func aRefusedGoalSendsNothing() async {
        let h = GoalHarness()
        await h.open()
        #expect(await h.session.setGoal("Pause.") == .refused(.commandWord))
        #expect(h.goals.commands.isEmpty)
        #expect(h.toasts.current?.title == "Say a longer goal")
        #expect(h.toasts.current?.detail == "A single word is read as a goal command.")
    }
}

// MARK: Set as goal from a draft

/// Where the draft's menu goes, for the tests.
@MainActor
final class FakeGoalSender: MessageSending {
    var agentBusy = false
    var hasGoal = false
    var outcome = GoalOutcome.done
    private(set) var goals: [(text: String, images: [String])] = []
    /// The files of each goal set.
    private(set) var goalFiles: [[OutgoingFile]] = []
    private(set) var questions: [String] = []

    func send(_ text: String, images: [OutgoingImage], files: [OutgoingFile], delivery: Delivery) async throws -> String { "msg_1" }
    func cancelWaiting(_ itemID: String) async throws {}
    func waitForConnection() async {}

    func setGoal(_ text: String, images: [OutgoingImage], files: [OutgoingFile], fromDraft: Bool) async -> GoalOutcome {
        goals.append((text, images.map(\.name)))
        goalFiles.append(files)
        return outcome
    }

    func askOnTheSide(_ question: String) {
        questions.append(question)
    }
}

@MainActor
struct DraftMenuTests {
    private func make(_ sender: FakeGoalSender) -> HomeModel {
        let folder = TestImages.folder()
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech(), sleep: { _ in }) { content in
            guard case let .data(data) = content else { throw PickedImage.Unreadable() }
            return try ImagePreparer.prepare(data: data, in: folder)
        }
        home.sender = sender
        return home
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 400 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func setAsGoalSendsTheChunksJoinedWithTheImages() async {
        let sender = FakeGoalSender()
        let home = make(sender)
        home.addSuggestion("Make login")
        home.addSuggestion("work offline")
        home.attach([PickedImage(kind: .screenshot) { .data(TestImages.jpeg(width: 64, height: 48)) }])
        await eventually { !home.imagesPending }
        #expect(home.canUseMenuItems)
        home.setDraftAsGoal()
        #expect(home.draft.isEmpty)
        await eventually { !sender.goals.isEmpty }
        #expect(sender.goals.first?.text == "Make login work offline")
        #expect(sender.goals.first?.images == ["Screenshot 1.jpg"])
    }

    @Test func aRefusedGoalKeepsTheDraft() async {
        let sender = FakeGoalSender()
        let home = make(sender)
        home.addSuggestion("Pause.")
        home.setDraftAsGoal()
        #expect(home.draft.text == "Pause.")
        #expect(home.toast?.title == "Say a longer goal")
        try? await Task.sleep(for: .milliseconds(30))
        #expect(sender.goals.isEmpty)
    }

    @Test func aFailedGoalBringsTheDraftBack() async {
        let sender = FakeGoalSender()
        sender.outcome = .failed
        let home = make(sender)
        home.addSuggestion("Ship it")
        home.setDraftAsGoal()
        #expect(home.draft.isEmpty)
        await eventually { !home.draft.isEmpty }
        #expect(home.draft.text == "Ship it")
    }

    @Test func askSendsTheQuestionAndClearsTheDraft() async {
        let sender = FakeGoalSender()
        let home = make(sender)
        home.addSuggestion("Why did")
        home.addSuggestion("the tests fail?")
        home.askDraft()
        #expect(home.draft.isEmpty)
        #expect(sender.questions == ["Why did the tests fail?"])
    }

    @Test func theItemsWaitForEveryChunk() async {
        let sender = FakeGoalSender()
        let home = make(sender)
        #expect(!home.canUseMenuItems)
        await home.toggleHandsFreeTake()
        #expect(!home.canUseMenuItems)
        home.askDraft()
        home.setDraftAsGoal()
        #expect(sender.questions.isEmpty)
        try? await Task.sleep(for: .milliseconds(30))
        #expect(sender.goals.isEmpty)
    }
}

// MARK: Ask

/// A generate call the test answers.
@MainActor
final class FakeAsker: @unchecked Sendable {
    private(set) var prompts: [String] = []
    private var waiting: [CheckedContinuation<Result<String, Error>, Never>] = []

    nonisolated init() {}

    var generate: @Sendable (String) async throws -> String {
        { [self] prompt in
            let result = await self.call(prompt)
            return try result.get()
        }
    }

    private func call(_ prompt: String) async -> Result<String, Error> {
        prompts.append(prompt)
        return await withCheckedContinuation { waiting.append($0) }
    }

    var pending: Int { waiting.count }

    func reply(_ result: Result<String, Error>) {
        guard !waiting.isEmpty else { return }
        waiting.removeFirst().resume(returning: result)
    }
}

@MainActor
final class MemoryAskStore: AskHistoryStoring {
    var saved: SavedAsks?
    func load() -> SavedAsks? { saved }
    func save(_ saved: SavedAsks) { self.saved = saved }
}

@MainActor
struct AskTests {
    private func make(timeout: Duration = .seconds(60), store: MemoryAskStore? = nil) -> (AskModel, FakeAsker) {
        let asker = FakeAsker()
        let model = AskModel(store: store ?? MemoryAskStore(), timeout: timeout)
        model.open(sessionID: "ses_a")
        model.generate = asker.generate
        return (model, asker)
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 300 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func thePromptIsTheWrapperThenTheQuestion() async {
        let (model, asker) = make()
        model.ask("  What does mathutils.py have?  ")
        await eventually { asker.pending == 1 }
        #expect(asker.prompts == [SessionPrompts.Ask.wrapper + "\n\nQuestion: What does mathutils.py have?"])
        #expect(asker.prompts[0].hasPrefix("The user is asking a quick side question about the conversation so far."))
        #expect(model.shown?.state == .asking)
        asker.reply(.success("  It has `add` and `sub`.  "))
        await eventually { model.shown?.state != .asking }
        #expect(model.shown?.answer == "It has `add` and `sub`.")
    }

    @Test func anEmptyReplyShowsTheEmptyState() async {
        let (model, asker) = make()
        model.ask("Check whether the tests pass.")
        await eventually { asker.pending == 1 }
        asker.reply(.success(""))
        await eventually { model.shown?.state == .empty }
        #expect(model.shown?.state == .empty)
    }

    @Test func aFailureShowsFailedAndTryAgainSendsTheSameQuestion() async {
        let (model, asker) = make()
        model.ask("Why did the auth tests fail?")
        await eventually { asker.pending == 1 }
        asker.reply(.failure(SessionStateAPIError.httpStatus(503, "ServiceUnavailableError")))
        await eventually { model.shown?.state == .failed }
        model.retry()
        await eventually { asker.pending == 1 }
        #expect(asker.prompts.count == 2)
        #expect(asker.prompts[1] == asker.prompts[0])
        asker.reply(.success("One test still uses the old helper."))
        await eventually { model.shown?.answer != nil }
        #expect(model.shown?.question == "Why did the auth tests fail?")
    }

    @Test func theTimeoutShowsFailed() async {
        let (model, asker) = make(timeout: .milliseconds(50))
        model.ask("Anything?")
        await eventually { model.shown?.state == .failed }
        #expect(model.shown?.state == .failed)
        asker.reply(.success("too late"))
    }

    @Test func aGoneSessionDropsTheMessageAndRunsSetup() async {
        let (model, asker) = make()
        var gone = false
        model.onSessionGone = { gone = true }
        model.ask("Anything?")
        await eventually { asker.pending == 1 }
        asker.reply(.failure(SessionStateAPIError.httpStatus(404, "SessionNotFoundError")))
        await eventually { gone }
        #expect(model.shown == nil)
    }

    @Test func aNewAskMovesAnAnsweredOneIntoHistoryAndAbandonsAnUnansweredOne() async {
        let (model, asker) = make()
        model.ask("First?")
        await eventually { asker.pending == 1 }
        asker.reply(.success("One."))
        await eventually { model.shown?.answer == "One." }
        model.ask("Second?")
        #expect(model.history.map(\.question) == ["First?"])
        await eventually { asker.pending == 1 }
        model.ask("Third?")
        #expect(model.history.map(\.question) == ["First?"])
        await eventually { asker.pending == 2 }
        // The abandoned one's reply is ignored.
        asker.reply(.success("Two."))
        try? await Task.sleep(for: .milliseconds(30))
        #expect(model.shown?.question == "Third?")
        #expect(model.shown?.state == .asking)
        asker.reply(.success("Three."))
        await eventually { model.shown?.answer == "Three." }
    }

    @Test func closingKeepsAnAnswerAndDropsTheRest() async {
        let (model, asker) = make()
        model.ask("Failing?")
        await eventually { asker.pending == 1 }
        asker.reply(.failure(URLError(.timedOut)))
        await eventually { model.shown?.state == .failed }
        model.close()
        #expect(model.shown == nil)
        #expect(model.history.isEmpty)
        model.ask("Answered?")
        await eventually { asker.pending == 1 }
        asker.reply(.success("Yes."))
        await eventually { model.shown?.answer == "Yes." }
        model.expand()
        #expect(model.isOpen)
        model.close()
        #expect(model.shown == nil)
        #expect(!model.isOpen)
        #expect(model.history.map(\.answer) == ["Yes."])
    }

    @Test func anAskAndASummaryRunTogetherAndASecondRefreshIsStillDropped() async {
        let summaryGate = Gate()
        let generator = GatedSummary(gate: summaryGate)
        let summary = StatusSummaryModel(generator: generator)
        let (model, asker) = make()
        let first = Task { await summary.refresh() }
        await eventually { generator.calls == 1 }
        model.ask("Still going?")
        await eventually { asker.pending == 1 }
        #expect(asker.prompts.count == 1)
        await summary.refresh()
        #expect(generator.calls == 1)
        asker.reply(.success("Yes."))
        await eventually { model.shown?.answer == "Yes." }
        summaryGate.open()
        await first.value
    }
}

/// A summary generator that waits for the test.
final class GatedSummary: SummaryGenerating, @unchecked Sendable {
    let gate: Gate
    private let lock = NSLock()
    private var count = 0

    init(gate: Gate) { self.gate = gate }

    var calls: Int { lock.withLock { count } }

    func generateSummary(prompt: String) async throws -> String {
        lock.withLock { count += 1 }
        await gate.wait()
        return "## Task\nNow: Doing it."
    }
}

@MainActor
struct AskHistoryTests {
    private func answered(_ question: String, _ model: AskModel, _ asker: FakeAsker) async {
        model.ask(question)
        for _ in 0 ..< 300 where asker.pending == 0 { try? await Task.sleep(for: .milliseconds(5)) }
        asker.reply(.success("A: \(question)"))
        for _ in 0 ..< 300 where model.shown?.answer == nil { try? await Task.sleep(for: .milliseconds(5)) }
    }

    @Test func atMostFiveNewestFirst() async {
        let model = AskModel(store: MemoryAskStore())
        let asker = FakeAsker()
        model.open(sessionID: "ses_a")
        model.generate = asker.generate
        for n in 1 ... 7 { await answered("Q\(n)", model, asker) }
        model.close()
        #expect(model.history.map(\.question) == ["Q7", "Q6", "Q5", "Q4", "Q3"])
    }

    @Test func savedForTodaysSessionAndEmptyForANewOne() async {
        let store = MemoryAskStore()
        let model = AskModel(store: store)
        let asker = FakeAsker()
        model.open(sessionID: "ses_a")
        model.generate = asker.generate
        await answered("Q1", model, asker)
        await answered("Q2", model, asker)

        let again = AskModel(store: store)
        again.open(sessionID: "ses_a")
        #expect(again.shown?.question == "Q2")
        #expect(again.history.map(\.question) == ["Q1"])

        let tomorrow = AskModel(store: store)
        tomorrow.open(sessionID: "ses_b")
        #expect(tomorrow.shown == nil)
        #expect(tomorrow.history.isEmpty)
    }

    @Test func openingARowSwapsItWithTheShownMessage() async {
        let model = AskModel(store: MemoryAskStore())
        let asker = FakeAsker()
        model.open(sessionID: "ses_a")
        model.generate = asker.generate
        await answered("Q1", model, asker)
        await answered("Q2", model, asker)
        let row = model.history[0]
        #expect(row.question == "Q1")
        model.openFromHistory(row.id)
        #expect(model.shown?.question == "Q1")
        #expect(model.isOpen)
        #expect(model.history.map(\.question) == ["Q2"])
    }

    @Test func theAgeIsNowThenMinutes() {
        let start = Date(timeIntervalSince1970: 1_000)
        #expect(AskAge.text(since: start, at: start.addingTimeInterval(44)) == "now")
        #expect(AskAge.text(since: start, at: start.addingTimeInterval(45)) == "1m")
        #expect(AskAge.text(since: start, at: start.addingTimeInterval(190)) == "3m")
    }

    @Test func detailsOpensARowOnceItHasClosed() async {
        let h = GoalHarness(ask: AskModel(store: MemoryAskStore()))
        await h.open()
        h.session.ask.ask("Q1")
        await h.eventually { h.session.ask.shown?.answer != nil }
        h.session.ask.close()
        let row = h.session.ask.history[0]
        h.session.showsDetails = true
        h.session.openAskFromDetails(row.id)
        #expect(!h.session.showsDetails)
        h.session.detailsDismissed()
        #expect(h.session.ask.shown?.id == row.id)
        #expect(h.session.ask.isOpen)
        #expect(h.session.ask.history.isEmpty)
    }
}

// MARK: Run end while a refresh runs

/// A summary generator that holds every request until the test opens its gate, then answers
/// with a full card (Next included).
final class HeldSummary: SummaryGenerating, @unchecked Sendable {
    let gate = Gate()
    private let lock = NSLock()
    private var count = 0

    var calls: Int { lock.withLock { count } }

    func generateSummary(prompt: String) async throws -> String {
        lock.withLock { count += 1 }
        await gate.wait()
        return "## Task\nNow: Done it.\nNext: Ship it"
    }
}

/// App plan, "When": a refresh asked for while one runs is dropped, except a run end, which waits
/// and runs once when the current refresh finishes, so a mid-run card never outlives the run.
@MainActor
struct RunEndRefreshTests {
    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 300 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func aRunEndDuringAMidRunRefreshRunsOnceItFinishes() async {
        let generator = HeldSummary()
        let summary = StatusSummaryModel(generator: generator)
        var busy = true
        summary.isWorking = { busy }
        let midRun = Task { await summary.refresh() }
        await eventually { generator.calls == 1 }
        busy = false
        let runEnd = Task { await summary.refresh(runEnd: true) }
        try? await Task.sleep(for: .milliseconds(30))
        #expect(generator.calls == 1)
        generator.gate.open()
        await midRun.value
        await runEnd.value
        await eventually { generator.calls == 2 }
        #expect(generator.calls == 2)
        // The run-end card is a full one: it keeps its Next.
        await eventually { !summary.isUpdating }
        if case let .card(card)? = summary.summary { #expect(card.next == "Ship it") } else { Issue.record("expected a card") }
    }

    @Test func atMostOneRunEndWaits() async {
        let generator = HeldSummary()
        let summary = StatusSummaryModel(generator: generator)
        let first = Task { await summary.refresh() }
        await eventually { generator.calls == 1 }
        await summary.refresh(runEnd: true)
        await summary.refresh(runEnd: true)
        generator.gate.open()
        await first.value
        await eventually { generator.calls == 2 }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(generator.calls == 2)
    }

    @Test func anyOtherRefreshIsStillDropped() async {
        let generator = HeldSummary()
        let summary = StatusSummaryModel(generator: generator)
        let first = Task { await summary.refresh() }
        await eventually { generator.calls == 1 }
        await summary.refresh()
        generator.gate.open()
        await first.value
        try? await Task.sleep(for: .milliseconds(50))
        #expect(generator.calls == 1)
    }

    @Test func aWaitingRunEndGoesWithTheCard() async {
        let generator = HeldSummary()
        let summary = StatusSummaryModel(generator: generator)
        let first = Task { await summary.refresh() }
        await eventually { generator.calls == 1 }
        await summary.refresh(runEnd: true)
        // Another session opened: the request on its way and the waiting run end both go.
        summary.clear()
        generator.gate.open()
        await first.value
        try? await Task.sleep(for: .milliseconds(50))
        #expect(generator.calls == 1)
    }
}
