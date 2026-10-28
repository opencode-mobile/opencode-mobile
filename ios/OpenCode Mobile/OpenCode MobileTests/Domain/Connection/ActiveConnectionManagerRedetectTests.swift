import Foundation
import Testing
@testable import OpenCode_Mobile

/// A server replaced at the same address by the other opencode protocol (#828).
struct ActiveConnectionManagerRedetectTests {
    @Test func redetectProtocol_movesSavedV1ProfileToV2AndSavesIt() async {
        let store = RecordingProfileStore()
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
