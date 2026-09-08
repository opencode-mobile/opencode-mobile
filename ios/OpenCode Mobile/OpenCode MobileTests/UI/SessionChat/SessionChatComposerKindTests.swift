import Testing
@testable import OpenCode_Mobile

struct SessionChatComposerKindTests {
    @Test func resolve_rootWithoutRequests_isPrompt() {
        #expect(
            SessionChatComposerKind.resolve(isChildSession: false, hasPermission: false, hasQuestion: false) == .prompt
        )
    }

    @Test func resolve_childWithoutRequests_isChildLockout() {
        #expect(
            SessionChatComposerKind.resolve(isChildSession: true, hasPermission: false, hasQuestion: false) == .childLockout
        )
    }

    @Test func resolve_permissionBeatsChildLockoutAndQuestion() {
        #expect(
            SessionChatComposerKind.resolve(isChildSession: true, hasPermission: true, hasQuestion: true) == .permission
        )
    }

    @Test func resolve_questionBeatsChildLockout() {
        #expect(
            SessionChatComposerKind.resolve(isChildSession: true, hasPermission: false, hasQuestion: true) == .question
        )
    }
}
