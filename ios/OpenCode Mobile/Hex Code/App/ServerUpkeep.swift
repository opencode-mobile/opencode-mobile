import Foundation
import OpenCodeKit
import os

/// The pairing sign-in (a session token) expires 30 days after it was issued. The app renews it
/// silently at setup once it is more than 7 days old (app plan, "Sign-in renewal").
enum SignIn {
    static let renewAfter: TimeInterval = 7 * 86400

    /// A sign-in with no date was paired before the date was kept: it is renewed once.
    static func needsRenewal(signedInAt: Date?, now: Date) -> Bool {
        guard let signedInAt else { return true }
        return now.timeIntervalSince(signedInAt) > renewAfter
    }
}

/// How the server answered `GET /api/info` with the saved credentials.
enum ServerInfoResult: Equatable, Sendable {
    case ok(version: String?)
    /// `401`: the sign-in expired, or the server's password changed.
    case signedOut
    /// No answer, or one that isn't the server's.
    case unreachable
}

/// What setup can go on with.
enum UpkeepOutcome: Equatable, Sendable {
    case ready
    /// "Signed out of <name>", with Pair again.
    case signedOut
}

/// The server calls the checks at setup make. Tests swap them for fakes.
protocol ServerChecking: Sendable {
    func info(_ connection: ServerConnection) async -> ServerInfoResult
    /// `POST /api/pair`, then `GET /auth/connect/<code>`, both with the current credentials: a new
    /// token, valid for 30 days. The old one keeps working until its own expiry.
    func renew(_ connection: ServerConnection) async throws -> String
    /// Another of the pairing's addresses that answers and accepts the sign-in; nil when the one in
    /// use is the best or none answers.
    func resolveAddress(_ profile: ConnectionProfile) async -> String?
    /// The release-dependent v2 features, for the upload route and `paths.tmp` (app plan, "Attach
    /// files (M7)"); nil when the API contract can't be read.
    func features(_ connection: ServerConnection) async -> V2ServerFeatures?
}

extension ServerChecking {
    func features(_ connection: ServerConnection) async -> V2ServerFeatures? { nil }
}

/// Before today's session is set up: read the server's version (and, while it doesn't answer, move
/// to another of the pairing's addresses that does), then renew the sign-in when it is a week old.
/// A failure here never stops setup, except a `401`, which is Signed out.
enum ServerUpkeep {
    static func run(_ setup: HexSetup, checks: any ServerChecking, now: Date) async -> (HexSetup, UpkeepOutcome) {
        var next = setup
        guard var connection = ServerConnection(profile: next.profile, serverProtocol: .v2) else { return (setup, .ready) }
        var info = await checks.info(connection)
        if info == .unreachable, next.profile.serverURLs.count > 1, let winner = await checks.resolveAddress(next.profile),
           ConnectionProfileIdentity.normalizeBaseURL(winner) != ConnectionProfileIdentity.normalizeBaseURL(next.profile.baseURL) {
            var moved = next
            moved.profile.baseURL = winner
            if let movedConnection = ServerConnection(profile: moved.profile, serverProtocol: .v2) {
                let answer = await checks.info(movedConnection)
                if answer != .unreachable {
                    HexLog.server.info("server: switched to another of its addresses")
                    next = moved
                    connection = movedConnection
                    info = answer
                }
            }
        }
        switch info {
        case .signedOut:
            return (next, .signedOut)
        case .unreachable:
            return (next, .ready)
        case let .ok(version):
            if let version { next.serverVersion = version }
        }
        if SignIn.needsRenewal(signedInAt: next.signedInAt, now: now) {
            do {
                let token = try await checks.renew(connection)
                next.profile.password = token
                next.signedInAt = now
                HexLog.server.info("server: renewed the sign-in")
            } catch {
                // The old token still works; the next setup tries again.
                HexLog.server.error("server: couldn't renew the sign-in: \(String(describing: error), privacy: .public)")
            }
        }
        return (next, .ready)
    }
}

/// The checks on a real server.
struct LiveServerChecks: ServerChecking {
    var probing: any ConnectionProbing = ConnectionProbeService()

    func info(_ connection: ServerConnection) async -> ServerInfoResult {
        struct Info: Decodable { let version: String? }
        var request = connection.authorizedRequest(path: "api/info")
        // Short, so an address that is down moves on to the next quickly.
        request.timeoutInterval = 5
        guard let (data, response) = try? await OpenCodeURLSessions.rest.data(for: request),
              let status = (response as? HTTPURLResponse)?.statusCode
        else { return .unreachable }
        if status == 401 { return .signedOut }
        guard status == 200, let info = try? JSONDecoder().decode(Info.self, from: data) else { return .unreachable }
        return .ok(version: info.version)
    }

    func renew(_ connection: ServerConnection) async throws -> String {
        struct Code: Decodable { let code: String }
        struct Token: Decodable { let token: String }
        var pair = connection.authorizedRequest(path: "api/pair", method: "POST")
        pair.httpShouldHandleCookies = false
        let code = try JSONDecoder().decode(Code.self, from: try await Self.data(pair)).code
        guard let escaped = code.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) else {
            throw URLError(.badURL)
        }
        var connect = connection.authorizedRequest(path: "auth/connect/\(escaped)")
        connect.httpShouldHandleCookies = false
        let token = try JSONDecoder().decode(Token.self, from: try await Self.data(connect)).token
        guard !token.isEmpty else { throw URLError(.cannotParseResponse) }
        return token
    }

    func resolveAddress(_ profile: ConnectionProfile) async -> String? {
        let resolution = await ServerAddressProbe.resolve(
            urls: ConnectionProfileIdentity.normalizedAddressList(profile.serverURLs),
            username: profile.username,
            password: profile.password,
            currentBaseURL: profile.baseURL,
            probing: probing
        )
        guard let winner = resolution.chosen, resolution.reachableProtocol != nil else { return nil }
        return winner
    }

    func features(_ connection: ServerConnection) async -> V2ServerFeatures? {
        try? await probing.probeV2Features(connection: connection)
    }

    private static func data(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await OpenCodeURLSessions.rest.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return data
    }
}

/// The connection every server client reads at request time, so a new address or a renewed
/// sign-in reaches them without rebuilding them.
final class LiveConnection: @unchecked Sendable {
    private let lock = NSLock()
    private var connection: ServerConnection?

    init(_ profile: ConnectionProfile) {
        connection = ServerConnection(profile: profile, serverProtocol: .v2)
    }

    var current: ServerConnection? {
        lock.lock()
        defer { lock.unlock() }
        return connection
    }

    func update(_ profile: ConnectionProfile) {
        let next = ServerConnection(profile: profile, serverProtocol: .v2)
        lock.lock()
        connection = next
        lock.unlock()
    }
}
