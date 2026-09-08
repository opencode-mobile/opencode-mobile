import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ComposerDraftStoreTests {
    @Test func saveAndLoad_isIsolatedByConnectionWorktreeAndSession() {
        let store = makeStore("ComposerDraftStoreTests.isolation")
        let alice = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096", username: "alice")
        let bob = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "bob")
        let mention = AgentMention(id: "m1", name: "explore", start: 0, end: 8)

        store.save(
            connectionIdentity: alice,
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "hello @explore", agentMentions: [mention], savedAtEpochMs: 2_000)
        )
        store.save(
            connectionIdentity: alice,
            worktree: "/repo",
            sessionID: "ses-2",
            draft: ComposerDraft(text: "other session", savedAtEpochMs: 2_000)
        )
        store.save(
            connectionIdentity: alice,
            worktree: "/other",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "other worktree", savedAtEpochMs: 2_000)
        )
        store.save(
            connectionIdentity: bob,
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "other user", savedAtEpochMs: 2_000)
        )

        let loaded = store.load(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-1")
        #expect(loaded?.text == "hello @explore")
        #expect(loaded?.agentMentions == [mention])
        #expect(store.load(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-2")?.text == "other session")
        #expect(store.load(connectionIdentity: alice, worktree: "/other", sessionID: "ses-1")?.text == "other worktree")
        #expect(store.load(connectionIdentity: bob, worktree: "/repo", sessionID: "ses-1")?.text == "other user")
    }

    @Test func deleteAndDeleteSessionEntries_removeMatchingDrafts() {
        let store = makeStore("ComposerDraftStoreTests.delete")
        let alice = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "alice")
        store.save(
            connectionIdentity: alice,
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "one", savedAtEpochMs: 1_000)
        )
        store.save(
            connectionIdentity: alice,
            worktree: "/other",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "two", savedAtEpochMs: 1_000)
        )
        store.save(
            connectionIdentity: alice,
            worktree: "/repo",
            sessionID: "ses-2",
            draft: ComposerDraft(text: "keep", savedAtEpochMs: 1_000)
        )

        store.delete(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-2")
        #expect(store.load(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-2") == nil)

        store.deleteSessionEntries(connectionIdentity: alice, sessionID: "ses-1")
        #expect(store.load(connectionIdentity: alice, worktree: "/repo", sessionID: "ses-1") == nil)
        #expect(store.load(connectionIdentity: alice, worktree: "/other", sessionID: "ses-1") == nil)
    }

    @Test func olderWrite_cannotReplaceNewerDraft() {
        let store = makeStore("ComposerDraftStoreTests.ordered")
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "opencode")

        store.save(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "new", savedAtEpochMs: 2_000)
        )
        store.save(
            connectionIdentity: identity,
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "old", savedAtEpochMs: 1_000)
        )

        #expect(store.load(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-1")?.text == "new")
    }

    @Test func retentionEvictsOldestAndClearAllRemovesDrafts() {
        let store = makeStore("ComposerDraftStoreTests.retention")
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "opencode")
        for index in 0...500 {
            store.save(
                connectionIdentity: identity,
                worktree: "/repo",
                sessionID: "ses-\(index)",
                draft: ComposerDraft(text: "draft-\(index)", savedAtEpochMs: Int64(index))
            )
        }

        #expect(store.load(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-0") == nil)
        #expect(store.load(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-500") != nil)
        store.clearAll()
        #expect(store.load(connectionIdentity: identity, worktree: "/repo", sessionID: "ses-500") == nil)
    }

    @Test func trailingSlashAndBlankUsername_normalizeToSameKey() {
        let store = makeStore("ComposerDraftStoreTests.normalize")
        store.save(
            connectionIdentity: ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096", username: ""),
            worktree: "/repo",
            sessionID: "ses-1",
            draft: ComposerDraft(text: "normalized", savedAtEpochMs: 1_000)
        )

        let loaded = store.load(
            connectionIdentity: ModelSelectionConnectionIdentity(serverBaseURL: "http://127.0.0.1:4096/", username: "opencode"),
            worktree: "/repo",
            sessionID: "ses-1"
        )
        #expect(loaded?.text == "normalized")
        #expect(loaded?.savedAtEpochMs != nil)
    }

    private func makeStore(_ suiteName: String) -> UserDefaultsComposerDraftStore {
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return UserDefaultsComposerDraftStore(defaults: defaults)
    }
}
