import Foundation

// Artifacts (app plan, "Artifacts (M8)"): pages the agent publishes and OpenCode keeps. They are off
// by default and no API turns them on, so Hex Code asks OpenCode's own agent to edit its config in a
// separate setup session, and checks the result itself. This file holds what can be decided without
// the server: what an answer means, which config file wins, whether an edit kept the rest of the
// config, and which artifact the card shows.

/// One artifact as the list route gives it (`Artifact.Info`).
struct ArtifactInfo: Equatable, Sendable, Decodable {
    var id: String
    var provider: String
    /// The session whose agent made it; a subagent's own id for a subagent's. Optional in the schema.
    var sessionID: String?
    var title: String
    var entry: String
    /// A path from the local provider (`/api/experimental/artifacts/<id>/view/`); a plugin
    /// provider may give anything.
    var url: String
    /// `time.updated`, epoch ms.
    var updated: Int64

    private enum CodingKeys: String, CodingKey { case id, provider, sessionID, title, entry, url, time }
    private struct Time: Decodable { let updated: Double }

    init(id: String, provider: String = "local", sessionID: String?, title: String, entry: String = "index.html", url: String? = nil, updated: Int64) {
        self.id = id
        self.provider = provider
        self.sessionID = sessionID
        self.title = title
        self.entry = entry
        self.url = url ?? "/api/experimental/artifacts/\(id)/view/"
        self.updated = updated
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        provider = try container.decode(String.self, forKey: .provider)
        sessionID = try container.decodeIfPresent(String.self, forKey: .sessionID)
        title = try container.decode(String.self, forKey: .title)
        entry = try container.decodeIfPresent(String.self, forKey: .entry) ?? ""
        url = try container.decode(String.self, forKey: .url)
        updated = Int64(try container.decode(Time.self, forKey: .time).updated)
    }
}

/// Whether artifacts can be used with this OpenCode, for this project.
enum ArtifactsState: Equatable, Sendable {
    /// Not read yet.
    case checking
    case on
    case off
    /// The routes aren't declared, or they answer in a way this Hex Code doesn't know: the feature
    /// switches off quietly (no tile, Settings reads Not available) until the next connection.
    case unavailable
}

/// What one answer of `GET /api/experimental/artifacts?location[directory]=<dir>` means
/// (artifacts-proposal.md §1.5). On and off are always read from this route, with the location.
enum ArtifactsAnswer: Equatable, Sendable {
    case on([ArtifactInfo])
    /// `403`: "Artifacts are disabled". The wording isn't relied on.
    case off
    /// A bare `404`, `405`, `400`, or a `200` whose body doesn't decode: the route has changed.
    case changed
    /// `401`: the sign-in expired.
    case signedOut
    /// `5xx`, a timeout, unreachable, or anything else: keep the last state.
    case unknown

    /// `status` is nil when no answer came.
    static func read(status: Int?, body: Data) -> ArtifactsAnswer {
        guard let status else { return .unknown }
        switch status {
        case 200:
            struct List: Decodable { let data: [ArtifactInfo] }
            guard let list = try? JSONDecoder().decode(List.self, from: body) else { return .changed }
            return .on(list.data)
        case 403: return .off
        case 401: return .signedOut
        case 400, 405: return .changed
        case 404:
            // A route the server doesn't know answers with an empty body; a known error has a tag.
            let tagged = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["_tag"] != nil
            return tagged ? .unknown : .changed
        default: return .unknown
        }
    }
}

/// Whether `/openapi.json` declares what Hex Code uses (artifacts-proposal.md §2.2): the list and
/// the view routes, and an `Artifact.Info` with `id`, `title`, `url` and `time.updated`.
enum ArtifactsContract {
    static let listPath = "/api/experimental/artifacts"
    static let viewPath = "/api/experimental/artifacts/{artifactID}/view/*"

