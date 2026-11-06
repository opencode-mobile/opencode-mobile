import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// M4 (app plan, "Needs you and control" › Testing): approvals, pending, questions, stop, details.

/// What a test's control API was asked, in order.
@MainActor
final class FakeControl: SessionControlAPI, @unchecked Sendable {
    enum Call: Equatable {
        case approval(session: String, id: String, decision: String, note: String?)
        case answer(session: String, id: String, answer: [String: FormAnswerV2])
        case dismiss(session: String, id: String, note: String)
        case interrupt(String)
        case runningSubagents(String)
        case running(String)
        case recent(String)
    }

    struct Failure: Error {}

    var calls: [Call] = []
    /// Thrown by the next answer: `ControlError.gone`, or another error.
    var answerError: (any Error)?
    /// What each interrupt answers, by session id; missing ones answer false.
    var interrupted: [String: Bool] = [:]
    var interruptFails = false
    var subagents: [String] = []
    var runningSnapshot = RunningSnapshot(childBusySince: [:], childSessions: [:], shells: [])
    var tools: [ToolPartV2] = []

    nonisolated init() {}

    func replyToApproval(sessionID: String, requestID: String, decision: SessionStateAPIClient.PermissionDecision, note: String?) async throws {
        calls.append(.approval(session: sessionID, id: requestID, decision: decision.rawValue, note: note))
        if let error = answerError { answerError = nil; throw error }
    }

    func answerQuestion(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws {
        calls.append(.answer(session: sessionID, id: formID, answer: answer))
        if let error = answerError { answerError = nil; throw error }
    }

    func dismissQuestion(sessionID: String, formID: String, note: String) async throws {
        calls.append(.dismiss(session: sessionID, id: formID, note: note))
        if let error = answerError { answerError = nil; throw error }
    }

    func interrupt(sessionID: String) async throws -> Bool {
        calls.append(.interrupt(sessionID))
        if interruptFails { throw Failure() }
        return interrupted[sessionID] ?? false
    }

    func runningSubagents(rootID: String) async throws -> [String] {
        calls.append(.runningSubagents(rootID))
        return subagents
    }

    func running(rootID: String, directory: String) async throws -> RunningSnapshot {
        calls.append(.running(rootID))
        return runningSnapshot
    }

    func recentTools(sessionID: String) async throws -> [ToolPartV2] {
        calls.append(.recent(sessionID))
        return tools
    }
}

/// Today's session against a fake server, stream and control API.
@MainActor
struct ControlHarness {
    let server = FakeSessionServer()
    let control = FakeControl()
    let transport = FakeTransport()
    let toasts = HexToaster()
    let session: TodaySession

    init(snapshot: LiveSnapshot? = nil) {
        if let snapshot { server.snapshot = snapshot }
        server.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: "/p", updatedAt: Date(), busySince: Date(timeIntervalSince1970: 1))]
        session = TodaySession(
            setup: DailySessionSetup(service: server, memory: FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: toasts,
            api: server,
            control: control,
            stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
            now: { Date(timeIntervalSince1970: 1_793_880_000) },
            timeZone: TimeZone(identifier: "UTC")!
        )
    }

    /// Opens today's session and its stream, and reads the state back.
    func open() async {
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        await eventually { server.calls.contains(.snapshot("ses_a")) }
        try? await Task.sleep(for: .milliseconds(30))
    }

    func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 300 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
}

enum Requests {
    static func approval(_ id: String, session: String = "ses_a", resources: [String] = ["ls"], save: [String] = ["ls *"]) -> PendingItem {
        PendingItem(approval: PendingPermissionV2(id: id, sessionID: session, action: "shell", resources: resources, save: save))
    }

    static let twoQuestions = PendingFormV2(id: "frm_1", sessionID: "ses_a", title: "Questions", kind: "question", fields: [
        .init(key: "q0", title: "Colour", description: "Which colour?", type: "string", options: [
            .init(value: "Red", label: "Red", description: "warm"),
            .init(value: "Blue", label: "Blue", description: "cool"),
        ], custom: true),
        .init(key: "q1", title: "Fruits", description: "Which fruits?", type: "multiselect", options: [
            .init(value: "Apple", label: "Apple", description: ""),
            .init(value: "Kiwi", label: "Kiwi", description: ""),
            .init(value: "Pear", label: "Pear", description: ""),
        ], custom: true),
    ])
}

// MARK: Approvals

