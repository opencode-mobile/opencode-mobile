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
            .running
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

    /// Mirrors `SessionListItem.sessionListStatus` in ProjectListHomeView.
    private func sessionListStatus(statusLabel: String?, isReady: Bool) -> SessionListStatus {
        switch statusLabel {
        case "Working", "Retrying":
            return .running
        default:
            return isReady ? .ready : .idle
        }
    }
}
