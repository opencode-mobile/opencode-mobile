import Foundation

// OpenCode v2 session data that a phone reads to follow one session live: the session with its
// location, model and busy time, its pending approvals and questions, and its inbox. Decoding is
// lenient: unknown fields are ignored and optional ones may be missing.

/// A session's model and reasoning level (`Model.Ref`).
public nonisolated struct SessionModelRefV2: Codable, Sendable, Equatable {
    public var providerID: String
    public var id: String
    public var variant: String?

    public init(providerID: String, id: String, variant: String? = nil) {
        self.providerID = providerID
        self.id = id
        self.variant = variant
    }
}

/// `Session.Info`, with the fields the live view needs. Times are epoch milliseconds.
public nonisolated struct SessionInfoV2: Decodable, Sendable, Equatable {
    public var id: String
    public var title: String?
    public var parentID: String?
    /// The project the session belongs to; `global` for a folder that isn't a git repository with
    /// commits.
    public var projectID: String?
    /// The agent a subagent runs as, such as `general`.
    public var agent: String?
    /// `location.directory`; there is no top-level directory.
    public var directory: String?
    public var model: SessionModelRefV2?
    public var createdMs: Int64
    public var updatedMs: Int64
    /// The start of the latest execution; nil for a session that never ran.
    public var busyMs: Int64?
    public var archivedMs: Int64?

    public init(
        id: String,
        title: String? = nil,
        parentID: String? = nil,
        directory: String? = nil,
        model: SessionModelRefV2? = nil,
        createdMs: Int64 = 0,
        updatedMs: Int64 = 0,
        busyMs: Int64? = nil,
        archivedMs: Int64? = nil,
        projectID: String? = nil,
        agent: String? = nil
    ) {
        self.id = id
        self.title = title
        self.parentID = parentID
        self.projectID = projectID
        self.agent = agent
        self.directory = directory
        self.model = model
        self.createdMs = createdMs
        self.updatedMs = updatedMs
        self.busyMs = busyMs
        self.archivedMs = archivedMs
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, parentID, projectID, agent, location, model, time
    }

    private nonisolated struct Location: Decodable { let directory: String? }

    private nonisolated struct Time: Decodable {
        let created: Double?
        let updated: Double?
        let busy: Double?
        let archived: Double?
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        parentID = try container.decodeIfPresent(String.self, forKey: .parentID)
        projectID = try? container.decodeIfPresent(String.self, forKey: .projectID)
        agent = try? container.decodeIfPresent(String.self, forKey: .agent)
        directory = try container.decodeIfPresent(Location.self, forKey: .location)?.directory
        model = try? container.decodeIfPresent(SessionModelRefV2.self, forKey: .model)
        let time = try container.decodeIfPresent(Time.self, forKey: .time)
        createdMs = Int64(time?.created ?? 0)
        updatedMs = Int64(time?.updated ?? 0)
        busyMs = time?.busy.map { Int64($0) }
        archivedMs = time?.archived.map { Int64($0) }
    }
}

/// One page of `GET /api/session`. `nextCursor` is set on every page with results, even the last.
public nonisolated struct SessionPageV2: Sendable, Equatable {
    public var sessions: [SessionInfoV2]
    public var nextCursor: String?

    public init(sessions: [SessionInfoV2], nextCursor: String?) {
        self.sessions = sessions
        self.nextCursor = nextCursor
    }
}

