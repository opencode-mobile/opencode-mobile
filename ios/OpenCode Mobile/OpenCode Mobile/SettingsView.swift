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
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundPrimary)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
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
