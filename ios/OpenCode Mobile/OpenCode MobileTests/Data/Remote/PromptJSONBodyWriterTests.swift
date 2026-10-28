import Foundation
import Testing
@testable import OpenCode_Mobile

struct PromptJSONBodyWriterTests {
    @Test func writeV1Prompt_streamsFileBackedDataURL() throws {
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("prompt-writer-source.bin")
        try Data([1, 2, 3, 4]).write(to: source)
        let attachment = Attachment(
            id: "a1",
            filename: "note.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "",
            fileURL: source,
            byteCount: 4
        )
        let body = SendMessageRequestDTO(
            parts: [
                SendMessagePartDTO(type: "text", text: "Hi"),
                SendMessagePartDTO(type: "file", mime: "text/plain", filename: "note.txt", url: ""),
            ]
        )

        let file = try PromptJSONBodyWriter.writeV1Prompt(body, attachments: [attachment])
        let json = try String(contentsOf: file, encoding: .utf8)

        #expect(json.contains("\"text\":\"Hi\""))
        #expect(json.contains("\"filename\":\"note.txt\""))
        #expect(json.contains("data:text/plain;base64,AQIDBA=="))
    }

    @Test func writeV1Prompt_writesFileMentionURLLiterallyAndKeepsAttachmentIndex() throws {
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("prompt-writer-attachment.bin")
        try Data([1, 2, 3, 4]).write(to: source)
        let attachment = Attachment(
            id: "a1",
            filename: "note.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "",
            fileURL: source,
            byteCount: 4
        )
        let body = SendMessageRequestDTO(
            parts: [
                SendMessagePartDTO(
                    type: "file",
                    mime: "text/plain",
                    filename: "App.kt",
                    url: "file:///repo/src/App.kt",
                    fileSource: FileReferenceSourceDTO(
                        path: "src/App.kt",
                        value: "@src/App.kt",
                        start: 4,
                        end: 15
                    )
                ),
                SendMessagePartDTO(type: "file", mime: "text/plain", filename: "note.txt", url: ""),
            ]
        )
        let file = try PromptJSONBodyWriter.writeV1Prompt(body, attachments: [attachment])
        let json = try String(contentsOf: file, encoding: .utf8)
        #expect(json.contains("\"url\":\"file:///repo/src/App.kt\""))
        #expect(json.contains("\"path\":\"src/App.kt\""))
        #expect(json.contains("\"value\":\"@src/App.kt\""))
        #expect(json.contains("data:text/plain;base64,AQIDBA=="))
        let fileURL = json.range(of: "file:///repo/src/App.kt")
        let dataURL = json.range(of: "data:text/plain;base64,AQIDBA==")
        #expect(fileURL != nil && dataURL != nil && fileURL!.lowerBound < dataURL!.lowerBound)
    }

    @Test func writeV2Prompt_writesMentionURILiterallyAfterAttachment() throws {
        let source = FileManager.default.temporaryDirectory.appendingPathComponent("prompt-writer-v2.bin")
        try Data([1, 2, 3, 4]).write(to: source)
        let attachment = Attachment(
            id: "a1",
            filename: "note.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "",
            fileURL: source,
            byteCount: 4
        )
        let body = V2PromptRequestDTO(
            text: "see @src/App.kt",
            files: [V2PromptFileDTO(uri: "", name: "note.txt", mime: "text/plain")],
            delivery: "steer",
            fileMentions: [
                V2PromptFileMentionDTO(
                    uri: "file:///repo/src/App.kt",
                    name: "App.kt",
                    mention: V2PromptSourceDTO(start: 4, end: 15, text: "@src/App.kt")
                ),
            ]
        )
        let file = try PromptJSONBodyWriter.writeV2Prompt(body, attachments: [attachment])
        let json = try String(contentsOf: file, encoding: .utf8)
        #expect(json.contains("data:text/plain;base64,AQIDBA=="))
        #expect(json.contains("\"uri\":\"file:///repo/src/App.kt\""))
        #expect(json.contains("\"text\":\"@src/App.kt\""))
        let dataURL = json.range(of: "data:text/plain;base64,AQIDBA==")
        let fileURL = json.range(of: "file:///repo/src/App.kt")
        #expect(dataURL != nil && fileURL != nil && dataURL!.lowerBound < fileURL!.lowerBound)
    }

    @Test func writeV2Prompt_writesSkillWithMention() throws {
        let body = V2PromptRequestDTO(
            text: "fix /tdd now",
            skills: [
                V2PromptSkillDTO(id: "tdd", mention: V2PromptSourceDTO(start: 4, end: 8, text: "/tdd")),
                V2PromptSkillDTO(id: "bare"),
            ],
            delivery: "steer"
        )
        let file = try PromptJSONBodyWriter.writeV2Prompt(body, attachments: [])
        let json = try String(contentsOf: file, encoding: .utf8)
        #expect(json.contains("\"skills\":[{\"id\":\"tdd\",\"mention\":{\"start\":4,\"end\":8,\"text\":\"/tdd\"}},{\"id\":\"bare\"}]"))
    }

    @Test func writeV2Command_writesSkillIDs() throws {
        let body = V2RunCommandRequestDTO(name: "init", text: "go", skills: [V2PromptSkillDTO(id: "tdd")], delivery: "steer")
        let file = try PromptJSONBodyWriter.writeV2Command(body, attachments: [])
        let json = try String(contentsOf: file, encoding: .utf8)
        #expect(json.contains("\"skills\":[{\"id\":\"tdd\"}]"))
    }

    @Test func load_rejectsDecodedBytesOverTwentyMebibytes() {
        let data = Data(count: Int(AttachmentLimits.maxDecodedBytes) + 1)
        #expect(throws: AttachmentLoadError.tooLarge) {
            try AttachmentLoader().load(data: data, filename: "huge.pdf", declaredMime: "application/pdf")
        }
    }

    @Test func writers_sendClientMessageID() throws {
        let v1Prompt = try PromptJSONBodyWriter.writeV1Prompt(
            SendMessageRequestDTO(parts: [SendMessagePartDTO(type: "text", text: "Hi")], messageID: "msg_v1"),
            attachments: []
        )
        let v2Prompt = try PromptJSONBodyWriter.writeV2Prompt(
            V2PromptRequestDTO(id: "msg_v2", text: "Hi"),
            attachments: []
        )
        let v1Command = try PromptJSONBodyWriter.writeV1Command(
            RunCommandRequestDTO(command: "review", arguments: "", messageID: "msg_cmd"),
            attachments: []
        )

        #expect(try String(contentsOf: v1Prompt, encoding: .utf8).contains("\"messageID\":\"msg_v1\""))
        #expect(try String(contentsOf: v2Prompt, encoding: .utf8).contains("\"id\":\"msg_v2\""))
        #expect(try String(contentsOf: v1Command, encoding: .utf8).contains("\"messageID\":\"msg_cmd\""))
    }
}
