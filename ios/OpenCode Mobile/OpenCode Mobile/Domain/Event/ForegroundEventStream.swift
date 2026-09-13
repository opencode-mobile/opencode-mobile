import Foundation

/// Explicitly nonisolated: app default actor isolation is MainActor, but SSE
/// publish must stay off the UI thread so dense deltas do not starve Session Chat
/// (Android SharedFlow parity; OPE-140 Stage 2).
nonisolated protocol ForegroundEventStream: AnyObject, Sendable {
    var connectionState: EventStreamConnectionState { get }
    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState>
    func events() -> AsyncStream<OpencodeEvent>
    func start()
    func stop() async
    func onReconnectWake()
}

extension ForegroundEventStream {
    func onReconnectWake() {}
}
