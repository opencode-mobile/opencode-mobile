import Foundation

@MainActor
protocol ForegroundEventStream: AnyObject, Sendable {
    var connectionState: EventStreamConnectionState { get }
    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState>
    func events() -> AsyncStream<OpencodeEvent>
    func start()
    func stop()
}
