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

    @Test func toServerCommandsV2_mapsSource() {
        let commands = adapter.toServerCommands([
            V2CommandDTO(name: "init", template: nil, description: "Setup", agent: nil, source: "command", subtask: nil),
            V2CommandDTO(name: "prompt", template: nil, description: nil, agent: nil, source: "mcp", subtask: nil),
            V2CommandDTO(name: "skill-a", template: nil, description: nil, agent: nil, source: "skill", subtask: nil),
        ])
        #expect(commands[0].source == .command)
        #expect(commands[1].source == .mcp)
        #expect(commands[2].source == .skill)
    }

    @Test func mergeV2SlashCatalog_appendsSkillsSkippingDuplicatesAndSlashFalse() {
        let commands = adapter.toServerCommands([
            V2CommandDTO(name: "init", template: "x", description: "Setup", agent: nil, source: nil, subtask: nil),
        ])
        let merged = adapter.mergeV2SlashCatalog(
            commands: commands,
            skills: [
                V2SkillDTO(name: "init", description: "dup", slash: nil, location: nil, content: "ignored"),
                V2SkillDTO(name: "hidden", description: nil, slash: false, location: nil, content: "nope"),
                V2SkillDTO(
                    name: "prototype",
                    description: "A skill",
                    slash: nil,
                    location: "/skills/prototype/SKILL.md",
                    content: "Do the thing"
                ),
            ]
        )
        #expect(merged.map(\.name) == ["init", "prototype"])
        #expect(merged[1].source == .skill)
        #expect(merged[1].description == "A skill")
        #expect(merged[1].template?.contains("Do the thing") == true)
        #expect(merged[1].template?.contains("Base directory for this skill: /skills/prototype") == true)
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

    @Test func toV2RunRequest_mapsModelFilesAndDelivery() {
        let request = adapter.toV2RunRequest(
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

        #expect(request.name == "init")
        #expect(request.text == "please")
        #expect(request.files?.count == 1)
        #expect(request.files?.first?.uri == "data:image/png;base64,abc")
        #expect(request.files?.first?.name == "a.png")
        #expect(request.files?.first?.mime == "image/png")
        #expect(request.delivery == "steer")
        let model = adapter.v2SwitchModelRequest(
            CommandRunOptions(
                command: "init",
                arguments: "please",
                model: PromptModelSelection(providerID: "openai", modelID: "gpt-4"),
                variant: "high",
                mode: "build"
            )
        )
        #expect(model?.model.id == "gpt-4")
        #expect(model?.model.providerID == "openai")
        #expect(model?.model.variant == "high")
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

    @Test func commandDTO_decodesObjectTemplateAsNil() throws {
        let json = Data(
            """
            [
              {"name":"init","description":"Setup","source":"command","template":"do init","hints":["$ARGUMENTS"]},
              {"name":"firebase:firebase_init","description":"Init Firebase","source":"mcp","template":{},"hints":[]}
            ]
            """.utf8
        )

        let commands = try JSONDecoder().decode([CommandDTO].self, from: json)

        #expect(commands.map(\.name) == ["init", "firebase:firebase_init"])
        #expect(commands[0].template == "do init")
        #expect(commands[1].template == nil)
        #expect(commands[1].source == "mcp")
    }

    @Test func v2CommandDTO_decodesObjectTemplateAsNil() throws {
        let json = Data(
            """
            {"name":"firebase:firebase_init","description":"Init Firebase","source":"mcp","template":{}}
            """.utf8
        )

        let command = try JSONDecoder().decode(V2CommandDTO.self, from: json)

        #expect(command.name == "firebase:firebase_init")
        #expect(command.template == nil)
        #expect(command.source == "mcp")
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

    @Test func mapError_mapsV2SwitchHttpFailuresToHttpNotTransport() {
        #expect(
            adapter.mapError(MessagesAPIError.httpStatus(400, "unknown agent"))
                == .http(statusCode: 400, message: "unknown agent")
        )
        #expect(adapter.mapError(MessagesAPIError.noActiveConnection) == .transport("Invalid response"))
        #expect(adapter.mapError(MessagesAPIError.invalidResponse) == .transport("Invalid response"))
        let offline = URLError(.notConnectedToInternet)
        #expect(adapter.mapError(offline) == .transport(TransportFailure.captured(from: offline)))
    }
}
