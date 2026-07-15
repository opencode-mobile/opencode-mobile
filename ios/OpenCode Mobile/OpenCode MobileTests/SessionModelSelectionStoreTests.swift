import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionModelSelectionStoreTests {
    @Test func saveAndLoad_scopedByServerWorktreeAndSession() async {
        let defaults = UserDefaults(suiteName: "SessionModelSelectionStoreTests")!
        defaults.removePersistentDomain(forName: "SessionModelSelectionStoreTests")
        let store = UserDefaultsSessionModelSelectionStore(defaults: defaults)

        await store.save(
            serverBaseURL: "http://127.0.0.1:4096",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: "high")
        )

        let loaded = await store.load(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1"
        )
        #expect(loaded?.providerID == "openai")
        #expect(loaded?.modelID == "gpt-4")
        #expect(loaded?.variant == "high")
        #expect(loaded?.savedAtEpochMs != nil)
        #expect(await store.load(serverBaseURL: "http://127.0.0.1:4096/", worktree: "/repo", sessionID: "ses-2") == nil)
    }

    @Test func clearAll_removesSavedSelections() async {
        let defaults = UserDefaults(suiteName: "SessionModelSelectionStoreTests.clear")!
        defaults.removePersistentDomain(forName: "SessionModelSelectionStoreTests.clear")
        let store = UserDefaultsSessionModelSelectionStore(defaults: defaults)

        await store.save(
            serverBaseURL: "http://127.0.0.1:4096/",
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: nil)
        )
        await store.clearAll()

        #expect(await store.load(serverBaseURL: "http://127.0.0.1:4096/", worktree: "/repo", sessionID: "ses-1") == nil)
    }

    @Test func preference_isScopedByUsernameAndSurvivesSessionDeletion() async throws {
        let defaults = UserDefaults(suiteName: "SessionModelSelectionStoreTests.preference")!
        defaults.removePersistentDomain(forName: "SessionModelSelectionStoreTests.preference")
        let store = UserDefaultsSessionModelSelectionStore(defaults: defaults)
        let alice = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096", username: "alice")
        let bob = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "bob")
        let selection = SessionModelSelection(
            providerID: "openai",
            modelID: "gpt-4",
            variant: "high",
            savedAtEpochMs: 2_000
        )

        await store.saveNewSessionPreference(connectionIdentity: alice, worktree: "/repo", selection: selection)
        await store.saveSession(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-1", selection: selection)
        await store.saveSession(connectionIdentity: alice, worktree: "/other", sessionID: "ses-1", selection: selection)
        let legacyKey = "sessionModelSelection.http://127.0.0.1:4096/|/legacy|ses-1"
        defaults.set(try JSONEncoder().encode(selection), forKey: legacyKey)
        await store.deleteSessionEntries(connectionIdentity: alice, sessionID: "ses-1")

        #expect(await store.loadSession(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-1") == nil)
        #expect(await store.loadSession(connectionIdentity: alice, worktree: "/other", sessionID: "ses-1") == nil)
        #expect(defaults.object(forKey: legacyKey) == nil)
        #expect(await store.loadNewSessionPreference(connectionIdentity: alice, worktree: "/repo")?.modelID == "gpt-4")
        #expect(await store.loadNewSessionPreference(connectionIdentity: bob, worktree: "/repo") == nil)
    }

    @Test func olderWrite_cannotReplaceNewerSelection() async {
        let defaults = UserDefaults(suiteName: "SessionModelSelectionStoreTests.ordered")!
        defaults.removePersistentDomain(forName: "SessionModelSelectionStoreTests.ordered")
        let store = UserDefaultsSessionModelSelectionStore(defaults: defaults)
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "opencode")

        await store.saveSession(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "new", variant: nil, savedAtEpochMs: 2_000)
        )
        await store.saveSession(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "old", variant: nil, savedAtEpochMs: 1_000)
        )

        #expect(await store.loadSession(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "ses-1"
        )?.modelID == "new")
    }

    @Test func legacyEntry_loadsThenMigratesOnSave() async throws {
        let suite = "SessionModelSelectionStoreTests.migration"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let store = UserDefaultsSessionModelSelectionStore(defaults: defaults)
        let legacyKey = "sessionModelSelection.http://127.0.0.1:4096/|/repo|ses-1"
        defaults.set(
            try JSONEncoder().encode(SessionModelSelection(providerID: "openai", modelID: "legacy", variant: nil)),
            forKey: legacyKey
        )
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "alice")

        let loaded = await store.loadSession(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-1")
        await store.saveSession(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "ses-1",
            selection: SessionModelSelection(providerID: "openai", modelID: "migrated", variant: nil, savedAtEpochMs: 2_000)
        )

        #expect(loaded?.modelID == "legacy")
        #expect(loaded?.savedAtEpochMs == nil)
        #expect(defaults.object(forKey: legacyKey) == nil)
        #expect(await store.loadSession(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-1")?.modelID == "migrated")
    }

    @Test func retention_evictsOldestSessionEntryAfterFiveHundred() async {
        let suite = "SessionModelSelectionStoreTests.retention"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let store = UserDefaultsSessionModelSelectionStore(defaults: defaults)
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "opencode")

        for index in 0...500 {
            await store.saveSession(
                connectionIdentity: identity,
                worktree: "/repo",
                sessionID: "ses-\(index)",
                selection: SessionModelSelection(
                    providerID: "openai",
                    modelID: "model-\(index)",
                    variant: nil,
                    savedAtEpochMs: Int64(index + 1)
                )
            )
        }

        #expect(await store.loadSession(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-0") == nil)
        #expect(await store.loadSession(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-500")?.modelID == "model-500")
    }
}