    static func declared(_ document: [String: Any]) -> Bool {
        let paths = document["paths"] as? [String: Any] ?? [:]
        guard let list = (paths[listPath] as? [String: Any])?["get"] as? [String: Any],
              (paths[viewPath] as? [String: Any])?["get"] != nil
        else { return false }
        // The list's 200: `{location, data: Info[]}`.
        let schema = (((((list["responses"] as? [String: Any])?["200"] as? [String: Any])?["content"] as? [String: Any])?["application/json"] as? [String: Any])?["schema"] as? [String: Any])
            .flatMap { resolve($0, in: document) }
        guard let data = (schema?["properties"] as? [String: Any])?["data"] as? [String: Any],
              let items = (resolve(data, in: document)?["items"] as? [String: Any]).flatMap({ resolve($0, in: document) }),
              let properties = items["properties"] as? [String: Any],
              ["id", "title", "url"].allSatisfy({ properties[$0] != nil }),
              let time = (properties["time"] as? [String: Any]).flatMap({ resolve($0, in: document) }),
              (time["properties"] as? [String: Any])?["updated"] != nil
        else { return false }
        return true
    }

    private static func resolve(_ schema: [String: Any], in document: [String: Any]) -> [String: Any]? {
        var schema = schema
        for _ in 0 ..< 8 {
            guard let ref = schema["$ref"] as? String else { return schema }
            let name = ref.split(separator: "/").last.map(String.init) ?? ""
            guard let next = ((document["components"] as? [String: Any])?["schemas"] as? [String: Any])?[name] as? [String: Any] else { return nil }
            schema = next
        }
        return nil
    }
}

// MARK: Config

/// A JSON value, compared as parsed: the config's documents before and after an edit.
indirect enum JSONValue: Equatable, Sendable, Decodable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    var object: [String: JSONValue]? {
        if case let .object(value) = self { return value }
        return nil
    }
}

/// `GET /api/config?location[directory]=<dir>`: the documents in priority order (lowest first),
/// then the global folder's entry. The values are parsed and normalised, and may hold API keys:
/// never logged.
struct OpenCodeConfig: Equatable, Sendable {
    struct Document: Equatable, Sendable {
        /// Nil for `OPENCODE_CONFIG_CONTENT`.
        var path: String?
        var info: [String: JSONValue]

        var experimental: [String: JSONValue]? { info["experimental"]?.object }
        /// `experimental` is set at all: the highest such document wins it, with no merge.
        var setsExperimental: Bool { info["experimental"] != nil }
    }

    var documents: [Document]
    /// The global config folder (`$XDG_CONFIG_HOME/opencode`), listed even when it has no file.
    var globalFolder: String?

    static func read(_ data: Data) -> OpenCodeConfig? {
        struct Entry: Decodable {
            let type: String
            let path: String?
            let info: [String: JSONValue]?
        }
        guard let entries = try? JSONDecoder().decode([Entry].self, from: data) else { return nil }
        return OpenCodeConfig(
            documents: entries.filter { $0.type == "document" }.map { Document(path: $0.path, info: $0.info ?? [:]) },
            globalFolder: entries.first { $0.type == "directory" }?.path
        )
    }

    func document(at path: String) -> Document? {
        documents.first { $0.path == path }
    }
}

/// The file the prompt names: the one that wins `experimental` (artifacts-proposal.md §2.1, spike
/// §3.1), as an absolute path.
enum ConfigTarget: Equatable, Sendable {
    case file(TargetFile)
    /// The winner has no path (`OPENCODE_CONFIG_CONTENT`): no prompt, the line to copy.
    case environment
    /// The config couldn't be read: no prompt.
    case unreadable

    struct TargetFile: Equatable, Sendable {
        /// Absolute.
        var path: String
        /// In the global config folder: one change for every project, nothing in the repo.
        var isGlobal: Bool
        /// The file already has an `"experimental"` block.
        var hasExperimental: Bool
        /// OpenCode has read a document from it.
        var hasDocument: Bool

        var folder: String { (path as NSString).deletingLastPathComponent }
        var name: String { (path as NSString).lastPathComponent }

        /// The line a person adds by hand.
        var line: String { hasExperimental ? #""artifacts": true"# : #""experimental": { "artifacts": true }"# }
    }

