import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ConnectionSessionCoordinatorTests {
    @Test @MainActor func clearConnectionData_clearsModelSelectionCache() async {
        let store = InMemorySessionModelSelectionStore()
        let draftStore = InMemoryComposerDraftStore()
        await store.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil)
        )
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: InMemoryConnectionProfileStore(),
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: store,
            composerDraftStore: draftStore
        )
        draftStore.save(
            connectionIdentity: ModelSelectionConnectionIdentity(
                serverBaseURL: "http://127.0.0.1:4096/",
                username: "opencode"
            ),
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "draft")
        )

        await coordinator.clearConnectionData()

        #expect(await store.load(serverBaseURL: "http://127.0.0.1:4096/", worktree: "/repo", sessionID: "ses-1") == nil)
        #expect(
            draftStore.load(
                connectionIdentity: ModelSelectionConnectionIdentity(
                    serverBaseURL: "http://127.0.0.1:4096/",
                    username: "opencode"
                ),
                worktree: "/repo",
                sessionID: "ses-1"
            ) == nil
        )
    }

    @Test @MainActor func removeProfile_purgesCacheDraftsAndModelSelectionsOfThatServerOnly() async throws {
        let suite = "ConnectionSessionCoordinatorTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let profileStore = UserDefaultsConnectionProfileStore(
            defaults: defaults,
            secureCredentialStore: InMemorySecureCredentialStore()
        )
        let modelStore = UserDefaultsSessionModelSelectionStore(defaults: defaults)
        let draftStore = UserDefaultsComposerDraftStore(defaults: defaults)
        let cacheStore = InMemorySessionCacheStore()
        let manager = ActiveConnectionManager(profileStore: profileStore)
        let cache = DefaultSessionCache(store: cacheStore, connectionIdProvider: { manager.currentProfile()?.id })
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: profileStore,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: modelStore,
            composerDraftStore: draftStore,
            sessionCache: { cache }
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.1:4096/", username: "opencode", password: "a", name: "A")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.2:4096/", username: "opencode", password: "b", name: "B")
        )
        let profiles = await profileStore.listProfiles()
        let a = try #require(profiles.first { $0.name == "A" })
        let b = try #require(profiles.first { $0.name == "B" })
        let identityA = ModelSelectionConnectionIdentity(serverBaseURL: a.baseURL, username: a.username)
        let identityB = ModelSelectionConnectionIdentity(serverBaseURL: b.baseURL, username: b.username)
        let selection = SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil)
        await modelStore.saveSession(connectionIdentity: identityA, worktree: "/repo", sessionID: "ses-1", selection: selection)
        await modelStore.saveNewSessionPreference(connectionIdentity: identityA, worktree: "/repo", selection: selection)
        await modelStore.saveSession(connectionIdentity: identityB, worktree: "/repo", sessionID: "ses-2", selection: selection)
        draftStore.save(connectionIdentity: identityA, worktree: "/repo", sessionID: "ses-1", draft: ComposerDraft(text: "a"))
        draftStore.save(connectionIdentity: identityB, worktree: "/repo", sessionID: "ses-2", draft: ComposerDraft(text: "b"))
        for (connectionId, worktree) in [(a.id, "/repo"), (a.id, "/other"), (b.id, "/repo")] {
            await cacheStore.commit(
                key: SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: .sessionList),
                value: SessionCacheValue(payloadJSON: "[]", schemaVersion: 1),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: 1)
            )
        }

        await coordinator.removeProfile(id: a.id)

        #expect(await modelStore.loadSession(connectionIdentity: identityA, worktree: "/repo", sessionID: "ses-1") == nil)
        #expect(await modelStore.loadNewSessionPreference(connectionIdentity: identityA, worktree: "/repo") == nil)
        #expect(await modelStore.loadSession(connectionIdentity: identityB, worktree: "/repo", sessionID: "ses-2") != nil)
        #expect(draftStore.load(connectionIdentity: identityA, worktree: "/repo", sessionID: "ses-1") == nil)
        #expect(draftStore.load(connectionIdentity: identityB, worktree: "/repo", sessionID: "ses-2")?.text == "b")
        let remainingA = await cacheStore.report(.connection(connectionIdOrUrlPrefix: a.id, matchByUrlPrefix: false))
        let remainingB = await cacheStore.report(.connection(connectionIdOrUrlPrefix: b.id, matchByUrlPrefix: false))
        #expect(remainingA.entryCount == 0)
        #expect(remainingB.entryCount == 1)
        #expect(await profileStore.listProfiles().map(\.name) == ["B"])
    }

    @Test @MainActor func clearConnectionData_deletesTheFeedbackToken() async {
        let tokens = InMemoryFeedbackRefreshTokenStore()
        tokens.write("refresh-token")
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: InMemoryConnectionProfileStore(),
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            composerDraftStore: InMemoryComposerDraftStore(),
            feedbackRefreshTokenStore: tokens
        )

        await coordinator.clearConnectionData()

        #expect(tokens.read() == nil)
    }

    @Test @MainActor func saveProfile_reportsMovedWhenAnotherSavedAddressWins() async throws {
        let lan = "http://192.168.1.2:4096/"
        let tailscale = "http://100.64.0.3:4096/"
        let store = CatalogConnectionProfileStore()
        let manager = ActiveConnectionManager(connectionProbing: AnsweringProbe(), profileStore: store)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            hasActiveConnection: { manager.connection != nil }
        )

        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: lan, serverURLs: [lan, tailscale], username: "opencode", password: "", name: "Mac")
        )

        #expect(await eventually { coordinator.addressCheck.state == .moved })
        #expect(coordinator.addressCheck.generation == coordinator.profileGeneration)
        #expect(manager.connection?.baseURL.absoluteString == tailscale)
    }

    @Test @MainActor func saveProfile_reportsKeptWhenThereIsNoOtherAddress() async throws {
        let lan = "http://192.168.1.2:4096/"
        let store = CatalogConnectionProfileStore()
        let manager = ActiveConnectionManager(connectionProbing: AnsweringProbe(), profileStore: store)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            hasActiveConnection: { manager.connection != nil }
        )

        try await coordinator.saveProfile(ConnectionProfile(baseURL: lan, username: "opencode", password: "", name: "Mac"))

        #expect(coordinator.addressCheck == AddressCheck(generation: coordinator.profileGeneration, state: .kept))
        #expect(manager.connection?.baseURL.absoluteString == lan)
    }

    @Test @MainActor func registration_stopsDeliveringProfileSavesWhenCancelledOrReleased() async throws {
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: InMemoryConnectionProfileStore(),
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            composerDraftStore: InMemoryComposerDraftStore()
        )
        var cancelledSaves = 0
        var releasedSaves = 0
        let cancelled = coordinator.onProfileSaved { cancelledSaves += 1 }
        var released: ConnectionSessionCoordinator.Registration? = coordinator.onProfileSaved { releasedSaves += 1 }

        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "test-password", name: "A")
        )
        #expect(cancelledSaves == 1)
        #expect(releasedSaves == 1)

        cancelled.cancel()
        #expect(released != nil)
        released = nil
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://127.0.0.1:4097/", username: "opencode", password: "test-password", name: "B")
        )
        // Neither a cancelled registration nor a released one leaves its handler behind.
        #expect(cancelledSaves == 1)
        #expect(releasedSaves == 1)
    }

    @Test @MainActor func profileSavesDeliverHandlersOnTheMainActor() async throws {
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: InMemoryConnectionProfileStore(),
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            composerDraftStore: InMemoryComposerDraftStore()
        )
        var delivered = false
        let registration = coordinator.onProfileSaved {
            // Traps if profile-change work runs off the main thread, which UI handlers assume.
            MainActor.assertIsolated()
            delivered = true
        }
        defer { withExtendedLifetime(registration) {} }

        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "test-password", name: "A")
        )
        #expect(delivered)
    }

    @Test @MainActor func applyProfileChange_ordersStopCachesRebindSignalStart() async throws {
        let log = TransactionLog()
        let store = CatalogConnectionProfileStore()
        let manager = ActiveConnectionManager(profileStore: store)
        let stream = DelayedRecordingStream(log: log)
        let sync = RecordingSyncCoordinator(log: log)
        let caches = RecordingProjectionCaches(log: log)
        let world = FakeWorldSignals()
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            eventStream: { stream },
            syncCoordinator: { sync },
            projectionCaches: { caches },
            bindReadyProfile: { id in log.append("bind:\(id ?? "nil")") },
            occupancy: { world.occupancy },
            emitProfileSwitched: {
                log.append("profileSwitched")
                world.emitProfileSwitched()
            },
            hasActiveConnection: { manager.connection != nil || manager.currentProfile() != nil }
        )
        var savedCount = 0
        let savedRegistration = coordinator.onProfileSaved {
            log.append("uiSaved")
            savedCount += 1
        }
        defer { withExtendedLifetime(savedRegistration) {} }
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.1:4096/", username: "opencode", password: "a", name: "A")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.2:4096/", username: "opencode", password: "b", name: "B")
        )
        let profiles = await store.listProfiles()
        let profileA = profiles.first { $0.name == "A" }!
        log.reset()
        savedCount = 0
        world.setOccupancy(.active)
        stream.start()
        log.reset()

        await coordinator.switchProfile(id: profileA.id)

        #expect(savedCount == 1)
        #expect(coordinator.currentProfile()?.id == profileA.id)
        #expect(coordinator.profileGeneration >= 1)
        let steps = log.snapshot()
        #expect(steps.contains("stop"))
        #expect(steps.contains("beginSwitch"))
        #expect(steps.contains("clear"))
        #expect(steps.contains("bind:\(profileA.id)"))
        #expect(steps.contains("profileSwitched"))
        #expect(steps.contains("start"))
        #expect(steps.contains("uiSaved"))
        #expect(index(of: "stop", in: steps) < index(of: "clear", in: steps))
        #expect(index(of: "clear", in: steps) < index(of: "bind:\(profileA.id)", in: steps))
        #expect(index(of: "bind:\(profileA.id)", in: steps) < index(of: "profileSwitched", in: steps))
        #expect(index(of: "profileSwitched", in: steps) < index(of: "start", in: steps))
        #expect(index(of: "start", in: steps) < index(of: "uiSaved", in: steps))
        #expect(steps.filter { $0 == "profileSwitched" }.count == 1)
        #expect(steps.filter { $0 == "stop" }.count == 1)
        #expect(steps.filter { $0 == "start" }.count == 1)
        #expect(world.occupancy == .active)
    }

    @Test @MainActor func concurrentSwitchProfile_serializesAndEndsOnLast() async throws {
        let log = TransactionLog()
        let store = CatalogConnectionProfileStore()
        let manager = ActiveConnectionManager(profileStore: store)
        let stream = DelayedRecordingStream(log: log, stopDelayMs: 40)
        let sync = RecordingSyncCoordinator(log: log)
        let caches = RecordingProjectionCaches(log: log)
        let switched = Locked(0)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            eventStream: { stream },
            syncCoordinator: { sync },
            projectionCaches: { caches },
            occupancy: { .active },
            emitProfileSwitched: { switched.mutate { $0 += 1 } },
            hasActiveConnection: { true }
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.1:4096/", username: "opencode", password: "a", name: "A")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.2:4096/", username: "opencode", password: "b", name: "B")
        )
        let profiles = await store.listProfiles()
        let profileA = profiles.first { $0.name == "A" }!
        let profileB = profiles.first { $0.name == "B" }!
        switched.value = 0

        async let first: Void = coordinator.switchProfile(id: profileA.id)
        try? await Task.sleep(for: .milliseconds(10))
        async let second: Void = coordinator.switchProfile(id: profileB.id)
        await first
        await second

        #expect(coordinator.currentProfile()?.id == profileB.id)
        #expect(switched.value == 2)
    }

    @Test @MainActor func switchBindsTheNewServerBeforeClearingProjections() async throws {
        let log = TransactionLog()
        let store = CatalogConnectionProfileStore()
        let manager = ActiveConnectionManager(profileStore: store)
        let caches = RecordingProjectionCaches(log: log)
        caches.onClear = { log.append("clear-on:\(manager.connection?.baseURL.absoluteString ?? "none")") }
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            projectionCaches: { caches }
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.1:4096/", username: "opencode", password: "test-password", name: "A")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.2:4096/", username: "opencode", password: "test-password", name: "B")
        )
        let a = try #require(await store.listProfiles().first { $0.name == "A" })
        log.reset()

        await coordinator.switchProfile(id: a.id)

        #expect(log.snapshot().contains("clear-on:http://192.0.2.1:4096/"))
    }

    @Test @MainActor func removingAnInactiveProfileLeavesTheActiveServerRunning() async throws {
        let log = TransactionLog()
        let store = CatalogConnectionProfileStore()
        let manager = ActiveConnectionManager(profileStore: store)
        let stream = DelayedRecordingStream(log: log)
        let caches = RecordingProjectionCaches(log: log)
        let purged = Locked<[String?]>([])
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore(),
            eventStream: { stream },
            projectionCaches: { caches },
            hasActiveConnection: { manager.connection != nil },
            purgeProfileState: { profile in purged.mutate { $0.append(profile?.id) } }
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.1:4096/", username: "opencode", password: "test-password", name: "A")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://192.0.2.2:4096/", username: "opencode", password: "test-password", name: "B")
        )
        let a = try #require(await store.listProfiles().first { $0.name == "A" })
        var listChanges = 0
        var saves = 0
        let listRegistration = coordinator.onProfileListChanged { listChanges += 1 }
        let saveRegistration = coordinator.onProfileSaved { saves += 1 }
        defer { withExtendedLifetime((listRegistration, saveRegistration)) {} }
        log.reset()

        await coordinator.removeProfile(id: a.id)

        #expect(log.snapshot().isEmpty)
        #expect(saves == 0)
        #expect(listChanges == 1)
        #expect(purged.value == [a.id])
        #expect(manager.connection?.baseURL.absoluteString == "http://192.0.2.2:4096/")
    }

    @Test @MainActor func savingAProfileDoesNotHoldASwitchBehindItsAddressCheck() async throws {
        let dead = "http://192.0.2.1:4096/"
        let other = "http://198.51.100.1:4096/"
        let healthy = "http://192.0.2.2:4096/"
        let store = CatalogConnectionProfileStore()
        let probe = HeldAddressProbe(winner: other)
        let manager = ActiveConnectionManager(connectionProbing: probe, profileStore: store)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore()
        )
        try await store.saveProfile(
            ConnectionProfile(baseURL: healthy, username: "opencode", password: "test-password", name: "Healthy")
        )
        let target = try #require(await store.activeProfile())
        let saving = Task {
            try await coordinator.saveProfile(ConnectionProfile(
                baseURL: dead, serverURLs: [dead, other], username: "opencode", password: "test-password", name: "Dead"
            ))
        }
        await probe.started.wait()

        let switching = Task { await coordinator.switchProfile(id: target.id) }
        let switched = await eventually { coordinator.currentProfile()?.id == target.id }
        probe.release.fire()
        try await saving.value
        await switching.value

        #expect(switched)
        // The stale check found a winner for the server we left; it must not rebind.
        #expect(manager.connection?.baseURL.absoluteString == healthy)
    }

    @Test @MainActor func removingTheActiveProfileDoesNotHoldASwitchBehindTheNextAddressCheck() async throws {
        let dead = "http://192.0.2.1:4096/"
        let other = "http://198.51.100.1:4096/"
        let healthy = "http://192.0.2.2:4096/"
        let removedURL = "http://192.0.2.3:4096/"
        let store = CatalogConnectionProfileStore()
        let probe = HeldAddressProbe(winner: other)
        let manager = ActiveConnectionManager(connectionProbing: probe, profileStore: store)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore()
        )
        try await store.saveProfile(
            ConnectionProfile(baseURL: healthy, username: "opencode", password: "test-password", name: "Healthy")
        )
        let target = try #require(await store.activeProfile())
        try await store.saveProfile(ConnectionProfile(
            baseURL: dead, serverURLs: [dead, other], username: "opencode", password: "test-password", name: "Dead"
        ))
        let next = try #require(await store.activeProfile())
        try await store.saveProfile(
            ConnectionProfile(baseURL: removedURL, username: "opencode", password: "test-password", name: "Removed")
        )
        let removed = try #require(await store.activeProfile())
        // Removing the last entry makes the one before it ("Dead") active.
        #expect(await store.listProfiles().last?.id == removed.id)
        #expect(next.id != removed.id)

        let removing = Task { await coordinator.removeProfile(id: removed.id) }
        await probe.started.wait()
        let switching = Task { await coordinator.switchProfile(id: target.id) }
        let switched = await eventually { coordinator.currentProfile()?.id == target.id }
        probe.release.fire()
        await removing.value
        await switching.value

        #expect(switched)
        #expect(manager.connection?.baseURL.absoluteString == healthy)
    }

    @Test @MainActor func lateAddressCheckDoesNotBringBackARemovedProfile() async throws {
        let lan = "http://192.0.2.1:4096/"
        let other = "http://198.51.100.1:4096/"
        let store = CatalogConnectionProfileStore()
        let probe = HeldAddressProbe(winner: other)
        let manager = ActiveConnectionManager(connectionProbing: probe, profileStore: store)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore()
        )
        try await store.saveProfile(
            ConnectionProfile(baseURL: lan, serverURLs: [lan, other], username: "opencode", password: "test-password", name: "A")
        )
        let a = try #require(await store.activeProfile())
        manager.apply(a)
        await probe.started.wait()

        await coordinator.removeProfile(id: a.id)
        probe.release.fire()
        await manager.awaitAddressResolution()
        for _ in 0 ..< 50 { await Task.yield() }

        #expect(await store.listProfiles().isEmpty)
        #expect(manager.connection == nil)
    }

    @Test @MainActor func detectedProtocolNeverRecreatesARemovedProfile() async throws {
        let store = CatalogConnectionProfileStore()

        let updated = await store.updateDetectedServer(
            id: "missing",
            baseURL: "http://192.0.2.1:4096/",
            serverProtocol: .v2
        )

        #expect(updated == false)
        #expect(await store.listProfiles().isEmpty)
    }
}

