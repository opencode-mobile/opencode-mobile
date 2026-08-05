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
    @State private var showTextMateLogbook = false
    @State private var showDiagnosticLogs = false
    @State private var showTextMateLanguages = false
    @State private var showClearConfirmation = false
    @State private var showThemePicker = false
    @State private var showRecentWindowPicker = false
    @State private var showThemeImporter = false
    @State private var importError: String?
    @State private var themeManager = ThemeManager.shared
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @State private var markdownHighlightPreferences = MarkdownHighlightPreferences.shared

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Settings",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            ScrollView {
                VStack(spacing: 8) {
                    SectionHeader(title: "Server")

                    OpenCodeListItem(
                        title: "Server Connection",
                        subtitle: "Configure server URL and credentials",
                        style: .card,
                        startDecorator: .icon(.cloud, accessibilityLabel: "Server Connection"),
                        action: onConnectionSetup
                    )
                    .padding(.horizontal, 16)

                    SectionHeader(title: "Appearance")

                    OpenCodeListItem(
                        title: "Theme",
                        subtitle: themeManager.currentTheme.name,
                        style: .card,
                        startDecorator: .icon(.widgets, accessibilityLabel: "Theme"),
                        action: { showThemePicker = true }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Session List",
                        subtitle: "Switch home between session list and project selector",
                        style: .card,
                        startDecorator: .icon(.list, accessibilityLabel: "Session List"),
                        trailing: {
                            Toggle(
                                "Session List",
                                isOn: Binding(
                                    get: { homeDisplayPreferences.showSessionListOnHome },
                                    set: { homeDisplayPreferences.setShowSessionListOnHome($0) }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Hide User Directory",
                        subtitle: "Show ~/Projects/… instead of /Users/you/Projects/…",
                        style: .card,
                        startDecorator: .icon(.folder, accessibilityLabel: "Hide User Directory"),
                        trailing: {
                            Toggle(
                                "Hide User Directory",
                                isOn: Binding(
                                    get: { homeDisplayPreferences.hideUserDirectoryPrefix },
                                    set: { homeDisplayPreferences.setHideUserDirectoryPrefix($0) }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Recent Window",
                        subtitle: HomeSessionLadder.formatWindowHoursLabel(
                            homeDisplayPreferences.recentWindowHours
                        ),
                        style: .card,
                        startDecorator: .icon(.list, accessibilityLabel: "Recent Window"),
                        action: { showRecentWindowPicker = true }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Import Theme",
                        subtitle: "Custom OpenCode OC-2 JSON file",
                        style: .card,
                        startDecorator: .icon(.folder, accessibilityLabel: "Import Theme"),
                        action: {
                            importError = nil
                            showThemeImporter = true
                        }
                    )
                    .padding(.horizontal, 16)

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
                        style: .card,
                        startDecorator: .icon(.architecture, accessibilityLabel: "Component Gallery"),
                        action: { showComponentGallery = true }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "TextMate Highlighting",
                        subtitle: "On by default for promoted languages; semantic fallback otherwise (can disable)",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "TextMate Highlighting"),
                        trailing: {
                            Toggle(
                                "TextMate Highlighting",
                                isOn: Binding(
                                    get: { markdownHighlightPreferences.textMateEnabled },
                                    set: { newValue in
                                        markdownHighlightPreferences.setTextMateEnabled(newValue)
                                        MessageContentPreparer.shared.clearDocumentCache()
                                        BlockDecorationCache.shared.clear()
                                    }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "TextMate Languages",
                        subtitle: "Opt out of promoted languages or force-enable packaged ones",
                        style: .card,
                        startDecorator: .icon(.architecture, accessibilityLabel: "TextMate Languages"),
                        action: { showTextMateLanguages = true }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "TextMate Logbook",
                        subtitle: "Persisted code-fence parsing measurements",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "TextMate Logbook"),
                        action: { showTextMateLogbook = true }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Diagnostic Logs",
                        subtitle: "Share a redacted rolling runtime log window",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Diagnostic Logs"),
                        action: { showDiagnosticLogs = true }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Clear All Data",
                        subtitle: "Remove connection, cache, and imported themes",
                        style: .card,
                        startDecorator: .icon(.close, accessibilityLabel: "Clear All Data"),
                        action: { showClearConfirmation = true }
                    )
                    .padding(.horizontal, 16)
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        .navigationDestination(isPresented: $showTextMateLanguages) {
            TextMateLanguagesView()
        }
        .navigationDestination(isPresented: $showTextMateLogbook) {
            TextMateLogbookView()
        }
        .navigationDestination(isPresented: $showDiagnosticLogs) {
            DiagnosticLogsView()
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
        .openCodeBottomSheet(
            isPresented: $showRecentWindowPicker,
            title: "Recent Window",
            scrollTargetID: "hours-\(homeDisplayPreferences.recentWindowHours)"
        ) {
            Text("Sessions stay in Recent while Running or Ready, or when updated within this window.")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

            ForEach(HomeSessionLadder.allowedWindowHours, id: \.self) { hours in
                OpenCodeBottomSheetEntry(
                    title: HomeSessionLadder.formatWindowHoursLabel(hours),
                    endDecorator: hours == homeDisplayPreferences.recentWindowHours
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        homeDisplayPreferences.setRecentWindowHours(hours)
                        showRecentWindowPicker = false
                    }
                )
                .id("hours-\(hours)")
            }
        }
        .fileImporter(
            isPresented: $showThemeImporter,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false
        ) { result in
            handleThemeImport(result)
        }
        .openCodeDialog(
            isPresented: $showClearConfirmation,
            title: "Clear all data?",
            subtitle: "This cannot be undone.",
            primaryOption: OpenCodeDialogOption(title: "Clear") {
                Container.clearAllData()
                showClearConfirmation = false
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                showClearConfirmation = false
            }
        )
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