    static func choose(_ config: OpenCodeConfig?) -> ConfigTarget {
        guard let config else { return .unreadable }
        if let winner = config.documents.last(where: \.setsExperimental) {
            guard let path = winner.path else { return .environment }
            return .file(TargetFile(
                path: path,
                isGlobal: config.globalFolder.map { (path as NSString).deletingLastPathComponent == $0 } ?? false,
                hasExperimental: true,
                hasDocument: true
            ))
        }
        guard let folder = config.globalFolder else { return .unreadable }
        // `opencode.jsonc` when OpenCode reads one there, else `opencode.json`.
        let jsonc = (folder as NSString).appendingPathComponent("opencode.jsonc")
        let json = (folder as NSString).appendingPathComponent("opencode.json")
        let path = config.document(at: jsonc) != nil ? jsonc : json
        return .file(TargetFile(path: path, isGlobal: true, hasExperimental: false, hasDocument: config.document(at: path) != nil))
    }
}

/// The config after the agent's edit against the copy saved before it (spike §3.2): every key
/// outside `experimental` must match, and `experimental` must keep its other keys.
enum ConfigCheck {
    enum Result: Equatable, Sendable {
        case intact
        /// The target file is there but OpenCode can't read it any more: it is ignored as a whole.
        case broken
        /// These keys went missing or changed (`provider`, `experimental.todo`, or a whole file's
        /// name when another document went).
        case changed([String])
    }

    /// `fileExists`: the target file is listed in its folder now; nil when that couldn't be read.
    static func compare(before: OpenCodeConfig, after: OpenCodeConfig, target: String, fileExists: Bool?) -> Result {
        let had = before.document(at: target)
        let has = after.document(at: target)
        // There before, or listed now, with no document: it no longer parses.
        if has == nil, had != nil || fileExists == true { return .broken }
        var changed: [String] = []
        for document in before.documents {
            guard let path = document.path else { continue }
            guard let now = after.document(at: path) else {
                changed.append((path as NSString).lastPathComponent)
                continue
            }
            changed += differences(document.info, now.info)
        }
        var seen = Set<String>()
        changed = changed.filter { seen.insert($0).inserted }
        return changed.isEmpty ? .intact : .changed(changed)
    }

    /// Keys outside `experimental` that went or changed, then `experimental`'s own keys other than
    /// `artifacts`.
    static func differences(_ before: [String: JSONValue], _ after: [String: JSONValue]) -> [String] {
        var keys: [String] = []
        for key in before.keys.sorted() where key != "experimental" {
            if after[key] != before[key] { keys.append(key) }
        }
        let oldExperimental = before["experimental"]?.object ?? [:]
        let newExperimental = after["experimental"]?.object ?? [:]
        for key in oldExperimental.keys.sorted() where key != "artifacts" {
            if newExperimental[key] != oldExperimental[key] { keys.append("experimental.\(key)") }
        }
        return keys
    }
}

// MARK: The card's artifact

/// The artifact the card shows as its first tile, saved with the card.
struct CardArtifact: Equatable, Codable, Sendable {
    var id: String
    var title: String
}

enum ArtifactPick {
    static let localPath = "/api/experimental/artifacts/"

    /// The newest artifact of today's session or one of its subagents (`sessions`) whose
    /// `time.updated` is at or after the start of the latest run (`time.busy`, epoch ms); nil for
    /// none. Only the local provider's, with its own path; one without a `sessionID` never shows.
    static func newest(_ artifacts: [ArtifactInfo], sessions: Set<String>, since busy: Int64?) -> CardArtifact? {
        guard let busy else { return nil }
        let candidates = artifacts.filter { artifact in
            guard artifact.provider == "local", artifact.url.hasPrefix(localPath),
                  let owner = artifact.sessionID, sessions.contains(owner)
            else { return false }
            return artifact.updated >= busy
        }
        return candidates.max { $0.updated < $1.updated }.map { CardArtifact(id: $0.id, title: $0.title) }
    }
}

/// `Link:` lines and links in Now or Next that point at an artifact are dropped, whatever the host:
/// the artifact tile covers them, and the host is the agent's guess (artifacts-proposal.md §2.2).
enum ArtifactLinks {
    static func isArtifact(_ url: String) -> Bool {
        if url.hasPrefix(ArtifactPick.localPath) { return true }
        guard let path = URLComponents(string: url)?.path else { return false }
        return path.hasPrefix(ArtifactPick.localPath)
    }
}

// MARK: The setup session

/// The agent's reply, read only for its reason: it is never the status.
enum SetupReply: Equatable, Sendable {
    case done
    case failed(String)
    case other(String)

