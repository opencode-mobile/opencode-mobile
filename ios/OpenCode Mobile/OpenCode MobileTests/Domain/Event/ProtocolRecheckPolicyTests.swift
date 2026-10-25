import Foundation
import Testing
@testable import OpenCode_Mobile

struct ProtocolRecheckPolicyTests {
    @Test func httpErrorOnEventPathSuggestsProtocolChange() {
        // opencode v2 answers the v1 event paths with 500.
        #expect(suggests(.http(500)))
        #expect(suggests(.http(404)))
    }

    @Test func rejectedCredentialIsNotAProtocolChange() {
        #expect(!suggests(.http(401)))
        #expect(!suggests(.http(403)))
    }

    @Test func transportFailureBeforeAnyResponseIsAnOutage() {
        #expect(!suggests(.transport(URLError(.cannotConnectToHost))))
        #expect(!suggests(.ended))
    }

    @Test func openedWithoutServerConnectedSuggestsProtocolChange() {
        #expect(suggests(.transport(SseHandshakeError(reason: .timeout)), httpOpened: true))
        #expect(suggests(.ended, httpOpened: true))
    }

    @Test func streamThatWentLiveAndDroppedIsNotAProtocolSignal() {
        #expect(!suggests(.ended, httpOpened: true, serverConnected: true))
        #expect(!suggests(.transport(nil), httpOpened: true, serverConnected: true))
    }

    @Test func isDueThrottlesToOneRecheckPerWindow() {
        let start = ContinuousClock.now
        #expect(ProtocolRecheckPolicy.isDue(now: start, lastRecheck: nil))
        #expect(!ProtocolRecheckPolicy.isDue(now: start + .seconds(9), lastRecheck: start))
        #expect(ProtocolRecheckPolicy.isDue(now: start + .seconds(15), lastRecheck: start))
    }

    @Test func actionRetargetsOnlyWhenTheDetectedProtocolDiffers() {
        #expect(ProtocolRecheckPolicy.action(result: .detected(.v2), applied: .v1) == .retarget(.v2))
        #expect(ProtocolRecheckPolicy.action(result: .detected(.v1), applied: .v2) == .retarget(.v1))
        #expect(ProtocolRecheckPolicy.action(result: .detected(.v1), applied: .v1) == .keepProtocol)
    }

    @Test func actionMarksUnreachableOnlyWhenNoProtocolAnswered() {
        #expect(ProtocolRecheckPolicy.action(result: .unreachable, applied: .v1) == .markUnreachable)
        #expect(ProtocolRecheckPolicy.action(result: .skipped, applied: .v1) == ProtocolRecheckAction.none)
    }

    private func suggests(
        _ outcome: SseAttemptOutcome,
        httpOpened: Bool = false,
        serverConnected: Bool = false
    ) -> Bool {
        ProtocolRecheckPolicy.suggestsProtocolChange(
            outcome: outcome,
            httpOpened: httpOpened,
            serverConnected: serverConnected
        )
    }
}
