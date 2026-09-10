import Foundation
import Testing
@testable import OpenCode_Mobile

struct TitleCaseIdentifierTests {
    @Test func displayName_titleCasesModelVariantAndEffortIds() {
        #expect(TitleCaseIdentifier.displayName(for: "build") == "Build")
        #expect(TitleCaseIdentifier.displayName(for: "grok-4.6") == "Grok 4.6")
        #expect(TitleCaseIdentifier.displayName(for: "high") == "High")
        #expect(TitleCaseIdentifier.displayName(for: "Default") == "Default")
        #expect(TitleCaseIdentifier.displayName(for: "code-reviewer") == "Code Reviewer")
        #expect(TitleCaseIdentifier.displayName(for: "Claude Sonnet 4") == "Claude Sonnet 4")
    }
}
