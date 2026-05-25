import Foundation

protocol ForegroundEventStream: AnyObject, Sendable {
    var connectionState: EventStreamConnectionState { get }
    func events() -> AsyncStream<OpencodeEvent>
    func start()
    func stop()
}
