import Foundation

// OpenCode v2 session messages, read only for their tool calls (the agent's to-do list is the
// latest `todowrite` call). Decoding is lenient: unknown fields and parts are ignored, and a part
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

    public init(id: String? = nil, name: String, status: String? = nil, metadataTodos: [TodoItemV2]? = nil, inputTodos: [TodoItemV2]? = nil) {
        self.id = id
        self.name = name
        self.status = status
        self.metadataTodos = metadataTodos
        self.inputTodos = inputTodos
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

        private enum CodingKeys: String, CodingKey { case type, id, name, state }
        private nonisolated struct Todos: Decodable { let todos: [TodoItemV2]? }
        private nonisolated struct State: Decodable {
            let status: String?
            let input: Todos?
            let metadata: Todos?

            private enum CodingKeys: String, CodingKey { case status, input, metadata }

            init(from decoder: any Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                status = try? container.decodeIfPresent(String.self, forKey: .status)
                input = try? container.decodeIfPresent(Todos.self, forKey: .input)
                metadata = try? container.decodeIfPresent(Todos.self, forKey: .metadata)
            }
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
            tool = ToolPartV2(
                id: try? container.decodeIfPresent(String.self, forKey: .id),
                name: name,
                status: state?.status,
                metadataTodos: state?.metadata?.todos,
                inputTodos: state?.input?.todos
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