    static func read(_ text: String) -> SetupReply {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.uppercased().hasPrefix("FAILED") {
            var reason = trimmed.dropFirst("FAILED".count).trimmingCharacters(in: .whitespaces)
            if reason.hasPrefix(":") { reason = reason.dropFirst().trimmingCharacters(in: .whitespaces) }
            return .failed(reason)
        }
        if trimmed.uppercased().hasPrefix("DONE") { return .done }
        return .other(trimmed)
    }
}

/// A run's error from `session.execution.failed` (`Session.StructuredError`).
struct SetupRunError: Equatable, Sendable {
    var type: String
    var message: String
    var status: Int?

    /// Every free model can be cut off at once (`429 provider.free-limit`).
    var isFreeLimit: Bool { status == 429 || type.contains("free-limit") }
}

/// What the setup session's events tell the flow, read from a raw `data:` line.
enum SetupEvent: Equatable, Sendable {
    /// `session.tool.input.started`: the tool the agent is calling.
    case toolStarted(name: String)
    case approvalAsked(PendingItem)
    case approvalReplied(id: String)
    case runStarted
    case runSucceeded
    case runFailed(SetupRunError)
    case runInterrupted

    static func decode(_ line: String, sessionID: String) -> SetupEvent? {
        // Most lines are another session's: skip them before parsing.
        guard line.contains(sessionID),
              let object = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
              let type = object["type"] as? String,
              let data = object["data"] as? [String: Any]
        else { return nil }
        switch type {
        case "session.tool.input.started":
            guard data["sessionID"] as? String == sessionID, let name = data["name"] as? String else { return nil }
            return .toolStarted(name: name)
        case "session.execution.failed":
            guard data["sessionID"] as? String == sessionID else { return nil }
            let error = data["error"] as? [String: Any] ?? [:]
            return .runFailed(SetupRunError(
                type: error["type"] as? String ?? "",
                message: error["message"] as? String ?? "",
                status: (error["status"] as? NSNumber)?.intValue
            ))
        default:
            break
        }
        switch SessionEvent.decode(line) {
        case let .permissionAsked(item)? where item.sessionID == sessionID:
            return .approvalAsked(item)
        case let .permissionReplied(owner, id)? where owner == sessionID:
            return .approvalReplied(id: id)
        case let .executionStarted(owner, _)? where owner == sessionID:
            return .runStarted
        case let .executionEnded(owner)? where owner == sessionID:
            return type == "session.execution.interrupted" ? .runInterrupted : .runSucceeded
        default:
            return nil
        }
    }
}

/// One entry of the setup session as "See what the agent says" shows it.
struct SetupMessage: Equatable, Sendable, Identifiable {
    enum Kind: Equatable, Sendable {
        /// Hex Code's prompt, sent as you.
        case you
        case agent
        /// A tool call: its name and the path or command it was given.
        case tool
        /// The run failed: the error's message.
        case error
    }

    var id: String
    var kind: Kind
    var text: String

    /// `GET /api/session/{id}/message` newest first, read oldest first.
    static func read(_ data: Data) -> [SetupMessage] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let messages = object["data"] as? [[String: Any]]
        else { return [] }
        var out: [SetupMessage] = []
        for message in messages.reversed() {
            let id = message["id"] as? String ?? UUID().uuidString
            switch message["type"] as? String {
            case "user":
                out.append(SetupMessage(id: id, kind: .you, text: message["text"] as? String ?? ""))
            case "assistant":
                for (index, part) in (message["content"] as? [[String: Any]] ?? []).enumerated() {
                    let partID = "\(id).\(index)"
                    switch part["type"] as? String {
                    case "text":
                        let text = (part["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                        if !text.isEmpty { out.append(SetupMessage(id: partID, kind: .agent, text: text)) }
                    case "tool":
                        let input = (part["state"] as? [String: Any])?["input"] as? [String: Any]
                        let target = (input?["filePath"] ?? input?["path"] ?? input?["command"] ?? input?["pattern"]) as? String
                        let name = part["name"] as? String ?? ""
                        out.append(SetupMessage(id: partID, kind: .tool, text: [name, target].compactMap { $0 }.joined(separator: " ")))
                    default:
                        break
                    }
                }
            case "idle":
                if message["outcome"] as? String == "failed", let error = message["error"] as? [String: Any] {
                    out.append(SetupMessage(id: id, kind: .error, text: error["message"] as? String ?? ""))
                }
            default:
                break
            }
        }
        return out
    }

    /// The agent's last words, for its reason.
    static func lastReply(_ messages: [SetupMessage]) -> String? {
        messages.last { $0.kind == .agent }?.text
    }
}
