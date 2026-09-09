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
}