@MainActor
struct ApprovalTests {
    private func harness(_ pending: [PendingItem], children: [String] = []) async -> ControlHarness {
        let harness = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: children, pending: pending, inbox: []))
        await harness.open()
        return harness
    }

    @Test func onceAlwaysAndDenySendTheirBodies() async {
        let h = await harness([Requests.approval("per_1"), Requests.approval("per_2"), Requests.approval("per_3")])
        #expect(await h.session.reply(to: h.session.pendingItems[0], .once) == .sent)
        #expect(await h.session.reply(to: h.session.pendingItems[0], .always) == .sent)
        #expect(await h.session.reply(to: h.session.pendingItems[0], .reject) == .sent)
        #expect(h.control.calls == [
            .approval(session: "ses_a", id: "per_1", decision: "once", note: nil),
            .approval(session: "ses_a", id: "per_2", decision: "always", note: nil),
            .approval(session: "ses_a", id: "per_3", decision: "reject", note: SessionPrompts.Needs.denyNote),
        ])
    }

    @Test func denySendsTheSpokenNoteOrTheDefault() async {
        let h = await harness([Requests.approval("per_1"), Requests.approval("per_2", session: "ses_b")], children: ["ses_b"])
        _ = await h.session.reply(to: h.session.pendingItems[0], .reject, note: "  Use printf instead.  ")
        _ = await h.session.reply(to: h.session.pendingItems[0], .reject, note: "   ")
        #expect(h.control.calls == [
            .approval(session: "ses_a", id: "per_1", decision: "reject", note: "Use printf instead."),
            .approval(session: "ses_b", id: "per_2", decision: "reject", note: "The user denied this request. Don't try it again; carry on another way, or ask if you're stuck."),
        ])
    }

    @Test func aDenyAlsoTakesTheSessionsOtherApprovals() async {
        let h = await harness([Requests.approval("per_1"), Requests.approval("per_2"), Requests.approval("per_3", session: "ses_c")], children: ["ses_c"])
        _ = await h.session.reply(to: h.session.pendingItems[0], .reject)
        #expect(h.session.pendingItems.map(\.id) == ["per_3"])
    }

    @Test func aChildsRequestIsAnsweredOnTheChildsRoute() async {
        let h = await harness([Requests.approval("per_c", session: "ses_child")], children: ["ses_child"])
        _ = await h.session.reply(to: h.session.pendingItems[0], .once)
        #expect(h.control.calls == [.approval(session: "ses_child", id: "per_c", decision: "once", note: nil)])
    }

    @Test func aGoneReplyClosesTheSheetWithItsNotice() async {
        let h = await harness([Requests.approval("per_1"), Requests.approval("per_2")])
        h.session.openPendingInput()
        #expect(h.session.presentedNeed?.id == "per_1")
        h.control.answerError = ControlError.gone
        #expect(await h.session.reply(to: h.session.pendingItems[0], .once) == .gone)
        #expect(h.session.presentedNeed == nil)
        #expect(h.session.pendingItems.map(\.id) == ["per_2"])
        #expect(h.toasts.current?.title == "No longer needed")
        // Gone doesn't open the next one by itself.
        try? await Task.sleep(for: .milliseconds(400))
        #expect(h.session.presentedNeed == nil)
    }

    @Test func aFailedReplyKeepsTheSheet() async {
        let h = await harness([Requests.approval("per_1")])
        h.session.openPendingInput()
        h.control.answerError = FakeControl.Failure()
        #expect(await h.session.reply(to: h.session.pendingItems[0], .once) == .failed)
        #expect(h.session.presentedNeed?.id == "per_1")
        #expect(h.session.pendingItems.count == 1)
        #expect(h.toasts.current?.title == "Couldn’t send")
        #expect(h.toasts.current?.tone == .stop)
    }

    @Test func twoPendingOpenOneAfterTheOther() async {
        let h = await harness([Requests.approval("per_1"), Requests.approval("per_2")])
        h.session.openPendingInput()
        #expect(NeedsSheet.count(of: h.session.presentedNeed!, in: h.session.pendingItems) == "1 of 2")
        _ = await h.session.reply(to: h.session.presentedNeed!, .once)
        #expect(h.session.presentedNeed == nil)
        #expect(h.toasts.current == nil)
        await h.eventually { h.session.presentedNeed != nil }
        #expect(h.session.presentedNeed?.id == "per_2")
        #expect(NeedsSheet.count(of: h.session.presentedNeed!, in: h.session.pendingItems) == nil)
    }

    @Test func answeredElsewhereClosesTheSheet() async {
        let h = await harness([Requests.approval("per_1")])
        h.session.openPendingInput()
        h.transport.send(EventLines.replied("ses_a", id: "per_1"))
        await h.eventually { h.session.presentedNeed == nil }
        #expect(h.toasts.current?.title == "No longer needed")
        #expect(h.toasts.current?.detail == "It was answered elsewhere or the run ended.")
    }

    @Test func hideLeavesItWaiting() async {
        let h = await harness([Requests.approval("per_1")])
        h.session.openPendingInput()
        h.session.hideNeed()
        #expect(h.session.presentedNeed == nil)
        #expect(h.session.pendingInput?.id == "per_1")
    }

    @Test func whatTheSheetShows() {
        let shell = PendingPermissionV2(id: "per_1", sessionID: "ses_a", action: "shell", resources: ["sleep 20", "echo done"], save: ["sleep *", "echo *"])
        let content = ApprovalContent(shell, childTitle: nil, projectID: "c6e7")
        #expect(content.what == "Run a command")
        #expect(content.fromSubagent == nil)
        #expect(content.code == [HexCodeLine("sleep 20"), HexCodeLine("echo done")])
        #expect(content.alwaysLine == "Always allows `sleep *`, `echo *` in this project, from now on.")
        #expect(ApprovalContent(shell, childTitle: "m4 date check", projectID: nil).fromSubagent == "From a subagent · m4 date check")
        #expect(ApprovalContent.what("webfetch") == "Open a web page")
        #expect(ApprovalContent.what("subagent") == "Start a subagent")
        #expect(ApprovalContent.what("edit") == "Edit files")
        #expect(ApprovalContent.what("mcp_tool") == "mcp_tool")
    }

    @Test func alwaysIsHiddenForGlobalProjectsAndEmptySaves() {
        #expect(ApprovalContent.alwaysLine(save: ["ls *"], action: "shell", projectID: "global") == nil)
        #expect(ApprovalContent.alwaysLine(save: [], action: "shell", projectID: "c6e7") == nil)
        #expect(ApprovalContent.alwaysLine(save: ["*"], action: "edit", projectID: "c6e7") == "Always allows any edit in this project, from now on.")
    }

    @Test func anEditShowsEachFileAndItsDiffCappedAt12Lines() {
        let patch = (["--- a/x.ts", "+++ b/x.ts", "@@ -1,20 +1,20 @@", " context"] + (1 ... 8).map { "-old \($0)" } + (1 ... 8).map { "+new \($0)" }).joined(separator: "\n")
        let edit = PendingPermissionV2(id: "per_e", sessionID: "ses_a", action: "edit", resources: ["x.ts", "y.ts"], save: ["*"], files: [
            .init(file: "x.ts", patch: patch, status: "modified", additions: 8, deletions: 8),
            .init(file: "y.ts", patch: "+one", status: "added", additions: 1, deletions: 0),
        ])
        let code = ApprovalContent(edit, childTitle: nil, projectID: "p").code
        #expect(code.first == HexCodeLine("x.ts"))
        #expect(code[1] == HexCodeLine("-old 1", kind: .removed))
        #expect(code[12] == HexCodeLine("+new 4", kind: .added))
        #expect(code[13] == HexCodeLine("… 4 more lines", kind: .note))
        #expect(code.suffix(2) == [HexCodeLine("y.ts"), HexCodeLine("+one", kind: .added)])
        // No diff: just the paths.
        let bare = PendingPermissionV2(id: "per_b", sessionID: "ses_a", action: "edit", resources: ["a.ts", "b.ts"], save: ["*"])
        #expect(ApprovalContent(bare, childTitle: nil, projectID: "p").code == [HexCodeLine("a.ts"), HexCodeLine("b.ts")])
    }

    @Test func aSubagentsTitleComesFromTheChild() async {
        let h = ControlHarness(snapshot: LiveSnapshot(
            busySince: Date(), hasRun: true, children: ["ses_c"], pending: [Requests.approval("per_c", session: "ses_c")], inbox: [],
            childSessions: ["ses_c": ChildSession(title: "m4 date check", agent: "general")], projectID: "global"
        ))
        await h.open()
        let item = h.session.pendingItems[0]
        #expect(h.session.subagentTitle(of: item) == "m4 date check")
        #expect(h.session.projectID == "global")
        #expect(h.session.subagentTitle(of: Requests.approval("per_x")) == nil)
    }
}

