import Foundation
import OpenCodeKit

/// An HTTP answer as it came: nil `status` when none came (unreachable, timed out).
struct HTTPAnswer: Equatable, Sendable {
    var status: Int?
    var body: Data = Data()
}

/// A permission rule for a new session.
struct PermissionRule: Equatable, Sendable, Encodable {
    var action: String
    var resource: String
    var effect: String
}

/// What artifacts ask of the server (app plan, "Artifacts (M8)"); a fake in tests.
protocol ArtifactsAPI: Sendable {
    /// `/openapi.json` declares the routes and shape Hex Code uses; nil when it couldn't be read.
    func declared() async -> Bool?
    /// `GET /api/experimental/artifacts?location[directory]=<directory>`.
    func list(directory: String) async -> HTTPAnswer
    /// `GET /api/config?location[directory]=<directory>`; nil when it couldn't be read.
    func config(directory: String) async -> OpenCodeConfig?
    /// The names of the files in `folder` (`GET /api/fs/list`, with today's project as the
    /// location); nil when it couldn't be read.
    func files(in folder: String, directory: String) async -> [String]?
    /// A file's exact bytes (`GET /api/fs/read/<name>` with its folder as the location).
    func read(name: String, folder: String) async -> Data?
    /// Writes `data` to the absolute `path` (`POST /api/experimental/fs/write`, through the kit's
    /// upload client); true on a `2xx`.
    func write(_ data: Data, to path: String, directory: String) async -> Bool
    /// `PUT` or `DELETE …/instructions/entries/artifact-rules` on today's session.
    func putRules(sessionID: String) async -> Bool
    func deleteRules(sessionID: String) async
    /// The start of the session's latest run (`time.busy`, epoch ms); nil when it never ran or
    /// couldn't be read.
    func busyStart(sessionID: String) async -> Int64?

    // The setup session.

    /// The newest root session in `directory` titled exactly "Hex Code setup".
    func findSetupSession(directory: String) async throws -> String?
    /// Creates it with the model in use and the lockdown rules.
    func createSetupSession(directory: String, model: ModelChoice, rules: [PermissionRule]) async throws -> String
    func send(_ text: String, sessionID: String) async throws
    /// The setup session's own pending approvals.
    func pendingApprovals(sessionID: String) async -> [PendingPermissionV2]
    /// Allow once (`once`) or Reject (`reject`).
    func reply(sessionID: String, requestID: String, allow: Bool) async throws
    /// Its messages, oldest first.
    func transcript(sessionID: String) async -> [SetupMessage]
}

/// The real server, through the connection in use.
struct OpenCodeArtifactsService: ArtifactsAPI {
    let connection: @Sendable () -> ServerConnection?
    private let client: SessionStateAPIClient
    private let uploads: AttachmentUploadAPIClient

    init(connection: @escaping @Sendable () -> ServerConnection?) {
        self.connection = connection
        client = SessionStateAPIClient(connectionProvider: connection)
        uploads = AttachmentUploadAPIClient(connectionProvider: connection)
    }

    static let timeout: TimeInterval = 15

