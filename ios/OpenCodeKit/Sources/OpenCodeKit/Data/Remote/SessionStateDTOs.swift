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
        archivedMs: Int64? = nil
    ) {
        self.id = id
        self.title = title
        self.parentID = parentID
        self.directory = directory
        self.model = model
        self.createdMs = createdMs
        self.updatedMs = updatedMs
        self.busyMs = busyMs
        self.archivedMs = archivedMs
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, parentID, location, model, time
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
    public var id: String
    public var sessionID: String
    /// What the agent wants to do, such as `shell`.
    public var action: String
    /// What it applies to, such as the command.
    public var resources: [String]
    public var message: String?

    public init(id: String, sessionID: String, action: String, resources: [String] = [], message: String? = nil) {
        self.id = id
        self.sessionID = sessionID
        self.action = action
        self.resources = resources
        self.message = message
    }

    private enum CodingKeys: String, CodingKey { case id, sessionID, action, resources, message }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        action = try container.decodeIfPresent(String.self, forKey: .action) ?? ""
        resources = (try? container.decodeIfPresent([String].self, forKey: .resources)) ?? []
        message = try? container.decodeIfPresent(String.self, forKey: .message)
    }
}

/// A pending question (`GET /api/session/{id}/form`, `form.created`).
public nonisolated struct PendingFormV2: Decodable, Sendable, Equatable {
    public nonisolated struct Field: Decodable, Sendable, Equatable {
        public var key: String?
        /// The header.
        public var title: String?
        /// The question's text.
        public var description: String?

        public init(key: String? = nil, title: String? = nil, description: String? = nil) {
            self.key = key
            self.title = title
            self.description = description
        }
    }

    public var id: String
    public var sessionID: String
    public var title: String?
    public var fields: [Field]

    public init(id: String, sessionID: String, title: String? = nil, fields: [Field] = []) {
        self.id = id
        self.sessionID = sessionID
        self.title = title
        self.fields = fields
    }

    private enum CodingKeys: String, CodingKey { case id, sessionID, title, fields }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        title = try? container.decodeIfPresent(String.self, forKey: .title)
        fields = (try? container.decodeIfPresent([Field].self, forKey: .fields)) ?? []
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

    public init(id: String, sessionID: String? = nil, delivery: String? = nil, text: String? = nil, files: [InboxFileV2] = []) {
        self.id = id
        self.sessionID = sessionID
        self.delivery = delivery
        self.text = text
        self.files = files
    }

    private enum CodingKeys: String, CodingKey { case id, sessionID, delivery, payload }
    private nonisolated struct Payload: Decodable {
        let text: String?
        let files: [InboxFileV2]?
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try? container.decodeIfPresent(String.self, forKey: .sessionID)
        delivery = try? container.decodeIfPresent(String.self, forKey: .delivery)
        let payload = try? container.decodeIfPresent(Payload.self, forKey: .payload)
        text = payload?.text
        files = payload?.files ?? []
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