// MARK: Pending

struct PendingTests {
    @Test func anApprovalThenTheRunsEndLeavesNothingPending() {
        var state = LiveSessionState(rootID: "ses_a")
        for line in [EventLines.started("ses_a"), EventLines.permission("ses_a"), EventLines.interrupted("ses_a")] {
            if let event = SessionEvent.decode(line) { state.apply(event) }
        }
        #expect(state.pending.isEmpty)
    }

    @Test func aChildsRunEndDropsOnlyItsOwnItems() {
        var state = LiveSessionState(rootID: "ses_a")
        for line in [
            EventLines.child("ses_c", parent: "ses_a"), EventLines.permission("ses_c", id: "per_c"),
            EventLines.form("ses_a", id: "frm_a"), EventLines.succeeded("ses_c"),
        ] {
            if let event = SessionEvent.decode(line) { state.apply(event) }
        }
        #expect(state.pending.map(\.id) == ["frm_a"])
    }

    @Test func theRequestAndFormAreKeptWhole() throws {
        let asked = SessionEvent.decode(EventLines.line(
            "permission.asked",
            #"{"id":"per_1","sessionID":"ses_a","action":"edit","resources":["notes.txt"],"save":["*"],"metadata":{"files":[{"file":"notes.txt","patch":"+hi","status":"modified","additions":1,"deletions":0}]},"source":{"type":"tool","messageID":"msg_1","id":"call-1"}}"#
        ))
        guard case let .permissionAsked(item)? = asked else { Issue.record("no request"); return }
        let request = try #require(item.approval)
        #expect(request.save == ["*"])
        #expect(request.files == [.init(file: "notes.txt", patch: "+hi", status: "modified", additions: 1, deletions: 0)])
        #expect(request.source == .init(type: "tool", messageID: "msg_1", id: "call-1"))
        #expect(item.text == "notes.txt")
        guard case let .formCreated(form)? = SessionEvent.decode(EventLines.form("ses_a")) else { Issue.record("no form"); return }
        #expect(form.question?.fields.first?.title == "Proceed")
        #expect(form.text == "Run the tests now?")
    }

    @Test func aSubagentsCreationCarriesItsTitleAndAgent() {
        // v2.2.5's `session.created` has the session's fields in `data` itself.
        let line = EventLines.line(
            "session.created",
            #"{"sessionID":"ses_c","parentID":"ses_a","title":"m4 bg","agent":"general","location":{"directory":"/p"}}"#
        )
        #expect(SessionEvent.decode(line) == .sessionCreated(id: "ses_c", parentID: "ses_a", title: "m4 bg", agent: "general"))
        var state = LiveSessionState(rootID: "ses_a")
        state.apply(SessionEvent.decode(line)!)
        #expect(state.children == ["ses_c"])
        #expect(state.childSessions["ses_c"] == ChildSession(title: "m4 bg", agent: "general"))
    }
}

// MARK: Questions

@MainActor
struct QuestionTests {
    @Test func singleMultiselectAndOwnAnswerBuildTheAnswer() {
        var form = QuestionForm(Requests.twoQuestions)
        #expect(form.isSupported)
        #expect(!form.hasAnswer)
        form.choose("Blue")
        form.choose("Red")
        form.next()
        #expect(form.page == 1)
        #expect(form.isMultiple)
        form.choose("Kiwi")
        form.choose("Apple")
        form.choose("Pear")
        form.choose("Pear")
        #expect(form.answerBody == ["q0": .text("Red"), "q1": .choices(["Apple", "Kiwi"])])
        form.setOwn("dark kiwi")
        #expect(form.answerBody["q1"] == .choices(["Apple", "Kiwi", "dark kiwi"]))
        form.back()
        form.setOwn("Green, but only a dark one")
        #expect(form.answerBody["q0"] == .text("Green, but only a dark one"))
        // An option picked after the own answer replaces it.
        form.choose("Blue")
        #expect(form.answerBody["q0"] == .text("Blue"))
        form.toggleOwn()
        #expect(form.answerBody["q0"] == .text("Green, but only a dark one"))
    }

    @Test func backKeepsAnswersAndNextNeedsOne() {
        var form = QuestionForm(Requests.twoQuestions)
        form.next()
        #expect(form.page == 0)
        form.choose("Blue")
        form.next()
        form.choose("Apple")
        form.back()
        #expect(form.answer.chosen == ["Blue"])
        form.next()
        #expect(form.answer.chosen == ["Apple"])
        #expect(form.isLastPage)
        #expect(form.title == "Fruits")
    }

