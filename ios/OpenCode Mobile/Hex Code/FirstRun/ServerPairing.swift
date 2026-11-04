import Foundation
import OpenCodeKit

/// A paired OpenCode server that passed the checks Hex Code needs.
struct PairedServer: Equatable, Sendable {
    let profile: ConnectionProfile
    /// Health `version`, when the server sent one.
    let version: String?

    /// Host shown in the app, for example `mbp.local`.
    var hostLabel: String { ServerHost.name(profile.baseURL) }
}

/// The host alone, without scheme or port, as the app names a server.
enum ServerHost {
    static func name(_ baseURL: String) -> String {
        URL(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines))?.host() ?? ConnectionProfileIdentity.hostLabel(baseURL)
    }
}

/// Why pairing stopped. The scan screen shows these; the checks behind it show only here.
enum PairingFailure: Error, Equatable, Sendable {
    /// The QR is not one `opencode pair` prints.
    case notAPairingCode
    /// The code only lists addresses on the computer itself.
    case onlyLocalAddresses
    /// None of the code's addresses answered.
    case unreachable(host: String)
    /// The code was already used or is older than 5 minutes.
    case codeExpired
    /// The server answered, but it is not an OpenCode 2 server.
    case notOpenCode2
    /// OpenCode 2, but older than the release Hex Code needs.
    case tooOld(version: String)
    /// The server's API lacks a route Hex Code relies on.
    case missingRoutes
}

protocol ServerPairing: Sendable {
    /// Host to name while connecting, read from the code without contacting anything.
    func host(of payload: String) -> String?
    func pair(payload: String) async -> Result<PairedServer, PairingFailure>
}

/// Pairing as OpenCode Mobile does it: one unauthenticated sweep over the code's addresses,
/// the one-time code redeemed on the single host that answered, then the server checks with the
/// session token it returned.
struct OpenCodeServerPairing: ServerPairing {
    /// The first release with session generate, inbox cancel and `/goal` (see the build plan).
    static let minimumVersion = "2.2.5"

    var probing: any ConnectionProbing = ConnectionProbeService()
    var redeemer: any PairingCodeRedeeming = PairingCodeRedeemer()

    func host(of payload: String) -> String? {
        guard case let .success(credentials) = PairingQRPayload.parse(payload) else { return nil }
        return ServerHost.name(credentials.baseURL)
    }

    func pair(payload: String) async -> Result<PairedServer, PairingFailure> {
        let credentials: PairingQRCredentials
        switch PairingQRPayload.parse(payload) {
        case let .success(parsed):
            credentials = parsed
        case .failure(.noReachableURL):
            return .failure(.onlyLocalAddresses)
        case .failure(.invalidPayload):
            return .failure(.notAPairingCode)
        }
        let urls = ConnectionProfileIdentity.normalizedAddressList(credentials.urls)
        let resolution = await ServerAddressProbe.resolve(
            urls: urls,
            username: credentials.username,
            password: credentials.code == nil ? credentials.password : "",
            currentBaseURL: nil,
            probing: probing
        )
        guard let winner = resolution.chosen else {
            return .failure(.unreachable(host: ServerHost.name(credentials.baseURL)))
        }
        var password = credentials.password
        if let code = credentials.code {
            do {
                password = try await redeemer.redeem(baseURL: winner, code: code)
            } catch PairingCodeRedeemError.expired {
                return .failure(.codeExpired)
            } catch {
                return .failure(.unreachable(host: ServerHost.name(winner)))
            }
        }
        var profile = ConnectionProfile(
            baseURL: winner,
            serverURLs: urls,
            username: credentials.username,
            password: password
        )
        guard let connection = ServerConnection(profile: profile) else {
            return .failure(.notAPairingCode)
        }
        let outcome: ServerProbeOutcome
        do {
            outcome = try await probing.probeServer(connection: connection)
        } catch let error as ServerProbeError {
            if case let .http(status, _) = error, status == 401 || status == 403 {
                return .failure(.codeExpired)
            }
            return .failure(.unreachable(host: ServerHost.name(winner)))
        } catch {
            return .failure(.unreachable(host: ServerHost.name(winner)))
        }
        guard outcome.serverProtocol == .v2 else { return .failure(.notOpenCode2) }
        if let version = outcome.serverVersion, !Self.meetsMinimum(version) {
            return .failure(.tooOld(version: version))
        }
        let features = try? await probing.probeV2Features(connection: connection.withProtocol(.v2))
        if let features, !features.sessionGenerate {
            return .failure(.missingRoutes)
        }
        profile.serverProtocol = .v2
        return .success(PairedServer(profile: profile, version: outcome.serverVersion))
    }

    /// Compares dotted release numbers. A version that is not one (a local build) passes, since
    /// it cannot be judged; the route check still runs.
    static func meetsMinimum(_ version: String, minimum: String = minimumVersion) -> Bool {
        guard let have = releaseNumbers(version), let need = releaseNumbers(minimum) else { return true }
        for index in 0 ..< max(have.count, need.count) {
            let left = index < have.count ? have[index] : 0
            let right = index < need.count ? need[index] : 0
            if left != right { return left > right }
        }
        return true
    }

    private static func releaseNumbers(_ version: String) -> [Int]? {
        var trimmed = version.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("v") { trimmed.removeFirst() }
        let core = trimmed.split(whereSeparator: { $0 == "-" || $0 == "+" }).first.map(String.init) ?? ""
        let parts = core.split(separator: ".").map { Int($0) }
        guard !parts.isEmpty, parts.allSatisfy({ $0 != nil }) else { return nil }
        return parts.compactMap { $0 }
    }
}
