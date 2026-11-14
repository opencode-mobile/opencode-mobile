import Foundation
import Testing
@testable import Hex_Code

/// A session that never ran stops being empty once a message is sent or it is busy (app plan,
/// "Empty session"): never "Nothing running yet" while Working.
@MainActor
struct EmptySessionAfterSendTests {
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

    private func eventually(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    private func card(_ session: TodaySession) -> StatusCard? {
        if case let .card(card)? = session.summary.summary { return card }
        return nil
    }

    @Test func aSendTurnsTheEmptyCardIntoWorkingOnYourMessage() async throws {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        #expect(card(session)?.now == "New session. Nothing running yet.")
        let longDate = card(session)?.title
        _ = try await session.send("Run the tests", images: [], delivery: .steer)
        #expect(card(session)?.now == "Working on your message.")
        // Under "Still working · <long date>" once busy.
        #expect(card(session)?.title == longDate)
        #expect(card(session)?.next == nil)
    }

    @Test func theRunStartingTurnsTheEmptyCardIntoWorkingOnYourMessage() async {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        await eventually { card(session)?.now == "New session. Nothing running yet." }
        // Sent from another device.
        transport.send(EventLines.started("ses_new1"))
        await eventually { session.busySince != nil }
        #expect(card(session)?.now == "Working on your message.")
    }

    @Test func aReconnectAfterTheSendDoesntBringTheEmptyCardBack() async throws {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        _ = try await session.send("Run the tests", images: [], delivery: .steer)
        // The stream drops before the run's start arrives; the read-back still says it never ran.
        transport.drop()
        await eventually { transport.connections == 2 }
        transport.send(EventLines.connected)
        await eventually { server.calls.filter { $0 == .snapshot("ses_new1") }.count == 2 }
        try? await Task.sleep(for: .milliseconds(30))
        #expect(card(session)?.now == "Working on your message.")
        #expect(!server.calls.contains(.generate("ses_new1")))
    }

    @Test func theRunsEndReplacesItWithTheSummary() async throws {
        let server = FakeSessionServer()
        let (session, transport) = make(server)
        await session.open()
        await eventually { transport.connections == 1 }
        transport.send(EventLines.connected)
        _ = try await session.send("Run the tests", images: [], delivery: .steer)
        transport.send(EventLines.started("ses_new1"))
        transport.send(EventLines.succeeded("ses_new1"))
        await eventually { card(session)?.title == "Task" }
        #expect(card(session)?.now == "Doing it.")
    }
}
