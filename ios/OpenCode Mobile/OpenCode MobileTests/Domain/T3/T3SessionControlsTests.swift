import Foundation
import Testing
@testable import OpenCode_Mobile

/// Thread lifecycle and settings commands the T3 session repository dispatches,
/// plus archived-thread parsing and provider label mapping.
struct T3SessionControlsTests {
    // MARK: - Fixtures

    private static func thread(
        id: String,
        activeRunID: String? = nil,
        archivedAt: String? = nil,
        settledAt: String? = nil,
        settledOverride: String? = nil,
        deletedAt: String? = nil,
        runtimeMode: String = "approval-required",
        instanceID: String = "codex-1",
        model: String = "gpt-5"
    ) -> T3Thread {
        T3Thread(
            id: id,
            title: "Thread \(id)",
            projectID: "p-alpha",
            status: "idle",
            archivedAt: archivedAt,
            settledAt: settledAt,
            settledOverride: settledOverride,
            deletedAt: deletedAt,
            activeRunID: activeRunID,
            activityRunStatus: nil,
            providerInstanceID: instanceID,
            runtimeMode: runtimeMode,
            interactionMode: nil,
            modelSelection: T3ModelSelection(instanceID: instanceID, model: model),
            worktreePath: nil,
            updatedAtEpochMs: 2,
            createdAtEpochMs: 1
        )
    }

    private static func snapshot(_ threads: [T3Thread]) -> T3ShellSnapshot {
        T3ShellSnapshot(
            sequence: 1,
            threads: threads,
            projects: [
                T3Project(id: "p-alpha", title: "Alpha", workspaceRoot: "/repos/alpha", defaultModelSelection: nil),
            ]
        )
    }

    private static func lastCommand(_ client: FakeT3EngineClient) -> [String: Any] {
        client.dispatched.last ?? [:]
    }

    private static let shellJSON = """
    {
      "snapshotSequence": 4,
      "threads": [
        { "id": "thr-active", "title": "Active", "projectId": "p-alpha", "status": "idle",
          "createdAt": "2026-10-01T08:00:00Z", "updatedAt": "2026-10-03T12:00:00Z" },
        { "id": "thr-settled", "title": "Settled", "projectId": "p-alpha", "status": "idle",
          "settledAt": "2026-10-02T00:00:00Z" },
        { "id": "thr-reactivated", "title": "Reactivated", "projectId": "p-alpha", "status": "idle",
          "settledAt": "2026-10-02T00:00:00Z", "settledOverride": "active" }
      ],
      "archivedThreads": [
        { "id": "thr-archived", "title": "Archived", "projectId": "p-alpha", "status": "idle",
          "archivedAt": "2026-10-02T00:00:00Z", "updatedAt": "2026-10-02T00:00:00Z" },
        { "id": "thr-deleted", "title": "Deleted", "projectId": "p-alpha", "status": "idle",
          "archivedAt": "2026-10-02T00:00:00Z", "deletedAt": "2026-10-02T01:00:00Z" }
      ],
      "projects": [
        { "id": "p-alpha", "title": "Alpha", "workspaceRoot": "/repos/alpha" }
      ]
    }
    """

    // MARK: - Parsing

    @Test func shellSnapshot_parsesArchivedThreadsAndCreatedAt() throws {
        let snapshot = try T3ShellMapper.snapshot(from: Data(Self.shellJSON.utf8))

        #expect(snapshot.activeThreads.map(\.id) == ["thr-active", "thr-reactivated"])
        #expect(snapshot.archivedThreads.map(\.id) == ["thr-settled", "thr-archived"])
        #expect(snapshot.thread(id: "thr-archived")?.isArchived == true)
        #expect(snapshot.thread(id: "thr-settled")?.isSettled == true)
        #expect(snapshot.thread(id: "thr-reactivated")?.isSettled == false)

        let active = try #require(snapshot.thread(id: "thr-active"))
        #expect(active.createdAtEpochMs == T3JSON.epochMs("2026-10-01T08:00:00Z"))
        #expect(active.updatedAtEpochMs == T3JSON.epochMs("2026-10-03T12:00:00Z"))
        let session = T3ShellMapper.session(from: active)
        #expect(session.createdAtEpochMs == T3JSON.epochMs("2026-10-01T08:00:00Z"))
        #expect(session.settledAtEpochMs == nil)

        let withoutCreated = try #require(snapshot.thread(id: "thr-settled"))
        #expect(T3ShellMapper.session(from: withoutCreated).createdAtEpochMs == nil)
        #expect(T3ShellMapper.session(from: withoutCreated).isSettled)
        #expect(T3ShellMapper.session(from: withoutCreated).isArchived == false)
    }

