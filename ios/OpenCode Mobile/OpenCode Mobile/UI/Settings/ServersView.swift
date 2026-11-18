import FactoryKit
import SwiftUI

@MainActor
@Observable
final class ServersViewModel {
    var profiles: [ConnectionProfile] = []
    var activeId: String?
    /// Server a tap is switching to; further taps wait until it lands.
    private(set) var switchingID: String?
    /// Server Health summary for the active profile's card; nil without an active profile.
    private(set) var healthQuickView: ServerHealthQuickView?
    /// Set to open Server Health.
    var healthRoute: ServerHealthRoute?

    private let coordinator: ConnectionSessionCoordinator
    @ObservationIgnored private let healthMonitor: any ServerHealthMonitoring
    @ObservationIgnored private let healthStore: any ServerHealthStore
    @ObservationIgnored private let now: () -> Int64

    init(
        coordinator: ConnectionSessionCoordinator = Container.shared.connectionSessionCoordinator(),
        healthMonitor: any ServerHealthMonitoring = Container.shared.serverHealthMonitor(),
        healthStore: any ServerHealthStore = Container.shared.serverHealthStore(),
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.coordinator = coordinator
        self.healthMonitor = healthMonitor
        self.healthStore = healthStore
        self.now = now
    }

    func refresh() async {
        profiles = await coordinator.listProfiles()
        activeId = coordinator.currentProfile()?.id
        await refreshHealth()
    }

    func openHealth(profileID: String) {
        guard let profile = profiles.first(where: { $0.id == profileID }) else { return }
        healthRoute = ServerHealthRoute(profile: profile)
    }

    /// Keeps the quick view current until the task is cancelled.
    func observeHealth() async {
        for await _ in healthMonitor.updates() {
            await refreshHealth()
        }
    }

    /// Recomputes the quick view for the active profile only, over the last 24 hours.
    func refreshHealth() async {
        guard let profileID = activeId, !profileID.isEmpty else {
            healthQuickView = nil
            return
        }
        let summary = await Self.daySummary(store: healthStore, profileID: profileID, nowMs: now())
        guard activeId == profileID else { return }
        let live = healthMonitor.liveStatus
        let isLive = live.profileID == profileID
        healthQuickView = ServerHealthQuickView(
            state: isLive ? live.state : .stopped,
            currentPingMs: isLive ? live.lastPingMs : nil,
            recentPings: summary.recentPings,
            uptimePercent: summary.uptimePercent,
            reconnects: summary.reconnects
        )
    }

    private struct DaySummary: Sendable {
        let recentPings: [Int?]
        let uptimePercent: Double?
        let reconnects: Int
    }

    /// A day of pings and its report, read and built off the MainActor.
    @concurrent
    private nonisolated static func daySummary(
        store: any ServerHealthStore,
        profileID: String,
        nowMs: Int64
    ) async -> DaySummary {
        let since = nowMs - ServerHealthRange.day.durationMs
        let pings = await store.pings(profileID: profileID, sinceMs: since)
        let events = await store.events(profileID: profileID, sinceMs: since)
        let report = ServerHealthReportBuilder.build(pings: pings, events: events, nowMs: nowMs, range: .day)
        return DaySummary(
            recentPings: ServerHealthReportBuilder.recentPings(pings, nowMs: nowMs),
            uptimePercent: report.uptimePercent,
            reconnects: report.drops
        )
    }

    /// Makes `profileID` the active server, the same switch as a pick in the Home server sheet.
    /// Ignores the active server and taps while an earlier switch is still running.
    func switchTo(profileID: String) async {
        guard switchingID == nil, profileID != activeId,
              profiles.contains(where: { $0.id == profileID }) else { return }
        switchingID = profileID
        defer { switchingID = nil }
        await coordinator.switchProfile(id: profileID)
        await refresh()
        if activeId != profileID {
            DiagnosticLog.warning(.connection, "servers switch did not apply id=\(profileID)")
        }
    }
}

struct ServersView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = ServersViewModel()
    var onAddServer: () -> Void
    var onEditProfile: (ConnectionProfile) -> Void
    var refreshToken: Int = 0

    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil

    var body: some View {
        let countLabel = OpenCodeText(LocalizedStringResource(
            "servers.profile_count",
            defaultValue: "\(viewModel.profiles.count) Connection Profiles"
        ))
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "servers.title",
                subtitle: countLabel,
                leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                showDivider: true
            )
            VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 8) {
                    OpenCodeSectionHeader(title: "servers.saved")
                    ForEach(viewModel.profiles, id: \.id) { profile in
                        let isActive = profile.id == viewModel.activeId
                        OpenCodeListItem(
                            title: .verbatim(profile.displayName),
                            subtitle: .verbatim(ConnectionProfileIdentity.hostLabel(profile.baseURL)),
                            status: ConnectionProfileListMeta.status(profile, isActive: isActive),
                            caption: .verbatim(ifPresent: ConnectionProfileListMeta.caption(profile, isActive: isActive)),
                            style: .card,
                            startDecorator: .letter(
                                letterCharacter(for: profile.displayName),
                                letterAccent(for: profile.id.isEmpty ? profile.displayName : profile.id)
                            ),
                            // A tap makes the server active; Edit (and Remove inside it) is the trailing button.
                            action: isActive ? nil : {
                                Task { await viewModel.switchTo(profileID: profile.id) }
                            },
                            trailing: {
                                OpenCodeButton(
                                    variant: .iconCircle(
                                        .edit,
                                        accessibilityLabel: OpenCodeText(LocalizedStringResource(
                                            "servers.edit_profile",
                                            defaultValue: "Edit \(profile.displayName)"
                                        ))
                                    ),
                                    contrast: .low,
                                    action: { onEditProfile(profile) }
                                )
                            },
                            footer: healthFooter(isActive: isActive, profile: profile)
                        )
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.bottom, 88)
            }
            .openCodeFocusGroup(.vertical)
            OpenCodeButton(
                variant: .fullWidthText(title: "servers.add"),
                action: onAddServer
            )
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
            }
            .openCodeReadableColumn()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .task { await viewModel.refresh() }
        .task { await viewModel.observeHealth() }
        .navigationDestination(item: $viewModel.healthRoute) { route in
            ServerHealthView(route: route)
        }
        .onAppear { Task { await viewModel.refresh() } }
        .onChange(of: refreshToken) { _, _ in
            Task { await viewModel.refresh() }
        }
    }

    /// Server Health summary under the active profile; inactive profiles have none.
    private func healthFooter(isActive: Bool, profile: ConnectionProfile) -> AnyView? {
        guard isActive, let quickView = viewModel.healthQuickView else { return nil }
        return AnyView(ServerHealthQuickFooter(quickView: quickView) {
            viewModel.openHealth(profileID: profile.id)
        })
    }
}