/// A pending approval (`GET /api/session/{id}/permission`, `permission.asked`).
public nonisolated struct PendingPermissionV2: Decodable, Sendable, Equatable {
    /// A file an edit would change (`metadata.files[]` of an `edit` request).
    public nonisolated struct File: Decodable, Sendable, Equatable {
        public var file: String
        /// A unified diff of the change.
        public var patch: String?
        /// `added` for a new file, `modified`, …
        public var status: String?
        public var additions: Int?
        public var deletions: Int?

        public init(file: String, patch: String? = nil, status: String? = nil, additions: Int? = nil, deletions: Int? = nil) {
            self.file = file
            self.patch = patch
            self.status = status
            self.additions = additions
            self.deletions = deletions
        }

        private enum CodingKeys: String, CodingKey { case file, patch, status, additions, deletions }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            file = try container.decode(String.self, forKey: .file)
            patch = try? container.decodeIfPresent(String.self, forKey: .patch)
            status = try? container.decodeIfPresent(String.self, forKey: .status)
            additions = try? container.decodeIfPresent(Int.self, forKey: .additions)
            deletions = try? container.decodeIfPresent(Int.self, forKey: .deletions)
        }
    }

    /// Where the request came from (`source`): a tool call in a message.
    public nonisolated struct Source: Decodable, Sendable, Equatable {
        public var type: String?
        public var messageID: String?
        /// The tool call's id.
        public var id: String?

        public init(type: String? = nil, messageID: String? = nil, id: String? = nil) {
            self.type = type
            self.messageID = messageID
            self.id = id
        }
    }

    public var id: String
    /// The session that owns the request: a subagent's own id for its requests. Replies go there.
    public var sessionID: String
    /// What the agent wants to do: `shell`, `edit`, `webfetch`, `subagent`, …
    public var action: String
    /// What it applies to: the commands, file paths, URL or agent id.
    public var resources: [String]
    /// Set only by plugins.
    public var message: String?
    /// The patterns an "always" reply saves for the project, such as `ls *`, or `*` for every edit.
    public var save: [String]
    /// The files an edit would change, with their diffs; empty for other actions.
    public var files: [File]
    public var source: Source?

    public init(
        id: String,
        sessionID: String,
        action: String,
        resources: [String] = [],
        message: String? = nil,
        save: [String] = [],
        files: [File] = [],
        source: Source? = nil
    ) {
        self.id = id
        self.sessionID = sessionID
        self.action = action
        self.resources = resources
        self.message = message
        self.save = save
        self.files = files
        self.source = source
    }

    private enum CodingKeys: String, CodingKey { case id, sessionID, action, resources, message, save, metadata, source }
    private nonisolated struct Metadata: Decodable { let files: [File]? }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        action = try container.decodeIfPresent(String.self, forKey: .action) ?? ""
        resources = (try? container.decodeIfPresent([String].self, forKey: .resources)) ?? []
        message = try? container.decodeIfPresent(String.self, forKey: .message)
        save = (try? container.decodeIfPresent([String].self, forKey: .save)) ?? []
        files = (try? container.decodeIfPresent(Metadata.self, forKey: .metadata))?.files ?? []
        source = try? container.decodeIfPresent(Source.self, forKey: .source)
    }
}

/// A pending question (`GET /api/session/{id}/form`, `form.created`).
public nonisolated struct PendingFormV2: Decodable, Sendable, Equatable {
    /// One choice of a field.
    public nonisolated struct Option: Decodable, Sendable, Equatable {
        /// What the answer sends.
        public var value: String
        public var label: String
        /// "" when there is none.
        public var description: String?

        public init(value: String, label: String, description: String? = nil) {
            self.value = value
            self.label = label
            self.description = description
        }

        private enum CodingKeys: String, CodingKey { case value, label, description }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            let value = try? container.decodeIfPresent(String.self, forKey: .value)
            let label = try? container.decodeIfPresent(String.self, forKey: .label)
            guard let text = value ?? label else {
                throw DecodingError.dataCorruptedError(forKey: .value, in: container, debugDescription: "An option needs a value or a label")
            }
            self.value = value ?? text
            self.label = label ?? text
            description = try? container.decodeIfPresent(String.self, forKey: .description)
        }
    }

    public nonisolated struct Field: Decodable, Sendable, Equatable {
        public var key: String?
        /// The header.
        public var title: String?
        /// The question's text.
        public var description: String?
        /// `string` (one choice, or free text), `multiselect`, or another type an MCP form uses.
        public var type: String?
        public var options: [Option]
        /// The user may give their own answer as well as the options.
        public var custom: Bool

        public init(
            key: String? = nil,
            title: String? = nil,
            description: String? = nil,
            type: String? = nil,
            options: [Option] = [],
            custom: Bool = false
        ) {
            self.key = key
            self.title = title
            self.description = description
            self.type = type
            self.options = options
            self.custom = custom
        }

        private enum CodingKeys: String, CodingKey { case key, title, description, type, options, custom }

        public init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            key = try? container.decodeIfPresent(String.self, forKey: .key)
            title = try? container.decodeIfPresent(String.self, forKey: .title)
            description = try? container.decodeIfPresent(String.self, forKey: .description)
            type = try? container.decodeIfPresent(String.self, forKey: .type)
            options = (try? container.decodeIfPresent([LenientOption].self, forKey: .options))?.compactMap(\.option) ?? []
            custom = (try? container.decodeIfPresent(Bool.self, forKey: .custom)) ?? false
        }

        private nonisolated struct LenientOption: Decodable {
            let option: Option?
            init(from decoder: Decoder) throws { option = try? Option(from: decoder) }
        }
    }

    public var id: String
    public var sessionID: String
    public var title: String?
    /// `metadata.kind`: `question` for the question tool's forms.
    public var kind: String?
    public var fields: [Field]

    public init(id: String, sessionID: String, title: String? = nil, kind: String? = nil, fields: [Field] = []) {
        self.id = id
        self.sessionID = sessionID
        self.title = title
        self.kind = kind
        self.fields = fields
    }

    private enum CodingKeys: String, CodingKey { case id, sessionID, title, metadata, fields }
    private nonisolated struct Metadata: Decodable { let kind: String? }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        title = try? container.decodeIfPresent(String.self, forKey: .title)
        kind = (try? container.decodeIfPresent(Metadata.self, forKey: .metadata))?.kind
        fields = (try? container.decodeIfPresent([Field].self, forKey: .fields)) ?? []
    }
}

