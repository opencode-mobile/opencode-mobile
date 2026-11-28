import XCTest
@testable import OpenCode_Mobile

final class SessionListStatusMappingTests: XCTestCase {
    func testRunningOutranksReady() {
        XCTAssertEqual(
            sessionListStatus(busyStatus: .running, isReady: true),
            .running
        )
        XCTAssertEqual(
            sessionListStatus(busyStatus: .retrying(attempt: nil), isReady: true),
            .retrying(attempt: nil)
        )
        XCTAssertEqual(
            sessionListStatus(busyStatus: .retrying(attempt: 2), isReady: true),
            .retrying(attempt: 2)
        )
    }

    func testReadyWhenIdleAndFlagged() {
        XCTAssertEqual(
            sessionListStatus(busyStatus: nil, isReady: true),
            .ready
        )
    }

    func testIdleWhenNotReady() {
        XCTAssertEqual(
            sessionListStatus(busyStatus: nil, isReady: false),
            .idle
        )
    }

    func testStaleToneUsesMutedChip() {
        let stale = SessionListStatus.running.listItemStatus(isStale: true)
        XCTAssertEqual(stale.text.resolved, "Running")
        XCTAssertEqual(stale.tone, .stale)
        let live = SessionListStatus.running.listItemStatus(isStale: false)
        XCTAssertEqual(live.tone, .interactive)
    }

    func testServerUnreadMapsOutcomeToDoneFailedStopped() {
        XCTAssertEqual(sessionListStatus(busyStatus: nil, isReady: false, unreadOutcome: .succeeded), .done)
        XCTAssertEqual(sessionListStatus(busyStatus: nil, isReady: false, unreadOutcome: .failed), .failed)
        XCTAssertEqual(sessionListStatus(busyStatus: nil, isReady: false, unreadOutcome: .interrupted), .stopped)
    }

    func testRunningOutranksServerUnread() {
        XCTAssertEqual(sessionListStatus(busyStatus: .running, isReady: false, unreadOutcome: .succeeded), .running)
        XCTAssertEqual(
            sessionListStatus(busyStatus: .retrying(attempt: 3), isReady: false, unreadOutcome: .failed),
            .retrying(attempt: 3)
        )
    }

    func testUnreadStatusesCopyToneAndAttention() {
        let done = SessionListStatus.done.listItemStatus()
        XCTAssertEqual(done.text.resolved, "Done")
        XCTAssertEqual(done.tone, .success)
        let failed = SessionListStatus.failed.listItemStatus()
        XCTAssertEqual(failed.text.resolved, "Failed")
        XCTAssertEqual(failed.tone, .error)
        let stopped = SessionListStatus.stopped.listItemStatus()
        XCTAssertEqual(stopped.text.resolved, "Stopped")
        XCTAssertEqual(stopped.tone, .warning)
        for status in [SessionListStatus.ready, .done, .failed, .stopped] {
            XCTAssertTrue(status.isAttention)
            XCTAssertTrue(status.isReady)
        }
        XCTAssertFalse(SessionListStatus.idle.isAttention)
        XCTAssertFalse(SessionListStatus.running.isReady)
    }

    private func sessionListStatus(
        busyStatus: SessionBusyStatus?,
        isReady: Bool,
        unreadOutcome: SessionIdleOutcome? = nil
    ) -> SessionListStatus {
        SessionListItem(
            id: "ses-1",
            title: "Session",
            worktree: "/repo",
            busyStatus: busyStatus,
            isReady: isReady,
            unreadOutcome: unreadOutcome
        ).sessionListStatus()
    }
}
