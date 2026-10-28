import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatHarnessPickerTests {
    private static let profile = ConnectionProfile(
        baseURL: "http://127.0.0.1:3773/",
        username: T3Auth.username,
        password: "bearer",
        engine: .t3
    )

    // MARK: - Picker state from the catalogue

    @Test func presentation_listsUsableHarnessesWithModelsBeneath() async {
        let (picker, _, _) = makePicker()
        await picker.loadCatalog(force: false)

        let state = picker.presentation(
            threadSelection: T3ModelSelection(instanceID: "claude", model: "opus"),
            hasStarted: false
        )

        #expect(state.options.map(\.id) == ["codex:gpt-5", "codex:gpt-4", "claude:opus", "claude:sonnet"])
        let providers = ModelSelectionCatalog(options: state.options, serverDefault: nil).providers
        #expect(providers.map(\.label) == ["Codex", "Claude"])
        #expect(state.selectedOption?.id == "claude:opus")
        #expect(state.selectedOption?.modelLabel == "Opus")
        #expect(state.chipLabel == "Claude · Opus")
        #expect(state.selectionLabel == "Claude · Opus")
        #expect(state.harnessLabel == "Claude")
        #expect(state.runtimeModes == T3RuntimeModeLabel.allModes)
        #expect(!state.isLocked)
    }

    @Test func presentation_runtimeModesFollowTheHarness() async {
        let (picker, _, _) = makePicker()
        await picker.loadCatalog(force: false)

        let state = picker.presentation(
            threadSelection: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            hasStarted: false
        )

        #expect(state.runtimeModes == ["approval-required", "full-access"])
    }

    @Test func presentation_startedThreadOffersOnlyItsHarness() async {
        let (picker, _, _) = makePicker()
        await picker.loadCatalog(force: false)

        let claude = picker.presentation(
            threadSelection: T3ModelSelection(instanceID: "claude", model: "opus"),
            hasStarted: true
        )
        let codex = picker.presentation(
            threadSelection: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            hasStarted: true
        )

        #expect(claude.options.map(\.id) == ["claude:opus", "claude:sonnet"])
        #expect(!claude.isLocked)
        #expect(codex.isLocked)
    }

    @Test func presentation_catalogueFailureShowsRetryNotice() async {
        let client = FakeT3EngineClient(snapshot: T3HarnessCatalogTests.shell(), catalog: nil)
        let (picker, _, _) = makePicker(client: client)
        await picker.loadCatalog(force: true)

        let state = picker.presentation(threadSelection: nil, hasStarted: false)

        #expect(state.options.isEmpty)
        #expect(state.chipLabel == "Model")
        #expect(state.notice == SessionChatHarnessPicker.loadFailedNotice)
    }

    // MARK: - Selection

    @Test func select_onExistingThread_dispatchesModelSelectionAndRemembersIt() async {
        let (picker, client, store) = makePicker()
        await picker.loadCatalog(force: false)
        let option = picker.presentation(threadSelection: nil, hasStarted: false).options.first { $0.id == "claude:sonnet" }!

        let session = await picker.select(
            option,
            sessionID: "t1",
            current: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            hasStarted: false
        )

        #expect(session?.providerInstanceID == "claude")
        #expect(session?.modelName == "sonnet")
        let command = client.dispatched.last
        #expect(command?["type"] as? String == "thread.model-selection.set")
        let selection = command?["modelSelection"] as? [String: Any]
        #expect(selection?["instanceId"] as? String == "claude")
        #expect(selection?["model"] as? String == "sonnet")
        #expect(picker.pendingSelection == nil)
        await waitUntil { store.preference != nil }
        #expect(store.preference?.providerID == "claude")
        #expect(store.preference?.modelID == "sonnet")
    }

    @Test func select_blockedWhenHarnessRequiresNewThread() async {
        let (picker, client, _) = makePicker()
        await picker.loadCatalog(force: false)
        let option = picker.presentation(threadSelection: nil, hasStarted: false).options.first { $0.id == "codex:gpt-4" }!

        let session = await picker.select(
            option,
            sessionID: "t1",
            current: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            hasStarted: true
        )

        #expect(session == nil)
        #expect(client.dispatched.isEmpty)
        #expect(picker.notice == T3ModelChangeGate.modelChangeNotice(harnessName: "Codex"))
        #expect(picker.presentation(
            threadSelection: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            hasStarted: true
        ).notice == T3ModelChangeGate.modelChangeNotice(harnessName: "Codex"))
    }

    @Test func select_beforeCreation_feedsTheNewThread() async {
        let (picker, client, _) = makePicker()
        await picker.loadCatalog(force: false)
        let option = picker.presentation(threadSelection: nil, hasStarted: false).options.first { $0.id == "claude:opus" }!

        let session = await picker.select(
            option,
            sessionID: OptimisticSessionCreate.placeholderSessionID,
            current: nil,
            hasStarted: false
        )

        #expect(session == nil)
        #expect(client.dispatched.isEmpty)
        #expect(await picker.selectionForNewThread() == T3ModelSelection(instanceID: "claude", model: "opus"))
        #expect(picker.presentation(threadSelection: nil, hasStarted: false).chipLabel == "Claude · Opus")

        // The launch used another selection: the pick is applied to the new thread.
        let applied = await picker.didCreateThread(
            sessionID: "t1",
            launchedWith: T3ModelSelection(instanceID: "codex", model: "gpt-5")
        )
        #expect(applied?.providerInstanceID == "claude")
        #expect(client.dispatched.count == 1)
    }

    @Test func selectionForNewThread_usesRememberedPickThenResolver() async {
        let store = InMemoryHarnessSelectionStore()
        let (picker, _, _) = makePicker(store: store)
        #expect(await picker.selectionForNewThread() == T3ModelSelection(instanceID: "codex", model: "gpt-5"))

        await store.saveNewSessionPreference(
            connectionIdentity: ModelSelectionConnectionIdentity(serverBaseURL: Self.profile.baseURL, username: Self.profile.username),
            worktree: "/repos/alpha",
            selection: SessionModelSelection(providerID: "claude", modelID: "sonnet", variant: nil)
        )
        let (remembering, _, _) = makePicker(store: store)
        await remembering.loadCatalog(force: false)
        #expect(await remembering.selectionForNewThread() == T3ModelSelection(instanceID: "claude", model: "sonnet"))
        #expect(remembering.presentation(threadSelection: nil, hasStarted: false).chipLabel == "Claude · Sonnet")
    }

    @Test func start_warmsTheConnection() async {
        let (picker, client, _) = makePicker()
        picker.start()
        #expect(client.warmCount == 1)
        await waitUntil { picker.catalog != nil }
        #expect(picker.catalog?.harnesses.count == 3)
    }

    // MARK: - Helpers

    private func makePicker(
        client: FakeT3EngineClient? = nil,
        store: InMemoryHarnessSelectionStore? = nil
    ) -> (SessionChatHarnessPicker, FakeT3EngineClient, InMemoryHarnessSelectionStore) {
        var shell = T3HarnessCatalogTests.shell()
        shell.threads = [
            T3Thread(
                id: "t1",
                title: "Thread",
                projectID: "p-alpha",
                status: "idle",
                providerInstanceID: "codex",
                modelSelection: T3ModelSelection(instanceID: "codex", model: "gpt-5")
            ),
        ]
        let client = client ?? FakeT3EngineClient(snapshot: shell, catalog: T3HarnessCatalogTests.catalog())
        let store = store ?? InMemoryHarnessSelectionStore()
        let manager = ActiveConnectionManager()
        manager.apply(Self.profile)
        let picker = SessionChatHarnessPicker(
            worktree: "/repos/alpha",
            repository: DefaultT3HarnessRepository(client: client),
            sessionRepository: T3SessionRepository(client: client),
            store: store,
            activeConnectionManager: manager
        )
        return (picker, client, store)
    }

    private func waitUntil(_ condition: () -> Bool) async {
        for _ in 0 ..< 200 where !condition() {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
}

final class InMemoryHarnessSelectionStore: SessionModelSelectionStore, @unchecked Sendable {
    private let lock = NSLock()
    private var preferences: [String: SessionModelSelection] = [:]

    var preference: SessionModelSelection? {
        lock.lock(); defer { lock.unlock() }
        return preferences.values.first
    }

    func loadSession(connectionIdentity _: ModelSelectionConnectionIdentity, worktree _: String, sessionID _: String) async -> SessionModelSelection? { nil }
    func saveSession(connectionIdentity _: ModelSelectionConnectionIdentity, worktree _: String, sessionID _: String, selection _: SessionModelSelection) async {}
    func deleteSession(connectionIdentity _: ModelSelectionConnectionIdentity, worktree _: String, sessionID _: String) async {}
    func deleteSessionEntries(connectionIdentity _: ModelSelectionConnectionIdentity, sessionID _: String) async {}

    func loadNewSessionPreference(connectionIdentity: ModelSelectionConnectionIdentity, worktree: String) async -> SessionModelSelection? {
        lock.lock(); defer { lock.unlock() }
        return preferences[key(connectionIdentity, worktree)]
    }

    func saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection
    ) async {
        lock.lock(); defer { lock.unlock() }
        preferences[key(connectionIdentity, worktree)] = selection
    }

    func clearAll() async {
        lock.lock(); defer { lock.unlock() }
        preferences = [:]
    }

    private func key(_ identity: ModelSelectionConnectionIdentity, _ worktree: String) -> String {
        "\(identity.serverBaseURL)|\(identity.username)|\(worktree)"
    }
}
