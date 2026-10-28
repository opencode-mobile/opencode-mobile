import Foundation

nonisolated enum EventStreamConnectionState: Equatable, Sendable {
    case idle
    case connecting
    case connected
    /// No server protocol answered the last check: the server is down or no longer opencode.
    case disconnected
    case reconnecting
    case authFailed
}
