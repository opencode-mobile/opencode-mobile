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

    @Test func currentProtocolOrProbe_cancelledProbeDoesNotLatchMiss() async {
        let probing = CancelThenSucceedProbe()
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        let first = await manager.currentProtocolOrProbe()
        let second = await manager.currentProtocolOrProbe()

        #expect(first == .v1)
        #expect(second == .v2)
        #expect(manager.serverProtocol == .v2)
        #expect(probing.calls == 2)
    }

    @Test func currentProtocolOrProbe_failedProbeDoesNotReprobeUntilForced() async {
        let probing = CountingProbe(result: .failure(ServerProbeError.transport("unreachable")))
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        _ = await manager.currentProtocolOrProbe()
        _ = await manager.currentProtocolOrProbe()
        #expect(probing.calls == 1)

        do {
            _ = try await manager.probeActive()
            Issue.record("expected forced probe to throw")
        } catch {
            #expect(probing.calls == 2)
        }
    }

    @Test func protocolHint_defaultsToV1WhenUnknown() {
        let manager = ActiveConnectionManager()
        manager.apply(sampleProfile)
        #expect(manager.protocolHint() == .v1)
        manager.apply(sampleProfile, serverProtocol: .v2)
        #expect(manager.protocolHint() == .v2)
    }

    @Test func resetProbeFailure_allowsImplicitProbeAgain() async {
        let probing = CountingProbe(result: .failure(ServerProbeError.transport("unreachable")))
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        _ = await manager.currentProtocolOrProbe()
        #expect(probing.calls == 1)
        manager.resetProbeFailure()
        _ = await manager.currentProtocolOrProbe()
        #expect(probing.calls == 2)
    }

    @Test func currentProtocolOrProbe_missingConnectionReturnsV1WithoutProbing() async {
        let probing = CountingProbe(result: .success(.v2))
        let manager = ActiveConnectionManager(connectionProbing: probing)

        let detected = await manager.currentProtocolOrProbe()

        #expect(detected == .v1)
        #expect(probing.calls == 0)
    }

    @Test func currentProtocolOrProbe_doesNotStampProtocolOntoReplacedConnection() async {
        let probing = GatedProbe(result: .v2)
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        let original = Task { await manager.currentProtocolOrProbe() }
        await probing.waitUntilStarted()
        manager.apply(
            ConnectionProfile(
                baseURL: "http://10.0.0.1:4096/",
                username: "opencode",
                password: "y"
            )
        )
        probing.release()

        let originalResult = await original.value
        #expect(originalResult == .v1)
        #expect(manager.serverProtocol == nil)

        let replacement = await manager.currentProtocolOrProbe()
        #expect(replacement == .v2)
        #expect(manager.serverProtocol == .v2)
        #expect(probing.calls == 2)
    }

    @Test func ensureLoaded_rechoosesTailscaleAndKeepsProfile() async throws {
        let lan = "http://192.168.1.2:49374/"
        let tailscale = "http://100.64.0.3:49374/"
        let defaults = UserDefaults(suiteName: "ActiveConnectionManagerProtocolTests.\(UUID().uuidString)")!
        let store = UserDefaultsConnectionProfileStore(
            defaults: defaults,
            secureCredentialStore: InMemoryAddressProbeCredentialStore()
        )
        try await store.saveProfile(
            ConnectionProfile(
                baseURL: lan,
                serverURLs: [lan, tailscale],
                username: "opencode",
                password: "token",
                id: "profile-1",
                name: "Mac"
            )
        )
        let probing = DelayedTailscaleProbe()
        let manager = ActiveConnectionManager(connectionProbing: probing, profileStore: store)

        await manager.ensureLoaded(from: store)

        #expect(Set(probing.probed) == Set([lan, tailscale]))
        #expect(manager.connection?.baseURL.absoluteString == tailscale)
        let loaded = await store.activeProfile()
        #expect(loaded?.id == "profile-1")
        #expect(loaded?.baseURL == tailscale)
        #expect(loaded?.serverURLs == [lan, tailscale])
    }

    @Test func currentProtocolOrProbe_doesNotStampProtocolAfterDisconnect() async {
        let probing = GatedProbe(result: .v2)
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile)

        let original = Task { await manager.currentProtocolOrProbe() }
        await probing.waitUntilStarted()
        manager.disconnect()
        probing.release()

        let originalResult = await original.value
        #expect(originalResult == .v1)
        #expect(manager.connection == nil)
        #expect(manager.serverProtocol == nil)
        #expect(probing.calls == 1)
    }

    @Test func probeActive_keepsTheServerVersionUntilTheProfileChanges() async throws {
        let manager = ActiveConnectionManager(connectionProbing: VersionedProbe())
        manager.apply(sampleProfile)
        #expect(manager.serverVersion == nil)

        _ = try await manager.probeActive()
        #expect(manager.serverVersion == "1.19.1")

        var other = sampleProfile
        other.id = "other-profile"
        manager.apply(other)
        #expect(manager.serverVersion == nil)

        _ = try await manager.probeActive()
        manager.disconnect()
        #expect(manager.serverVersion == nil)
    }

    private var sampleProfile: ConnectionProfile {
        ConnectionProfile(
            baseURL: "http://127.0.0.1:4096/",
            username: "opencode",
            password: "x"
        )
    }
}

