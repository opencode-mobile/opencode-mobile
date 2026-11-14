import Foundation

// OpenCode v2 session messages, read only for their tool calls (the agent's to-do list is the
// latest `todowrite` call; Details lists the latest edits, commands and subagents). Decoding is lenient: unknown fields and parts are ignored, and a part
// that can't be read is skipped rather than failing the page.

/// One item of the agent's to-do list (`todowrite`): `status` is `pending`, `in_progress`,
/// `completed` or `cancelled`.
public nonisolated struct TodoItemV2: Decodable, Sendable, Equatable {
    public var content: String
    public var status: String

    public init(content: String, status: String) {
        self.content = content
        self.status = status
    }
}

/// A file a tool call changed (`state.metadata.files[]` of `edit`, `write` and `patch`).
public nonisolated struct ToolFileV2: Decodable, Sendable, Equatable {
    public var file: String
    /// `added` for a new file, `modified`, `deleted`, …
    public var status: String?
    public var additions: Int?
    public var deletions: Int?

    public init(file: String, status: String? = nil, additions: Int? = nil, deletions: Int? = nil) {
        self.file = file
        self.status = status
        self.additions = additions
        self.deletions = deletions
    }

    private enum CodingKeys: String, CodingKey { case file, status, additions, deletions }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        file = try container.decode(String.self, forKey: .file)
        status = try? container.decodeIfPresent(String.self, forKey: .status)
        additions = try? container.decodeIfPresent(Int.self, forKey: .additions)
        deletions = try? container.decodeIfPresent(Int.self, forKey: .deletions)
    }
}

/// A tool call in a message (`{"type": "tool", "name": …, "state": {…}}`).
public nonisolated struct ToolPartV2: Sendable, Equatable {
    public var id: String?
    /// The tool, such as `todowrite`.
    public var name: String
    /// `state.status`: `pending`, `running`, `completed` or `error`.
    public var status: String?
    /// `state.metadata.todos`, when it is a list of to-do items.
    public var metadataTodos: [TodoItemV2]?
    /// `state.input.todos`, when it is a list of to-do items.
    public var inputTodos: [TodoItemV2]?
    /// `state.input.command` (`shell`).
    public var command: String?
    /// `state.input.description` (`subagent`).
    public var description: String?
    /// `state.input.agent` (`subagent`).
    public var agent: String?
    /// `state.input.path`, else `filePath` (`edit`, `write`).
    public var path: String?
    /// `state.metadata.files` (`edit`, `write`, `patch`).
    public var files: [ToolFileV2]
    /// `state.metadata.exit` (`shell`); nil when it was killed or never ran.
    public var exit: Int?
    /// `state.error.type` when the call failed: `aborted`, `permission.rejected`, `tool.execution`, …
    public var errorType: String?
    public var errorMessage: String?
    /// `time.created` and `time.completed`, epoch milliseconds.
    public var createdMs: Int64?
    public var completedMs: Int64?

    public init(
        id: String? = nil,
        name: String,
        status: String? = nil,
        metadataTodos: [TodoItemV2]? = nil,
        inputTodos: [TodoItemV2]? = nil,
        command: String? = nil,
        description: String? = nil,
        agent: String? = nil,
        path: String? = nil,
        files: [ToolFileV2] = [],
        exit: Int? = nil,
        errorType: String? = nil,
        errorMessage: String? = nil,
        createdMs: Int64? = nil,
        completedMs: Int64? = nil
    ) {
        self.id = id
        self.name = name
        self.status = status
        self.metadataTodos = metadataTodos
        self.inputTodos = inputTodos
        self.command = command
        self.description = description
        self.agent = agent
        self.path = path
        self.files = files
        self.exit = exit
        self.errorType = errorType
        self.errorMessage = errorMessage
        self.createdMs = createdMs
        self.completedMs = completedMs
    }
}