/// Answers health only for `winner`, and holds every answer until `release` fires.
private final class HeldAddressProbe: ConnectionProbing, @unchecked Sendable {
    let started = GateSignal()
    let release = GateSignal()
    private let winner: String

    init(winner: String) {
        self.winner = winner
    }

    func probe(connection: ServerConnection) async throws -> ServerProtocol {
        started.fire()
        await release.wait()
        guard connection.baseURL.absoluteString == winner else { throw URLError(.cannotConnectToHost) }
        return .v2
    }
}

private final class InMemoryComposerDraftStore: ComposerDraftStore, @unchecked Sendable {
    private var saved: [String: ComposerDraft] = [:]

    func load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) -> ComposerDraft? {
        saved["\(connectionIdentity.serverBaseURL)|\(worktree)|\(sessionID)"]
    }

    func save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        draft: ComposerDraft
    ) {
        saved["\(connectionIdentity.serverBaseURL)|\(worktree)|\(sessionID)"] = draft
    }

    func delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) {
        saved.removeValue(forKey: "\(connectionIdentity.serverBaseURL)|\(worktree)|\(sessionID)")
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) {
        saved.keys
            .filter { $0.hasPrefix(connectionIdentity.serverBaseURL) && $0.hasSuffix("|\(sessionID)") }
            .forEach { saved.removeValue(forKey: $0) }
    }

    func clearAll() {
        saved.removeAll()
    }

    @Test @MainActor func saveProfile_doesNotNotifyWhenEditingInactive() async throws {
        let store = CatalogConnectionProfileStore()
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: store,
            secureCredentialStore: InMemorySecureCredentialStore(),
            sessionModelSelectionStore: InMemorySessionModelSelectionStore()
        )
        var savedCount = 0
        let savedRegistration = coordinator.onProfileSaved { savedCount += 1 }
        defer { withExtendedLifetime(savedRegistration) {} }

        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.1:4096/", username: "opencode", password: "secret", name: "Home")
        )
        try await coordinator.saveProfile(
            ConnectionProfile(baseURL: "http://10.0.0.2:4096/", username: "opencode", password: "secret", name: "Work")
        )
        let home = await store.listProfiles().first { $0.name == "Home" }
        #expect(home != nil)
        savedCount = 0

        var edited = home!
        edited.name = "Studio"
        try await coordinator.saveProfile(edited)
        #expect(savedCount == 0)
        #expect(await store.activeProfile()?.name == "Work")
        #expect(await store.listProfiles().first { $0.id == home?.id }?.name == "Studio")
    }
}

