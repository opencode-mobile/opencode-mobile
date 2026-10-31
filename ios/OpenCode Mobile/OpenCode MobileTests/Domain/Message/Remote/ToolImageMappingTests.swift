import Foundation
import Testing
@testable import OpenCode_Mobile

/// Images a tool returns reach `ToolPart.images` on both protocols, from REST and live events,
/// without changing the tool's text output (#898).
@MainActor
struct ToolImageMappingTests {
    /// A 1×1 PNG.
    static let pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    static let pngDataURL = "data:image/png;base64,\(pngBase64)"
    static let pdfDataURL = "data:application/pdf;base64,JVBERi0xLjQK"

    private let adapter = MessageDomainAdapter()

    // MARK: - v1

    @Test func v1CompletedToolKeepsImageAttachmentsAndOutput() throws {
        let message = try v1Message(
            state: """
            {
              "status": "completed",
              "input": {"filePath": "/Users/example/project/screen.png"},
              "output": "Image read successfully",
              "title": "screen.png",
              "metadata": {},
              "time": {"start": 1, "end": 2},
              "attachments": [
                {"id": "prt_file_1", "type": "file", "mime": "image/png", "filename": "screen.png", "url": "\(Self.pngDataURL)"},
                {"id": "prt_file_2", "type": "file", "mime": "application/pdf", "filename": "spec.pdf", "url": "\(Self.pdfDataURL)"}
              ]
            }
            """
        )
        let tool = try #require(tools(message).first)

        #expect(tool.output == "Image read successfully")
        #expect(tool.images.map(\.id) == ["prt_file_1"])
        #expect(tool.images.first?.filename == "screen.png")
        #expect(tool.images.first?.mime == "image/png")
        #expect(tool.images.first?.kind == .image)
        #expect(tool.images.first?.dataURL == Self.pngDataURL)
    }

