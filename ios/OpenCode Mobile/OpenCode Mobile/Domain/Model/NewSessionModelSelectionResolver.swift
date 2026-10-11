import Foundation

struct NewSessionModelSelectionResolver: Sendable {
    private let store: any SessionModelSelectionStore
    private let modelCatalogStore: ModelCatalogStore

    init(store: any SessionModelSelectionStore, modelCatalogStore: ModelCatalogStore) {
        self.store = store
        self.modelCatalogStore = modelCatalogStore
    }

    func resolve(profile: ConnectionProfile, worktree: String) async -> SessionModelSelection? {
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: profile.baseURL, username: profile.username)
        async let preferenceLoad = store.loadNewSessionPreference(connectionIdentity: identity, worktree: worktree)
        async let catalogLoad = try? modelCatalogStore.refreshCatalog(worktree: worktree)
        let (preference, fetchedCatalog) = await (preferenceLoad, catalogLoad)
        let loadedCatalog = fetchedCatalog.flatMap { $0.isEmpty ? nil : $0 }
            ?? modelCatalogStore.peek(worktree: worktree)?.catalog
            ?? fetchedCatalog
        guard let catalog = loadedCatalog else {
            OpenCodeLog.sessionChat.warning("newSessionModel catalog load failed")
            return nil
        }
        guard let option = preference.flatMap({ catalog.option(providerID: $0.providerID, modelID: $0.modelID) })
            ?? catalog.serverDefault
            ?? catalog.options.first
        else { return nil }
        let variant = preference?.variant.flatMap { option.variants.contains($0) ? $0 : nil }
        return SessionModelSelection(
            providerID: option.providerID,
            modelID: option.modelID,
            variant: variant,
            savedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000)
        )
    }

    func seedSession(
        profile: ConnectionProfile,
        worktree: String,
        sessionID: String,
        selection: SessionModelSelection
    ) async {
        await store.saveSession(
            connectionIdentity: ModelSelectionConnectionIdentity(
                serverBaseURL: profile.baseURL,
                username: profile.username
            ),
            worktree: worktree,
            sessionID: sessionID,
            selection: selection
        )
        modelCatalogStore.recordSelection(selection, worktree: worktree, sessionID: sessionID)
        OpenCodeLog.sessionChat.info("newSessionModel session handoff saved")
    }
}
