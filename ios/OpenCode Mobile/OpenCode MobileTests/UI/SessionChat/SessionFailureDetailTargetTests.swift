import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionFailureDetailTargetTests {
    @Test func compactWithoutABodyStillOpensASheet() {
        let target = SessionChatToolDetailTarget.failure(
            text: "Context overflow",
            providerBody: nil,
            action: .compact,
            actionLabel: "Compact"
        )
        #expect(target?.title == "Context overflow")
        #expect(target?.body == "")
        #expect(target?.monospace == false)
        #expect(target?.sheetAction == .compact)
        #expect(target?.sheetActionLabel == "Compact")
    }

    @Test func copyWithABodyUsesTheBodyCopyOnly() {
        let target = SessionChatToolDetailTarget.failure(
            text: "Something went wrong",
            providerBody: "{\"error\":1}",
            action: .copy,
            actionLabel: "Copy"
        )
        #expect(target?.title == "Provider response")
        #expect(target?.monospace == true)
        #expect(target?.sheetAction == nil)
    }

    @Test func copyWithoutABodyCopiesTheCaption() {
        let target = SessionChatToolDetailTarget.failure(
            text: "Something went wrong",
            providerBody: "  ",
            action: .copy,
            actionLabel: "Copy"
        )
        #expect(target?.sheetAction == .copyCaption)
        #expect(target?.title == "Something went wrong")
        #expect(target?.monospace == false)
    }

    @Test func authWithABodyHasNoAction() {
        let target = SessionChatToolDetailTarget.failure(
            text: "HTTP 401 · Sign in",
            providerBody: "denied",
            action: nil,
            actionLabel: nil
        )
        #expect(target?.title == "Provider response")
        #expect(target?.body == "denied")
        #expect(target?.sheetAction == nil)
        #expect(target?.sheetLinkLabel == nil)
    }

    @Test func markWithoutBodyOrActionDoesNotOpen() {
        let target = SessionChatToolDetailTarget.failure(
            text: "Permission rejected",
            providerBody: nil,
            action: nil,
            actionLabel: nil
        )
        #expect(target == nil)
    }

    @Test func liveLinkRidesAlongWithTheBody() {
        let target = SessionChatToolDetailTarget.failure(
            text: "Slow down",
            providerBody: "limited",
            action: nil,
            actionLabel: nil,
            linkLabel: " Upgrade ",
            link: " https://example.com "
        )
        #expect(target?.sheetLinkLabel == "Upgrade")
        #expect(target?.sheetLink == "https://example.com")
        #expect(target?.sheetAction == nil)
    }

    @Test func newestLiveRetryBodySkipsOlderAndUncoveredMarks() {
        let older = failureRow("1", body: "old", covered: true)
        let newer = failureRow("2", body: "new", covered: true)
        let auth = failureRow("3", body: "denied", covered: false)
        #expect(SessionChatTimelineBlocks.newestLiveRetryProviderBody(in: [older, newer, auth]) == "new")
    }

    private func failureRow(_ id: String, body: String, covered: Bool) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: .assistant,
            text: "",
            metadataLabel: "",
            blocks: [
                .failureLine(
                    blockID: "\(id):failure",
                    text: "Slow down",
                    providerBody: body,
                    isNeutral: false,
                    action: nil,
                    actionLabel: nil,
                    coveredByLiveRetry: covered
                ),
            ]
        )
    }
}
