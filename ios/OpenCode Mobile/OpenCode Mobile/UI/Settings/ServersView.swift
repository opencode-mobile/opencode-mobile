import FactoryKit
import SwiftUI

@MainActor
@Observable
final class ServersViewModel {
    var profiles: [ConnectionProfile] = []
    var activeId: String?
    var pendingRemoval: ConnectionProfile?

    private let coordinator: ConnectionSessionCoordinator

    init(coordinator: ConnectionSessionCoordinator = Container.shared.connectionSessionCoordinator()) {
        self.coordinator = coordinator
    }

    func refresh() async {
        profiles = await coordinator.listProfiles()
        activeId = coordinator.currentProfile()?.id
    }

    func requestRemove(_ profile: ConnectionProfile) {
        pendingRemoval = profile
    }

    func dismissRemove() {
        pendingRemoval = nil
    }

    func confirmRemove() async {
        guard let pending = pendingRemoval else { return }
        await coordinator.removeProfile(id: pending.id)
        pendingRemoval = nil
        await refresh()
    }
}

struct ServersView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = ServersViewModel()
    @State private var headerTapCount = 0
    @State private var lastHeaderTapAtMs: Int64 = 0
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
                        .contentShape(Rectangle())
                        .onTapGesture {
                            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                            let next = DeveloperToolsUnlock.nextCount(
                                previousCount: headerTapCount,
                                lastTapAtMs: lastHeaderTapAtMs,
                                nowMs: nowMs
                            )
                            headerTapCount = next
                            lastHeaderTapAtMs = nowMs
                            if next >= DeveloperToolsUnlock.requiredTaps {
                                DeveloperToolsPreferences.shared.unlock()
                            }
                        }
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
                            action: { onEditProfile(profile) },
                            trailing: {
                                OpenCodeButton(
                                    variant: .iconCircle(
                                        .close,
                                        accessibilityLabel: OpenCodeText(LocalizedStringResource(
                                            "servers.remove_profile",
                                            defaultValue: "Remove \(profile.displayName)"
                                        ))
                                    ),
                                    contrast: .low,
                                    action: { viewModel.requestRemove(profile) }
                                )
                            }
                        )
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.bottom, 88)
            }
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
        .onAppear { Task { await viewModel.refresh() } }
        .onChange(of: refreshToken) { _, _ in
            Task { await viewModel.refresh() }
        }
        .openCodeDialog(
            isPresented: Binding(
                get: { viewModel.pendingRemoval != nil },
                set: { if !$0 { viewModel.dismissRemove() } }
            ),
            title: viewModel.pendingRemoval.map { profile in
                OpenCodeText(LocalizedStringResource(
                    "servers.remove_dialog.title_named",
                    defaultValue: "Remove “\(profile.displayName)”?"
                ))
            } ?? "servers.remove_dialog.title",
            subtitle: "servers.remove_dialog.message",
            primaryOption: OpenCodeDialogOption(title: "common.remove", destructive: true) {
                Task { await viewModel.confirmRemove() }
            },
            secondaryOption: OpenCodeDialogOption(title: "common.cancel") {
                viewModel.dismissRemove()
            }
        )
    }
}
