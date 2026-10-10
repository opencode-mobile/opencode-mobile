import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatViewModelReuseTests {
    @Test func firstAppearanceBuildsViewModel() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-1",
            boundTaskID: nil,
            hasViewModel: false
        )

        #expect(decision == .rebuild)
    }

    @Test func reappearForSameSessionKeepsViewModel() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-1",
            boundTaskID: "ses-1",
            hasViewModel: true
        )

        #expect(decision == .reuse)
    }

    @Test func differentSessionRebuilds() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-2",
            boundTaskID: "ses-1",
            hasViewModel: true
        )

        #expect(decision == .rebuild)
    }

    @Test func newOptimisticInstanceRebuilds() {
        let placeholder = OptimisticSessionCreate.placeholderSessionID
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "\(placeholder)-\(UUID().uuidString)",
            boundTaskID: "\(placeholder)-\(UUID().uuidString)",
            hasViewModel: true
        )

        #expect(decision == .rebuild)
    }

    @Test func missingViewModelRebuildsEvenWhenIDMatches() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-1",
            boundTaskID: "ses-1",
            hasViewModel: false
        )

        #expect(decision == .rebuild)
    }
}
