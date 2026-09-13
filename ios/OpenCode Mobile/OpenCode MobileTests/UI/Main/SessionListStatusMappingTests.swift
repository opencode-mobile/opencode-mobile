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

    /// Mirrors `SessionListItem.sessionListStatus` in ProjectListHomeView.
    private func sessionListStatus(statusLabel: String?, isReady: Bool) -> SessionListStatus {
        switch statusLabel {
        case "Working":
            return .running
        case "Retrying":
            return .retrying(attempt: nil)
        case let label? where label.hasPrefix("Retrying · "):
            return .retrying(attempt: Int(label.dropFirst("Retrying · ".count)))
        default:
            return isReady ? .ready : .idle
        }
    }
}
