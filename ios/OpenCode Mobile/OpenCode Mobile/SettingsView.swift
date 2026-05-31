import Factory
import SwiftUI

private struct SectionHeader: View {
    let title: String

    var body: some View {
        HStack {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
                .textCase(.uppercase)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.top, 24)
        .padding(.bottom, 4)
    }
}

struct SettingsView: View {
    let onConnectionSetup: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showComponentGallery = false
    @State private var showClearConfirmation = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Settings",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            ScrollView {
                VStack(spacing: 0) {
                    SectionHeader(title: "Server")

                    OpenCodeListItem(
                        title: "Server Connection",
                        subtitle: "Configure server URL and credentials",
                        startDecorator: .icon(.cloud, accessibilityLabel: "Server Connection"),
                        action: onConnectionSetup
                    )

                    SectionHeader(title: "Developer Tools")

                    OpenCodeListItem(
                        title: "Component Gallery",
                        subtitle: "Design-system foundations and reusable components",
                        startDecorator: .icon(.architecture, accessibilityLabel: "Component Gallery"),
                        action: { showComponentGallery = true }
                    )

                    OpenCodeListItem(
                        title: "Clear All Data",
                        subtitle: "Remove connection, cache, and imported themes",
                        startDecorator: .icon(.close, accessibilityLabel: "Clear All Data"),
                        action: { showClearConfirmation = true }
                    )
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundPrimary)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        .alert("Clear all data?", isPresented: $showClearConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                Container.clearAllData()
            }
        } message: {
            Text("This cannot be undone.")
        }
    }
}

#Preview {
    NavigationStack {
        SettingsView(onConnectionSetup: {})
    }
    .openCodeTheme()
}

#Preview("Dark") {
    NavigationStack {
        SettingsView(onConnectionSetup: {})
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
