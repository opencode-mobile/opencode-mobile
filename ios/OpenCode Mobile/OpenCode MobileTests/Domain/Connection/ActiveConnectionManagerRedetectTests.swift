import Foundation
import Testing
@testable import OpenCode_Mobile

/// A server replaced at the same address by the other opencode protocol (#828).
struct ActiveConnectionManagerRedetectTests {
    @Test func redetectProtocol_movesSavedV1ProfileToV2AndSavesIt() async {
        let store = RecordingProfileStore()
        try? await store.saveProfile(sampleProfile)
        let manager = ActiveConnectionManager(connectionProbing: FixedProbe(result: .success(.v2)), profileStore: store)
        manager.apply(sampleProfile, serverProtocol: .v1)

        let result = await manager.redetectProtocol()

        #expect(result == .detected(.v2))
        #expect(manager.serverProtocol == .v2)
        let saved = await store.waitForSave { $0.serverProtocol == .v2 }
        #expect(saved?.baseURL == sampleProfile.baseURL)
        #expect(saved?.id == sampleProfile.id)
    }

    @Test func redetectProtocol_movesSavedV2ProfileBackToV1() async {
        let store = RecordingProfileStore()
        try? await store.saveProfile(sampleProfile)
        let manager = ActiveConnectionManager(connectionProbing: FixedProbe(result: .success(.v1)), profileStore: store)
        manager.apply(sampleProfile, serverProtocol: .v2)

        #expect(await manager.redetectProtocol() == .detected(.v1))
        #expect(await store.waitForSave { $0.serverProtocol == .v1 } != nil)
    }

    @Test func redetectProtocol_noAnswerKeepsSavedProtocol() async {
        let store = RecordingProfileStore()
        let manager = ActiveConnectionManager(
            connectionProbing: FixedProbe(result: .failure(ServerProbeError.transport("refused"))),
            profileStore: store
        )
        manager.apply(sampleProfile, serverProtocol: .v1)

        #expect(await manager.redetectProtocol() == .unreachable)
        #expect(manager.serverProtocol == .v1)
        #expect(store.saved.isEmpty)
    }

    @Test func redetectProtocol_rejectedCredentialIsNotAnAnswer() async {
        let manager = ActiveConnectionManager(
            connectionProbing: FixedProbe(result: .failure(ServerProbeError.http(statusCode: 401, message: nil)))
        )
        manager.apply(sampleProfile, serverProtocol: .v1)

        #expect(await manager.redetectProtocol() == .skipped)
        #expect(manager.serverProtocol == .v1)
    }

    @Test func redetectProtocol_withoutConnectionIsSkipped() async {
        let manager = ActiveConnectionManager(connectionProbing: FixedProbe(result: .success(.v2)))

        #expect(await manager.redetectProtocol() == .skipped)
    }

    private var sampleProfile: ConnectionProfile {
        ConnectionProfile(
            baseURL: "http://127.0.0.1:4096/",
            username: "opencode",
            password: "token",
            id: "profile-1",
            name: "Mac"
        )
    }
}

private struct FixedProbe: ConnectionProbing {
    let result: Result<ServerProtocol, Error>

    func probe(connection _: ServerConnection) async throws -> ServerProtocol {
        try result.get()
    }
}

private final class RecordingProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private let lock = NSLock()
    private var _saved: [ConnectionProfile] = []

    var saved: [ConnectionProfile] {
        lock.lock()
        defer { lock.unlock() }
        return _saved
    }

    /// The manager saves off its lock in a detached task; wait for the matching write.
    func waitForSave(where matches: (ConnectionProfile) -> Bool) async -> ConnectionProfile? {
        for _ in 0 ..< 200 {
            if let match = saved.last(where: matches) { return match }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return nil
    }

    func activeProfile() async -> ConnectionProfile? { saved.last }

    func saveProfile(_ profile: ConnectionProfile) async throws {
        lock.lock()
        _saved.append(profile)
        lock.unlock()
    }

    func addedWorktrees(serverBaseURL _: String) async -> [String] { [] }
    func addWorktree(serverBaseURL _: String, worktree _: String) async {}
    func removeWorktree(serverBaseURL _: String, worktree _: String) async {}
    func setAddedWorktrees(serverBaseURL _: String, worktrees _: [String]) async {}

    func clearAll() async {
        lock.lock()
        _saved.removeAll()
        lock.unlock()
    }
}

@Suite struct ActiveConnectionManagerProbeIsolationTests {
    private func profile(_ host: String, id: String) -> ConnectionProfile {
        ConnectionProfile(baseURL: "http://\(host):4096/", username: "opencode", password: "test-password", id: id, name: id)
    }