private final class InMemorySessionModelSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private var saved: [String: SessionModelSelection] = [:]

    func load(serverBaseURL: String, worktree: String, sessionID: String) async -> SessionModelSelection? {
        saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)]
    }

    func save(serverBaseURL: String, worktree: String, sessionID: String, selection: SessionModelSelection) async {
        saved[key(serverBaseURL: serverBaseURL, worktree: worktree, sessionID: sessionID)] = selection
    }

    func loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async -> SessionModelSelection? {
        await load(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID)
    }

    func saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        await save(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID, selection: selection)
    }

    func deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionID: String
    ) async {
        saved.removeValue(forKey: key(serverBaseURL: connectionIdentity.serverBaseURL, worktree: worktree, sessionID: sessionID))
    }

    func deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionID: String
    ) async {
        saved.keys
            .filter { $0.hasPrefix(connectionIdentity.serverBaseURL) && $0.hasSuffix("|\(sessionID)") }
            .forEach { saved.removeValue(forKey: $0) }
    }

    func loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String
    ) async -> SessionModelSelection? { nil }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {}

    func clearAll() async {
        saved.removeAll()
    }

    private func key(serverBaseURL: String, worktree: String, sessionID: String) -> String {
        "\(serverBaseURL)|\(worktree)|\(sessionID)"
    }
}

