import Factory
import SwiftUI
import UniformTypeIdentifiers

private struct SectionHeader: View {
    let title: String

    var body: some View {
        HStack {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
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
    @State private var showThemePicker = false
    @State private var showThemeImporter = false
    @State private var importError: String?
    @State private var themeManager = ThemeManager.shared

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

                    SectionHeader(title: "Appearance")

                    OpenCodeListItem(
                        title: "Theme",
                        subtitle: themeManager.currentTheme.name,
                        startDecorator: .icon(.widgets, accessibilityLabel: "Theme"),
                        action: { showThemePicker = true }
                    )

                    OpenCodeListItem(
                        title: "Import Theme",
                        subtitle: "Custom OpenCode OC-2 JSON file",
                        startDecorator: .icon(.folder, accessibilityLabel: "Import Theme"),
                        action: {
                            importError = nil
                            showThemeImporter = true
                        }
                    )

                    if let importError {
                        Text(importError)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.top, 4)
                    } else if let loadError = themeManager.loadError {
                        Text(loadError)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.top, 4)
                    }

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
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        .openCodeBottomSheet(
            isPresented: $showThemePicker,
            title: "Theme",
            scrollTargetID: themeManager.currentTheme.id
        ) {
            ForEach(themeManager.availablePresets()) { preset in
                OpenCodeBottomSheetEntry(
                    title: preset.name,
                    endDecorator: preset.id == themeManager.currentTheme.id
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        themeManager.applyPreset(id: preset.id)
                        showThemePicker = false
                    }
                )
                .id(preset.id)
            }
        }
        .fileImporter(
            isPresented: $showThemeImporter,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false
        ) { result in
            handleThemeImport(result)
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

    private func handleThemeImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            do {
                try themeManager.importTheme(from: url)
                importError = nil
            } catch {
                importError = error.localizedDescription
            }
        case .failure(let error):
            importError = error.localizedDescription
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