/// A message of a session, with its tool calls in order. Parts live in `content`.
public nonisolated struct SessionMessageV2: Decodable, Sendable, Equatable {
    public var id: String
    /// `user`, `assistant`, …
    public var type: String
    public var toolParts: [ToolPartV2]

    public init(id: String, type: String, toolParts: [ToolPartV2] = []) {
        self.id = id
        self.type = type
        self.toolParts = toolParts
    }

    private enum CodingKeys: String, CodingKey { case id, type, content }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        let parts = (try? container.decodeIfPresent([LenientPart].self, forKey: .content)) ?? []
        toolParts = parts.compactMap(\.tool)
    }

    private nonisolated struct LenientPart: Decodable {
        let tool: ToolPartV2?

        private enum CodingKeys: String, CodingKey { case type, id, name, state, time }
        private nonisolated struct Todos: Decodable { let todos: [TodoItemV2]? }
        /// Each field on its own, so one of an unexpected type doesn't lose the rest.
        private nonisolated struct Input: Decodable {
            let command: String?
            let description: String?
            let agent: String?
            let path: String?

            private enum CodingKeys: String, CodingKey { case command, description, agent, path, filePath }

            init(from decoder: any Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                command = try? container.decodeIfPresent(String.self, forKey: .command)
                description = try? container.decodeIfPresent(String.self, forKey: .description)
                agent = try? container.decodeIfPresent(String.self, forKey: .agent)
                path = (try? container.decodeIfPresent(String.self, forKey: .path))
                    ?? (try? container.decodeIfPresent(String.self, forKey: .filePath))
            }
        }
        private nonisolated struct Metadata: Decodable {
            let files: [ToolFileV2]?
            let exit: Int?

            private enum CodingKeys: String, CodingKey { case files, exit }

            init(from decoder: any Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                files = try? container.decodeIfPresent([ToolFileV2].self, forKey: .files)
                exit = try? container.decodeIfPresent(Int.self, forKey: .exit)
            }
        }
        private nonisolated struct Failure: Decodable {
            let type: String?
            let message: String?
        }
        private nonisolated struct State: Decodable {
            let status: String?
            let input: Todos?
            let metadata: Todos?
            let inputFields: Input?
            let metadataFields: Metadata?
            let error: Failure?

            private enum CodingKeys: String, CodingKey { case status, input, metadata, error }

            init(from decoder: any Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                status = try? container.decodeIfPresent(String.self, forKey: .status)
                input = try? container.decodeIfPresent(Todos.self, forKey: .input)
                metadata = try? container.decodeIfPresent(Todos.self, forKey: .metadata)
                inputFields = try? container.decodeIfPresent(Input.self, forKey: .input)
                metadataFields = try? container.decodeIfPresent(Metadata.self, forKey: .metadata)
                error = try? container.decodeIfPresent(Failure.self, forKey: .error)
            }
        }
        private nonisolated struct Time: Decodable {
            let created: Double?
            let completed: Double?
        }

        init(from decoder: any Decoder) throws {
            guard let container = try? decoder.container(keyedBy: CodingKeys.self),
                  (try? container.decode(String.self, forKey: .type)) == "tool",
                  let name = try? container.decode(String.self, forKey: .name)
            else {
                tool = nil
                return
            }
            let state = try? container.decodeIfPresent(State.self, forKey: .state)
            let time = try? container.decodeIfPresent(Time.self, forKey: .time)
            tool = ToolPartV2(
                id: try? container.decodeIfPresent(String.self, forKey: .id),
                name: name,
                status: state?.status,
                metadataTodos: state?.metadata?.todos,
                inputTodos: state?.input?.todos,
                command: state?.inputFields?.command,
                description: state?.inputFields?.description,
                agent: state?.inputFields?.agent,
                path: state?.inputFields?.path,
                files: state?.metadataFields?.files ?? [],
                exit: state?.metadataFields?.exit,
                errorType: state?.error?.type,
                errorMessage: state?.error?.message,
                createdMs: time?.created.map { Int64($0) },
                completedMs: time?.completed.map { Int64($0) }
            )
        }
    }
}

/// One page of `GET /api/session/{id}/message`.
public nonisolated struct MessagePageV2: Sendable, Equatable {
    public var messages: [SessionMessageV2]
    public var nextCursor: String?

    public init(messages: [SessionMessageV2], nextCursor: String?) {
        self.messages = messages
        self.nextCursor = nextCursor
    }
}
