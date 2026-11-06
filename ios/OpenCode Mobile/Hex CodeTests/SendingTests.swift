import Foundation
import Testing
@testable import Hex_Code

/// Where Send goes, for the tests.
@MainActor
final class FakeSender: MessageSending {
    var agentBusy = false
    var connected = true
    var fails = false
    private(set) var sent: [(text: String, delivery: Delivery)] = []
    private(set) var cancelled: [String] = []

    func send(_ text: String, delivery: Delivery) async throws -> String {
        if fails { throw CancellationError() }
        sent.append((text, delivery))
        return "msg_\(sent.count)"
    }

    func cancelWaiting(_ itemID: String) async throws {
        cancelled.append(itemID)
    }

    func waitForConnection() async {
        while !connected { try? await Task.sleep(for: .milliseconds(5)) }
    }
}

/// Sending a draft (app plan, "Sending").
@MainActor
struct SendingTests {
    private let gate = Gate()

    private func make(_ sender: FakeSender, speech: FakeSpeech? = nil) -> HomeModel {
        let gate = gate
        let home = HomeModel(recorder: FakeRecorder(), speech: speech ?? FakeSpeech(), sleep: { _ in await gate.wait() })
        home.sender = sender
        return home
    }

    /// One hands-free take: "hello there".
    private func dictate(_ home: HomeModel) async {
        await home.toggleHandsFreeTake()
        await home.toggleHandsFreeTake()
    }

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    @Test func anIdleAgentGetsItAtOnceAsSteer() async {
        let sender = FakeSender()
        let home = make(sender)
        await dictate(home)
        home.send()
        #expect(home.draft.isEmpty)
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["hello there"])
        #expect(sender.sent.map(\.delivery) == [.steer])
        await eventually { home.toast != nil }
        #expect(home.toast?.title == "Sent · starting now")
    }

    @Test func aWorkingAgentGetsItAfterThisTurnAndUndoTakesItBack() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let home = make(sender)
        await dictate(home)
        let draft = home.draft
        home.send()
        await eventually { home.toast?.title == "Queued · sends after this turn" }
        #expect(sender.sent.map(\.delivery) == [.queue])
        #expect(home.toast?.action?.title == "Undo")
        home.toasts.performAction()
        await eventually { home.toast?.title == "Taken back" }
        #expect(sender.cancelled == ["msg_1"])
        #expect(home.draft == draft)
    }

    @Test func nowIsHeldThreeSecondsThenGoesAsACorrection() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let home = make(sender)
        await dictate(home)
        home.sendMode = .now
        home.send()
        #expect(home.held != nil)
        #expect(home.toast?.title == "Sending now…")
        try? await Task.sleep(for: .milliseconds(30))
        #expect(sender.sent.isEmpty)
        gate.open()
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["[Correction] hello there"])
        #expect(sender.sent.map(\.delivery) == [.steer])
        await eventually { home.toast?.title == "Sent · lands at the next step" }
    }

    @Test func undoDuringTheHoldSendsNothingAndGivesTheDraftBack() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let home = make(sender)
        await dictate(home)
        let draft = home.draft
        home.sendMode = .now
        home.send()
        home.toasts.performAction()
        #expect(home.held == nil)
        #expect(home.toast?.title == "Not sent")
        #expect(home.draft == draft)
        gate.open()
        try? await Task.sleep(for: .milliseconds(30))
        #expect(sender.sent.isEmpty)
    }

    @Test func aHeldNowWaitsForTheReconnect() async {
        let sender = FakeSender()
        sender.agentBusy = true
        sender.connected = false
        let home = make(sender)
        await dictate(home)
        home.sendMode = .now
        home.send()
        gate.open()
        try? await Task.sleep(for: .milliseconds(40))
        #expect(sender.sent.isEmpty)
        sender.connected = true
        await eventually { !sender.sent.isEmpty }
        #expect(sender.sent.map(\.text) == ["[Correction] hello there"])
    }

    @Test func theSwitchGoesBackToAfterTurnAfterEverySend() async {
        let sender = FakeSender()
        sender.agentBusy = true
        let home = make(sender)
        await dictate(home)
        home.sendMode = .now
        home.send()
        #expect(home.sendMode == .afterTurn)
    }

    @Test func sendWaitsForEveryTake() async {
        let speech = FakeSpeech(.loading)
        let home = make(FakeSender(), speech: speech)
        await dictate(home)
        #expect(home.draft.isWaiting)
        #expect(!home.canSend)
        #expect(home.takesPending)
        home.send()
        #expect(!home.draft.isEmpty)
        speech.status = .ready
        await eventually { home.canSend }
        #expect(!home.takesPending)
    }

    @Test func aFailedSendGivesTheDraftBackAsItWas() async {
        let sender = FakeSender()
        sender.fails = true
        let home = make(sender)
        await dictate(home)
        await dictate(home)
        let draft = home.draft
        home.send()
        await eventually { home.toast?.title == "Couldn’t send" }
        #expect(home.toast?.detail == "Your draft is kept.")
        #expect(home.toast?.tone == .stop)
        #expect(home.draft == draft)
    }
}

/// The Waiting to send sheet's list and Cancel, on today's session.
@MainActor
struct WaitingToSendTests {
    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    private func make(_ server: FakeSessionServer) -> (TodaySession, FakeTransport) {
        let transport = FakeTransport()
        let session = TodaySession(
            setup: DailySessionSetup(service: server, memory: FakeMemory("2026-11-05")),
            summary: StatusSummaryModel(),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: HexToaster(),
            api: server,
            stream: LiveEventStream(transport: transport, sleep: { _ in try await Task.sleep(for: .milliseconds(10)) }),
            now: { Date(timeIntervalSince1970: 1_793_880_000) },
            timeZone: TimeZone(identifier: "UTC")!
        )
        return (session, transport)
    }

    @Test func cancelDeletesTheInboxItem() async throws {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        transport.send(EventLines.enqueued("ses_new1", id: "msg_7"))
        await eventually { session.waitingMessages.count == 1 }
        #expect(session.waitingMessages.first?.text == "hi")
        try await session.cancelWaiting("msg_7")
        #expect(server.cancelled == ["msg_7"])
        #expect(session.waitingMessages.isEmpty)
    }

    @Test func aDeliveredMessageLeavesTheList() async {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        transport.send(EventLines.enqueued("ses_new1", id: "msg_1"))
        transport.send(EventLines.enqueued("ses_new1", id: "msg_2"))
        await eventually { session.waitingMessages.count == 2 }
        transport.send(EventLines.delivered("ses_new1", id: "msg_1"))
        await eventually { session.waitingMessages.count == 1 }
        #expect(session.waitingMessages.map(\.id) == ["msg_2"])
    }

    @Test func sendingGoesToTodaysSession() async throws {
        let server = FakeSessionServer()
        let (session, _) = make(server)
        await session.open()
        let id = try await session.send("Run the tests", delivery: .queue)
        #expect(id == "msg_1")
        #expect(server.prompts.map(\.text) == ["Run the tests"])
        #expect(server.prompts.map(\.delivery) == [.queue])
    }
}