    @Test func v1ToolWithoutAttachmentsHasNoImages() throws {
        let message = try v1Message(state: #"{"status":"completed","input":{},"output":"done"}"#)
        let tool = try #require(tools(message).first)
        #expect(tool.images.isEmpty)
        #expect(tool.output == "done")
    }

    @Test func v1AttachmentThatIsNotADecodableImageIsDropped() throws {
        let message = try v1Message(
            state: """
            {"status":"completed","input":{},"output":"x","attachments":[
              {"id":"f1","mime":"image/png","url":"https://example.com/remote.png"},
              {"id":"f2","mime":"image/png","url":"data:image/png;base64,%%%"},
              {"id":"f3","mime":"image/svg+xml","url":"data:image/svg+xml;base64,PHN2Zy8+"}
            ]}
            """
        )
        let tool = try #require(tools(message).first)
        #expect(tool.images.isEmpty)
    }

    // MARK: - v2 REST

    @Test func v2RestToolContentFileBecomesImagesAndTextStaysOutput() throws {
        let dtos = try JSONDecoder().decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id": "msg_asst",
                    "type": "assistant",
                    "content": [
                      {"type": "tool", "id": "call_shot", "name": "browser_screenshot", "state": {
                        "status": "completed",
                        "input": {},
                        "content": [
                          {"type": "text", "text": "Captured 2 pages"},
                          {"type": "file", "uri": "\(Self.pngDataURL)", "mime": "image/png", "name": "home.png"},
                          {"type": "file", "uri": "\(Self.pdfDataURL)", "mime": "application/pdf", "name": "home.pdf"},
                          {"type": "file", "uri": "\(Self.pngDataURL)", "mime": "image/png"}
                        ]
                      }}
                    ],
                    "time": {"created": 3}
                  }
                ]
                """.utf8
            )
        )
        let tool = try #require(tools(adapter.messages(fromV2: dtos)[0]).first)

        #expect(tool.output == "Captured 2 pages")
        // Ids follow each file's position in `content`, so the pdf at 1 is skipped.
        #expect(tool.images.map(\.id) == ["call_shot:file:0", "call_shot:file:2"])
        #expect(tool.images.map(\.filename) == ["home.png", ""])
        #expect(tool.images.allSatisfy { $0.kind == .image && $0.dataURL == Self.pngDataURL })
    }

    // MARK: - v2 live

    @Test func v2LiveToolSuccessProjectsFilesAsAttachments() {
        let event = EventDomainAdapter().parseEvent(
            dataLine: #"{"type":"session.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"call_shot","name":"browser_screenshot","content":[{"type":"text","text":"ok"},{"type":"file","uri":"\#(Self.pngDataURL)","mime":"image/png","name":"home.png"}]}}"#
        )
        let state = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue
        #expect(state?["output"]?.stringValue == "ok")
        guard case .array(let attachments)? = state?["attachments"] else {
            Issue.record("Expected attachments")
            return
        }
        #expect(attachments.count == 1)
        #expect(attachments.first?.dictionaryValue?["url"]?.stringValue == Self.pngDataURL)
        #expect(attachments.first?.dictionaryValue?["filename"]?.stringValue == "home.png")
    }

    @Test func v2LiveToolSuccessWithTextOnlyHasNoAttachments() {
        let event = EventDomainAdapter().parseEvent(
            dataLine: #"{"type":"session.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"call_1","name":"bash","content":[{"type":"text","text":"ok"}]}}"#
        )
        let state = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue
        #expect(state?.keys.contains("attachments") == false)
        #expect(state?["output"]?.stringValue == "ok")
    }

    @Test func v2LiveImagesReachTheStoreAndMatchTheRestIds() throws {
        let store = InMemoryMessageStore()
        let lines = [
            #"{"type":"session.step.started","data":{"sessionID":"ses-1","agent":"build","model":{"id":"m","providerID":"p"},"assistantMessageID":"msg-2"}}"#,
            #"{"type":"session.tool.called","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"call_shot","name":"browser_screenshot","input":{}}}"#,
            #"{"type":"session.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"call_shot","name":"browser_screenshot","content":[{"type":"file","uri":"\#(Self.pdfDataURL)","mime":"application/pdf"},{"type":"file","uri":"\#(Self.pngDataURL)","mime":"image/png","name":"home.png"}]}}"#,
        ]
        let events = EventDomainAdapter()
        for event in lines.flatMap({ events.parseEvents(dataLine: $0) }) {
            guard let properties = event.rawProperties else { continue }
            switch event.type {
            case "message.updated":
                if let info = properties["info"]?.dictionaryValue {
                    _ = store.apply(sessionID: "ses-1", patch: .infoUpdated(info))
                }
            case "message.part.updated":
                if let part = properties["part"]?.dictionaryValue {
                    _ = store.apply(sessionID: "ses-1", patch: .partUpdated(part: part, delta: nil))
                }
            default:
                break
            }
        }
        let message = try #require(store.messages(sessionID: "ses-1").first { $0.id == "msg-2" })
        let tool = try #require(tools(message).first)
        #expect(tool.status == .completed)
        // The pdf keeps position 0, so the image id equals the REST projection's.
        #expect(tool.images.map(\.id) == ["call_shot:file:1"])
        #expect(tool.images.first?.filename == "home.png")
    }

    @Test func storeKeepsImagesWhenALaterUpdateCarriesNone() throws {
        let store = InMemoryMessageStore()
        func apply(_ state: String) {
            let part = """
            {"id":"call_1","sessionID":"ses-1","messageID":"msg-1","type":"tool","tool":"read","state":\(state)}
            """
            let object = try! JSONSerialization.jsonObject(with: Data(part.utf8)) as! [String: Any]
            _ = store.apply(
                sessionID: "ses-1",
                patch: .partUpdated(part: object.mapValues { AnySendable.from($0) ?? .null }, delta: nil)
            )
        }
        _ = store.apply(
            sessionID: "ses-1",
            patch: .infoUpdated(["id": .string("msg-1"), "sessionID": .string("ses-1"), "role": .string("assistant")])
        )
        apply(#"{"status":"completed","input":{},"output":"x","attachments":[{"id":"f1","mime":"image/png","url":"\#(Self.pngDataURL)"}]}"#)
        apply(#"{"status":"completed","input":{},"output":"x","metadata":{"late":true}}"#)

        let message = try #require(store.messages(sessionID: "ses-1").first)
        #expect(tools(message).first?.images.map(\.id) == ["f1"])
    }

    // MARK: - Helpers

    private func v1Message(state: String) throws -> Message {
        let json = """
        {
          "info": {"id": "msg-1", "role": "assistant", "time": {"created": 1, "completed": 2}},
          "parts": [{"id": "call_1", "type": "tool", "tool": "read", "callID": "call_1", "state": \(state)}]
        }
        """
        return adapter.message(from: try JSONDecoder().decode(MessageDTO.self, from: Data(json.utf8)))
    }

    private func tools(_ message: Message) -> [ToolPart] {
        message.parts.compactMap { part in
            if case .tool(let tool) = part { return tool }
            return nil
        }
    }
}