    @Test func providerLabel_titleCasesDriverAndDropsInstanceSuffix() {
        #expect(T3ProviderLabel.displayName("codex-1") == "Codex")
        #expect(T3ProviderLabel.displayName("codex") == "Codex")
        #expect(T3ProviderLabel.displayName("claude-code") == "Claude Code")
        #expect(T3ProviderLabel.displayName("claude-code-2") == "Claude Code")
        #expect(T3ProviderLabel.displayName("cursor_agent") == "Cursor Agent")
        #expect(T3ProviderLabel.displayName(nil) == nil)
        #expect(T3ProviderLabel.displayName("  ") == nil)
        #expect(T3ProviderLabel.modelSelectionLabel(instanceID: "codex-1", model: "gpt-5") == "Codex · gpt-5")
        #expect(T3ProviderLabel.modelSelectionLabel(instanceID: "codex-1", model: nil) == "Codex")
        #expect(T3ProviderLabel.modelSelectionLabel(instanceID: nil, model: "gpt-5") == "gpt-5")
    }

    @Test func runtimeModes_matchContractValues() {
        #expect(T3RuntimeModeLabel.allModes == ["approval-required", "auto-accept-edits", "auto", "full-access"])
        #expect(T3RuntimeModeLabel.allModes.compactMap(T3RuntimeModeLabel.displayName) == ["Supervised", "Auto-accept edits", "Auto", "Full access"])
    }

    // MARK: - Repository commands

    @Test func listArchivedSessions_returnsArchivedAndSettledThreadsOnly() async throws {
        let snapshot = try T3ShellMapper.snapshot(from: Data(Self.shellJSON.utf8))
        let repository = T3SessionRepository(client: FakeT3EngineClient(snapshot: snapshot))

        let active = try await repository.listSessions(directory: "/repos/alpha")
        let archived = try await repository.listArchivedSessions(directory: "/repos/alpha")

        #expect(active.map(\.id) == ["thr-active", "thr-reactivated"])
        #expect(archived.map(\.id) == ["thr-settled", "thr-archived"])
        #expect(archived.first { $0.id == "thr-settled" }?.isSettled == true)
        #expect(archived.first { $0.id == "thr-archived" }?.isArchived == true)
        #expect(archived.first?.directory == "/repos/alpha")
        #expect(try await repository.listArchivedSessions(directory: "/elsewhere").isEmpty)
    }

