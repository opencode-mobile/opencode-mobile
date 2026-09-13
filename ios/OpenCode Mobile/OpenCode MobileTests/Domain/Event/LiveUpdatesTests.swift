import Testing
@testable import OpenCode_Mobile

@Suite
struct LiveUpdatesTests {
    @Test func occupyingIdleIsConnectingNotOffline() {
        let chrome = composeLiveUpdates(
            connectionState: .idle,
            path: .ready,
            occupancy: .active,
            hasConnection: true
        )
        #expect(chrome.label == .connecting)
        #expect(chrome.canMutate)
    }

    @Test func occupyingDisconnectedIsConnecting() {
        let chrome = composeLiveUpdates(
            connectionState: .disconnected,
            path: .ready,
            occupancy: .active,
            hasConnection: true
        )
        #expect(chrome.label == .connecting)
        #expect(chrome.canMutate)
    }

    @Test func connectedAndReadyIsLive() {
        let chrome = composeLiveUpdates(
            connectionState: .connected,
            path: .ready,
            occupancy: .active,
            hasConnection: true
        )
        #expect(chrome.label == .live)
        #expect(chrome.canMutate)
    }

    @Test func graceKeepsLiveWhileSocketStillUp() {
        let chrome = composeLiveUpdates(
            connectionState: .connected,
            path: .ready,
            occupancy: .grace,
            hasConnection: true
        )
        #expect(chrome.label == .live)
        #expect(chrome.canMutate)
    }

    @Test func backgroundStopIsOfflineAndBlocksMutations() {
        let chrome = composeLiveUpdates(
            connectionState: .idle,
            path: .ready,
            occupancy: .stopped,
            hasConnection: true
        )
        #expect(chrome.label == .offline)
        #expect(!chrome.canMutate)
    }

    @Test func unavailablePathIsOfflineEvenIfSocketLooksConnected() {
        let chrome = composeLiveUpdates(
            connectionState: .connected,
            path: .unavailable,
            occupancy: .active,
            hasConnection: true
        )
        #expect(chrome.label == .offline)
        #expect(!chrome.canMutate)
    }

    @Test func missingConnectionProfileIsOffline() {
        let chrome = composeLiveUpdates(
            connectionState: .connecting,
            path: .ready,
            occupancy: .active,
            hasConnection: false
        )
        #expect(chrome.label == .offline)
        #expect(!chrome.canMutate)
    }

    @Test func connectingAllowsMutationsWhenPathIsReady() {
        let chrome = composeLiveUpdates(
            connectionState: .connecting,
            path: .ready,
            occupancy: .active,
            hasConnection: true
        )
        #expect(chrome.label == .connecting)
        #expect(chrome.canMutate)
    }

    @Test func authFailedIsOwnChromeAndBlocksMutations() {
        let chrome = composeLiveUpdates(
            connectionState: .authFailed,
            path: .ready,
            occupancy: .active,
            hasConnection: true
        )
        #expect(chrome.label == .authFailed)
        #expect(!chrome.canMutate)
    }
}
