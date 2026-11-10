import Foundation

/// Home's title / server switcher chrome and switching the active Connection Profile.
/// Switches run one after another, so the last pick is where the app ends up.
@MainActor
final class HomeServers {
    private(set) var state = HomeServersChrome() {
        didSet {
            if state != oldValue { onChange?() }
        }
    }

    var onChange: (() -> Void)?
    /// A switch started: Home drops the previous server's live updates and paints `target`
    /// from cache while `generation` is still current.
    var onSwitchStarted: ((_ target: ConnectionProfile, _ generation: Int) -> Void)?
    /// The switch did not stick; Home reloads from the server that is actually active.
    var onSwitchAbandoned: (() async -> Void)?

    private let connectionProfileStore: any ConnectionProfileStore
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let currentProfile: @MainActor () -> ConnectionProfile?

    /// Server the user just picked. A late save from the previous server must not paint over it.
    private(set) var pendingID: String?
    private(set) var generation = 0
    private var switchTask: Task<Void, Never>?

    init(
        connectionProfileStore: any ConnectionProfileStore,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        currentProfile: @escaping @MainActor () -> ConnectionProfile?
    ) {
        self.connectionProfileStore = connectionProfileStore
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.currentProfile = currentProfile
    }

    func showSheet() {
        state.sheetVisible = true
    }

    func dismissSheet() {
        state.sheetVisible = false
    }

    /// True while the switch to `id` that started at `generation` is still the one in flight.
    func isCurrent(id: String, generation: Int) -> Bool {
        generation == self.generation && pendingID == id
    }

    func switchProfile(id: String) {
        guard let target = state.others.first(where: { $0.id == id }) else { return }
        pendingID = id
        generation += 1
        let generation = generation
        let previous = state.active
        state = HomeServersChrome(
            title: .verbatim(target.displayName),
            isSwitcher: true,
            active: target,
            others: ([previous].compactMap { $0 } + state.others).filter { $0.id != id },
            sheetVisible: false
        )
        // Drop the previous server's live updates immediately. Local projects paint next;
        // the connection switch and session lists catch up in the background.
        onSwitchStarted?(target, generation)
        DiagnosticLog.info(.connection, "switch profile ui id=\(id)")
        let previousSwitch = switchTask
        switchTask = Task { [weak self] in
            await previousSwitch?.value
            // A newer pick replaced this one before it started; only the newest one runs.
            guard let self, self.pendingID == id else { return }
            await self.connectionSessionCoordinator.switchProfile(id: id)
            if await self.connectionProfileStore.activeProfile()?.id != id {
                await self.abandonSwitch(id: id)
            }
        }
    }

    /// The pending switch's profile was saved: later saves are no longer ignored.
    func completeSwitch() {
        pendingID = nil
        generation += 1
    }

    /// Back to the default chrome (no Connection Profile).
    func clear() {
        state = HomeServersChrome()
    }

    func refreshChrome() async {
        let profiles = await connectionProfileStore.listProfiles()
        let active = await connectionProfileStore.activeProfile()
            ?? currentProfile()
        state = HomeServersChrome(
            title: active.map { OpenCodeText.verbatim($0.displayName) } ?? "home.sessions_title",
            isSwitcher: active != nil,
            active: active,
            others: profiles.filter { $0.id != active?.id },
            sheetVisible: state.sheetVisible
        )
    }

    /// The switch to `id` did not stick. Drop the pending pick, so later saves are not ignored,
    /// and reload Home and its title from the server that is actually active.
    private func abandonSwitch(id: String) async {
        guard pendingID == id else { return }
        DiagnosticLog.warning(.connection, "switch profile did not apply id=\(id)")
        pendingID = nil
        generation += 1
        await onSwitchAbandoned?()
    }
}