private final class CatalogConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private var catalog = ConnectionProfileCatalog()

    func activeProfile() async -> ConnectionProfile? { catalog.activeProfile() }

    func listProfiles() async -> [ConnectionProfile] { catalog.profiles }

    func saveProfile(_ profile: ConnectionProfile) async throws {
        catalog = try catalog.save(profile)
    }

    func applyProfile(id: String) async {
        catalog = catalog.apply(id: id)
    }

    func removeProfile(id: String) async {
        catalog = catalog.remove(id: id)
    }

    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }

    func addWorktree(serverBaseURL: String, worktree: String) async {}

    func removeWorktree(serverBaseURL: String, worktree: String) async {}

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}

    func clearAll() async {
        catalog = ConnectionProfileCatalog()
    }
}

private struct AnsweringProbe: ConnectionProbing {
    func probe(connection _: ServerConnection) async throws -> ServerProtocol { .v2 }
}

@MainActor
private func eventually(_ condition: () -> Bool) async -> Bool {
    for _ in 0 ..< 300 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}

private final class InMemoryConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    func activeProfile() async -> ConnectionProfile? { nil }

    func saveProfile(_ profile: ConnectionProfile) async {}

    func addedWorktrees(serverBaseURL: String) async -> [String] { [] }

    func addWorktree(serverBaseURL: String, worktree: String) async {}

    func removeWorktree(serverBaseURL: String, worktree: String) async {}

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}

    func clearAll() async {}
}

