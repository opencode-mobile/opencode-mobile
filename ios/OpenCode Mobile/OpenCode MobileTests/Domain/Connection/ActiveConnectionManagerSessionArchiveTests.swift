import Foundation
import Testing
@testable import OpenCode_Mobile

struct ActiveConnectionManagerSessionArchiveTests {
    @Test func v2ServerDeclaringArchiveEnablesArchive() async throws {
        let probing = ArchiveProbe(protocol: .v2) { _ in true }
        let manager = connected(probing)

        #expect(!manager.capabilities.supportsSessionArchive)
        _ = try await manager.probeActive()

        #expect(manager.capabilities.supportsSessionArchive)
        #expect(probing.archiveProbes == 1)
    }

    @Test func v2ServerDeclaringTurnDiffEnablesLastTurn() async throws {
        let manager = connected(ArchiveProbe(protocol: .v2) { _ in true })

        #expect(!manager.capabilities.supportsSessionMessageDiff)
        _ = try await manager.probeActive()

        #expect(manager.capabilities.supportsSessionMessageDiff)
    }

    @Test func olderV2ServerKeepsArchiveHidden() async throws {
        let manager = connected(ArchiveProbe(protocol: .v2) { _ in false })

        _ = try await manager.probeActive()

        #expect(!manager.capabilities.supportsSessionArchive)
        #expect(!manager.capabilities.supportsSessionMessageDiff)
    }

    @Test func unreadableContractKeepsArchiveHiddenAndRetriesNextProbe() async throws {
        let probing = ArchiveProbe(protocol: .v2) { _ in true }
        probing.fail = true
        let manager = connected(probing)

        _ = try await manager.probeActive()
        #expect(!manager.capabilities.supportsSessionArchive)

        probing.fail = false
        _ = try await manager.probeActive()
        #expect(manager.capabilities.supportsSessionArchive)
        #expect(probing.archiveProbes == 2)
    }

    @Test func sameServerVersionReadsContractOnce() async throws {
        let probing = ArchiveProbe(protocol: .v2) { _ in true }
        let manager = connected(probing)

        _ = try await manager.probeActive()
        _ = try await manager.probeActive()

        #expect(probing.archiveProbes == 1)
        #expect(manager.capabilities.supportsSessionArchive)
    }

    @Test func upgradedServerReadsContractAgain() async throws {
        let probing = ArchiveProbe(protocol: .v2) { $0 == "2.1.0" }
        let manager = connected(probing)

        probing.version = "2.0.31"
        _ = try await manager.probeActive()
        #expect(!manager.capabilities.supportsSessionArchive)

        probing.version = "2.1.0"
        _ = try await manager.probeActive()
        #expect(manager.capabilities.supportsSessionArchive)
        #expect(probing.archiveProbes == 2)
    }

    @Test func v1ServerKeepsArchiveWithoutReadingContract() async throws {
        let probing = ArchiveProbe(protocol: .v1) { _ in false }
        let manager = connected(probing)

        _ = try await manager.probeActive()

        #expect(manager.capabilities.supportsSessionArchive)
        #expect(probing.archiveProbes == 0)
    }

    @Test func switchingServerForgetsArchiveSupport() async throws {
        let manager = connected(ArchiveProbe(protocol: .v2) { _ in true })
        _ = try await manager.probeActive()

        var other = sampleProfile
        other.id = "other-profile"
        manager.apply(other, serverProtocol: .v2)
        #expect(!manager.capabilities.supportsSessionArchive)

        _ = try await manager.probeActive()
        manager.disconnect()
        manager.apply(sampleProfile, serverProtocol: .v2)
        #expect(!manager.capabilities.supportsSessionArchive)
    }

    /// Starts from a profile stamped v2, as after an earlier session, so archive waits on the contract.
    private func connected(_ probing: ArchiveProbe) -> ActiveConnectionManager {
        let manager = ActiveConnectionManager(connectionProbing: probing)
        manager.apply(sampleProfile, serverProtocol: .v2)
        return manager
    }

    private var sampleProfile: ConnectionProfile {
        ConnectionProfile(
            baseURL: "http://127.0.0.1:4096/",
            username: "opencode",
            password: "x"
        )
    }
}

private final class ArchiveProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private let serverProtocol: ServerProtocol
    private let archive: @Sendable (String?) -> Bool
    private var _version: String? = "2.1.0"
    private var _fail = false
    private var _archiveProbes = 0

    init(protocol serverProtocol: ServerProtocol, archive: @escaping @Sendable (String?) -> Bool) {
        self.serverProtocol = serverProtocol
        self.archive = archive
    }

    var version: String? {
        get { lock.withLock { _version } }
        set { lock.withLock { _version = newValue } }
    }

    var fail: Bool {
        get { lock.withLock { _fail } }
        set { lock.withLock { _fail = newValue } }
    }

    var archiveProbes: Int { lock.withLock { _archiveProbes } }

    func probe(connection _: ServerConnection) async throws -> ServerProtocol { serverProtocol }

    func probeServer(connection _: ServerConnection) async throws -> ServerProbeOutcome {
        ServerProbeOutcome(serverProtocol: serverProtocol, serverVersion: version)
    }

    func probeV2Features(connection _: ServerConnection) async throws -> V2ServerFeatures {
        let (fail, version) = lock.withLock {
            _archiveProbes += 1
            return (_fail, _version)
        }
        if fail { throw ServerProbeError.http(statusCode: 404, message: "Not Found") }
        let supported = archive(version)
        return V2ServerFeatures(sessionArchive: supported, sessionTurnDiff: supported)
    }
}
