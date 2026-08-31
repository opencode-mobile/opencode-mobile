import Testing
@testable import OpenCode_Mobile

struct TerminalEmulatorViewTests {
    @Test func jsStringLiteralEncodesPlainText() {
        #expect(terminalJsStringLiteral("hello") == "\"hello\"")
    }

    @Test func jsStringLiteralEscapesControlCharacters() {
        #expect(terminalJsStringLiteral("a\nb\t\"c\\") == "\"a\\nb\\t\\\"c\\\\\"")
    }
}