private final class InMemorySecureCredentialStore: SecureCredentialStore, @unchecked Sendable {
    func readPassword(serverBaseURL: String) -> String? { nil }

    func writePassword(serverBaseURL: String, password: String) {}

    func deletePassword(serverBaseURL: String) {}

    func clearAll() {}
}

private final class TransactionLog: @unchecked Sendable {
    private let lock = NSLock()
    private var steps: [String] = []

    func append(_ step: String) {
        lock.lock()
        steps.append(step)
        lock.unlock()
    }

    func reset() {
        lock.lock()
        steps.removeAll()
        lock.unlock()
    }

    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return steps
    }
}

private func index(of step: String, in steps: [String]) -> Int {
    steps.firstIndex(of: step) ?? .max
}

private final class DelayedRecordingStream: LiveConnection, @unchecked Sendable {
    private let log: TransactionLog
    private let stopDelayMs: Int
    private let fake = FakeForegroundEventStream(connected: false)

    init(log: TransactionLog, stopDelayMs: Int = 0) {
        self.log = log
        self.stopDelayMs = stopDelayMs
    }

    var connectionState: EventStreamConnectionState { fake.connectionState }
    var snapshot: LiveConnectionSnapshot { fake.snapshot }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        fake.connectionStateUpdates()
    }

    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot> {
        fake.snapshotUpdates()
    }

    func events() -> AsyncStream<OpencodeEvent> { fake.events() }

    func start() {
        log.append("start")
        fake.start()
    }

    func stop() async {
        log.append("stop")
        if stopDelayMs > 0 {
            try? await Task.sleep(for: .milliseconds(stopDelayMs))
        }
        await fake.stop()
    }
}

