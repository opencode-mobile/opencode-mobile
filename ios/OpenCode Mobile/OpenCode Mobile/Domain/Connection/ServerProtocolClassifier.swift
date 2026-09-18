import Foundation

/// Pure protocol classification from health payloads (desktop-compatible).
///
/// Returns nil when neither response is usable — callers treat that as
/// unreachable for connection probe instead of defaulting to v2.
nonisolated enum ServerProtocolClassifier {
    static func classify(
        globalHealth: HealthDTO?,
        apiHealth: HealthDTO?,
        apiInfo: HealthDTO? = nil
    ) -> ServerProtocol? {
        if globalHealth?.healthy == true { return .v1 }
        if apiHealth?.pid != nil { return .v2 }
        if apiHealth?.healthy == true { return .v1 }
        if apiInfo?.pid != nil { return .v2 }
        return nil
    }
}
