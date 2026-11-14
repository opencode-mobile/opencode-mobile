import Foundation

public enum ProbeStatus: Equatable {
    case reachable
    case authFailed
    case unreachable
}

public protocol ConnectionProbing: Sendable {
    func probe(connection: ServerConnection) async throws -> ServerProtocol
    /// Protocol plus the server's health `version`. Defaults to `probe` with no version.
    func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome
    /// Release-dependent v2 features (opencode 2.1.0+). Throws when the server's API contract
    /// could not be read, so the caller can retry later. Defaults to none.
    func probeV2Features(connection: ServerConnection) async throws -> V2ServerFeatures
}

extension ConnectionProbing {
    public func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome {
        ServerProbeOutcome(serverProtocol: try await probe(connection: connection), serverVersion: nil)
    }

    public func probeV2Features(connection _: ServerConnection) async throws -> V2ServerFeatures {
        V2ServerFeatures()
    }
}

/// Result of a health probe: the detected protocol and the health `version`.
public nonisolated struct ServerProbeOutcome: Equatable, Sendable {
    public let serverProtocol: ServerProtocol
    public let serverVersion: String?

    public init(
        serverProtocol: ServerProtocol,
        serverVersion: String?
    ) {
        self.serverProtocol = serverProtocol
        self.serverVersion = serverVersion
    }
}

public struct ConnectionProbeService: ConnectionProbing, Sendable {
    public static let probeSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: RedirectPolicyDelegate.shared, delegateQueue: nil)
    }()

    private let adapter = ProjectDomainAdapter()
    private let urlSession: URLSession

    public init(urlSession: URLSession = ConnectionProbeService.probeSession) {
        self.urlSession = urlSession
    }

    public func probe(connection: ServerConnection) async throws -> ServerProtocol {
        try await probeServer(connection: connection).serverProtocol
    }

    public func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome {
        let client = ProjectsAPIClient(fixedConnection: connection, urlSession: urlSession)
        do {
            let outcome = try await client.detectServer()
            DiagnosticLog.info(.connection, "protocol=\(outcome.serverProtocol.wireValue)")
            return outcome
        } catch {
            throw adapter.mapProbeError(error)
        }
    }

    public func probeV2Features(connection: ServerConnection) async throws -> V2ServerFeatures {
        let client = ProjectsAPIClient(fixedConnection: connection, urlSession: urlSession)
        var features = try V2ServerContract.features(data: try await client.getV2OpenAPIDocument())
        if features.streamedUploadRoute {
            do {
                features.uploadDirectory = try await client.getV2TmpDirectory()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                features.uploadDirectoryUnread = true
            }
        }
        return features
    }
}

extension ServerProbeError {
    public var probeStatus: ProbeStatus {
        switch self {
        case let .http(statusCode, _):
            return (statusCode == 401 || statusCode == 403) ? .authFailed : .unreachable
        case .transport, .unhealthy:
            return .unreachable
        }
    }

    public var probeUserMessage: String {
        switch self {
        case .transport:
            return "Unable to reach the server"
        case let .http(statusCode, _):
            if statusCode == 401 || statusCode == 403 {
                return "Authentication failed"
            }
            return "Unable to reach the server"
        case .unhealthy:
            return "Server reported unhealthy"
        }
    }
}

extension ProbeStatus {
    public var label: String {
        switch self {
        case .reachable:
            return "Server reachable"
        case .authFailed:
            return "Authentication failed"
        case .unreachable:
            return "Unable to reach the server"
        }
    }
}
