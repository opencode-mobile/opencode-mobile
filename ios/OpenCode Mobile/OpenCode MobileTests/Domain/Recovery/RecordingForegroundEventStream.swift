import Foundation
@testable import OpenCode_Mobile

final class RecordingForegroundEventStream: LiveConnection, @unchecked Sendable {
    let fake = FakeForegroundEventStream()
    private let lock = NSLock()
    private var _ops: [String] = []

    var ops: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _ops
    }

    var connectionState: EventStreamConnectionState { fake.connectionState }
    var snapshot: LiveConnectionSnapshot { fake.snapshot }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        fake.connectionStateUpdates()
    }

    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot> {
        fake.snapshotUpdates()
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
