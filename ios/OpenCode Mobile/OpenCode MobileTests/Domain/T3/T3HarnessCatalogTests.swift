import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3HarnessCatalogTests {
    // MARK: - server.getConfig parsing

    @Test func parsesProvidersIntoHarnesses() throws {
        let config = T3ConfigFixtures.config(
            providers: [
                T3ConfigFixtures.provider(
                    "codex",
                    driver: "codex",
                    displayName: nil,
                    models: [
                        T3ConfigFixtures.model("gpt-5", name: "GPT-5", shortName: "5", isDefault: true),
                        T3ConfigFixtures.model("gpt-4", name: "GPT-4", isLegacy: true),
                    ],
                    supportedRuntimeModes: ["full-access", "approval-required", "warp-speed"],
                    requiresNewThreadForModelChange: true
                ),
                T3ConfigFixtures.provider("claude-work", driver: "claudeAgent", displayName: "Claude (work)", auth: "unauthenticated"),
                T3ConfigFixtures.provider("gemini", installed: false),
                T3ConfigFixtures.provider("broken", status: "error"),
                T3ConfigFixtures.provider("off", enabled: false),
                ["driver": "missing-instance"],
            ],
            defaultSelection: ["instanceId": "codex", "model": "gpt-5"]
        )

        let catalog = try #require(T3JSON.harnessCatalog(from: config))

        #expect(catalog.harnesses.map(\.instanceID) == ["codex", "claude-work", "gemini", "broken", "off"])
        let codex = try #require(catalog.harness(instanceID: "codex"))
        #expect(codex.displayName == "Codex")
        #expect(codex.driver == "codex")
        #expect(codex.isUsable)
        #expect(codex.supportedRuntimeModes == ["full-access", "approval-required"])
        #expect(codex.requiresNewThreadForModelChange)
        #expect(codex.models == [
            T3HarnessModel(slug: "gpt-5", name: "GPT-5", shortName: "5", isDefault: true, isLegacy: false),
            T3HarnessModel(slug: "gpt-4", name: "GPT-4", shortName: nil, isDefault: false, isLegacy: true),
        ])
        #expect(catalog.harness(instanceID: "claude-work")?.displayName == "Claude (work)")
        #expect(catalog.harness(instanceID: "claude-work")?.available == false)
        #expect(catalog.harness(instanceID: "gemini")?.available == false)
        #expect(catalog.harness(instanceID: "broken")?.available == false)
        #expect(catalog.harness(instanceID: "off")?.isUsable == false)
        #expect(catalog.defaultSelection == T3ModelSelection(instanceID: "codex", model: "gpt-5"))
    }

    @Test func nullGlobalDefault_parsesAsNil() throws {
        let catalog = try #require(T3JSON.harnessCatalog(from: T3ConfigFixtures.config(providers: [])))
        #expect(catalog.harnesses.isEmpty)
        #expect(catalog.defaultSelection == nil)
        #expect(T3JSON.harnessCatalog(from: "nope") == nil)
    }

    @Test func catalogLabel_usesDisplayNameAndShortName() {
        let catalog = Self.catalog()
        #expect(catalog.label(for: T3ModelSelection(instanceID: "codex", model: "gpt-5")) == "Codex · GPT-5")
        #expect(catalog.label(for: T3ModelSelection(instanceID: "claude", model: "opus")) == "Claude · Opus")
        #expect(catalog.label(for: T3ModelSelection(instanceID: "cursor-2", model: "x")) == "Cursor · x")
    }

    // MARK: - Resolver chain

    @Test func resolver_projectOverrideWins() {
        let selection = T3ModelSelectionResolver.resolve(
            catalog: Self.catalog(defaultSelection: T3ModelSelection(instanceID: "codex", model: "gpt-5")),
            projectDefault: T3ModelSelection(instanceID: "claude", model: "sonnet")
        )
        #expect(selection == T3ModelSelection(instanceID: "claude", model: "sonnet"))
    }

    @Test func resolver_globalDefaultWhenProjectHasNone() {
        let selection = T3ModelSelectionResolver.resolve(
            catalog: Self.catalog(defaultSelection: T3ModelSelection(instanceID: "claude", model: "opus")),
            projectDefault: nil
        )
        #expect(selection == T3ModelSelection(instanceID: "claude", model: "opus"))
    }

    @Test func resolver_unusableOverrideFallsThroughToGlobalDefault() {
        let selection = T3ModelSelectionResolver.resolve(
            catalog: Self.catalog(defaultSelection: T3ModelSelection(instanceID: "claude", model: "opus")),
            projectDefault: T3ModelSelection(instanceID: "gemini", model: "pro")
        )
        #expect(selection == T3ModelSelection(instanceID: "claude", model: "opus"))
    }

    @Test func resolver_firstUsableHarnessAndItsDefaultModel() {
        // `gemini` is listed first but not installed; `claude` marks `sonnet` as its default.
        let selection = T3ModelSelectionResolver.resolve(
            catalog: Self.catalog(order: ["gemini", "claude", "codex"]),
            projectDefault: T3ModelSelection(instanceID: "removed", model: "x")
        )
        #expect(selection == T3ModelSelection(instanceID: "claude", model: "sonnet"))
    }

    @Test func resolver_firstModelWhenHarnessHasNoDefault() {
        let selection = T3ModelSelectionResolver.resolve(catalog: Self.catalog(order: ["codex"]), projectDefault: nil)
        #expect(selection == T3ModelSelection(instanceID: "codex", model: "gpt-5"))
    }

    @Test func resolver_preferredBeatsProjectOverride() {
        let selection = T3ModelSelectionResolver.resolve(
            catalog: Self.catalog(),
            projectDefault: T3ModelSelection(instanceID: "claude", model: "sonnet"),
            preferred: T3ModelSelection(instanceID: "codex", model: "gpt-4")
        )
        #expect(selection == T3ModelSelection(instanceID: "codex", model: "gpt-4"))
    }

    @Test func resolver_nilWhenNoHarnessIsUsable() {
        let catalog = T3HarnessCatalog(
            harnesses: [
                T3Harness(instanceID: "gemini", displayName: "Gemini", driver: "gemini", enabled: true, available: false, models: [T3HarnessModel(slug: "pro", name: "Pro")]),
                T3Harness(instanceID: "off", displayName: "Off", driver: "off", enabled: false, available: true, models: [T3HarnessModel(slug: "m", name: "M")]),
                T3Harness(instanceID: "empty", displayName: "Empty", driver: "empty", enabled: true, available: true, models: []),
            ],
            defaultSelection: T3ModelSelection(instanceID: "gemini", model: "pro")
        )
        #expect(T3ModelSelectionResolver.resolve(catalog: catalog, projectDefault: nil) == nil)
    }

    // MARK: - Model change gate

    @Test func gate_newThreadMayChangeAnything() {
        let reason = T3ModelChangeGate.blockReason(
            catalog: Self.catalog(),
            current: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            next: T3ModelSelection(instanceID: "claude", model: "opus"),
            hasStarted: false
        )
        #expect(reason == nil)
    }

    @Test func gate_startedThreadKeepsItsHarness() {
        let reason = T3ModelChangeGate.blockReason(
            catalog: Self.catalog(),
            current: T3ModelSelection(instanceID: "claude", model: "opus"),
            next: T3ModelSelection(instanceID: "codex", model: "gpt-5"),
            hasStarted: true
        )
        #expect(reason == T3ModelChangeGate.switchHarnessNotice)
    }

    @Test func gate_requiresNewThreadForModelChange_blocksModelSwitch() {
        let catalog = Self.catalog()
        let current = T3ModelSelection(instanceID: "codex", model: "gpt-5")
        let reason = T3ModelChangeGate.blockReason(
            catalog: catalog,
            current: current,
            next: T3ModelSelection(instanceID: "codex", model: "gpt-4"),
            hasStarted: true
        )
        #expect(reason == T3ModelChangeGate.modelChangeNotice(harnessName: "Codex"))
        #expect(T3ModelChangeGate.isLocked(catalog: catalog, current: current, hasStarted: true))
        #expect(!T3ModelChangeGate.isLocked(catalog: catalog, current: current, hasStarted: false))
    }

    @Test func gate_harnessWithoutFlag_allowsModelSwitch() {
        let reason = T3ModelChangeGate.blockReason(
            catalog: Self.catalog(),
            current: T3ModelSelection(instanceID: "claude", model: "opus"),
            next: T3ModelSelection(instanceID: "claude", model: "sonnet"),
            hasStarted: true
        )
        #expect(reason == nil)
    }

    // MARK: - Session creation

    @Test func createSession_passesExplicitSelectionAndRuntimeModeToLaunch() async throws {
        let client = FakeT3EngineClient(snapshot: Self.shell())
        let repository = T3SessionRepository(client: client)

        _ = try await repository.createSession(
            parentID: nil,
            title: nil,
            directory: "/repos/alpha",
            options: SessionCreateOptions(providerInstanceID: "claude", modelID: "opus", runtimeMode: "full-access")
        )

        #expect(client.launchedSelections == [T3ModelSelection(instanceID: "claude", model: "opus")])
        #expect(client.launchedRuntimeModes == ["full-access"])
    }

    @Test func createSession_withoutOptions_letsTheClientResolve() async throws {
        let client = FakeT3EngineClient(snapshot: Self.shell())
        let repository = T3SessionRepository(client: client)

        _ = try await repository.createSession(parentID: nil, title: nil, directory: "/repos/alpha")
        _ = try await repository.createSession(
            parentID: nil,
            title: nil,
            directory: "/repos/alpha",
            options: SessionCreateOptions(providerInstanceID: "claude", modelID: " ", runtimeMode: "warp")
        )

        #expect(client.launchedSelections == [nil, nil])
        #expect(client.launchedRuntimeModes == [nil, nil])
    }

    @Test func engineRepository_forwardsCreateOptionsAndModelSelection() async throws {
        let client = FakeT3EngineClient(snapshot: Self.shell())
        let manager = ActiveConnectionManager()
        manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:3000/", username: T3Auth.username, password: "b", engine: .t3))
        let repository = EngineSessionRepository(
            opencode: T3SessionRepository(client: FakeT3EngineClient(snapshot: Self.shell())),
            t3: T3SessionRepository(client: client),
            activeConnectionManager: manager
        )

        _ = try await repository.createSession(
            parentID: nil,
            title: nil,
            directory: nil,
            options: SessionCreateOptions(providerInstanceID: "codex", modelID: "gpt-5")
        )
        // The fake shell has no thread to re-read afterwards; only the dispatch matters here.
        _ = try? await repository.setModelSelection(id: "t1", providerInstanceID: "codex", modelID: "gpt-4", directory: nil)

        #expect(client.launchedSelections == [T3ModelSelection(instanceID: "codex", model: "gpt-5")])
        #expect(client.dispatched.first?["type"] as? String == "thread.model-selection.set")
    }

    /// Every T3-only call reaches the T3 repository instead of the protocol defaults.
    @Test func engineRepository_forwardsT3SessionControls() async throws {
        var shell = Self.shell()
        shell.threads = [
            T3Thread(id: "t1", title: "Live", projectID: "p-alpha", status: "idle"),
            T3Thread(id: "t2", title: "Old", projectID: "p-alpha", status: "idle", archivedAt: "2026-10-01T00:00:00Z"),
        ]
        let client = FakeT3EngineClient(snapshot: shell)
        let manager = ActiveConnectionManager()
        manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:3000/", username: T3Auth.username, password: "b", engine: .t3))
        let repository = EngineSessionRepository(
            opencode: T3SessionRepository(client: FakeT3EngineClient(snapshot: Self.shell())),
            t3: T3SessionRepository(client: client),
            activeConnectionManager: manager
        )

        #expect(try await repository.listArchivedSessions(directory: nil).map(\.id) == ["t2"])
        #expect(try await repository.setRuntimeMode(id: "t1", runtimeMode: "full-access", directory: nil).runtimeMode == "full-access")
        #expect(try await repository.setModelSelection(id: "t1", providerInstanceID: "codex", modelID: "gpt-5", directory: nil).modelName == "gpt-5")
        _ = try await repository.unarchiveSession(id: "t2", directory: nil)
        #expect(try await repository.unsettleSession(id: "t1", directory: nil))

        #expect(client.dispatched.compactMap { $0["type"] as? String } == [
            "thread.runtime-mode.set",
            "thread.model-selection.set",
            "thread.unarchive",
            "thread.unsettle",
        ])
    }

    @Test func harnessRepository_newThreadSelection_usesProjectOverrideForDirectory() async throws {
        var shell = Self.shell()
        shell.projects[1].defaultModelSelection = T3ModelSelection(instanceID: "claude", model: "opus")
        let client = FakeT3EngineClient(snapshot: shell, catalog: Self.catalog())
        let repository = DefaultT3HarnessRepository(client: client)

        let beta = try await repository.newThreadSelection(directory: "/repos/beta", preferred: nil)
        let alpha = try await repository.newThreadSelection(directory: "/repos/alpha", preferred: nil)

        #expect(beta == T3ModelSelection(instanceID: "claude", model: "opus"))
        #expect(alpha == T3ModelSelection(instanceID: "codex", model: "gpt-5"))
    }

    @Test func harnessRepository_newThreadSelection_throwsWithoutUsableHarness() async {
        let client = FakeT3EngineClient(snapshot: Self.shell(), catalog: .empty)
        let repository = DefaultT3HarnessRepository(client: client)
        await #expect(throws: T3ClientError.rpc(T3ModelSelectionResolver.noHarnessMessage)) {
            _ = try await repository.newThreadSelection(directory: nil, preferred: nil)
        }
    }

    // MARK: - launchThread over the socket

    @Test func launchThread_explicitSelectionSkipsCatalogue() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = Self.shell()
        let call = Task {
            try await harness.client.launchThread(
                title: "Hi",
                projectID: "p-alpha",
                selection: T3ModelSelection(instanceID: "claude", model: "opus"),
                runtimeMode: "auto-accept-edits"
            )
        }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let request = try await socket.firstRequest(tag: "orchestration.launchThread")
        let selection = request.payload["modelSelection"] as? [String: Any]
        #expect(selection?["instanceId"] as? String == "claude")
        #expect(selection?["model"] as? String == "opus")
        #expect(request.payload["runtimeMode"] as? String == "auto-accept-edits")
        socket.receive(Frames.exitSuccess(requestID: request.id, value: [
            "thread": ["id": "t9", "title": "Hi", "projectId": "p-alpha", "status": "idle"],
        ]))
        _ = try await call.value
    }

    @Test func launchThread_resolvesGlobalDefaultFromCatalogue() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = Self.shell()
        let call = Task { try await harness.client.launchThread(title: "Hi", projectID: "p-alpha") }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let responder = T3ConfigResponder.start(socket, config: T3ConfigFixtures.config(
            providers: [
                T3ConfigFixtures.provider("codex", models: [T3ConfigFixtures.model("gpt-5")]),
                T3ConfigFixtures.provider("claude", models: [T3ConfigFixtures.model("opus")]),
            ],
            defaultSelection: ["instanceId": "claude", "model": "opus"]
        ))
        defer { responder.cancel() }
        let request = try await socket.firstRequest(tag: "orchestration.launchThread")
        let selection = request.payload["modelSelection"] as? [String: Any]
        #expect(selection?["instanceId"] as? String == "claude")
        #expect(selection?["model"] as? String == "opus")
        #expect(request.payload["runtimeMode"] as? String == "approval-required")
        socket.receive(Frames.exitSuccess(requestID: request.id, value: [
            "thread": ["id": "t9", "title": "Hi", "projectId": "p-alpha", "status": "idle"],
        ]))
        _ = try await call.value
        #expect(harness.client.cachedHarnessCatalog?.harnesses.count == 2)
    }

    @Test func launchThread_withoutUsableHarness_throwsNoHarnessMessage() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = Self.shell()
        let call = Task { try await harness.client.launchThread(title: "Hi", projectID: nil) }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let responder = T3ConfigResponder.start(socket, config: T3ConfigFixtures.config(providers: [
            T3ConfigFixtures.provider("codex", auth: "unauthenticated", models: [T3ConfigFixtures.model("gpt-5")]),
        ]))
        defer { responder.cancel() }
        await #expect(throws: T3ClientError.rpc(T3ModelSelectionResolver.noHarnessMessage)) {
            _ = try await call.value
        }
        #expect(socket.frames(tag: "Request").allSatisfy { $0["tag"] as? String != "orchestration.launchThread" })
        #expect(T3ClientError.rpc(T3ModelSelectionResolver.noHarnessMessage).errorDescription
            == "No T3 harness is available. Configure a provider in T3 Code first.")
    }

    @Test func reset_clearsCachedCatalogue() async throws {
        let harness = T3ClientHarness()
        let fetch = Task { try await harness.client.fetchHarnessCatalog(force: false) }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let responder = T3ConfigResponder.start(socket, config: T3ConfigFixtures.config(providers: [
            T3ConfigFixtures.provider("codex", models: [T3ConfigFixtures.model("gpt-5")]),
        ]))
        defer { responder.cancel() }
        _ = try await fetch.value
        #expect(harness.client.cachedHarnessCatalog != nil)
        await harness.client.reset()
        #expect(harness.client.cachedHarnessCatalog == nil)
    }

    // MARK: - Fixtures

    /// codex (gpt-5, gpt-4; new thread for model changes), claude (opus, sonnet default), gemini (not installed).
    static func catalog(
        order: [String] = ["codex", "claude", "gemini"],
        defaultSelection: T3ModelSelection? = nil
    ) -> T3HarnessCatalog {
        let all: [String: T3Harness] = [
            "codex": T3Harness(
                instanceID: "codex",
                displayName: "Codex",
                driver: "codex",
                enabled: true,
                available: true,
                supportedRuntimeModes: ["approval-required", "full-access"],
                requiresNewThreadForModelChange: true,
                models: [T3HarnessModel(slug: "gpt-5", name: "GPT-5"), T3HarnessModel(slug: "gpt-4", name: "GPT-4")]
            ),
            "claude": T3Harness(
                instanceID: "claude",
                displayName: "Claude",
                driver: "claudeAgent",
                enabled: true,
                available: true,
                models: [
                    T3HarnessModel(slug: "opus", name: "Claude Opus", shortName: "Opus"),
                    T3HarnessModel(slug: "sonnet", name: "Claude Sonnet", shortName: "Sonnet", isDefault: true),
                ]
            ),
            "gemini": T3Harness(
                instanceID: "gemini",
                displayName: "Gemini",
                driver: "gemini",
                enabled: true,
                available: false,
                models: [T3HarnessModel(slug: "pro", name: "Gemini Pro")]
            ),
        ]
        return T3HarnessCatalog(harnesses: order.compactMap { all[$0] }, defaultSelection: defaultSelection)
    }

    static func shell() -> T3ShellSnapshot {
        T3ShellSnapshot(
            sequence: 1,
            threads: [],
            projects: [
                T3Project(id: "p-alpha", title: "Alpha", workspaceRoot: "/repos/alpha", defaultModelSelection: nil),
                T3Project(id: "p-beta", title: "Beta", workspaceRoot: "/repos/beta", defaultModelSelection: nil),
            ]
        )
    }
}

