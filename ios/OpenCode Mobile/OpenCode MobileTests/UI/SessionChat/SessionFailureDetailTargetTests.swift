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

    @Test func failureLineCarriesTheRetryTime() throws {
        let failure = SessionFailure(
            type: "provider.free-limit",
            message: "Free usage limit reached.",
            status: 429,
            providerBody: nil,
            retryAfterMs: 60_000,
            recordedAtEpochMs: 1_000
        )
        let body = DisplayMessageBody(
            message: Message(
                id: "a1", role: .assistant, text: "", model: nil, mode: nil, sentAtEpochMs: nil, failure: failure
            ),
            isStreaming: false,
            blocks: [],
            renderedMarkdown: nil
        )
        let blocks = try #require(
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                [body],
                collapseConsecutiveToolCalls: false
            ).first?.blocks
        )
        guard case .failureLine(_, let text, _, _, let action, _, _, let retryAt) = blocks.last else {
            Issue.record("expected a failure line")
            return
        }
        #expect(text == "HTTP 429 · Free usage limit reached.")
        #expect(action == .pickModel)
        #expect(retryAt == 61_000)
    }

    @Test func retryCountdownOnlyOnTheLastTurn() {
        let failed = failureRow("a1", body: "limit", covered: false, retryAt: 9_000)
        #expect(SessionChatTimelineBlocks.retryCountdownBlockID(in: [failed]) == "a1:failure")
        #expect(
            SessionChatTimelineBlocks.retryCountdownBlockID(in: [failed, failureRow("a2", body: "x", covered: false)])
                == "a1:failure"
        )

        let user = SessionChatRow(id: "u2", role: .user, text: "again", metadataLabel: "", blocks: [])
        #expect(SessionChatTimelineBlocks.retryCountdownBlockID(in: [failed, user]) == nil)
        #expect(
            SessionChatTimelineBlocks.retryCountdownBlockID(in: [failureRow("a0", body: "x", covered: false), user]) == nil
        )
    }

    @Test func noRetryTimeMeansNoCountdown() {
        #expect(
            SessionChatTimelineBlocks.retryCountdownBlockID(in: [failureRow("a1", body: "limit", covered: true)]) == nil
        )
    }

    private func failureRow(_ id: String, body: String, covered: Bool, retryAt: Int64? = nil) -> SessionChatRow {
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
                    coveredByLiveRetry: covered,
                    retryAtEpochMs: retryAt
                ),
            ]
        )
    }
}
