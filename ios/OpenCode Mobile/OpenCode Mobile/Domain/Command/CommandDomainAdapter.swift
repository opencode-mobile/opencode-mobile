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
                source: toSource(dto.source),
                template: dto.template?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            )
        }
    }

    func mergeV2SlashCatalog(commands: [ServerCommand], skills: [V2SkillDTO]) -> [ServerCommand] {
        let names = Set(commands.map(\.name))
        return commands + skills.compactMap(toSkillCommand).filter { !names.contains($0.name) }
    }

    func toSkillCommand(_ dto: V2SkillDTO) -> ServerCommand? {
        if dto.slash == false { return nil }
        let skillID = dto.id?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? dto.name
        return ServerCommand(
            name: dto.name,
            description: dto.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            source: .skill,
            template: Self.skillTemplate(content: dto.content, location: Self.skillLocation(dto)),
            skillID: skillID
        )
    }

    private static func skillLocation(_ dto: V2SkillDTO) -> String? {
        if let location = dto.location?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty {
            return location
        }
        guard let path = dto.path?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else { return nil }
        if path == builtInSkillLocation || path.hasPrefix("/builtin") {
            return builtInSkillLocation
        }
        return path
    }

    static func skillTemplate(content: String?, location: String?) -> String? {
        let body = content?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard let body else { return nil }
        guard let location, !location.isEmpty, location != builtInSkillLocation else { return body }
        let directory = (location as NSString).deletingLastPathComponent
        guard !directory.isEmpty, directory != "/" else { return body }
        return """
        \(body)

        Base directory for this skill: \(directory)
        Relative paths in this skill (e.g., scripts/, references/) are relative to this base directory.
        """
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
            name: options.command,
            text: options.arguments,
            files: options.attachments.isEmpty ? nil : options.attachments.map { attachment in
                V2PromptFileDTO(uri: attachment.dataURL, name: attachment.filename, mime: attachment.mime)
            },
            skills: options.skillIDs.isEmpty ? nil : options.skillIDs.map { V2PromptSkillDTO(id: $0) },
            delivery: options.delivery.wireValue
        )
    }

    func v2SwitchModelRequest(_ options: CommandRunOptions) -> V2SwitchModelRequestDTO? {
        options.model.map {
            V2SwitchModelRequestDTO(
                model: V2PromptModelRefDTO(id: $0.modelID, providerID: $0.providerID, variant: options.variant)
            )
        }
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
        if let apiError = error as? MessagesAPIError {
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
        return .transport(TransportFailure.captured(from: error))
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

    private static let builtInSkillLocation = "<built-in>"
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
