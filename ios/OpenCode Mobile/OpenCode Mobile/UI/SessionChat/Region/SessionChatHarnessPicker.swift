import Foundation

/// T3 harness + model chip and picker for Session Chat. The harness is the
/// picker's provider level and its models sit beneath it. A pick on a thread
/// that does not exist yet feeds creation; a pick on an existing thread
/// dispatches `thread.model-selection.set` unless the T3 web client's rules
/// block it for a started thread.
@MainActor
final class SessionChatHarnessPicker {
    var onChange: (() -> Void)?

    private(set) var catalog: T3HarnessCatalog?
    private(set) var isLoading = false
    private(set) var loadError: String?
    private(set) var notice: String?
    /// The user's pick while the thread is being created or the server has not confirmed it yet.
    private(set) var pendingSelection: T3ModelSelection?
    private(set) var isApplying = false
    private(set) var rememberedSelection: T3ModelSelection?

    private let worktree: String
    private let repository: any T3HarnessRepository
    private let sessionRepository: any SessionRepository
    private let store: any SessionModelSelectionStore
    private let activeConnectionManager: ActiveConnectionManager
    private let noticeDuration: Duration
    private var loadTask: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var preferenceLoaded = false

    static let loadFailedNotice = "Unable to load T3 harnesses. Tap Model to retry."
    static let applyFailedNotice = "Couldn't change the model."

    init(
        worktree: String,
        repository: any T3HarnessRepository,
        sessionRepository: any SessionRepository,
        store: any SessionModelSelectionStore,
        activeConnectionManager: ActiveConnectionManager,
        noticeDuration: Duration = .seconds(4)
    ) {
        self.worktree = worktree
        self.repository = repository
        self.sessionRepository = sessionRepository
        self.store = store
        self.activeConnectionManager = activeConnectionManager
        self.noticeDuration = noticeDuration
    }

    /// Warms the socket and loads the catalogue plus the remembered pick.
    func start() {
        repository.warmConnection()
        if catalog == nil {
            reload(force: false)
        }
        if !preferenceLoaded {
            Task { await self.loadRememberedSelection() }
        }
    }

    func stop() {
        loadTask?.cancel()
        loadTask = nil
        noticeTask?.cancel()
        noticeTask = nil
    }

    func reload(force: Bool) {
        loadTask?.cancel()
        loadTask = Task { await self.loadCatalog(force: force) }
    }

    func loadCatalog(force: Bool) async {
        isLoading = true
        notifyChange()
        do {
            let loaded = try await repository.catalog(force: force)
            guard !Task.isCancelled else { return }
            catalog = loaded
            loadError = nil
        } catch {
            guard !Task.isCancelled else { return }
            OpenCodeLog.sessionChat.warning("t3 harness catalogue failed error=\(error.localizedDescription)")
            loadError = Self.loadFailedNotice
        }
        isLoading = false
        notifyChange()
    }

    func loadRememberedSelection() async {
        guard let identity = currentIdentity() else {
            preferenceLoaded = true
            return
        }
        let stored = await store.loadNewSessionPreference(connectionIdentity: identity, worktree: worktree)
        preferenceLoaded = true
        rememberedSelection = stored.map { T3ModelSelection(instanceID: $0.providerID, model: $0.modelID) }
        notifyChange()
    }

    /// The selection a new thread launches with: the user's pick, else the
    /// remembered one while its harness is usable, else the resolver. Nil when
    /// nothing resolves; the launch then resolves (and reports) on its own.
    func selectionForNewThread() async -> T3ModelSelection? {
        if let pendingSelection { return pendingSelection }
        if !preferenceLoaded { await loadRememberedSelection() }
        return try? await repository.newThreadSelection(directory: worktree, preferred: rememberedSelection)
    }

    /// Applies a picker choice. Returns the server's session when an existing
    /// thread changed, so the caller can refresh the chrome.
    func select(
        _ option: ModelSelectionOption,
        sessionID: String,
        current: T3ModelSelection?,
        hasStarted: Bool
    ) async -> Session? {
        let next = T3ModelSelection(instanceID: option.providerID, model: option.modelID)
        if let reason = T3ModelChangeGate.blockReason(catalog: catalog, current: current, next: next, hasStarted: hasStarted) {
            showNotice(reason)
            return nil
        }
        remember(next)
        guard !OptimisticSessionCreate.isPlaceholder(sessionID) else {
            pendingSelection = next
            notifyChange()
            return nil
        }
        guard next != current else {
            pendingSelection = nil
            notifyChange()
            return nil
        }
        return await apply(next, sessionID: sessionID)
    }

    /// Dispatches the selection on an existing thread.
    func apply(_ selection: T3ModelSelection, sessionID: String) async -> Session? {
        pendingSelection = selection
        isApplying = true
        notifyChange()
        defer {
            isApplying = false
            notifyChange()
        }
        do {
            let session = try await sessionRepository.setModelSelection(
                id: sessionID,
                providerInstanceID: selection.instanceID,
                modelID: selection.model,
                directory: worktree
            )
            pendingSelection = nil
            return session
        } catch {
            OpenCodeLog.sessionChat.warning("t3 model selection failed error=\(error.localizedDescription)")
            pendingSelection = nil
            showNotice(Self.applyFailedNotice)
            return nil
        }
    }

