import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// App plan, home "Cold start" and Details "Size and first load": the home shows in the first
// frame, its card area from what's saved on the phone or else the date card, and Details opens with
// Recent and Running now already read.

// MARK: Cold start

@MainActor
struct CatchingUpTests {
    @Test func whileSetupFindsTodaysSessionItSaysConnecting() {
        #expect(CatchingUp.step(setup: .opening, isUpdating: false, failed: false) == .connecting)
    }

    @Test func onceFoundItSaysOpeningUntilTheSummaryIsAskedFor() {
        #expect(CatchingUp.step(setup: .ready(sessionID: "ses_1"), isUpdating: false, failed: false) == .opening)
        #expect(CatchingUp.step(setup: .ready(sessionID: "ses_1"), isUpdating: true, failed: false) == .summarising)
    }

    @Test func aFailedFirstSummarySaysSoAndARetrySummarisesAgain() {
        #expect(CatchingUp.step(setup: .ready(sessionID: "ses_1"), isUpdating: false, failed: true) == .failed)
        #expect(CatchingUp.step(setup: .ready(sessionID: "ses_1"), isUpdating: true, failed: true) == .summarising)
    }

    @Test func setupFailuresShowTheirOwnViewsInstead() {
        #expect(CatchingUp.step(setup: .failed(retrying: false), isUpdating: false, failed: false) == nil)
        #expect(CatchingUp.step(setup: .signedOut, isUpdating: false, failed: false) == nil)
    }

    @Test func theDateCardIsTitledWithTodaysLongDate() {
        let utc = TimeZone(identifier: "UTC")!
        let date = Date(timeIntervalSince1970: 1_794_441_600) // Thursday 12 November 2026, 00:00 UTC
        let card = StatusCard.catchingUp(.connecting, on: date, timeZone: utc)
        #expect(card.title == "Thursday 12 November")
        #expect(card.now == "Reaching your computer…")
        #expect(card.next == nil)
    }
}

// MARK: Details first load

@MainActor
struct DetailsPrefetchTests {
    @Test func openingTodaysSessionReadsRunningAndRecentWithoutDetails() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [], inbox: []))
        h.control.tools = [ToolPartV2(id: "t1", name: "shell", status: "completed", command: "date", exit: 0)]
        await h.session.open()
        await h.eventually { h.transport.connections == 1 }
        // Not read yet: Details would show its spinner, not the empty line.
        #expect(!h.session.detailsRead)
        h.transport.send(EventLines.connected)
        await h.eventually { h.session.detailsRead }
        #expect(!h.session.showsDetails)
        #expect(h.control.calls.contains(.running("ses_a")))
        #expect(h.control.calls.contains(.recent("ses_a")))
        #expect(h.session.recentSteps.map(\.id) == ["t1"])
        #expect(h.session.detailsRead)
    }

    @Test func readsThatReturnEmptyStillCountAsRead() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: []))
        await h.open()
        await h.eventually { h.session.detailsRead }
        #expect(h.session.detailsRead)
        #expect(h.session.recentSteps.isEmpty)
    }

    @Test func oneSecondAfterARunEndsRecentAndRunningAreReadAgain() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [], inbox: []))
        await h.open()
        await h.eventually { h.session.detailsRead }
        var asked: [Duration] = []
        h.session.sleep = { duration in await MainActor.run { asked.append(duration) } }
        h.control.calls = []
        h.control.tools = [ToolPartV2(id: "t9", name: "shell", status: "completed", command: "ls", exit: 0)]
        h.transport.send(EventLines.succeeded("ses_a"))
        await h.eventually { h.session.recentSteps.map(\.id) == ["t9"] }
        #expect(asked.contains(.seconds(1)))
        #expect(h.control.calls.contains(.running("ses_a")))
        #expect(h.control.calls.contains(.recent("ses_a")))
        #expect(!h.session.showsDetails)
    }
}