    @Test func archiveSession_dispatchesThreadArchive() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        let session = try await repository.archiveSession(id: "thr-1", directory: "/repos/alpha")

        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "thread.archive")
        #expect(command["threadId"] as? String == "thr-1")
        #expect((command["commandId"] as? String)?.isEmpty == false)
        #expect(session.id == "thr-1")
        #expect(session.isArchived)
        #expect(client.cachedThreadIDs.active.isEmpty)
        #expect(client.cachedThreadIDs.archived == ["thr-1"])
    }

    @Test func unarchiveSession_dispatchesThreadUnarchiveAndClearsArchivedAt() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1", archivedAt: "2026-10-02T00:00:00Z")]))
        let repository = T3SessionRepository(client: client)

        let session = try await repository.unarchiveSession(id: "thr-1", directory: "/repos/alpha")

        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "thread.unarchive")
        #expect(command["threadId"] as? String == "thr-1")
        #expect(session.isArchived == false)
        #expect(client.cachedThreadIDs.active == ["thr-1"])
        #expect(client.cachedThreadIDs.archived.isEmpty)
    }

    @Test func settleAndUnsettle_dispatchContractCommands() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        #expect(try await repository.settleSession(id: "thr-1", directory: "/repos/alpha"))
        let settle = Self.lastCommand(client)
        #expect(settle["type"] as? String == "thread.settle")
        #expect(settle["threadId"] as? String == "thr-1")
        #expect(settle["reason"] == nil)

        #expect(try await repository.unsettleSession(id: "thr-1", directory: "/repos/alpha"))
        let unsettle = Self.lastCommand(client)
        #expect(unsettle["type"] as? String == "thread.unsettle")
        #expect(unsettle["threadId"] as? String == "thr-1")
        #expect(unsettle["reason"] as? String == "user")
        #expect(client.dispatched.count == 2)
    }

    @Test func snoozeAndWake_dispatchContractCommands() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        #expect(try await repository.snoozeSession(id: "thr-1", snoozedUntil: "2099-01-01T00:00:00Z", directory: "/repos/alpha"))
        let snooze = Self.lastCommand(client)
        #expect(snooze["type"] as? String == "thread.snooze")
        #expect(snooze["threadId"] as? String == "thr-1")
        #expect(snooze["snoozedUntil"] as? String == "2099-01-01T00:00:00Z")
        #expect(snooze["reason"] == nil)

        #expect(try await repository.wakeSession(id: "thr-1", directory: "/repos/alpha"))
        let wake = Self.lastCommand(client)
        #expect(wake["type"] as? String == "thread.unsnooze")
        #expect(wake["threadId"] as? String == "thr-1")
        #expect(wake["reason"] as? String == "user")
    }

    @Test func session_mapsSnoozeAndBlockReason() {
        var snoozed = Self.thread(id: "thr-s")
        snoozed.snoozedUntil = "2099-01-01T00:00:00Z"
        let snoozedSession = T3ShellMapper.session(from: snoozed)
        #expect(snoozedSession.snoozedUntilEpochMs == T3JSON.epochMs("2099-01-01T00:00:00Z"))
        #expect(snoozedSession.snoozeBlockReason == nil)

        var running = Self.thread(id: "thr-r", activeRunID: "run-1")
        running.pendingRequestID = "req"
        running.snoozedUntil = "2099-01-01T00:00:00Z"
        #expect(T3ShellMapper.session(from: running).snoozeBlockReason == "Still running")

        var waiting = Self.thread(id: "thr-w")
        waiting.pendingRequestID = "req"
        waiting.pendingRequestKind = "user_input"
        #expect(T3ShellMapper.session(from: waiting).snoozeBlockReason == "Waiting on you")
    }

    @Test func deleteSession_dispatchesThreadDelete() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        #expect(try await repository.deleteSession(id: "thr-1", directory: "/repos/alpha"))

        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "thread.delete")
        #expect(command["threadId"] as? String == "thr-1")
    }

    @Test func updateSession_dispatchesMetadataUpdateAndSkipsBlankTitle() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        let session = try await repository.updateSession(id: "thr-1", title: "Renamed", directory: "/repos/alpha")

        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "thread.metadata.update")
        #expect(command["threadId"] as? String == "thr-1")
        #expect(command["title"] as? String == "Renamed")
        #expect(session.title == "Renamed")

        _ = try await repository.updateSession(id: "thr-1", title: "   ", directory: "/repos/alpha")
        #expect(client.dispatched.count == 1)
    }

    @Test func abortSession_interruptsActiveRunOnly() async throws {
        let client = FakeT3EngineClient(
            snapshot: Self.snapshot([Self.thread(id: "thr-idle"), Self.thread(id: "thr-busy", activeRunID: "run-7")])
        )
        let repository = T3SessionRepository(client: client)

        #expect(try await repository.abortSession(id: "thr-idle", directory: "/repos/alpha"))
        #expect(client.dispatched.isEmpty)

        #expect(try await repository.abortSession(id: "thr-busy", directory: "/repos/alpha"))
        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "run.interrupt")
        #expect(command["threadId"] as? String == "thr-busy")
        #expect(command["runId"] as? String == "run-7")
    }

    @Test func setRuntimeMode_dispatchesContractValueAndRejectsUnknown() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        let session = try await repository.setRuntimeMode(id: "thr-1", runtimeMode: "full-access", directory: "/repos/alpha")

        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "thread.runtime-mode.set")
        #expect(command["threadId"] as? String == "thr-1")
        #expect(command["runtimeMode"] as? String == "full-access")
        #expect(session.runtimeMode == "full-access")

        await #expect(throws: SessionError.http(statusCode: 400, message: "Unknown runtime mode: yolo")) {
            _ = try await repository.setRuntimeMode(id: "thr-1", runtimeMode: "yolo", directory: "/repos/alpha")
        }
        #expect(client.dispatched.count == 1)
    }

    @Test func setModelSelection_dispatchesInstanceAndModel() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot([Self.thread(id: "thr-1")]))
        let repository = T3SessionRepository(client: client)

        let session = try await repository.setModelSelection(
            id: "thr-1",
            providerInstanceID: "claude-code",
            modelID: "claude-sonnet",
            directory: "/repos/alpha"
        )

        let command = Self.lastCommand(client)
        #expect(command["type"] as? String == "thread.model-selection.set")
        #expect(command["threadId"] as? String == "thr-1")
        let selection = command["modelSelection"] as? [String: Any]
        #expect(selection?["instanceId"] as? String == "claude-code")
        #expect(selection?["model"] as? String == "claude-sonnet")
        #expect(session.providerInstanceID == "claude-code")
        #expect(session.modelName == "claude-sonnet")

        await #expect(throws: SessionError.http(statusCode: 400, message: "A provider instance and model are required")) {
            _ = try await repository.setModelSelection(id: "thr-1", providerInstanceID: "", modelID: "x", directory: "/repos/alpha")
        }
        #expect(client.dispatched.count == 1)
    }
}