    /// After launch: a pick made while creating that differs from what the
    /// launch used is applied to the new thread.
    func didCreateThread(sessionID: String, launchedWith: T3ModelSelection?) async -> Session? {
        guard let pending = pendingSelection else { return nil }
        guard pending != launchedWith else {
            pendingSelection = nil
            notifyChange()
            return nil
        }
        return await apply(pending, sessionID: sessionID)
    }

    /// Chip tap on a thread whose model cannot change any more.
    func showLockedNotice(current: T3ModelSelection?) {
        guard let current, let harness = catalog?.harness(instanceID: current.instanceID) else { return }
        showNotice(T3ModelChangeGate.modelChangeNotice(harnessName: harness.displayName))
    }

    func showNotice(_ message: String) {
        notice = message
        notifyChange()
        noticeTask?.cancel()
        let duration = noticeDuration
        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled, let self, self.notice == message else { return }
            self.notice = nil
            self.notifyChange()
        }
    }

    func presentation(threadSelection: T3ModelSelection?, hasStarted: Bool) -> SessionChatHarnessPickerUiState {
        let current = pendingSelection ?? threadSelection ?? (hasStarted ? nil : rememberedSelection)
        let harness = current.flatMap { catalog?.harness(instanceID: $0.instanceID) }
        let options = Self.options(catalog: catalog, current: current, hasStarted: hasStarted)
        let selected = current.flatMap { selection in
            options.first { $0.providerID == selection.instanceID && $0.modelID == selection.model }
        }
        let selectionLabel = current.flatMap { selection in
            catalog?.label(for: selection)
                ?? T3ProviderLabel.modelSelectionLabel(instanceID: selection.instanceID, model: selection.model)
        }
        let chipLabel = selectionLabel ?? (catalog == nil && isLoading ? "Loading model…" : "Model")
        return SessionChatHarnessPickerUiState(
            options: options,
            selectedOption: selected,
            chipLabel: chipLabel,
            selectionLabel: selectionLabel,
            harnessLabel: harness?.displayName,
            runtimeModes: T3RuntimeModeLabel.modes(supported: harness?.supportedRuntimeModes ?? []),
            isLocked: T3ModelChangeGate.isLocked(catalog: catalog, current: current, hasStarted: hasStarted),
            notice: notice ?? loadError,
            isApplying: isApplying
        )
    }

    /// Enabled harnesses with models; a started thread only offers its own harness.
    static func options(
        catalog: T3HarnessCatalog?,
        current: T3ModelSelection?,
        hasStarted: Bool
    ) -> [ModelSelectionOption] {
        guard let catalog else { return [] }
        return catalog.harnesses
            .filter { harness in
                if hasStarted, let current { return harness.instanceID == current.instanceID }
                return harness.isUsable || harness.instanceID == current?.instanceID
            }
            .flatMap { harness in
                harness.models.map { model in
                    ModelSelectionOption(
                        providerID: harness.instanceID,
                        providerName: harness.displayName,
                        modelID: model.slug,
                        modelName: model.label,
                        variants: [],
                        usesVerbatimModelName: true
                    )
                }
            }
    }

    private func remember(_ selection: T3ModelSelection) {
        rememberedSelection = selection
        guard let identity = currentIdentity() else { return }
        let stored = SessionModelSelection(
            providerID: selection.instanceID,
            modelID: selection.model,
            variant: nil,
            savedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000)
        )
        let worktree = worktree
        let store = store
        Task {
            await store.saveNewSessionPreference(connectionIdentity: identity, worktree: worktree, selection: stored)
        }
    }

    private func currentIdentity() -> ModelSelectionConnectionIdentity? {
        guard let profile = activeConnectionManager.currentProfile() else { return nil }
        return ModelSelectionConnectionIdentity(serverBaseURL: profile.baseURL, username: profile.username)
    }

    private func notifyChange() {
        onChange?()
    }
}

/// What the composer chip and picker sheet show for a T3 thread.
struct SessionChatHarnessPickerUiState: Equatable {
    var options: [ModelSelectionOption] = []
    var selectedOption: ModelSelectionOption?
    /// `Codex · GPT-5`
    var chipLabel: String = "Model"
    /// Same as `chipLabel` but nil until a selection is known (session menu entry).
    var selectionLabel: String?
    /// Catalogue name of the selected harness, when known.
    var harnessLabel: String?
    /// Runtime modes the selected harness accepts (every mode when unknown).
    var runtimeModes: [String] = T3RuntimeModeLabel.allModes
    /// The started thread's harness requires a new thread for model changes.
    var isLocked = false
    var notice: String?
    var isApplying = false
}
