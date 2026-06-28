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
        #expect(loaded == SessionModelSelection(providerID: "openai", modelID: "gpt-4", variant: "high"))
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
}