    @Test func anEmptyTakeKeepsTheAnswer() {
        var form = QuestionForm(Requests.twoQuestions)
        form.choose("Red")
        form.setOwn("  ")
        #expect(form.answerBody["q0"] == .text("Red"))
    }

    @Test func otherFormsAreAnsweredOnTheComputer() {
        let mcp = PendingFormV2(id: "frm_m", sessionID: "ses_a", title: "Deploy settings", fields: [.init(key: "region", title: "Region", type: "number")])
        #expect(!QuestionForm(mcp).isSupported)
        #expect(!QuestionForm(PendingFormV2(id: "frm_e", sessionID: "ses_a")).isSupported)
        #expect(QuestionForm(PendingFormV2(id: "frm_t", sessionID: "ses_a", fields: [.init(key: "q0", description: "Why?")])).title == "Question")
    }

    @Test func submitAndDismissSendTheirBodies() async {
        let item = PendingItem(question: Requests.twoQuestions)
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [item], inbox: []))
        await h.open()
        #expect(await h.session.submit(item, answer: ["q0": .text("Red")]) == .sent)
        #expect(await h.session.dismiss(item) == .sent)
        #expect(h.control.calls == [
            .answer(session: "ses_a", id: "frm_1", answer: ["q0": .text("Red")]),
            .dismiss(session: "ses_a", id: "frm_1", note: "The user dismissed these questions without answering. Carry on with your best judgement and say what you chose."),
        ])
    }

    @Test func aSettledFormCountsAsAnswered() async {
        let item = PendingItem(question: Requests.twoQuestions)
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [item], inbox: []))
        await h.open()
        h.session.openPendingInput()
        h.control.answerError = ControlError.gone
        #expect(await h.session.submit(item, answer: ["q0": .text("Red")]) == .gone)
        #expect(h.session.presentedNeed == nil)
        #expect(h.session.pendingItems.isEmpty)
        #expect(h.toasts.current?.title == "No longer needed")
    }

    @Test func aSpokenAnswerHoldsReleasesAndTaps() async {
        var clock = Date(timeIntervalSince1970: 0)
        var began = 0
        var cancelled = 0
        let answer = SpokenAnswer(begin: { began += 1; return true }, end: { " dark kiwi " }, cancel: { cancelled += 1 }, now: { clock })
        var texts: [String] = []
        var taps = 0
        answer.pressBegan()
        #expect(answer.state == .listening)
        #expect(answer.isBusy)
        clock += 1.2
        await answer.pressEnded(onText: { texts.append($0) }, onTap: { taps += 1 })
        #expect(texts == ["dark kiwi"])
        #expect(answer.text == "dark kiwi")
        #expect(answer.state == .idle)
        // A tap: the take is dropped and the row is toggled instead.
        answer.pressBegan()
        clock += 0.1
        await answer.pressEnded(onText: { texts.append($0) }, onTap: { taps += 1 })
        #expect(taps == 1)
        #expect(cancelled == 1)
        #expect(began == 2)
        #expect(texts.count == 1)
    }
}

// MARK: Stop

@MainActor
struct StopTests {
    @Test func releaseTiming() {
        #expect(StopHold.release(after: 0.2) == .tap)
        #expect(StopHold.release(after: 0.35) == .cancelled)
        #expect(StopHold.release(after: 1.5) == .cancelled)
        #expect(StopHold.release(after: 3) == .stopped)
        #expect(StopHold.secondsLeft(after: 0) == 3)
        #expect(StopHold.secondsLeft(after: 0.9) == 3)
        #expect(StopHold.secondsLeft(after: 1.1) == 2)
        #expect(StopHold.secondsLeft(after: 2.5) == 1)
        #expect(StopHold.secondsLeft(after: 3.2) == 1)
        #expect(HexPress.distanceOutside(CGPoint(x: 20, y: 20), size: CGSize(width: 46, height: 46)) == 0)
        #expect(HexPress.distanceOutside(CGPoint(x: 46 + 30, y: -40), size: CGSize(width: 46, height: 46)) == 50)
    }

    /// A hold whose seconds pass only when the test says.
    private func hold(canStop: Bool = true) -> (StopHoldModel, HexToaster, Gate, () -> Int, (TimeInterval) -> Void, () -> [StopHoldModel.Haptic]) {
        final class Box: @unchecked Sendable {
            var now = Date(timeIntervalSince1970: 0)
            var stops = 0
            var haptics: [StopHoldModel.Haptic] = []
        }
        let box = Box()
        let gate = Gate()
        let toasts = HexToaster()
        let model = StopHoldModel(
            toasts: toasts,
            canStop: { canStop },
            stop: { box.stops += 1 },
            now: { box.now },
            sleep: { _ in await gate.wait() }
        )
        model.haptic = { box.haptics.append($0) }
        return (model, toasts, gate, { box.stops }, { box.now += $0 }, { box.haptics })
    }

    @Test func releaseAtATapShowsHoldToStop() {
        let (model, toasts, _, stops, advance, haptics) = hold()
        model.pressBegan()
        #expect(model.heldSince != nil)
        #expect(toasts.isHeld)
        advance(0.2)
        model.pressEnded()
        #expect(model.heldSince == nil)
        #expect(!toasts.isHeld)
        #expect(toasts.current?.title == "Hold to stop")
        #expect(toasts.current?.detail == "Keep pressing for 3 seconds. A tap does nothing.")
        #expect(stops() == 0)
        #expect(haptics() == [.press])
    }

    @Test func releaseAt1500MillisecondsCancels() {
        let (model, toasts, _, stops, advance, _) = hold()
        model.pressBegan()
        advance(1.5)
        model.pressEnded()
        #expect(toasts.current?.title == "Stop cancelled")
        #expect(toasts.current?.tone == .info)
        #expect(toasts.current?.duration == 2.2)
        #expect(stops() == 0)
    }

    @Test func slidingOffCancels() {
        let (model, toasts, _, _, advance, _) = hold()
        model.pressBegan()
        advance(1)
        model.moved(outside: 30)
        #expect(model.heldSince != nil)
        model.moved(outside: 45)
        #expect(model.heldSince == nil)
        #expect(toasts.current?.title == "Stop cancelled")
    }

