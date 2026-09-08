import Testing
@testable import OpenCode_Mobile

struct OpenCodeAssistantMessageTests {
    @Test func displayTextUsesPlaceholderWhenEmpty() {
        #expect(OpenCodeAssistantMessage.displayText("") == "…")
        #expect(OpenCodeAssistantMessage.displayText("   ") == "…")
    }

    @Test func displayTextKeepsVisibleCopy() {
        #expect(OpenCodeAssistantMessage.displayText("Ready") == "Ready")
    }
}