/// One field's answer in `POST /api/session/{id}/form/{form}/reply`: a string for a single choice or
/// free text, a string array for a multiselect.
public nonisolated enum FormAnswerV2: Encodable, Sendable, Equatable {
    case text(String)
    case choices([String])

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .text(text): try container.encode(text)
        case let .choices(choices): try container.encode(choices)
        }
    }
}

/// A shell the agent started (`GET /api/shell`, `shell.created`).
public nonisolated struct ShellInfoV2: Decodable, Sendable, Equatable {
    public var id: String
    /// `running`, `exited`, `killed`, …
    public var status: String?
    public var command: String?
    /// `metadata.sessionID`: the session whose agent started it.
    public var sessionID: String?
    /// `time.started`, epoch milliseconds.
    public var startedMs: Int64?

    public init(id: String, status: String? = nil, command: String? = nil, sessionID: String? = nil, startedMs: Int64? = nil) {
        self.id = id
        self.status = status
        self.command = command
        self.sessionID = sessionID
        self.startedMs = startedMs
    }

    private enum CodingKeys: String, CodingKey { case id, status, command, metadata, time }
    private nonisolated struct Metadata: Decodable { let sessionID: String? }
    private nonisolated struct Time: Decodable { let started: Double? }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        status = try? container.decodeIfPresent(String.self, forKey: .status)
        command = try? container.decodeIfPresent(String.self, forKey: .command)
        sessionID = (try? container.decodeIfPresent(Metadata.self, forKey: .metadata))?.sessionID
        startedMs = (try? container.decodeIfPresent(Time.self, forKey: .time))?.started.map { Int64($0) }
    }
}

/// A message waiting in a session's inbox (`GET /api/session/{id}/inbox`, and the item of a
/// `session.inbox.enqueued` event).
public nonisolated struct InboxItemV2: Decodable, Sendable, Equatable {
    public var id: String
    public var sessionID: String?
    /// `queue` (after this turn) or `steer`.
    public var delivery: String?
    public var text: String?
    /// The message's files, without their contents.
    public var files: [InboxFileV2]
    /// `user` for a message someone sent, `synthetic` for one the server made, such as a subagent's
    /// note that it finished.
    public var type: String?
    /// `payload.metadata`: where the server's own items come from, such as a goal's kickoff.
    public var metadata: InboxMetadataV2?

    public init(
        id: String,
        sessionID: String? = nil,
        delivery: String? = nil,
        text: String? = nil,
        files: [InboxFileV2] = [],
        type: String? = nil,
        metadata: InboxMetadataV2? = nil
    ) {
        self.id = id
        self.sessionID = sessionID
        self.delivery = delivery
        self.text = text
        self.files = files
        self.type = type
        self.metadata = metadata
    }

    private enum CodingKeys: String, CodingKey { case id, sessionID, delivery, payload, type }
    private nonisolated struct Payload: Decodable {
        let text: String?
        let files: [InboxFileV2]?
        let metadata: InboxMetadataV2?
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try? container.decodeIfPresent(String.self, forKey: .sessionID)
        delivery = try? container.decodeIfPresent(String.self, forKey: .delivery)
        type = try? container.decodeIfPresent(String.self, forKey: .type)
        let payload = try? container.decodeIfPresent(Payload.self, forKey: .payload)
        text = payload?.text
        files = payload?.files ?? []
        metadata = payload?.metadata
    }
}

