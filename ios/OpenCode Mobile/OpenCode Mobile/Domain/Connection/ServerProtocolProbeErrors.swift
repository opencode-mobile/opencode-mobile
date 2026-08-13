import Foundation

/// Prefers the most actionable dual-probe failure when classification cannot
/// pick a protocol (auth over unreachable, unhealthy bodies over generic miss).
nonisolated enum ServerProtocolProbeErrors {
    static func prefer(previous: Error?, next: Error) -> Error {
        guard let previous else { return next }
        return rank(next) > rank(previous) ? next : previous
    }

    static func unresolved(
        globalHealth: HealthDTO?,
        apiHealth: HealthDTO?,
        fetchError: Error?
    ) -> Error {
        var error = fetchError
        if let globalHealth, globalHealth.healthy != true {
            error = prefer(previous: error, next: ServerProbeError.unhealthy(version: globalHealth.version))
        }
        if let apiHealth, apiHealth.healthy != true, apiHealth.pid == nil {
            error = prefer(previous: error, next: ServerProbeError.unhealthy(version: apiHealth.version))
        }
        return error ?? ServerProbeError.transport("No health endpoint")
    }

    private static func rank(_ error: Error) -> Int {
        let probe: ServerProbeError
        if let typed = error as? ServerProbeError {
            probe = typed
        } else if case let ProjectsAPIError.httpStatus(code, message) = error {
            probe = .http(statusCode: code, message: message)
        } else {
            return 0
        }
        switch probe {
        case let .http(statusCode, _):
            return (statusCode == 401 || statusCode == 403) ? 40 : 10
        case .unhealthy:
            return 30
        case .transport:
            return 5
        }
    }
}
