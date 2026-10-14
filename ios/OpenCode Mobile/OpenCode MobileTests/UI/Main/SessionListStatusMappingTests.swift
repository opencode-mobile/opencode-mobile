import XCTest
@testable import OpenCode_Mobile

final class SessionListStatusMappingTests: XCTestCase {
    func testRunningOutranksReady() {
        XCTAssertEqual(
            sessionListStatus(statusLabel: "Working", isReady: true),
            .running
        )
        XCTAssertEqual(
            sessionListStatus(statusLabel: "Retrying", isReady: true),
            .retrying(attempt: nil)
        )
        XCTAssertEqual(
            sessionListStatus(statusLabel: "Retrying · 2", isReady: true),
            .retrying(attempt: 2)
        )
    }

    func testReadyWhenIdleAndFlagged() {
        XCTAssertEqual(
            sessionListStatus(statusLabel: nil, isReady: true),
            .ready
        )
    }

    func testIdleWhenNotReady() {
        XCTAssertEqual(
            sessionListStatus(statusLabel: nil, isReady: false),
            .idle
        )
    }

    func testStaleToneUsesMutedChip() {
        let stale = SessionListStatus.running.listItemStatus(isStale: true)
        XCTAssertEqual(stale.text, "Running")
        XCTAssertEqual(stale.tone, .stale)
        let live = SessionListStatus.running.listItemStatus(isStale: false)
        XCTAssertEqual(live.tone, .interactive)
    }

    func testServerUnreadMapsOutcomeToDoneFailedStopped() {
        XCTAssertEqual(sessionListStatus(statusLabel: nil, isReady: false, unreadOutcome: .succeeded), .done)
        XCTAssertEqual(sessionListStatus(statusLabel: nil, isReady: false, unreadOutcome: .failed), .failed)
        XCTAssertEqual(sessionListStatus(statusLabel: nil, isReady: false, unreadOutcome: .interrupted), .stopped)
    }

    func testRunningOutranksServerUnread() {
        XCTAssertEqual(sessionListStatus(statusLabel: "Working", isReady: false, unreadOutcome: .succeeded), .running)
        XCTAssertEqual(
            sessionListStatus(statusLabel: "Retrying · 3", isReady: false, unreadOutcome: .failed),
            .retrying(attempt: 3)
        )
    }

    func testUnreadStatusesCopyToneAndAttention() {
        let done = SessionListStatus.done.listItemStatus()
        XCTAssertEqual(done.text, "Done")
        XCTAssertEqual(done.tone, .success)
        let failed = SessionListStatus.failed.listItemStatus()
        XCTAssertEqual(failed.text, "Failed")
        XCTAssertEqual(failed.tone, .error)
        let stopped = SessionListStatus.stopped.listItemStatus()
        XCTAssertEqual(stopped.text, "Stopped")
        XCTAssertEqual(stopped.tone, .warning)
        for status in [SessionListStatus.ready, .done, .failed, .stopped] {
            XCTAssertTrue(status.isAttention)
            XCTAssertTrue(status.isReady)
        }
        XCTAssertFalse(SessionListStatus.idle.isAttention)
        XCTAssertFalse(SessionListStatus.running.isReady)
    }

    private func sessionListStatus(
        statusLabel: String?,
        isReady: Bool,
        unreadOutcome: SessionIdleOutcome? = nil
    ) -> SessionListStatus {
        SessionListItem(
            id: "ses-1",
            title: "Session",
            worktree: "/repo",
            statusLabel: statusLabel,
            isReady: isReady,
            unreadOutcome: unreadOutcome
        ).sessionListStatus()
    }
}
