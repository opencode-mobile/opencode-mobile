import Foundation
import Testing
@testable import OpenCode_Mobile

struct TerminalWsFrameParserTests {
    @Test func parseText_isOutput() {
        #expect(TerminalWsFrameParser.parseText("hello") == .output("hello"))
    }

    @Test func parseBinary_controlCursor_isCursor() {
        var bytes = Data([0])
        bytes.append(Data(#"{"cursor":12}"#.utf8))
        #expect(TerminalWsFrameParser.parseBinary(bytes) == .cursor("12"))
    }

    @Test func parseBinary_otherPayload_isIgnored() {
        #expect(TerminalWsFrameParser.parseBinary(Data([1, 2, 3])) == .ignored)
        #expect(TerminalWsFrameParser.parseBinary(Data()) == .ignored)
        var negative = Data([0])
        negative.append(Data(#"{"cursor":-1}"#.utf8))
        #expect(TerminalWsFrameParser.parseBinary(negative) == .ignored)
    }
}