private final class RecordingSyncCoordinator: SyncCoordinator, @unchecked Sendable {
    private let log: TransactionLog
    private(set) var profileGeneration: UInt64 = 0

    init(log: TransactionLog) {
        self.log = log
    }

    func beginProfileSwitch(generation: UInt64) {
        profileGeneration = generation
        log.append("beginSwitch")
    }

    func register(
        key _: ProjectionKey,
        loader _: @escaping @Sendable () async throws -> Void
    ) -> SyncRegistration {
        SyncRegistration(id: UUID(), key: .sessionList(directory: "/"))
    }

    func unregister(_: SyncRegistration) {}
    func observe(_: ProjectionKey) -> AsyncStream<ProjectionState> {
        AsyncStream { $0.finish() }
    }
    func ensureFresh(_: ProjectionKey) async {}
    func invalidate(_: ProjectionKey?) {}
    func bootstrap(_: SyncBootstrapReason) async {}
    func markFocused(_: ProjectionKey?) {}
    func visible(_: ProjectionKey, _: Bool) {}
}

@MainActor
private final class RecordingProjectionCaches: ProjectionCaching {
    private let log: TransactionLog
    var onClear: (() -> Void)?

    init(log: TransactionLog) {
        self.log = log
    }

    func clearAll() {
        log.append("clearAll")
        onClear?()
    }

    func clearLiveProjections() {
        log.append("clear")
        onClear?()
    }
}

private final class InMemoryFeedbackRefreshTokenStore: FeedbackRefreshTokenStore, @unchecked Sendable {
    private var token: String?
    func read() -> String? { token }
    func write(_ token: String) { self.token = token }
    func delete() { token = nil }
}
