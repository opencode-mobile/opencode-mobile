import Foundation

nonisolated enum EventStreamConnectionState: Equatable, Sendable {
    case idle
    case connecting
    case connected
    case disconnected
    case reconnecting
}
