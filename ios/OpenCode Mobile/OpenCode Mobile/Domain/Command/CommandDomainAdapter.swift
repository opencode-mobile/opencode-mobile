import Foundation

struct CommandDomainAdapter: Sendable {
    func toServerCommands(_ dtos: [CommandDTO]) -> [ServerCommand] {
        dtos.map { dto in
            ServerCommand(
                name: dto.name,
                description: dto.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                source: toSource(dto.source),
                template: dto.template?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        }
    }

    func toServerCommands(_ dtos: [V2CommandDTO]) -> [ServerCommand] {
        dtos.map { dto in
            ServerCommand(
                name: dto.name,
                description: dto.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
                source: .command,
                template: dto.template?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        }
    }

    func toRunRequest(_ options: CommandRunOptions) -> RunCommandRequestDTO {
        RunCommandRequestDTO(
            command: options.command,
            arguments: options.arguments,
            agent: options.mode,
            model: options.model.map { "\($0.providerID)/\($0.modelID)" },
            variant: options.variant,
            parts: options.attachments.map { attachment in
                CommandFilePartDTO(
                    mime: attachment.mime,
                    url: attachment.dataURL,
                    filename: attachment.filename
                )
            }
        )
    }

    func toV2RunRequest(_ options: CommandRunOptions) -> V2RunCommandRequestDTO {
        V2RunCommandRequestDTO(
            command: options.command,
            arguments: options.arguments.isEmpty ? nil : options.arguments,
            agent: options.mode,
            model: options.model.map {
                V2PromptModelRefDTO(id: $0.modelID, providerID: $0.providerID, variant: options.variant)
            },
            files: options.attachments.isEmpty ? nil : options.attachments.map { attachment in
                V2PromptFileDTO(uri: attachment.dataURL, name: attachment.filename, mime: attachment.mime)
            },
            delivery: options.delivery.wireValue
        )
    }

    func mapError(_ error: Error) -> CommandError {
        if let commandError = error as? CommandError {
            return commandError
        }
        if let apiError = error as? CommandsAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        if error is DecodingError {
            return .decoding(error.localizedDescription)
        }
        return .transport(error.localizedDescription)
    }

    private func toSource(_ raw: String?) -> ServerCommandSource {
        switch raw?.lowercased() {
        case "mcp":
            .mcp
        case "skill":
            .skill
        default:
            .command
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