    private func get(_ path: String, query: [URLQueryItem] = [], location: String? = nil) async -> HTTPAnswer {
        guard let connection = connection() else { return HTTPAnswer(status: nil) }
        var request = connection.authorizedRequest(path: path, locationDirectory: location)
        if !query.isEmpty, let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.queryItems = (components.queryItems ?? []) + query
            components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
            request.url = components.url
        }
        request.timeoutInterval = Self.timeout
        return await Self.perform(request)
    }

    private static func perform(_ request: URLRequest) async -> HTTPAnswer {
        guard let (data, response) = try? await OpenCodeURLSessions.rest.data(for: request) else { return HTTPAnswer(status: nil) }
        return HTTPAnswer(status: (response as? HTTPURLResponse)?.statusCode, body: data)
    }

    func declared() async -> Bool? {
        let answer = await get(V2ServerContract.openAPIPath)
        guard answer.status == 200, let document = try? JSONSerialization.jsonObject(with: answer.body) as? [String: Any] else { return nil }
        return ArtifactsContract.declared(document)
    }

    func list(directory: String) async -> HTTPAnswer {
        await get("api/experimental/artifacts", location: directory)
    }

    func config(directory: String) async -> OpenCodeConfig? {
        let answer = await get("api/config", location: directory)
        guard answer.status == 200 else { return nil }
        return OpenCodeConfig.read(answer.body)
    }

    func files(in folder: String, directory: String) async -> [String]? {
        struct Entry: Decodable { let path: String; let type: String? }
        struct List: Decodable { let data: [Entry] }
        let answer = await get("api/fs/list", query: [URLQueryItem(name: "path", value: folder)], location: directory)
        guard answer.status == 200, let list = try? JSONDecoder().decode(List.self, from: answer.body) else { return nil }
        return list.data.filter { $0.type != "directory" }.map { ($0.path as NSString).lastPathComponent }
    }

    func read(name: String, folder: String) async -> Data? {
        guard let escaped = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) else { return nil }
        let answer = await get("api/fs/read/\(escaped)", location: folder)
        return answer.status == 200 ? answer.body : nil
    }

    func write(_ data: Data, to path: String, directory: String) async -> Bool {
        let file = FileManager.default.temporaryDirectory.appending(path: "hexcode-restore-\(UUID().uuidString)")
        do {
            try data.write(to: file)
        } catch {
            return false
        }
        defer { try? FileManager.default.removeItem(at: file) }
        guard let result = try? await uploads.write(fileURL: file, path: path, directory: directory, onProgress: { _, _ in }) else { return false }
        return (200 ..< 300).contains(result.statusCode)
    }

    func putRules(sessionID: String) async -> Bool {
        do {
            try await client.putInstructionEntry(
                sessionID: sessionID,
                key: SessionPrompts.Artifacts.Rules.entryID,
                value: SessionPrompts.Artifacts.Rules.text
            )
            return true
        } catch {
            return false
        }
    }

    func deleteRules(sessionID: String) async {
        guard let connection = connection() else { return }
        var request = connection.authorizedRequest(
            path: "api/experimental/session/\(sessionID)/instructions/entries/\(SessionPrompts.Artifacts.Rules.entryID)",
            method: "DELETE"
        )
        request.timeoutInterval = Self.timeout
        _ = await Self.perform(request)
    }

    func busyStart(sessionID: String) async -> Int64? {
        try? await client.getSession(id: sessionID).busyMs
    }

    func findSetupSession(directory: String) async throws -> String? {
        let page = try await client.listSessions(
            directory: directory,
            search: SessionPrompts.Artifacts.Setup.title,
            parent: SessionStateAPIClient.Parent.none,
            archived: false,
            limit: 20
        )
        return page.sessions.first { $0.title == SessionPrompts.Artifacts.Setup.title && $0.directory == directory }?.id
    }

    func createSetupSession(directory: String, model: ModelChoice, rules: [PermissionRule]) async throws -> String {
        struct Location: Encodable { let directory: String }
        struct Body: Encodable {
            let title: String
            let location: Location
            let model: SessionModelRefV2
            let permissions: [PermissionRule]
        }
        struct Created: Decodable {
            struct Info: Decodable { let id: String }
            let data: Info
        }
        guard let connection = connection() else { throw SessionStateAPIError.noActiveConnection }
        var request = connection.authorizedRequest(path: "api/session", method: "POST")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(Body(
            title: SessionPrompts.Artifacts.Setup.title,
            location: Location(directory: directory),
            model: OpenCodeSessionService.ref(model),
            permissions: rules
        ))
        request.timeoutInterval = Self.timeout
        let answer = await Self.perform(request)
        guard let status = answer.status else { throw SessionStateAPIError.timedOut }
        guard (200 ..< 300).contains(status) else { throw SessionStateAPIError.httpStatus(status, String(data: answer.body, encoding: .utf8)) }
        return try JSONDecoder().decode(Created.self, from: answer.body).data.id
    }

    func send(_ text: String, sessionID: String) async throws {
        _ = try await client.prompt(sessionID: sessionID, text: text, delivery: .queue)
    }

    func pendingApprovals(sessionID: String) async -> [PendingPermissionV2] {
        (try? await client.pendingPermissions(sessionID: sessionID)) ?? []
    }

    func reply(sessionID: String, requestID: String, allow: Bool) async throws {
        try await client.replyToPermission(sessionID: sessionID, requestID: requestID, decision: allow ? .once : .reject)
    }

    func transcript(sessionID: String) async -> [SetupMessage] {
        let answer = await get(
            "api/session/\(sessionID)/message",
            query: [URLQueryItem(name: "order", value: "desc"), URLQueryItem(name: "limit", value: "40")]
        )
        guard answer.status == 200 else { return [] }
        return SetupMessage.read(answer.body)
    }
}
