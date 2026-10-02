import Foundation
import Testing
@testable import OpenCode_Mobile

struct PermissionDecisionTests {
    private let adapter = SessionDomainAdapter()

    @Test func allowOnceMapsToOnce() {
        #expect(adapter.permissionResponse(.allowOnce) == "once")
    }

    @Test func denyMapsToReject() {
        #expect(adapter.permissionResponse(.deny) == "reject")
    }

    @Test func alwaysMapsToAlways() {
        #expect(adapter.permissionResponse(.alwaysAllow) == "always")
    }

    @Test func v2FilesUseServerCounts() throws {
        let metadata = try JSONDecoder().decode(
            [String: AnyCodable].self,
            from: Data(#"{"files":[{"file":"README.md","patch":"@@\n-a\n+b","additions":1,"deletions":1},{"file":"notes/todo.md","patch":"--- a\n+++ b\n+one\n+two"}]}"#.utf8)
        )
        #expect(PermissionFileChanges.from(metadata) == [
            PermissionFileChange(path: "README.md", additions: 1, deletions: 1),
            PermissionFileChange(path: "notes/todo.md", additions: 2, deletions: 0),
        ])
    }

    @Test func v1FilepathAndDiff() throws {
        let metadata = try JSONDecoder().decode(
            [String: AnyCodable].self,
            from: Data(#"{"filepath":"src/app.ts","diff":"--- a/src/app.ts\n+++ b/src/app.ts\n@@\n-old\n+new\n+more"}"#.utf8)
        )
        #expect(PermissionFileChanges.from(metadata) == [PermissionFileChange(path: "src/app.ts", additions: 2, deletions: 1)])
        #expect(PermissionFileChanges.from(["command": .string("git status")]).isEmpty)
    }

    @Test func hidingKeepsTheRequestPendingUntilANewOneArrives() {
        func request(_ id: String) -> PermissionRequest {
            PermissionRequest(
                id: id, sessionID: "ses_1", permission: "shell", patterns: ["git status"],
                metadata: [:], always: [], toolMessageID: nil, toolCallID: nil
            )
        }
        var state = SessionChatInboxRegionState()
        state.permissionRequests = [request("per_1")]
        state.permissionHiddenID = "per_1"
        #expect(state.isPermissionDockHidden)
        #expect(state.visibleQuestionRequest == nil)
        state.permissionRequests = [request("per_2")]
        #expect(!state.isPermissionDockHidden)
    }
}