    @Test func threeSecondsStopsWithTicks() async {
        let (model, toasts, gate, stops, advance, haptics) = hold()
        model.pressBegan()
        advance(3)
        gate.open()
        for _ in 0 ..< 100 where stops() == 0 { try? await Task.sleep(for: .milliseconds(5)) }
        #expect(stops() == 1)
        #expect(model.heldSince == nil)
        #expect(!toasts.isHeld)
        #expect(haptics() == [.press, .tick, .tick, .success])
        // Lifting the finger afterwards does nothing more.
        model.pressEnded()
        #expect(toasts.current == nil)
    }

    @Test func aDimmedStopDoesNothing() {
        let (model, toasts, _, _, _, haptics) = hold(canStop: false)
        model.pressBegan()
        #expect(model.heldSince == nil)
        model.pressEnded()
        #expect(toasts.current == nil)
        #expect(haptics().isEmpty)
    }

    private func harness(inbox: [InboxEntry] = []) async -> ControlHarness {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: ["ses_c1", "ses_c2"], pending: [], inbox: inbox))
        await h.open()
        return h
    }

    @Test func todaysSessionFirstThenEachRunningSubagent() async {
        let h = await harness()
        h.control.interrupted = ["ses_a": true]
        h.control.subagents = ["ses_c1", "ses_c2"]
        await h.session.stop()
        #expect(h.control.calls == [.interrupt("ses_a"), .runningSubagents("ses_a"), .interrupt("ses_c1"), .interrupt("ses_c2")])
        #expect(h.toasts.current?.title == "Stopped")
        #expect(h.toasts.current?.detail == "Talk to start again.")
        #expect(h.toasts.current?.duration == 3.2)
    }

    @Test func withMessagesWaitingTheNoticeSaysSo() async {
        let h = await harness(inbox: [InboxEntry(id: "msg_1", delivery: "queue", text: "next")])
        h.control.interrupted = ["ses_a": true]
        await h.session.stop()
        #expect(h.toasts.current?.detail == "Waiting messages send after your next one.")
    }

    @Test func nothingToStopAndAFailure() async {
        let h = await harness()
        await h.session.stop()
        #expect(h.toasts.current?.title == "Nothing to stop")
        #expect(h.toasts.current?.tone == .info)
        h.control.interruptFails = true
        await h.session.stop()
        #expect(h.toasts.current?.title == "Couldn’t stop")
        #expect(h.toasts.current?.detail == "Check the connection and hold again.")
    }

    @Test func aSubagentStoppedCountsAsStopped() async {
        let h = await harness()
        h.control.interrupted = ["ses_c1": true]
        h.control.subagents = ["ses_c1"]
        await h.session.stop()
        #expect(h.toasts.current?.title == "Stopped")
    }

    @Test func aSubagentsNoteWakingTheSessionIsInterruptedOnceMore() async {
        let h = await harness()
        h.control.interrupted = ["ses_a": true]
        await h.session.stop()
        h.transport.send(EventLines.interrupted("ses_a"))
        h.transport.send(Self.note("ses_a"))
        h.transport.send(EventLines.started("ses_a"))
        await h.eventually { h.control.calls.filter { $0 == .interrupt("ses_a") }.count == 2 }
        #expect(h.control.calls.filter { $0 == .interrupt("ses_a") }.count == 2)
        // Only once.
        h.transport.send(EventLines.interrupted("ses_a"))
        h.transport.send(Self.note("ses_a"))
        h.transport.send(EventLines.started("ses_a"))
        try? await Task.sleep(for: .milliseconds(100))
        #expect(h.control.calls.filter { $0 == .interrupt("ses_a") }.count == 2)
    }

    @Test func theRetryNeedsTheNoteAndTheWindow() {
        let start = Date(timeIntervalSince1970: 0)
        var retry = StopRetry(rootID: "ses_a", stoppedAt: start)
        // A start without a note (someone sent a message) isn't interrupted.
        do { let fired = retry.observe(.executionStarted(sessionID: "ses_a", at: nil), at: start + 1, busy: true); #expect(!fired) }
        // A user message isn't the note.
        do { let fired = retry.observe(.inboxEnqueued(sessionID: "ses_a", id: "m", delivery: "steer", text: "<subagent", kind: "user"), at: start + 1, busy: false); #expect(!fired) }
        do { let fired = retry.observe(Self.noteEvent, at: start + 1, busy: false); #expect(!fired) }
        do { let fired = retry.observe(.executionStarted(sessionID: "ses_a", at: nil), at: start + 1.2, busy: true); #expect(fired) }
        do { let fired = retry.observe(.executionStarted(sessionID: "ses_a", at: nil), at: start + 2, busy: true); #expect(!fired) }
        var late = StopRetry(rootID: "ses_a", stoppedAt: start)
        do { let fired = late.observe(Self.noteEvent, at: start + 6, busy: false); #expect(!fired) }
        do { let fired = late.observe(.executionStarted(sessionID: "ses_a", at: nil), at: start + 6.1, busy: true); #expect(!fired) }
    }

    private static let noteEvent = SessionEvent.inboxEnqueued(
        sessionID: "ses_a", id: "msg_n", delivery: "steer",
        text: "<subagent sessionID=\"ses_c1\" state=\"cancelled\" description=\"m4 bg\">\n</subagent>", kind: "synthetic"
    )

    static func note(_ session: String) -> String {
        EventLines.line(
            "session.inbox.enqueued",
            #"{"sessionID":"\#(session)","inboxID":"msg_n\#(Int.random(in: 0 ... 99999))","item":{"type":"synthetic","payload":{"text":"<subagent sessionID=\"ses_c1\" state=\"cancelled\">\ndone\n</subagent>"},"delivery":"steer"}}"#
        )
    }

    @Test func theWaitingLineSaysWhenItSends() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: [InboxEntry(id: "msg_1", delivery: "queue")]))
        await h.open()
        #expect(h.session.agentState == .idle)
        #expect(!h.session.canStop)
        #expect(h.session.waitingCount == 1)
    }
}

// MARK: Details

@MainActor
struct DetailsTests {
    @Test func sectionOrderAndEmptyOnesLeftOut() {
        #expect(DetailsSection.shown(.init()) == [])
        #expect(DetailsSection.shown(.init(needs: 1, waiting: 2, asked: 0, running: 1, todo: 4, recent: 3)) == [.needs, .waiting, .running, .todo, .recent])
        #expect(DetailsSection.shown(.init(recent: 1)) == [.recent])
        #expect(DetailsSection.shown(.init(needs: 1, asked: 2)) == [.needs, .asked])
    }

    @Test func runningNowShowsSubagentsThenLongShellsAtMostThree() {
        let now = Date(timeIntervalSince1970: 10_000)
        var state = LiveSessionState(rootID: "ses_a")
        state.apply(running: RunningSnapshot(
            childBusySince: ["ses_c1": now - 60, "ses_c2": now - 30],
            childSessions: ["ses_c1": ChildSession(title: "m4 date check", agent: "general"), "ses_c2": ChildSession(title: nil, agent: "explore")],
            shells: [
                .init(id: "sh_1", sessionID: "ses_a", command: "bun run dev", startedAt: now - 300),
                .init(id: "sh_2", sessionID: "ses_c1", command: "sleep 40", startedAt: now - 12),
                .init(id: "sh_young", sessionID: "ses_a", command: "ls", startedAt: now - 3),
                .init(id: "sh_other", sessionID: "ses_other", command: "top", startedAt: now - 900),
            ]
        ))
        let running = RunningNow.rows(state, at: now)
        #expect(running.total == 4)
        #expect(running.more == 1)
        #expect(running.rows.map(\.id) == ["ses_c1", "ses_c2", "sh_1"])
        #expect(running.rows[0].title == "m4 date check")
        #expect(running.rows[0].kind == .subagent(agent: "general"))
        #expect(running.rows[1].title == "explore")
        #expect(running.rows[2].title == "bun run dev")
        #expect(running.rows[2].kind == .shell)
        // The Details link counts the same.
        #expect(state.runningCount(at: now) == running.total)
    }

    @Test func recentLabelsForEachToolAndOutcome() {
        func part(_ name: String, status: String = "completed", _ build: (inout ToolPartV2) -> Void = { _ in }) -> ToolPartV2 {
            var part = ToolPartV2(id: UUID().uuidString, name: name, status: status, completedMs: 1_000)
            build(&part)
            return part
        }
        let step = { (part: ToolPartV2) in RecentSteps.step(part) }
        let edited = step(part("edit") { $0.files = [ToolFileV2(file: "notes.txt", status: "modified", additions: 2, deletions: 0)] })
        #expect(edited?.title == "notes.txt")
        #expect(edited?.detail == "Edited · +2 −0")
        #expect(edited?.titleIsCode == true)
        #expect(step(part("write") { $0.files = [ToolFileV2(file: "new.ts", status: "added", additions: 12)] })?.detail == "Created · +12")
        let several = step(part("patch") { $0.files = [
            ToolFileV2(file: "a.ts", status: "modified", additions: 10, deletions: 2),
            ToolFileV2(file: "b.ts", status: "added", additions: 6),
            ToolFileV2(file: "c.ts", status: "modified", additions: 2, deletions: 1),
        ] })
        #expect(several?.title == "a.ts")
        #expect(several?.detail == "3 files · +18 −3")
        #expect(step(part("edit", status: "error") { $0.errorType = "permission.rejected"; $0.path = "x.ts" }) == nil)

        #expect(step(part("shell") { $0.command = "date"; $0.exit = 0 })?.detail == "Done")
        #expect(step(part("shell") { $0.command = "false"; $0.exit = 1 })?.detail == "Failed · exit 1")
        #expect(step(part("shell", status: "error") { $0.command = "sleep 20"; $0.errorType = "aborted"; $0.errorMessage = "Tool execution interrupted" })?.detail == "Stopped")
        #expect(step(part("shell", status: "error") { $0.command = "rm -rf x"; $0.errorType = "permission.rejected"; $0.errorMessage = "Use printf" })?.detail == "Denied")
        #expect(step(part("shell", status: "error") { $0.command = "rm -rf x"; $0.errorType = "aborted"; $0.errorMessage = "The user declined this tool call" })?.detail == "Denied")
        #expect(step(part("shell") { $0.command = "date" })?.kind == .command)

        let subagent = step(part("subagent") { $0.description = "m4 date check"; $0.agent = "general" })
        #expect(subagent?.title == "m4 date check")
        #expect(subagent?.detail == "Subagent · general")
        #expect(subagent?.titleIsCode == false)

        for name in ["read", "grep", "glob", "question", "todowrite", "webfetch"] {
            #expect(step(part(name)) == nil)
        }
        #expect(step(part("shell", status: "running") { $0.command = "sleep 9" }) == nil)
    }

    @Test func recentIsTheNewestThree() {
        let parts = (1 ... 6).map { ToolPartV2(id: "t\($0)", name: $0 == 2 ? "read" : "shell", status: "completed", command: "cmd \($0)", exit: 0) }
        #expect(RecentSteps.make(parts).map(\.id) == ["t1", "t3", "t4"])
    }

    @Test func ages() {
        let now = Date(timeIntervalSince1970: 100_000)
        #expect(RecentSteps.age(of: now - 20, at: now) == "now")
        #expect(RecentSteps.age(of: now - 120, at: now) == "2m")
        #expect(RecentSteps.age(of: now - 3 * 3600, at: now) == "3h")
        #expect(RecentSteps.age(of: now - 50 * 3600, at: now) == "2d")
    }

    @Test func openingReadsRunningAndRecentAndAToolReadsRecentAgain() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [], inbox: []))
        await h.open()
        h.control.tools = [ToolPartV2(id: "t1", name: "shell", status: "completed", command: "date", exit: 0)]
        h.session.openDetails()
        await h.eventually { h.session.recentSteps.count == 1 }
        #expect(h.control.calls.prefix(2).contains(.running("ses_a")))
        #expect(h.control.calls.contains(.recent("ses_a")))
        h.control.tools.insert(ToolPartV2(id: "t2", name: "shell", status: "completed", command: "ls", exit: 0), at: 0)
        h.transport.send(EventLines.line("session.tool.success", #"{"sessionID":"ses_a","id":"call-2","assistantMessageID":"msg_1"}"#))
        await h.eventually { h.session.recentSteps.count == 2 }
        #expect(h.session.recentSteps.map(\.id) == ["t2", "t1"])
        // Closed: no more reads.
        h.session.showsDetails = false
        let reads = h.control.calls.filter { $0 == .recent("ses_a") }.count
        h.transport.send(EventLines.line("session.tool.failed", #"{"sessionID":"ses_a","id":"call-3"}"#))
        try? await Task.sleep(for: .milliseconds(1300))
        #expect(h.control.calls.filter { $0 == .recent("ses_a") }.count == reads)
    }

    @Test func aNeedsYouRowClosesDetailsThenOpensItsSheet() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [Requests.approval("per_1"), Requests.approval("per_2")], inbox: []))
        await h.open()
        h.session.openDetails()
        h.session.openFromDetails("per_2")
        #expect(!h.session.showsDetails)
        #expect(h.session.presentedNeed == nil)
        h.session.detailsDismissed()
        #expect(h.session.presentedNeed?.id == "per_2")
    }

    @Test func waitingToSendListsEveryMessageButTheServersNotes() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [], inbox: [
            InboxEntry(id: "msg_1", delivery: "queue", text: "a"),
            InboxEntry(id: "msg_2", delivery: "steer", text: "b"),
            InboxEntry(id: "msg_3", delivery: "steer", text: "<subagent>", kind: "synthetic"),
        ]))
        await h.open()
        #expect(h.session.undeliveredMessages.map(\.id) == ["msg_1", "msg_2"])
        #expect(h.session.waitingCount == 1)
    }
}

