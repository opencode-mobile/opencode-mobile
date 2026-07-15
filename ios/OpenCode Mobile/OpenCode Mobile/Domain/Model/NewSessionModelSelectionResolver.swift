import Foundation
import OSLog

struct NewSessionModelSelectionResolver: Sendable {
    private let store: any SessionModelSelectionStore
    private let modelRepository: any ModelRepository

    init(store: any SessionModelSelectionStore, modelRepository: any ModelRepository) {
        self.store = store
        self.modelRepository = modelRepository
    }

    func resolve(profile: ConnectionProfile, worktree: String) async -> SessionModelSelection? {
        let identity = ModelSelectionConnectionIdentity(serverBaseURL: profile.baseURL, username: profile.username)
        async let preferenceLoad = store.loadNewSessionPreference(connectionIdentity: identity, worktree: worktree)
        async let catalogLoad = try? modelRepository.listModelOptions(directory: worktree)
        let (preference, loadedCatalog) = await (preferenceLoad, catalogLoad)
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
        OpenCodeLog.sessionChat.info("newSessionModel session handoff saved")
    }
}