/// An inbox item's `payload.metadata`. The goal's kickoff and each round's continuation carry
/// `source: "goal"`; the kickoff also carries its goal's `goalID`. Other keys are ignored.
public nonisolated struct InboxMetadataV2: Decodable, Sendable, Equatable {
    public var source: String?
    public var goalID: String?
    /// Uploaded files a client named in the prompt's `metadata.attachments`; empty for none.
    public var attachments: [PromptAttachment]

    public init(source: String? = nil, goalID: String? = nil, attachments: [PromptAttachment] = []) {
        self.source = source
        self.goalID = goalID
        self.attachments = attachments
    }

    private enum CodingKeys: String, CodingKey { case source, goalID, attachments }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        source = try? container.decodeIfPresent(String.self, forKey: .source)
        goalID = try? container.decodeIfPresent(String.self, forKey: .goalID)
        attachments = ((try? container.decodeIfPresent([Lossy<PromptAttachment>].self, forKey: .attachments)) ?? []).compactMap(\.value)
    }
}

/// A session's goal (`/goal`), as `POST /api/rpc/opencode.goal/get` and the
/// `rpc.opencode.goal.changed` event give it. See `m5-proposal.md`.
public nonisolated struct GoalStateV2: Decodable, Sendable, Equatable {
    public var id: String
    public var objective: String
    /// `active`, `paused` or `complete`.
    public var status: String
    /// Why it last changed: the reviewer's reason, or what paused it. Absent on a new goal.
    public var reason: String?
    /// Continuations sent since the goal was set or last resumed: 0 after setting, 1 after resuming.
    public var rounds: Int
    /// Always 50 on v2.2.5.
    public var maxRounds: Int
    /// Epoch ms of the last write.
    public var updatedAt: Int64?

    public init(id: String, objective: String, status: String, reason: String? = nil, rounds: Int = 0, maxRounds: Int = 50, updatedAt: Int64? = nil) {
        self.id = id
        self.objective = objective
        self.status = status
        self.reason = reason
        self.rounds = rounds
        self.maxRounds = maxRounds
        self.updatedAt = updatedAt
    }

    private enum CodingKeys: String, CodingKey { case id, objective, status, reason, rounds, maxRounds, updatedAt }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        objective = try container.decodeIfPresent(String.self, forKey: .objective) ?? ""
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? "active"
        reason = try? container.decodeIfPresent(String.self, forKey: .reason)
        rounds = (try? container.decodeIfPresent(Int.self, forKey: .rounds)) ?? 0
        maxRounds = (try? container.decodeIfPresent(Int.self, forKey: .maxRounds)) ?? 50
        updatedAt = (try? container.decodeIfPresent(Double.self, forKey: .updatedAt)).map { Int64($0) }
    }
}

/// A file in an inbox message: its name and type only. The stored contents (`data`, base64, about
/// 0.7 MB for a photo) are never decoded into it.
public nonisolated struct InboxFileV2: Decodable, Sendable, Equatable {
    public var name: String?
    public var mime: String?

    public init(name: String? = nil, mime: String? = nil) {
        self.name = name
        self.mime = mime
    }

    private enum CodingKeys: String, CodingKey { case name, mime }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try? container.decodeIfPresent(String.self, forKey: .name)
        mime = try? container.decodeIfPresent(String.self, forKey: .mime)
    }
}

/// An uploaded file named in a prompt's `metadata.attachments`: `{name, mime, path}`, as OpenCode's
/// own app and OpenCode Mobile write it. `path` is where the server holds the bytes.
public nonisolated struct PromptAttachment: Codable, Sendable, Equatable {
    public var name: String
    public var mime: String
    public var path: String

    public init(name: String, mime: String, path: String) {
        self.name = name
        self.mime = mime
        self.path = path
    }

    private enum CodingKeys: String, CodingKey { case name, mime, path }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        path = try container.decode(String.self, forKey: .path)
        name = (try? container.decodeIfPresent(String.self, forKey: .name)) ?? ""
        mime = (try? container.decodeIfPresent(String.self, forKey: .mime)) ?? ""
    }
}

/// One entry of a list that may hold entries of another shape: those decode as nil.
private nonisolated struct Lossy<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: Decoder) throws {
        value = try? Value(from: decoder)
    }
}
