import Foundation
import OpenCodeKit
import Testing
@testable import Hex_Code

// App plan, home "Cold start" and Details "Size and first load": the home appears in one piece,
// and Details opens with Recent and Running now already read.

// MARK: Cold start

@MainActor
struct ColdStartTests {
    @Test func theWholeHomeShowsAtOnceWhenTheFirstStateIsReady() {
        var start = ColdStart()
        #expect(!start.showsHome)
        #expect(!start.showsCard)
        let fades = start.ready()
        #expect(!fades)
        #expect(start.showsHome)
        #expect(start.showsCard)
    }

    @Test func afterTheLimitTheFrameShowsAndTheCardFadesInWhenItArrives() {
        var start = ColdStart()
        start.timedOut()
        #expect(start.showsHome)
        #expect(!start.showsCard)
        // The card arrives later: it fades in.
        let fades = start.ready()
        #expect(fades)
        #expect(start.showsCard)
    }

    @Test func onceShownItStaysShown() {
        var start = ColdStart()
        _ = start.ready()
        start.timedOut()
        #expect(start.showsCard)
        let fades = start.ready()
        #expect(!fades)
        #expect(start.showsCard)
    }

    @Test func theLimitIsOneAndAHalfSeconds() {
        #expect(ColdStart.limit == .milliseconds(1500))
    }
}

@MainActor
struct FirstStateTests {
    @Test func notReadyUntilTheSessionIsFoundAndItsStateAndSummaryAreRead() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: Date(), hasRun: true, children: [], pending: [], inbox: []))
        #expect(!h.session.hasFirstState)
        await h.session.open()
        // Found, but the stream hasn't read the state back yet.
        #expect(!h.session.hasFirstState)
        await h.eventually { h.transport.connections == 1 }
        h.transport.send(EventLines.connected)
        await h.eventually { h.session.hasFirstState }
        // Busy with no saved card: "Still working".
        #expect(h.session.hasFirstState)
        #expect(h.session.summary.summary != nil)
    }

    @Test func aSummaryStillOnItsWayWithNoCardIsNotReady() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: nil, hasRun: true, children: [], pending: [], inbox: []))
        h.server.generateDelay = .milliseconds(300)
        await h.session.open()
        await h.eventually { h.transport.connections == 1 }
        h.transport.send(EventLines.connected)
        await h.eventually { h.session.summary.isUpdating }
        #expect(h.session.summary.isUpdating)
        #expect(!h.session.hasFirstState)
        await h.eventually { h.session.hasFirstState }
        #expect(h.session.summary.summary != nil)
    }

    @Test func anEmptySessionIsReadyWithItsEmptyCard() async {
        let h = ControlHarness(snapshot: LiveSnapshot(busySince: nil, hasRun: false, children: [], pending: [], inbox: []))
        h.server.listed = [SessionListing(id: "ses_a", title: "2026-11-05", directory: "/p", updatedAt: Date())]
        await h.open()
        #expect(h.session.summary.showsEmpty)
        #expect(h.session.hasFirstState)
    }

    @Test func setupFailureIsReady() async {
        let h = ControlHarness()
        h.server.failsList = true
        await h.session.open()
        #expect(h.session.setup == .failed(retrying: false))
        #expect(h.session.hasFirstState)
    }

    @Test func signedOutIsReady() async {
        let h = ControlHarness()
        h.session.serverCheck = { .signedOut }
        await h.session.open()
        #expect(h.session.setup == .signedOut)
        #expect(h.session.hasFirstState)
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
