import Foundation

struct SessionChatProjectHeaderRegionState: Equatable {
    var identity: SessionChatProjectIdentity
}

/// The header's project identity: seeded from the inventories Home already cached, refined
/// from the server (added worktrees of the profile, current project per added path, refreshed
/// worktree inventory), and refined again when `worktree.updated` names one of the checkout's
/// projects.
@MainActor
final class SessionChatProjectHeader {
    var onChange: (() -> Void)?

    private(set) var state: SessionChatProjectHeaderRegionState

    private var worktree: String
    private let worktreeInventoryCache: WorktreeInventoryCache
    private let connectionProfileStore: any ConnectionProfileStore
    private let projectRepository: any ProjectRepository
    private let worktreeRepository: any WorktreeRepository
    private let foregroundEventStream: any ForegroundEventStream
    private let currentProfile: () -> ConnectionProfile?
    /// The connection's base URL, the key of `worktreeInventoryCache`.
    private let inventoryServerKey: () -> String?
    private var checkoutProjectIDs: Set<String> = []
    private var refreshTask: Task<Void, Never>?
    private var eventTask: Task<Void, Never>?

    init(
        worktree: String,
        worktreeInventoryCache: WorktreeInventoryCache,
        connectionProfileStore: any ConnectionProfileStore,
        projectRepository: any ProjectRepository,
        worktreeRepository: any WorktreeRepository,
        foregroundEventStream: any ForegroundEventStream,
        currentProfile: @escaping () -> ConnectionProfile?,
        inventoryServerKey: @escaping () -> String?
    ) {
        self.worktree = worktree
        self.worktreeInventoryCache = worktreeInventoryCache
        self.connectionProfileStore = connectionProfileStore
        self.projectRepository = projectRepository
        self.worktreeRepository = worktreeRepository
        self.foregroundEventStream = foregroundEventStream
        self.currentProfile = currentProfile
        self.inventoryServerKey = inventoryServerKey
        // What Home already loaded decides the identity before the first frame; `refresh()`
        // only changes it when the server now answers differently.
        self.state = SessionChatProjectHeaderRegionState(
            identity: Self.cachedIdentity(
                worktree: worktree,
                serverBaseURL: inventoryServerKey(),
                cache: worktreeInventoryCache
            ) ?? .folder(worktree)
        )
    }

    var identity: SessionChatProjectIdentity { state.identity }

    func retarget(worktree: String) {
        self.worktree = worktree
    }

    /// Re-resolves the identity from the server. Keeps the identity on screen until the answer
    /// lands, so an agreeing answer changes nothing.
    func refresh() {
        refreshTask?.cancel()
        guard !worktree.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            apply(.none)
            return
        }
        if let cached = Self.cachedIdentity(
            worktree: worktree,
            serverBaseURL: inventoryServerKey(),
            cache: worktreeInventoryCache
        ) {
            apply(cached)
        }
        checkoutProjectIDs = []
        refreshTask = Task { [worktree, connectionProfileStore] in
            let baseURL: String?
            if let current = currentProfile()?.baseURL, !current.isEmpty {
                baseURL = current
            } else if let stored = await connectionProfileStore.activeProfile()?.baseURL, !stored.isEmpty {
                baseURL = stored
            } else {
                baseURL = nil
            }
            let peers: [String]
            if let baseURL {
                peers = await connectionProfileStore.addedWorktrees(serverBaseURL: baseURL)
            } else {
                peers = []
            }
            guard !Task.isCancelled else { return }
            let inventories = await loadInventories(peers: peers)
            guard !Task.isCancelled, let inventories else { return }
            apply(.resolve(worktree: worktree, addedPaths: peers) { inventories[$0] ?? .unloaded })
        }
    }

    /// Refreshes when `worktree.updated` names one of the checkout's projects.
    func startObserving() {
        eventTask?.cancel()
        eventTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.foregroundEventStream.events() {
                guard event.isWorktreeUpdated, let projectID = event.namedProjectID else { continue }
                guard self.checkoutProjectIDs.contains(projectID) else { continue }
                self.refresh()
            }
        }
    }

    func stopObserving() {
        refreshTask?.cancel()
        refreshTask = nil
        eventTask?.cancel()
        eventTask = nil
    }

    /// Identity from the inventories Home (or an earlier Session Chat) loaded; nil when the
    /// added paths of this server are not known yet.
    private static func cachedIdentity(
        worktree: String,
        serverBaseURL: String?,
        cache: WorktreeInventoryCache
    ) -> SessionChatProjectIdentity? {
        guard let serverBaseURL, let snapshot = cache.snapshot(server: serverBaseURL) else { return nil }
        return .resolve(worktree: worktree, addedPaths: snapshot.addedPaths, inventoryFor: snapshot.inventory(for:))
    }

    private func apply(_ identity: SessionChatProjectIdentity) {
        guard identity != state.identity else { return }
        state.identity = identity
        onChange?()
    }

    /// Fresh inventory of every added path. A load that did not succeed falls back to the last
    /// known one, so a transient failure does not turn a checkout back into a project letter.
    /// Nil when cancelled.
    private func loadInventories(peers: [String]) async -> [String: WorktreeInventory]? {
        let server = inventoryServerKey()
        let cached = server.flatMap { worktreeInventoryCache.snapshot(server: $0) }
        if let server {
            worktreeInventoryCache.setAddedPaths(peers, server: server)
        }
        guard !peers.isEmpty else { return [:] }
        let baseURL = await connectionProfileStore.activeProfile()?.baseURL ?? currentProfile()?.baseURL
        if let baseURL, !baseURL.isEmpty {
            worktreeRepository.onConnection(key: baseURL)
        }
        var inventories: [String: WorktreeInventory] = [:]
        var projectIDs: Set<String> = []
        for added in peers {
            if Task.isCancelled { return nil }
            let projectID = try? await projectRepository.getCurrentProject(directory: added).id
            let usable = projectID?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let usable, !usable.isEmpty {
                projectIDs.insert(usable)
                if Task.isCancelled { return nil }
                checkoutProjectIDs.insert(usable)
            }
            let loaded = await worktreeRepository.loadRefreshed(addedPath: added, projectID: usable)
            switch loaded {
            case let .loaded(directories, _):
                let inventory = WorktreeInventory.loaded(directories)
                inventories[added] = inventory
                if let server {
                    worktreeInventoryCache.record(inventory, addedPath: added, server: server)
                }
            case .failed:
                inventories[added] = cached?.inventories[added] ?? .failed
            case .unloaded, .stopped:
                inventories[added] = cached?.inventories[added] ?? .unloaded
            }
        }
        if Task.isCancelled { return nil }
        checkoutProjectIDs = projectIDs
        return inventories
    }
}
