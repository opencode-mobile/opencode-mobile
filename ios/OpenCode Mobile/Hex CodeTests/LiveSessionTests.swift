import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

/// Event payloads as OpenCode v2.2.5 sends them (m2-api-notes.md §4).
enum EventLines {
    static func line(_ type: String, _ data: String, created: Int64 = 1_793_914_275_814) -> String {
        #"{"id":"evt_1","created":\#(created),"type":"\#(type)","data":\#(data)}"#
    }

    static let connected = #"{"id":"evt_0","type":"server.connected","data":{}}"#
    static func started(_ session: String, created: Int64 = 1_793_914_275_814) -> String {
        line("session.execution.started", #"{"sessionID":"\#(session)"}"#, created: created)
    }

    static func succeeded(_ session: String) -> String { line("session.execution.succeeded", #"{"sessionID":"\#(session)"}"#) }
    static func interrupted(_ session: String) -> String {
        line("session.execution.interrupted", #"{"sessionID":"\#(session)","reason":"shutdown"}"#)
    }

    static func permission(_ session: String, id: String = "per_1") -> String {
        line("permission.asked", #"{"id":"\#(id)","sessionID":"\#(session)","action":"shell","resources":["ls"],"save":["ls *"]}"#)
    }

    static func replied(_ session: String, id: String = "per_1") -> String {
        line("permission.replied", #"{"sessionID":"\#(session)","requestID":"\#(id)","reply":"once"}"#)
    }

    static func form(_ session: String, id: String = "frm_1") -> String {
        line(
            "form.created",
            #"{"form":{"id":"\#(id)","sessionID":"\#(session)","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","title":"Proceed","description":"Run the tests now?"}]}}"#
        )
    }

    static func formCancelled(_ session: String, id: String = "frm_1") -> String {
        line("form.cancelled", #"{"id":"\#(id)","sessionID":"\#(session)"}"#)
    }

    static func enqueued(_ session: String, id: String, delivery: String = "queue") -> String {
        line(
            "session.inbox.enqueued",
            #"{"sessionID":"\#(session)","inboxID":"\#(id)","item":{"type":"user","payload":{"text":"hi"},"delivery":"\#(delivery)"}}"#
        )
    }

    static func delivered(_ session: String, id: String) -> String {
        line("session.inbox.delivered", #"{"sessionID":"\#(session)","inboxID":"\#(id)"}"#)
    }

    static func child(_ id: String, parent: String) -> String {
        line("session.created", #"{"sessionID":"\#(id)","info":{"id":"\#(id)","parentID":"\#(parent)","time":{"created":1,"updated":1},"location":{"directory":"/p"}}}"#)
    }

    static func shellCreated(_ id: String, session: String) -> String {
        line("shell.created", #"{"info":{"id":"\#(id)","status":"running","command":"ls","metadata":{"sessionID":"\#(session)"},"time":{"started":1}}}"#)
    }

    static func shellExited(_ id: String) -> String { line("shell.exited", #"{"id":"\#(id)","exit":0,"status":"exited"}"#) }
}

/// Reading events and keeping only today's session and its subagents.
struct LiveSessionStateTests {
    private func apply(_ lines: [String], to state: inout LiveSessionState) -> [LiveSessionState.Effect?] {
        lines.compactMap(SessionEvent.decode).map { state.apply($0) }
    }

    @Test func decodesTheTypesHomeUses() {
        #expect(SessionEvent.decode(EventLines.connected) == .connected)
        #expect(SessionEvent.decode(EventLines.started("ses_a")) == .executionStarted(
            sessionID: "ses_a",
            at: Date(timeIntervalSince1970: 1_793_914_275.814)
        ))
        #expect(SessionEvent.decode(EventLines.permission("ses_a")) == .permissionAsked(
            PendingItem(id: "per_1", sessionID: "ses_a", kind: .approval, text: "ls")
        ))
        #expect(SessionEvent.decode(EventLines.form("ses_a")) == .formCreated(
            PendingItem(id: "frm_1", sessionID: "ses_a", kind: .question, text: "Run the tests now?")
        ))
        #expect(SessionEvent.decode(EventLines.enqueued("ses_a", id: "msg_1")) == .inboxEnqueued(sessionID: "ses_a", id: "msg_1", delivery: "queue"))
        #expect(SessionEvent.decode(EventLines.child("ses_c", parent: "ses_a")) == .sessionCreated(id: "ses_c", parentID: "ses_a"))
        #expect(SessionEvent.decode(EventLines.shellCreated("sh_1", session: "ses_a")) == .shellCreated(id: "sh_1", sessionID: "ses_a"))
        #expect(SessionEvent.decode(EventLines.shellExited("sh_1")) == .shellExited(id: "sh_1"))
        #expect(SessionEvent.decode(EventLines.line("session.step.ended", #"{"sessionID":"ses_a"}"#)) == nil)
        #expect(SessionEvent.decode("not json") == nil)
    }

    @Test func theTypesAreTheExactListEachOnItsOwn() {
        #expect(SessionEvent.types.count == 16)
        #expect(!SessionEvent.types.contains { $0.contains("*") || $0.contains(",") })
    }

    @Test func eventsForOtherSessionsAreDropped() {
        var state = LiveSessionState(rootID: "ses_a")
        _ = apply([
            EventLines.started("ses_other"),
            EventLines.permission("ses_other"),
            EventLines.enqueued("ses_other", id: "msg_x"),
            EventLines.succeeded("ses_other"),
        ], to: &state)
        #expect(state == LiveSessionState(rootID: "ses_a"))
    }

    @Test func busyFromStartToEndAndTheEndRefreshesTheSummary() {
        var state = LiveSessionState(rootID: "ses_a")
        let effects = apply([EventLines.started("ses_a"), EventLines.succeeded("ses_a")], to: &state)
        #expect(effects == [nil, .runEnded])
        #expect(state.busySince == nil)
        #expect(state.hasRun)
        _ = apply([EventLines.started("ses_a", created: 1_000_000)], to: &state)
        #expect(state.busySince == Date(timeIntervalSince1970: 1000))
        // A second start inside the same run (a queued message) keeps the clock.
        _ = apply([EventLines.started("ses_a", created: 2_000_000)], to: &state)
        #expect(state.busySince == Date(timeIntervalSince1970: 1000))
        #expect(apply([EventLines.interrupted("ses_a")], to: &state) == [.runEnded])
    }

    @Test func aSubagentsApprovalCountsOnceItIsKnown() {
        var state = LiveSessionState(rootID: "ses_a")
        _ = apply([EventLines.permission("ses_c", id: "per_early")], to: &state)
        #expect(state.pending.isEmpty)
        _ = apply([EventLines.child("ses_c", parent: "ses_a"), EventLines.permission("ses_c", id: "per_c")], to: &state)
        #expect(state.firstPending?.id == "per_c")
        // A subagent's run doesn't make today's session busy.
        _ = apply([EventLines.started("ses_c")], to: &state)
        #expect(state.busySince == nil)
        _ = apply([EventLines.replied("ses_c", id: "per_c")], to: &state)
        #expect(state.pending.isEmpty)
    }

    @Test func questionsAndApprovalsComeAndGo() {
        var state = LiveSessionState(rootID: "ses_a")
        _ = apply([EventLines.form("ses_a"), EventLines.permission("ses_a")], to: &state)
        #expect(state.firstPending?.kind == .question)
        _ = apply([EventLines.formCancelled("ses_a")], to: &state)
        #expect(state.firstPending?.kind == .approval)
        _ = apply([EventLines.permission("ses_a")], to: &state)
        #expect(state.pending.count == 1)
    }

    @Test func theWaitingCountIsQueuedInboxItems() {
        var state = LiveSessionState(rootID: "ses_a")
        _ = apply([
            EventLines.enqueued("ses_a", id: "msg_1"),
            EventLines.enqueued("ses_a", id: "msg_2"),
            EventLines.enqueued("ses_a", id: "msg_3", delivery: "steer"),
            EventLines.enqueued("ses_a", id: "msg_1"),
        ], to: &state)
        #expect(state.waitingCount == 2)
        _ = apply([EventLines.delivered("ses_a", id: "msg_1")], to: &state)
        #expect(state.waitingCount == 1)
        _ = apply([EventLines.line("session.inbox.delivery.changed", #"{"sessionID":"ses_a","inboxID":"msg_3","delivery":"queue"}"#)], to: &state)
        #expect(state.waitingCount == 2)
    }

    @Test func shellExitsMatchByTheShellFromAKeptStart() {
        var state = LiveSessionState(rootID: "ses_a")
        _ = apply([EventLines.shellCreated("sh_1", session: "ses_a"), EventLines.shellCreated("sh_2", session: "ses_other")], to: &state)
        #expect(state.shells == ["sh_1"])
        _ = apply([EventLines.shellExited("sh_1")], to: &state)
        #expect(state.shells.isEmpty)
    }

    @Test func theReadBackReplacesWhatWasMissedAndKeepsShells() {
        var state = LiveSessionState(rootID: "ses_a")
        _ = apply([EventLines.shellCreated("sh_1", session: "ses_a"), EventLines.permission("ses_a", id: "per_old")], to: &state)
        let since = Date(timeIntervalSince1970: 500)
        state.apply(LiveSnapshot(
            busySince: since,
            hasRun: true,
            children: ["ses_c"],
            pending: [PendingItem(id: "frm_9", sessionID: "ses_c", kind: .question, text: "Which one?")],
            inbox: [("msg_1", "queue")]
        ))
        #expect(state.busySince == since)
        #expect(state.children == ["ses_c"])
        #expect(state.firstPending?.id == "frm_9")
        #expect(state.waitingCount == 1)
        #expect(state.shells == ["sh_1"])
    }

    @Test func pendingTexts() {
        #expect(PendingPermissionText(action: "shell", resources: ["bun run db:reset"], message: nil).text == "bun run db:reset")
        #expect(PendingPermissionText(action: "edit", resources: [], message: "Edit src/app.ts").text == "Edit src/app.ts")
        #expect(PendingPermissionText(action: "webfetch", resources: []).text == "webfetch")
        #expect(PendingQuestionText(title: "Questions", fields: [.init(title: "Header", description: nil)]).text == "Header")
        #expect(PendingQuestionText(title: "Questions", fields: []).text == "Questions")
    }
}

/// OpenCodeKit's session DTOs read the server's real payloads (m2-api-notes.md §1, §5).
struct SessionStateDTOTests {
    @Test func sessionInfoReadsLocationModelAndBusy() throws {
        let json = #"""
        {"id":"ses_e5","projectID":"bae7","model":{"id":"big-pickle","providerID":"opencode","variant":"default"},
         "cost":0,"tokens":{"input":1},"outcome":"succeeded",
         "time":{"created":1793914193305,"updated":1793914385595,"busy":1793914371232,"idle":1793914367052},
         "title":"2026-11-05","location":{"directory":"/tmp/hex-m2/ios-project"}}
        """#
        let info = try JSONDecoder().decode(SessionInfoV2.self, from: Data(json.utf8))
        #expect(info.directory == "/tmp/hex-m2/ios-project")
        #expect(info.model == SessionModelRefV2(providerID: "opencode", id: "big-pickle", variant: "default"))
        #expect(info.busyMs == 1_793_914_371_232)
        #expect(OpenCodeSessionService.listing(info).busySince == Date(timeIntervalSince1970: 1_793_914_371.232))
        let fresh = try JSONDecoder().decode(SessionInfoV2.self, from: Data(#"{"id":"ses_n","time":{"created":1,"updated":1},"location":{"directory":"/p"}}"#.utf8))
        #expect(fresh.busyMs == nil)
    }

    @Test func pendingItemsAndInboxRead() throws {
        let permission = try JSONDecoder().decode(
            APIListEnvelope<PendingPermissionV2>.self,
            from: Data(#"{"data":[{"id":"per_1","sessionID":"ses_a","action":"shell","resources":["ls"],"save":["ls *"],"source":{"type":"tool"}}]}"#.utf8)
        )
        #expect(OpenCodeSessionService.approval(permission.data[0]).text == "ls")
        let form = try JSONDecoder().decode(
            APIListEnvelope<PendingFormV2>.self,
            from: Data(#"{"data":[{"id":"frm_1","sessionID":"ses_a","title":"Questions","fields":[{"key":"q0","title":"Proceed","description":"Run it?"}]}]}"#.utf8)
        )
        #expect(OpenCodeSessionService.question(form.data[0]).text == "Run it?")
        let inbox = try JSONDecoder().decode(
            APIListEnvelope<InboxItemV2>.self,
            from: Data(#"{"data":[{"id":"msg_1","sessionID":"ses_a","time":{"created":1},"type":"user","payload":{"text":"hi"},"delivery":"queue"}]}"#.utf8)
        )
        #expect(inbox.data == [InboxItemV2(id: "msg_1", sessionID: "ses_a", delivery: "queue", text: "hi")])
    }
}

/// A stream the test drives: it records each connection and plays the lines the test sends.
@MainActor
final class FakeTransport: EventStreamTransport, @unchecked Sendable {
    private(set) var connections = 0
    private(set) var types: [String] = []
    private var continuation: AsyncStream<String>.Continuation?

    func stream(
        types: [String],
        onActivity: @escaping @Sendable () async -> Void,
        onLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        let (lines, continuation) = AsyncStream<String>.makeStream()
        connections += 1
        self.types = types
        self.continuation = continuation
        for await line in lines {
            await onActivity()
            await onLine(line)
        }
        return .ended
    }

    func send(_ line: String) { continuation?.yield(line) }

    /// The connection drops.
    func drop() { continuation?.finish() }
}

/// Today's session on home, against a fake server and stream.
@MainActor
struct TodaySessionLiveTests {
    private let model = ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil)

    private func make(
        _ server: FakeSessionServer,
        lastDay: String? = "2026-11-05",
        clock: @escaping () -> Date = { Date(timeIntervalSince1970: 1_793_880_000) }
    ) -> (TodaySession, FakeTransport, HexToaster) {
        let toasts = HexToaster()
        let transport = FakeTransport()
        let session = TodaySession(
            setup: DailySessionSetup(service: server, memory: FakeMemory(lastDay)),
            summary: StatusSummaryModel(),
            directory: { "/p" },
            model: { [model] in model },
            toasts: toasts,
            api: server,
            stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
            now: clock,
            timeZone: TimeZone(identifier: "UTC")!
        )
        return (session, transport, toasts)
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    private func todays(_ id: String, ran: Bool) -> SessionListing {
        SessionListing(id: id, title: "2026-11-05", directory: "/p", updatedAt: Date(), busySince: ran ? Date(timeIntervalSince1970: 1) : nil)
    }

    @Test func aNewSessionGetsTheRulesThenTheStreamAndTheEmptyCard() async {
        let server = FakeSessionServer()
        let (session, transport, toasts) = make(server)
        #expect(session.agentState == nil)
        await session.open()
        #expect(server.calls.prefix(3) == [.list(search: "2026-11-05", cursor: nil), .create(title: "2026-11-05", model: model), .rules("ses_new1")])
        await eventually { transport.connections == 1 }
        #expect(transport.types == SessionEvent.types)
        guard case let .card(card) = session.summary.summary else {
            Issue.record("expected the empty card")
            return
        }
        #expect(card.now == "New session. Nothing running yet.")
        #expect(!server.calls.contains(.generate("ses_new1")))
        #expect(toasts.current == nil)
        #expect(session.agentState == .idle)
    }

    @Test func aSessionThatRanRefreshesOnOpenAndReadsTheStateOnConnect() async {
        let server = FakeSessionServer()
        server.listed = [todays("ses_a", ran: true)]
        let since = Date(timeIntervalSince1970: 1_793_879_000)
        server.snapshot = LiveSnapshot(
            busySince: since,
            hasRun: true,
            children: [],
            pending: [PendingItem(id: "per_1", sessionID: "ses_a", kind: .approval, text: "ls")],
            inbox: [("msg_1", "queue")]
        )
        let (session, transport, _) = make(server)
        await session.open()
        #expect(server.calls.contains(.generate("ses_a")))
        #expect(session.summary.summary == .card(StatusCard(title: "Task", now: "Doing it.")))
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        await eventually { session.waitingCount == 1 }
        #expect(server.calls.contains(.snapshot("ses_a")))
        #expect(session.busySince == since)
        #expect(session.pendingInput?.kind == .approval)
        #expect(session.agentState == .needsInput)
        // The first connect after open doesn't ask for a second summary.
        #expect(server.calls.filter { $0 == .generate("ses_a") }.count == 1)
    }

    @Test func eventsUpdateHomeAndARunsEndRefreshesTheSummary() async {
        let server = FakeSessionServer()
        server.listed = [todays("ses_a", ran: true)]
        let (session, transport, _) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        transport.send(EventLines.started("ses_a"))
        await eventually { session.busySince != nil }
        #expect(session.agentState == .working(since: Date(timeIntervalSince1970: 1_793_914_275.814)))
        transport.send(EventLines.enqueued("ses_a", id: "msg_1"))
        transport.send(EventLines.started("ses_other"))
        await eventually { session.waitingCount == 1 }
        server.reply = "## Task\nNow: Done."
        transport.send(EventLines.succeeded("ses_a"))
        await eventually { session.summary.summary == .card(StatusCard(title: "Task", now: "Done.")) }
        #expect(session.busySince == nil)
        #expect(server.calls.filter { $0 == .generate("ses_a") }.count == 2)
    }

    @Test func aDroppedStreamShowsReconnectingThenReadsTheStateAgain() async {
        let server = FakeSessionServer()
        server.listed = [todays("ses_a", ran: true)]
        let (session, transport, _) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        await eventually { server.calls.contains(.snapshot("ses_a")) }
        transport.drop()
        await eventually { session.agentState == .reconnecting }
        #expect(session.agentState == .reconnecting)
        await eventually { transport.connections == 2 }
        server.snapshot = LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: [("msg_9", "queue")])
        transport.send(EventLines.connected)
        await eventually { session.waitingCount == 1 }
        #expect(session.agentState == .idle)
        #expect(server.calls.filter { $0 == .snapshot("ses_a") }.count == 2)
        // A reconnect refreshes the summary.
        await eventually { server.calls.filter { $0 == .generate("ses_a") }.count == 2 }
        #expect(server.calls.filter { $0 == .generate("ses_a") }.count == 2)
    }

    @Test func aNewDayOnReturnMakesANewSessionWithTheNotice() async {
        let server = FakeSessionServer()
        var now = Date(timeIntervalSince1970: 1_793_880_000) // 5 November
        let (session, _, toasts) = make(server, lastDay: "2026-11-04", clock: { now })
        await session.open()
        #expect(session.sessionID == "ses_new1")
        #expect(toasts.current?.title == "New day, new session")
        toasts.dismiss()
        await session.enteredForeground()
        #expect(session.sessionID == "ses_new1")
        now += 86400 // 6 November
        await session.enteredForeground()
        #expect(session.sessionID == "ses_new2")
        #expect(toasts.current?.tone == .sun)
    }

    @Test func aFailedSetupIsEmptyUpTopAndTryAgainOpensIt() async {
        let server = FakeSessionServer()
        server.failsList = true
        let (session, _, _) = make(server)
        await session.open()
        #expect(session.setup == .failed(retrying: false))
        #expect(session.agentState == nil)
        server.failsList = false
        await session.retry()
        #expect(session.setup == .ready(sessionID: "ses_new1"))
    }

    @Test func aModelChangeMovesTheSessionsModel() async {
        let server = FakeSessionServer()
        let (session, _, _) = make(server)
        await session.open()
        let next = ModelChoice(providerID: "opencode", modelID: "beam-free", variant: "low")
        await session.switchModel(next)
        #expect(server.calls.last == .model("ses_new1", next))
    }
}