    @Test @MainActor func lateFailureFromTheOldServerDoesNotMarkTheNewOneFailed() async throws {
        let probe = HeldHostProbe()
        let manager = ActiveConnectionManager(connectionProbing: probe)
        manager.apply(profile("192.0.2.1", id: "a"))
        let old = Task { try await manager.probeActive() }
        await probe.waitForCall(host: "192.0.2.1")

        manager.apply(profile("192.0.2.2", id: "b"))
        probe.answer(host: "192.0.2.1", with: .failure(URLError(.cannotConnectToHost)))
        _ = try? await old.value

        probe.answer(host: "192.0.2.2", with: .success(.v2))
        #expect(await manager.currentProtocolOrProbe() == .v2)
        #expect(manager.serverProtocol == .v2)
    }

    @Test @MainActor func lateSuccessFromTheOldServerDoesNotStampTheNewOne() async throws {
        let probe = HeldHostProbe()
        let manager = ActiveConnectionManager(connectionProbing: probe)
        manager.apply(profile("192.0.2.1", id: "a"))
        let old = Task { try await manager.probeActive() }
        await probe.waitForCall(host: "192.0.2.1")

        manager.apply(profile("192.0.2.2", id: "b"))
        probe.answer(host: "192.0.2.1", with: .success(.v2))
        _ = try? await old.value

        #expect(manager.serverProtocol == nil)
    }

    @Test @MainActor func fullRedetectionNeverUsesTheSavedProtocolShortcut() async throws {
        let probe = ConfirmHoldingProbe()
        let manager = ActiveConnectionManager(connectionProbing: probe)
        manager.apply(profile("192.0.2.1", id: "a"), serverProtocol: .v2)
        let confirming = Task { try await manager.probeActive() }
        await probe.confirmStarted.wait()

        // A confirmation is running: redetection must still do its own full detection.
        #expect(await manager.redetectProtocol() == .detected(.v1))
        #expect(probe.fullProbes.value == 1)
        probe.releaseConfirm.fire()
        _ = try? await confirming.value
    }

    @Test @MainActor func joinerOfASupersededProbeAsksTheNewServerInsteadOfFallingBackToV1() async throws {
        let probe = HeldHostProbe()
        let manager = ActiveConnectionManager(connectionProbing: probe)
        manager.apply(profile("192.0.2.1", id: "a"))
        let joiner = Task { await manager.currentProtocolOrProbe() }
        await probe.waitForCall(host: "192.0.2.1")

        probe.answer(host: "192.0.2.2", with: .success(.v2))
        manager.apply(profile("192.0.2.2", id: "b"))

        #expect(await joiner.value == .v2)
    }
}

/// Each host's probe waits until the test supplies its answer.
final class HeldHostProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var answers: [String: Result<ServerProtocol, Error>] = [:]
    private var pending: [String: [UUID: CheckedContinuation<Result<ServerProtocol, Error>, Never>]] = [:]
    private var called: Set<String> = []
    private var callWaiters: [String: [CheckedContinuation<Void, Never>]] = [:]

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        let host = connection.baseURL.host ?? ""
        let id = UUID()
        // Like URLSession, a cancelled probe ends at once instead of waiting for its answer.
        let result = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                lock.lock()
                called.insert(host)
                let waiters = callWaiters.removeValue(forKey: host) ?? []
                if let answer = answers[host] {
                    lock.unlock()
                    continuation.resume(returning: answer)
                } else if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(returning: .failure(CancellationError()))
                } else {
                    pending[host, default: [:]][id] = continuation
                    lock.unlock()
                }
                waiters.forEach { $0.resume() }
            }
        } onCancel: {
            lock.lock()
            let waiting = pending[host]?.removeValue(forKey: id)
            lock.unlock()
            waiting?.resume(returning: .failure(CancellationError()))
        }
        return try result.get()
    }

    func waitForCall(host: String) async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if called.contains(host) {
                lock.unlock()
                continuation.resume()
            } else {
                callWaiters[host, default: []].append(continuation)
                lock.unlock()
            }
        }
    }

    func answer(host: String, with result: Result<ServerProtocol, Error>) {
        lock.lock()
        answers[host] = result
        let waiting = pending.removeValue(forKey: host) ?? [:]
        lock.unlock()
        waiting.values.forEach { $0.resume(returning: result) }
    }
}

/// A confirmation waits for `releaseConfirm`; a full detection answers v1 at once.
private final class ConfirmHoldingProbe: ConnectionProbing, @unchecked Sendable {
    let confirmStarted = GateSignal()
    let releaseConfirm = GateSignal()
    let fullProbes = Locked(0)

    func probe(connection _: ServerConnection) async throws -> ServerProtocol {
        fullProbes.mutate { $0 += 1 }
        return .v1
    }

    func confirmServer(connection _: ServerConnection, expected: ServerProtocol) async throws -> ServerProbeOutcome {
        confirmStarted.fire()
        await releaseConfirm.wait()
        return ServerProbeOutcome(serverProtocol: expected, serverVersion: nil)
    }
}
