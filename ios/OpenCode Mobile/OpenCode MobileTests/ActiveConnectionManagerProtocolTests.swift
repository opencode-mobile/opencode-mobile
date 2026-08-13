import Foundation
import Testing
@testable import OpenCode_Mobile

struct ActiveConnectionManagerProtocolTests {
    @Test func currentProtocolOrProbe_returnsCachedProtocolWithoutProbing() async {
        let probing = CountingProbe(result: .success(.v1))
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile, serverProtocol: .v2)

        let detected = await manager.currentProtocolOrProbe()

        #expect(detected == .v2)
        #expect(probing.calls == 0)
    }

    @Test func currentProtocolOrProbe_probesOnceOnCacheMiss() async {
        let probing = CountingProbe(result: .success(.v2))
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        let first = await manager.currentProtocolOrProbe()
        let second = await manager.currentProtocolOrProbe()

        #expect(first == .v2)
        #expect(second == .v2)
        #expect(manager.serverProtocol == .v2)
        #expect(probing.calls == 1)
    }

    @Test func currentProtocolOrProbe_parallelCallersShareOneProbe() async {
        let probing = CountingProbe(result: .success(.v2), delayNanoseconds: 50_000_000)
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        async let first = manager.currentProtocolOrProbe()
        async let second = manager.currentProtocolOrProbe()
        let detected = await (first, second)

        #expect(detected.0 == .v2)
        #expect(detected.1 == .v2)
        #expect(probing.calls == 1)
    }

    @Test func currentProtocolOrProbe_failedProbeReturnsV1WithoutThrowing() async {
        let probing = CountingProbe(result: .failure(ServerProbeError.transport("unreachable")))
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        let detected = await manager.currentProtocolOrProbe()

        #expect(detected == .v1)
        #expect(manager.serverProtocol == nil)
        #expect(probing.calls == 1)
    }

    @Test func currentProtocolOrProbe_missingConnectionReturnsV1WithoutProbing() async {
        let probing = CountingProbe(result: .success(.v2))
        let manager = ActiveConnectionManager(connectionProbing: probing)

        let detected = await manager.currentProtocolOrProbe()

        #expect(detected == .v1)
        #expect(probing.calls == 0)
    }

    private var sampleProfile: ConnectionProfile {
        ConnectionProfile(
            baseURL: "http://127.0.0.1:4096/",
            username: "opencode",
            password: "x"
        )
    }
}

private final class CountingProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private let result: Result<ServerProtocol, Error>
    private let delayNanoseconds: UInt64
    private var _calls = 0

    var calls: Int {
        lock.lock()
        defer { lock.unlock() }
        return _calls
    }

    init(result: Result<ServerProtocol, Error>, delayNanoseconds: UInt64 = 0) {
        self.result = result
        self.delayNanoseconds = delayNanoseconds
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        lock.lock()
        _calls += 1
        lock.unlock()
        if delayNanoseconds > 0 {
            try await Task.sleep(nanoseconds: delayNanoseconds)
        }
        return try result.get()
    }
}
