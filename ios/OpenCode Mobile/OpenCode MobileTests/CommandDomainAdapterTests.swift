import Foundation
import Testing
@testable import OpenCode_Mobile

struct CommandDomainAdapterTests {
    private let adapter = CommandDomainAdapter()

    @Test func toServerCommands_mapsSources() {
        let commands = adapter.toServerCommands([
            CommandDTO(
                name: "init",
                description: "Setup",
                agent: nil,
                model: nil,
                source: "command",
                template: "x",
                subtask: nil,
                hints: []
            ),
            CommandDTO(
                name: "prompt",
                description: nil,
                agent: nil,
                model: nil,
                source: "mcp",
                template: "x",
                subtask: nil,
                hints: []
            ),
            CommandDTO(
                name: "skill-a",
                description: "A skill",
                agent: nil,
                model: nil,
                source: "skill",
                template: "x",
                subtask: nil,
                hints: []
            ),
        ])

        #expect(commands[0].source == .command)
        #expect(commands[1].source == .mcp)
        #expect(commands[2].source == .skill)
        #expect(commands[0].description == "Setup")
    }

    @Test func toRunRequest_mapsComposerSnapshotWithoutMessageId() {
        let request = adapter.toRunRequest(
            CommandRunOptions(
                command: "init",
                arguments: "please",
                attachments: [
                    Attachment(
                        id: "att-1",
                        filename: "a.png",
                        mime: "image/png",
                        kind: .image,
                        dataURL: "data:image/png;base64,abc"
                    ),
                ],
                model: PromptModelSelection(providerID: "openai", modelID: "gpt-4"),
                variant: "high",
                mode: "build"
            )
        )

        #expect(request.command == "init")
        #expect(request.arguments == "please")
        #expect(request.agent == "build")
        #expect(request.model == "openai/gpt-4")
        #expect(request.variant == "high")
        #expect(request.parts.count == 1)
        #expect(request.parts[0].mime == "image/png")
        #expect(request.parts[0].url == "data:image/png;base64,abc")
        #expect(request.parts[0].id == nil)
        #expect(request.parts[0].type == "file")
        #expect(request.parts[0].filename == "a.png")
    }

    @Test func toServerCommands_blankDescriptionBecomesNil() {
        let commands = adapter.toServerCommands([
            CommandDTO(
                name: "init",
                description: "   ",
                agent: nil,
                model: nil,
                source: nil,
                template: nil,
                subtask: nil,
                hints: nil
            ),
        ])
        #expect(commands[0].description == nil)
        #expect(commands[0].source == .command)
    }

    @Test func mapError_mapsHttpAndDecoding() {
        #expect(
            adapter.mapError(CommandsAPIError.httpStatus(404, "missing"))
                == .http(statusCode: 404, message: "missing")
        )
        #expect(adapter.mapError(CommandsAPIError.noActiveConnection) == .transport("Invalid response"))

        let decodingError = DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "bad"))
        #expect(adapter.mapError(decodingError) == .decoding(decodingError.localizedDescription))
    }
}
