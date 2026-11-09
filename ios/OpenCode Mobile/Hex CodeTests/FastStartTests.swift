import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// App plan, home "Cold start" and Dictation "Load": the home shows at once from what's saved on
// the phone, and everything that needs the network or the speech model runs after the first frame.

// MARK: Connecting…

@MainActor
struct ConnectingStateTests {
    @Test func theTopBarSaysConnectingUntilTheFirstReadBack() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: []))
        // The first frame, before anything has been asked of the server.
        #expect(h.session.agentState == .connecting)
        await h.session.open()
        // Found, but the stream hasn't read the state back yet.
        #expect(h.session.agentState == .connecting)
        await h.eventually { h.transport.connections == 1 }
        h.transport.send(EventLines.connected)
        await h.eventually { h.session.agentState == .idle }
        #expect(h.session.agentState == .idle)
    }

    @Test func aStreamThatDropsAfterTheReadBackIsReconnecting() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: []))
        await h.open()
        h.session.streamUp = false
        #expect(h.session.agentState == .reconnecting)
    }

    @Test func setupFailureAndSignedOutLeaveTheStateEmpty() async {
        let failed = ControlHarness()
        failed.server.failsList = true
        await failed.session.open()
        #expect(failed.session.agentState == nil)
        let signedOut = ControlHarness()
        signedOut.session.serverCheck = { .signedOut }
        await signedOut.session.open()
        #expect(signedOut.session.agentState == nil)
    }

    @Test func connectingIsTheReconnectingArcInGreyAndIsNotAnnounced() {
        let state = AgentState.connecting.topBar(at: Date())
        #expect(state == HexTopBarState(tone: .connecting, label: "Connecting…"))
        #expect(AgentState.connecting.announcement == nil)
    }
}

// MARK: The saved card in the first frame

@MainActor
struct SavedCardAtStartTests {
    private let start = Date(timeIntervalSince1970: 1_793_880_000) // Thursday 5 November, 12:00 UTC
    private let card = StatusSummary.card(StatusCard(title: "Task", now: "Doing it."))

    private func session(_ store: MemorySummaryStore, memory: FakeMemory, server: FakeSessionServer? = nil) -> TodaySession {
        let server = server ?? FakeSessionServer()
        return TodaySession(
            setup: DailySessionSetup(service: server, memory: memory),
            summary: StatusSummaryModel(store: store, now: { [start] in start }),
            directory: { "/p" },
            model: { ModelChoice(providerID: "opencode", modelID: "big-pickle", variant: nil) },
            toasts: HexToaster(),
            api: server,
            now: { [start] in start },
            timeZone: TimeZone(identifier: "UTC")!
        )
    }

    private func saved(_ id: String, day: String = "2026-11-05") -> SavedSummary {
        SavedSummary(sessionID: id, summary: card, updatedAt: start - 600, day: day, directory: "/p")
    }

    @Test func todaysSavedSessionsCardShowsBeforeAnyRequest() {
        let store = MemorySummaryStore()
        store.saved = saved("ses_a")
        let server = FakeSessionServer()
        let session = session(store, memory: FakeMemory("2026-11-05", session: "ses_a"), server: server)
        session.preopen()
        #expect(session.summary.summary == card)
        #expect(session.summary.refreshLabel(at: start) == .minutesAgo(10))
        #expect(server.calls.isEmpty)
        #expect(!session.sessionKnown)
    }

    @Test func anotherSessionsCardDoesNotShow() {
        let store = MemorySummaryStore()
        store.saved = saved("ses_old")
        // Start a new session made ses_new today's saved session.
        let session = session(store, memory: FakeMemory("2026-11-05", session: "ses_new"))
        session.preopen()
        #expect(session.summary.summary == nil)
    }

    @Test func aNewDaysCardAreaStartsEmpty() {
        let store = MemorySummaryStore()
        store.saved = saved("ses_y", day: "2026-11-04")
        let session = session(store, memory: FakeMemory("2026-11-04", session: "ses_y"))
        session.preopen()
        #expect(session.summary.summary == nil)
    }

    @Test func todaysSessionIsKnownOnceSetupHasAnAnswer() async {
        let h = ControlHarness()
        #expect(!h.session.sessionKnown)
        await h.session.open()
        #expect(h.session.sessionKnown)
        let failed = ControlHarness()
        failed.server.failsList = true
        await failed.session.open()
        #expect(failed.session.sessionKnown)
        let signedOut = ControlHarness()
        signedOut.session.serverCheck = { .signedOut }
        await signedOut.session.open()
        #expect(signedOut.session.sessionKnown)
    }
}

// MARK: The speech model loads after the first frame

@MainActor
struct SpeechLoadTriggerTests {
    final class Waits: @unchecked Sendable {
        var asked: [Duration] = []
        let gate = Gate()
    }

    @MainActor
    final class Loads {
        var count = 0
    }

    private func settle() async {
        for _ in 0 ..< 20 { await Task.yield() }
    }

    @Test func notBeforeTheFirstFrame() async {
        let loads = Loads()
        _ = SpeechLoadTrigger(sleep: { _ in }) { loads.count += 1 }
        await settle()
        #expect(loads.count == 0)
    }

    @Test func aboutOneSecondAfterTheFirstFrame() async {
        let waits = Waits()
        let loads = Loads()
        let trigger = SpeechLoadTrigger(sleep: { duration in
            waits.asked.append(duration)
            await waits.gate.wait()
        }) { loads.count += 1 }
        trigger.firstFrameDrawn()
        await settle()
        #expect(waits.asked == [.seconds(1)])
        #expect(loads.count == 0)
        waits.gate.open()
        for _ in 0 ..< 200 where loads.count == 0 { try? await Task.sleep(for: .milliseconds(5)) }
        #expect(loads.count == 1)
    }

    @Test func itLoadsOnlyOnce() async {
        let loads = Loads()
        let trigger = SpeechLoadTrigger(sleep: { _ in }) { loads.count += 1 }
        trigger.firstFrameDrawn()
        trigger.firstFrameDrawn()
        for _ in 0 ..< 200 where loads.count == 0 { try? await Task.sleep(for: .milliseconds(5)) }
        await settle()
        trigger.firstFrameDrawn()
        await settle()
        #expect(loads.count == 1)
    }

    @Test func pressingTheTalkButtonStartsTheSpeechModel() {
        let speech = FakeSpeech(.loading)
        let home = HomeModel(recorder: FakeRecorder(), speech: speech, sleep: { _ in })
        #expect(speech.starts == 0)
        home.pressBegan()
        #expect(speech.starts == 1)
    }
}
