import Foundation
import Testing
@testable import OpenCode_Mobile

struct ToolArgumentsPreviewTests {
    @Test func partialStringFields_readsAStringStillBeingWritten() {
        #expect(fields("") == [:])
        #expect(fields("{") == [:])
        #expect(fields(#"{"comm"#) == [:])
        #expect(fields(#"{"command""#) == [:])
        #expect(fields(#"{"command": ""#) == ["command": ""])
        #expect(fields(#"{"command": "ls -la /t"#) == ["command": "ls -la /t"])
        #expect(
            fields(#"{"command":"ls","timeout":12000,"description":"Lis"#)
                == ["command": "ls", "description": "Lis"]
        )
    }

    @Test func partialStringFields_decodesEscapesAndDropsAnUnfinishedOne() {
        #expect(fields(#"{"content":"a\n\"b\"""#) == ["content": "a\n\"b\""])
        #expect(fields(#"{"content":"tab\"#) == ["content": "tab"])
        #expect(fields(#"{"content":"é"#) == ["content": "é"])
        #expect(fields(#"{"content":"x\u00"#) == ["content": "x"])
    }

    @Test func partialStringFields_skipsNestedValuesAndNeverFailsOnGarbage() {
        #expect(
            fields(#"{"edits":[{"old":"}"},{"x":[1,2]}],"filePath":"a.ts","newString":"b"#)
                == ["filePath": "a.ts", "newString": "b"]
        )
        #expect(fields("not json") == [:])
        #expect(fields(#"["command"]"#) == [:])
        #expect(fields(#"{"a":"1"} trailing"#) == ["a": "1"])
        #expect(fields(#"{"a":"#) == [:])
    }

    @Test func raw_isOnlyThePreviewOfAPendingOrRunningToolWithoutSettledArguments() {
        let preview: [String: AnyCodable] = ["preview": .string(#"{"command":"ls"#)]
        #expect(ToolArgumentsPreview.raw(tool(.pending, preview)) == #"{"command":"ls"#)
        #expect(ToolArgumentsPreview.string(tool(.running, preview), keys: "command") == "ls")
        #expect(ToolArgumentsPreview.raw(tool(.completed, preview)) == nil)
        #expect(ToolArgumentsPreview.raw(tool(.error, preview)) == nil)
        #expect(ToolArgumentsPreview.raw(tool(.pending, nil)) == nil)
        var settled = preview
        settled["command"] = .string("ls -la")
        #expect(!ToolArgumentsPreview.isReceiving(tool(.pending, settled)))
        #expect(ToolArgumentsPreview.isReceiving(tool(.pending, preview)))
    }

    @Test func mergedInput_settledArgumentsDropThePreview() {
        let preview: [String: AnyCodable] = ["preview": .string(#"{"command":"l"#)]
        let settled: [String: AnyCodable] = ["command": .string("ls")]
        #expect(ToolArgumentsPreview.mergedInput(preview, settled) == settled)
        let longer: [String: AnyCodable] = ["preview": .string(#"{"command":"ls"}"#)]
        #expect(ToolArgumentsPreview.mergedInput(preview, longer) == longer)
        #expect(ToolArgumentsPreview.mergedInput(preview, [:]) == preview)
    }

    private func fields(_ raw: String) -> [String: String] {
        ToolArgumentsPreview.partialStringFields(raw)
    }

    private func tool(_ status: ToolStatus, _ input: [String: AnyCodable]?) -> ToolPart {
        ToolPart(id: "call_1", toolName: "bash", status: status, input: input)
    }
}
