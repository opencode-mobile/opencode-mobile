import Foundation
@testable import OpenCode_Mobile

final class RecordingForegroundEventStream: ForegroundEventStream, @unchecked Sendable {
    let fake = FakeForegroundEventStream()
    private let lock = NSLock()
    private var _ops: [String] = []

    var ops: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _ops
    }

    var connectionState: EventStreamConnectionState { fake.connectionState }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        fake.connectionStateUpdates()
    }

    func events() -> AsyncStream<OpencodeEvent> {
        fake.events()
    }

    func start() {
        lock.lock()
        _ops.append("start")
        lock.unlock()
        fake.start()
    }

    func stop() {
        lock.lock()
        _ops.append("stop")
        lock.unlock()
        fake.stop()
    }
}