private final class CancelThenSucceedProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls = 0

    var calls: Int {
        lock.lock()
        defer { lock.unlock() }
        return _calls
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        lock.lock()
        _calls += 1
        let call = _calls
        lock.unlock()
        if call == 1 {
            throw CancellationError()
        }
        return .v2
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

private final class GatedProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private let result: ServerProtocol
    private var startContinuation: CheckedContinuation<Void, Never>?
    private var releaseContinuation: CheckedContinuation<Void, Never>?
    private var started = false
    private var released = false
    private var _calls = 0

    var calls: Int {
        lock.lock()
        defer { lock.unlock() }
        return _calls
    }

    init(result: ServerProtocol) {
        self.result = result
    }

    func waitUntilStarted() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if started {
                lock.unlock()
                continuation.resume()
            } else {
                startContinuation = continuation
                lock.unlock()
            }
        }
    }

    func release() {
        lock.lock()
        released = true
        let continuation = releaseContinuation
        releaseContinuation = nil
        lock.unlock()
        continuation?.resume()
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        lock.lock()
        _calls += 1
        started = true
        let startContinuation = startContinuation
        self.startContinuation = nil
        let alreadyReleased = released
        lock.unlock()
        startContinuation?.resume()

        if alreadyReleased {
            return result
        }

        await withCheckedContinuation { continuation in
            lock.lock()
            if released {
                lock.unlock()
                continuation.resume()
            } else {
                releaseContinuation = continuation
                lock.unlock()
            }
        }
        return result
    }
}

private final class DelayedTailscaleProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var urls: [String] = []

    var probed: [String] {
        lock.lock()
        defer { lock.unlock() }
        return urls
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        let url = connection.baseURL.absoluteString
        lock.lock()
        urls.append(url)
        lock.unlock()
        if url.contains("100.64") {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return .v2
    }
}

private final class InMemoryAddressProbeCredentialStore: SecureCredentialStore, @unchecked Sendable {
    private var passwords: [String: String] = [:]

    func readPassword(serverBaseURL: String) -> String? {
        passwords[serverBaseURL]
    }

    func writePassword(serverBaseURL: String, password: String) {
        passwords[serverBaseURL] = password
    }

    func deletePassword(serverBaseURL: String) {
        passwords.removeValue(forKey: serverBaseURL)
    }

    func clearAll() {
        passwords.removeAll()
    }
}

private struct VersionedProbe: ConnectionProbing {
    func probe(connection: ServerConnection) async throws -> ServerProtocol { .v1 }

    func probeServer(connection: ServerConnection) async throws -> ServerProbeOutcome {
        ServerProbeOutcome(serverProtocol: .v1, serverVersion: "1.19.1")
    }
}
