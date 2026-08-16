import Factory
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
    var onAddServer: () -> Void
    var onEditProfile: (ConnectionProfile) -> Void
    var refreshToken: Int = 0

    var body: some View {
        let countLabel = viewModel.profiles.count == 1
            ? "1 Connection Profile"
            : "\(viewModel.profiles.count) Connection Profiles"
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Servers",
                subtitle: countLabel,
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(spacing: 8) {
                    Text("SAVED")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, 16)
                    ForEach(viewModel.profiles, id: \.id) { profile in
                        OpenCodeListItem(
                            title: profile.displayName,
                            subtitle: ConnectionProfileIdentity.hostLabel(profile.baseURL),
                            status: profile.id == viewModel.activeId
                                ? OpenCodeListItemStatus(text: "Active", tone: .interactive)
                                : nil,
                            style: .card,
                            startDecorator: .letter(
                                letterCharacter(for: profile.displayName),
                                letterAccent(for: profile.id.isEmpty ? profile.displayName : profile.id)
                            ),
                            action: { onEditProfile(profile) },
                            trailing: {
                                OpenCodeButton(
                                    variant: .iconCircle(.close, accessibilityLabel: "Remove \(profile.displayName)"),
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
                variant: .fullWidthText(title: "Add server"),
                action: onAddServer
            )
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
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
            title: viewModel.pendingRemoval.map { "Remove “\($0.displayName)”?" } ?? "Remove server?",
            subtitle:
            "This device forgets the Connection Profile. Sessions stay on that server. If it’s the active server, you’ll pick another — or land on Connect if none remain.",
            primaryOption: OpenCodeDialogOption(title: "Remove", destructive: true) {
                Task { await viewModel.confirmRemove() }
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                viewModel.dismissRemove()
            }
        )
    }
}
