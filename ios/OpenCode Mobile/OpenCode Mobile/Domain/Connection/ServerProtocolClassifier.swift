import Foundation

/// Pure protocol classification from health payloads (desktop-compatible).
///
/// Returns nil when no response is usable — callers treat that as
/// unreachable for connection probe instead of defaulting to v2.
nonisolated enum ServerProtocolClassifier {
    static func classify(
        globalHealth: HealthDTO?,
        apiInfo: HealthDTO? = nil,
        apiHealth: HealthDTO? = nil
    ) -> ServerProtocol? {
        if globalHealth?.healthy == true { return .v1 }
        if v2Health(apiInfo: apiInfo, apiHealth: apiHealth) != nil { return .v2 }
        return nil
    }

    /// v2 payload used for the unhealthy check. Prefers a usable `/api/info`
    /// body, then a usable `/api/health` body, then either unhealthy body.
    static func v2Health(apiInfo: HealthDTO?, apiHealth: HealthDTO?) -> HealthDTO? {
        let candidates = [apiInfo, apiHealth].compactMap { $0 }
        if let usable = candidates.first(where: { $0.pid != nil && $0.healthy != false }) {
            return usable
        }
        return candidates.first { $0.pid != nil }
    }
}