// MARK: - server.getConfig fixtures

enum T3ConfigFixtures {
    static func model(
        _ slug: String,
        name: String? = nil,
        shortName: String? = nil,
        isDefault: Bool? = nil,
        isLegacy: Bool? = nil
    ) -> [String: Any] {
        var model: [String: Any] = ["slug": slug, "name": name ?? slug, "isCustom": false, "capabilities": NSNull()]
        if let shortName { model["shortName"] = shortName }
        if let isDefault { model["isDefault"] = isDefault }
        if let isLegacy { model["isLegacy"] = isLegacy }
        return model
    }

    static func provider(
        _ instanceID: String,
        driver: String? = nil,
        displayName: String? = nil,
        enabled: Bool = true,
        installed: Bool = true,
        status: String = "ready",
        auth: String = "authenticated",
        models: [[String: Any]] = [],
        supportedRuntimeModes: [String]? = nil,
        requiresNewThreadForModelChange: Bool? = nil
    ) -> [String: Any] {
        var provider: [String: Any] = [
            "instanceId": instanceID,
            "driver": driver ?? instanceID,
            "enabled": enabled,
            "installed": installed,
            "version": NSNull(),
            "status": status,
            "auth": ["status": auth],
            "checkedAt": "2026-10-01T00:00:00.000Z",
            "models": models,
            "slashCommands": [],
            "skills": [],
        ]
        if let displayName { provider["displayName"] = displayName }
        if let supportedRuntimeModes { provider["supportedRuntimeModes"] = supportedRuntimeModes }
        if let requiresNewThreadForModelChange {
            provider["requiresNewThreadForModelChange"] = requiresNewThreadForModelChange
        }
        return provider
    }

    static func config(providers: [[String: Any]], defaultSelection: [String: Any]? = nil) -> [String: Any] {
        [
            "cwd": "/repos",
            "providers": providers,
            "settings": ["defaultModelSelection": defaultSelection.map { $0 as Any } ?? NSNull()],
        ]
    }
}

/// Answers every `server.getConfig` request on a fake socket with `config`
/// (the client also refreshes the catalogue whenever the socket connects).
enum T3ConfigResponder {
    static func start(_ socket: FakeSocketConnection, config: [String: Any]) -> Task<Void, Never> {
        let payload = T3SendableBox(config)
        return Task {
            var answered: Set<String> = []
            while !Task.isCancelled {
                let requests = socket.frames(tag: "Request").filter {
                    $0["tag"] as? String == T3JSON.serverGetConfigTag
                }
                for request in requests {
                    guard let id = request["id"] as? String, !answered.contains(id) else { continue }
                    answered.insert(id)
                    socket.receive(Frames.exitSuccess(requestID: id, value: payload.value))
                }
                try? await Task.sleep(for: .milliseconds(5))
            }
        }
    }
}

struct T3SendableBox: @unchecked Sendable {
    let value: [String: Any]

    init(_ value: [String: Any]) {
        self.value = value
    }
}
