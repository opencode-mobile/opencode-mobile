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

    @Test func load_rejectsDecodedBytesOverTwentyMebibytes() {
        let data = Data(count: Int(AttachmentLimits.maxDecodedBytes) + 1)
        #expect(throws: AttachmentLoadError.tooLarge) {
            try AttachmentLoader().load(data: data, filename: "huge.pdf", declaredMime: "application/pdf")
        }
    }
}