// MARK: Kit requests

/// Records every request and answers each with the next status and body.
nonisolated final class ControlURLProtocol: URLProtocol {
    nonisolated(unsafe) static var requests: [(request: URLRequest, body: Data?)] = []
    nonisolated(unsafe) static var replies: [(status: Int, body: String)] = []
    /// Replies by path, for requests made at the same time.
    nonisolated(unsafe) static var byPath: [String: String] = [:]

    static func reset(_ replies: [(Int, String)], byPath: [String: String] = [:]) {
        requests = []
        self.replies = replies.map { (status: $0.0, body: $0.1) }
        self.byPath = byPath
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append((request, request.httpBody ?? request.httpBodyStream.map(Self.read)))
        let reply = Self.byPath[request.url!.path].map { (status: 200, body: $0) }
            ?? (Self.replies.isEmpty ? (status: 204, body: "") : Self.replies.removeFirst())
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

@Suite(.serialized)
struct ControlRequestTests {
    private func service() -> OpenCodeSessionService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ControlURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return OpenCodeSessionService(connection: connection, urlSession: URLSession(configuration: configuration))
    }

    private func json(_ index: Int) throws -> [String: Any] {
        let body = try #require(ControlURLProtocol.requests[index].body)
        return try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
    }

    @Test func approvalBodies() async throws {
        ControlURLProtocol.reset([])
        let service = service()
        try await service.replyToApproval(sessionID: "ses_c", requestID: "per_1", decision: .once, note: nil)
        try await service.replyToApproval(sessionID: "ses_c", requestID: "per_2", decision: .always, note: "ignored")
        try await service.replyToApproval(sessionID: "ses_c", requestID: "per_3", decision: .reject, note: "Use printf.")
        let first = ControlURLProtocol.requests[0].request
        #expect(first.httpMethod == "POST")
        #expect(first.url?.path == "/api/session/ses_c/permission/per_1/reply")
        #expect(try json(0) as NSDictionary == ["decision": "once"] as NSDictionary)
        #expect(try json(1) as NSDictionary == ["decision": "always"] as NSDictionary)
        #expect(try json(2) as NSDictionary == ["decision": "reject", "message": "Use printf."] as NSDictionary)
    }

    @Test func formBodiesAndTheDismissNote() async throws {
        ControlURLProtocol.reset([])
        let service = service()
        try await service.answerQuestion(sessionID: "ses_a", formID: "frm_1", answer: ["q0": .text("Red"), "q1": .choices(["Apple", "Kiwi"])])
        try await service.dismissQuestion(sessionID: "ses_a", formID: "frm_1", note: "Skip this + carry on")
        #expect(ControlURLProtocol.requests[0].request.url?.path == "/api/session/ses_a/form/frm_1/reply")
        #expect(try json(0) as NSDictionary == ["answer": ["q0": "Red", "q1": ["Apple", "Kiwi"]]] as NSDictionary)
        let dismiss = ControlURLProtocol.requests[1].request
        #expect(dismiss.httpMethod == "DELETE")
        #expect(dismiss.url?.path == "/api/session/ses_a/form/frm_1")
        let message = URLComponents(url: dismiss.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "message" }?.value
        #expect(message == "Skip this + carry on")
    }

    @Test func notFoundAndSettledAreGone() async throws {
        ControlURLProtocol.reset([
            (404, #"{"_tag":"PermissionNotFoundError","requestID":"per_1","message":"Permission request not found: per_1"}"#),
            (409, #"{"_tag":"FormAlreadySettledError","id":"frm_1","message":"Form already settled: frm_1"}"#),
            (500, "{}"),
        ])
        let service = service()
        await #expect(throws: ControlError.gone) { try await service.replyToApproval(sessionID: "ses_a", requestID: "per_1", decision: .once, note: nil) }
        await #expect(throws: ControlError.gone) { try await service.answerQuestion(sessionID: "ses_a", formID: "frm_1", answer: [:]) }
        await #expect(throws: SessionStateAPIError.self) { try await service.dismissQuestion(sessionID: "ses_a", formID: "frm_1", note: "x") }
    }

    @Test func interruptReadsItsAnswer() async throws {
        ControlURLProtocol.reset([(200, #"{"interrupted":true}"#), (200, #"{"interrupted":false}"#)])
        let service = service()
        #expect(try await service.interrupt(sessionID: "ses_a"))
        #expect(try await service.interrupt(sessionID: "ses_a") == false)
        #expect(ControlURLProtocol.requests[0].request.url?.path == "/api/session/ses_a/interrupt")
        #expect(ControlURLProtocol.requests[0].request.httpMethod == "POST")
    }

    @Test func runningReadsChildrenAndTheProjectsShells() async throws {
        ControlURLProtocol.reset([], byPath: [
            "/api/session/active": #"{"data":{"ses_a":{"type":"running"},"ses_c":{"type":"running"}}}"#,
            "/api/session": #"{"data":[{"id":"ses_c","parentID":"ses_a","agent":"general","title":"m4 date check","time":{"created":1,"updated":2,"busy":1793986480194},"location":{"directory":"/p"}},{"id":"ses_d","parentID":"ses_a","title":"done","time":{"created":1,"updated":2},"location":{"directory":"/p"}}]}"#,
            "/api/shell": #"{"location":{"directory":"/p"},"data":[{"id":"sh_1","status":"running","command":"sleep 20 && echo done","metadata":{"sessionID":"ses_a"},"time":{"started":1793986466300}},{"id":"sh_2","status":"running","command":"top","metadata":{"sessionID":"ses_other"},"time":{"started":1}}]}"#,
        ])
        let running = try await service().running(rootID: "ses_a", directory: "/p")
        #expect(running.childBusySince == ["ses_c": Date(timeIntervalSince1970: 1_793_986_480.194)])
        #expect(running.childSessions["ses_c"] == ChildSession(title: "m4 date check", agent: "general"))
        #expect(running.shells == [.init(id: "sh_1", sessionID: "ses_a", command: "sleep 20 && echo done", startedAt: Date(timeIntervalSince1970: 1_793_986_466.3))])
        let shellQuery = ControlURLProtocol.requests.first { $0.request.url?.path == "/api/shell" }?.request.url
        let directory = shellQuery.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "location[directory]" }?.value }
        #expect(directory == "/p")
    }
}

/// OpenCodeKit reads the request, form and tool payloads the server sent (m4-proposal.md §1).
struct M4DTOTests {
    @Test func aFormWithOptions() throws {
        let json = #"""
        {"data":[{"id":"frm_1","sessionID":"ses_a","title":"Questions","metadata":{"kind":"question","tool":{"messageID":"msg_1","id":"call-1"}},
         "fields":[{"key":"q0","title":"Colour","description":"Colour","type":"string","options":[{"value":"Red","label":"Red","description":"Red colour"}],"custom":true},
                   {"key":"q1","title":"Fruits","description":"Fruits","type":"multiselect","options":[{"value":"Apple","label":"Apple","description":""},{"bad":1}],"custom":true}]}]}
        """#
        let form = try JSONDecoder().decode(APIListEnvelope<PendingFormV2>.self, from: Data(json.utf8)).data[0]
        #expect(form.kind == "question")
        #expect(form.fields[0].options == [.init(value: "Red", label: "Red", description: "Red colour")])
        #expect(form.fields[1].type == "multiselect")
        #expect(form.fields[1].options.count == 1)
        #expect(form.fields[1].custom)
    }

    @Test func toolParts() throws {
        let json = #"""
        {"id":"msg_1","type":"assistant","content":[
         {"type":"tool","id":"call-1","name":"shell","state":{"status":"completed","input":{"command":"date"},"metadata":{"status":"completed","exit":0}},"time":{"created":1,"completed":2}},
         {"type":"tool","id":"call-2","name":"shell","state":{"status":"error","input":{"command":"sleep 20"},"error":{"type":"aborted","message":"Tool execution interrupted"},"metadata":{"shellID":"sh_1"}}},
         {"type":"tool","id":"call-3","name":"subagent","state":{"status":"completed","input":{"agent":"general","description":"m4 date check","prompt":"x"},"metadata":{"sessionID":"ses_c","status":"completed"}}},
         {"type":"tool","id":"call-4","name":"edit","state":{"status":"completed","input":{"path":"notes.txt","oldString":"a","newString":"b"},"metadata":{"files":[{"file":"notes.txt","patch":"…","status":"modified","additions":2,"deletions":0}]}}}]}
        """#
        let parts = try JSONDecoder().decode(SessionMessageV2.self, from: Data(json.utf8)).toolParts
        #expect(parts[0].command == "date")
        #expect(parts[0].exit == 0)
        #expect(parts[0].completedMs == 2)
        #expect(parts[1].errorType == "aborted")
        #expect(parts[1].errorMessage == "Tool execution interrupted")
        #expect(parts[2].description == "m4 date check")
        #expect(parts[2].agent == "general")
        #expect(parts[3].path == "notes.txt")
        #expect(parts[3].files == [ToolFileV2(file: "notes.txt", status: "modified", additions: 2, deletions: 0)])
    }

    @Test func sessionProjectAndAgentAndInboxType() throws {
        let info = try JSONDecoder().decode(SessionInfoV2.self, from: Data(#"{"id":"ses_c","parentID":"ses_a","projectID":"global","agent":"general","time":{"created":1,"updated":1},"location":{"directory":"/p"}}"#.utf8))
        #expect(info.projectID == "global")
        #expect(info.agent == "general")
        let note = try JSONDecoder().decode(InboxItemV2.self, from: Data(#"{"id":"msg_n","type":"synthetic","payload":{"text":"<subagent>"},"delivery":"steer"}"#.utf8))
        #expect(note.type == "synthetic")
    }
}
