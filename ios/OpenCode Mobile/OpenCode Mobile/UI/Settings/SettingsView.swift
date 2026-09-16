import Factory
import SwiftUI
import UIKit

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
    var onAddServer: () -> Void = {}
    var onEditProfile: (ConnectionProfile) -> Void = { _ in }
    var serversRefreshToken: Int = 0
    var version: String = AppVersion.current().version
    var build: String = AppVersion.current().build
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var showComponentGallery = false
    @State private var showTextMateLogbook = false
    @State private var showDiagnosticLogs = false
    @State private var showTextMateLanguages = false
    @State private var showClearConfirmation = false
    @State private var showClearSessionCacheConfirmation = false
    @State private var sessionCacheSizeLabel = "Empty"
    @State private var showAppearance = false
    @State private var showRecentWindowPicker = false
    @State private var showFollowUpPicker = false
    @State private var themeManager = ThemeManager.shared
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @State private var markdownHighlightPreferences = MarkdownHighlightPreferences.shared
    @State private var analyticsConsentPreferences = AnalyticsConsentPreferences.shared
    @State private var sessionChatDisplayPreferences = SessionChatDisplayPreferences.shared
    @State private var developerToolsPreferences = DeveloperToolsPreferences.shared
    @State private var showServers = false
    @State private var serversSubtitleState = "No servers saved"
    @State private var serversLetter: Character = "S"
    @State private var serversLetterAccent: LetterAccent = .blue

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Settings",
                leading: .back(action: {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.settings,
                        actionId: ProductAnalytics.Action.settingsBack
                    )
                    dismiss()
                }),
                showDivider: true
            )

            ScrollView {
                VStack(spacing: 8) {
                    SectionHeader(title: "Server")

                    OpenCodeListItem(
                        title: "Servers",
                        subtitle: serversSubtitleState,
                        style: .card,
                        startDecorator: .letter(serversLetter, serversLetterAccent),
                        action: {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.settings,
                                actionId: ProductAnalytics.Action.settingsOpenConnection
                            )
                            showServers = true
                        },
                        trailing: {
                            OpenCodeIconImage(icon: .chevronRight, size: 18)
                                .foregroundStyle(OpenCodeColors.textBase)
                        }
                    )
                    .padding(.horizontal, 16)

                    SectionHeader(title: "Appearance")

                    OpenCodeListItem(
                        title: "Appearance",
                        subtitle: "\(themeManager.currentTheme.name) · \(themeManager.currentSyntaxPreset.name)",
                        style: .card,
                        startDecorator: .icon(.widgets, accessibilityLabel: "Appearance"),
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsOpenAppearance)
                            showAppearance = true
                        },
                        trailing: {
                            OpenCodeIconImage(icon: .chevronRight, size: 18)
                                .foregroundStyle(OpenCodeColors.textBase)
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
                                    set: { newValue in
                                        logSettingsAction(
                                            ProductAnalytics.Action.settingsToggleHideUserDirectory
                                        )
                                        homeDisplayPreferences.setHideUserDirectoryPrefix(newValue)
                                    }
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
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsOpenRecentWindowPicker)
                            ProductAnalytics.logScreenView(
                                screenId: ProductAnalytics.Screen.settingsRecentWindowPicker,
                                presentation: ProductAnalytics.Presentation.sheet,
                                trigger: ProductAnalytics.Trigger.present
                            )
                            showRecentWindowPicker = true
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Project Picker",
                        subtitle: "Replace home filter chips with a project picker",
                        style: .card,
                        startDecorator: .icon(.widgets, accessibilityLabel: "Project Picker"),
                        trailing: {
                            Toggle(
                                "Project Picker",
                                isOn: Binding(
                                    get: { homeDisplayPreferences.sessionListProjectPickerEnabled },
                                    set: { newValue in
                                        logSettingsAction(
                                            ProductAnalytics.Action.settingsToggleSessionListProjectPicker
                                        )
                                        homeDisplayPreferences.setSessionListProjectPickerEnabled(newValue)
                                    }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Collapse Consecutive Tool Calls",
                        subtitle: "Group back-to-back tools into one summary card",
                        style: .card,
                        startDecorator: .icon(.collapseContent, accessibilityLabel: "Collapse Consecutive Tool Calls"),
                        trailing: {
                            Toggle(
                                "Collapse Consecutive Tool Calls",
                                isOn: Binding(
                                    get: { sessionChatDisplayPreferences.collapseConsecutiveToolCalls },
                                    set: { newValue in
                                        logSettingsAction(
                                            ProductAnalytics.Action.settingsToggleCollapseConsecutiveToolCalls
                                        )
                                        sessionChatDisplayPreferences.setCollapseConsecutiveToolCalls(newValue)
                                    }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Smooth Streaming",
                        subtitle: "Paint assistant text in short chunks instead of every token",
                        style: .card,
                        startDecorator: .icon(.expandContent, accessibilityLabel: "Smooth Streaming"),
                        trailing: {
                            Toggle(
                                "Smooth Streaming",
                                isOn: Binding(
                                    get: { sessionChatDisplayPreferences.smoothStreamingUpdates },
                                    set: { newValue in
                                        logSettingsAction(
                                            ProductAnalytics.Action.settingsToggleSmoothStreaming
                                        )
                                        sessionChatDisplayPreferences.setSmoothStreamingUpdates(newValue)
                                    }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Follow-up send",
                        subtitle: followUpSubtitle,
                        enabled: supportsFollowUpDelivery,
                        style: .card,
                        startDecorator: .icon(
                            followUpIcon,
                            accessibilityLabel: "Follow-up send"
                        ),
                        action: supportsFollowUpDelivery ? { showFollowUpPicker = true } : nil
                    )
                    .padding(.horizontal, 16)

                    if let loadError = themeManager.loadError {
                        Text(loadError)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.top, 4)
                    }

                    SectionHeader(title: "Session Cache")

                    OpenCodeListItem(
                        title: "Size",
                        subtitle: sessionCacheSizeLabel,
                        style: .card,
                        startDecorator: .icon(.archive, accessibilityLabel: "Session Cache Size")
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Clear Session Cache",
                        subtitle: "Remove cached sessions and messages",
                        style: .card,
                        startDecorator: .icon(.close, accessibilityLabel: "Clear Session Cache"),
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsClearSessionCache)
                            ProductAnalytics.logScreenView(
                                screenId: ProductAnalytics.Screen.settingsClearCacheConfirm,
                                presentation: ProductAnalytics.Presentation.dialog,
                                trigger: ProductAnalytics.Trigger.present
                            )
                            showClearSessionCacheConfirmation = true
                        }
                    )
                    .padding(.horizontal, 16)

                    SectionHeader(title: "Privacy")

                    OpenCodeListItem(
                        title: "Privacy Policy",
                        subtitle: "How the apps handle data",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Privacy Policy"),
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsOpenPrivacyPolicy)
                            openURL(PrivacyPolicy.url)
                        },
                        trailing: {
                            OpenCodeIconImage(icon: .chevronRight, size: 18)
                                .foregroundStyle(OpenCodeColors.textBase)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Product Analytics",
                        subtitle: "Allow optional product usage events. Crash reporting is unchanged.",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Product Analytics"),
                        trailing: {
                            Toggle(
                                "Product Analytics",
                                isOn: Binding(
                                    get: { analyticsConsentPreferences.productAnalyticsConsented },
                                    set: { newValue in
                                        // Log after applying consent: opting out is
                                        // dropped by the gate rather than reported.
                                        analyticsConsentPreferences
                                            .setProductAnalyticsConsented(newValue)
                                        logSettingsAction(
                                            ProductAnalytics.Action.settingsToggleProductAnalytics
                                        )
                                    }
                                )
                            )
                            .labelsHidden()
                            .tint(OpenCodeColors.interactive)
                        }
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
                        subtitle: "Remove connection and cache",
                        style: .card,
                        startDecorator: .icon(.close, accessibilityLabel: "Clear All Data"),
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsClearAllData)
                            ProductAnalytics.logScreenView(
                                screenId: ProductAnalytics.Screen.settingsClearAllDataConfirm,
                                presentation: ProductAnalytics.Presentation.dialog,
                                trigger: ProductAnalytics.Trigger.present
                            )
                            showClearConfirmation = true
                        }
                    )
                    .padding(.horizontal, 16)

                    SectionHeader(title: "About")

                    OpenCodeListItem(
                        title: "Version",
                        subtitle: version,
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Version"),
                        action: { UIPasteboard.general.string = version }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Build",
                        subtitle: build,
                        style: .card,
                        startDecorator: .icon(.settings, accessibilityLabel: "Build"),
                        action: { UIPasteboard.general.string = build }
                    )
                    .padding(.horizontal, 16)

                    if developerToolsPreferences.unlocked {
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
                            subtitle: "On by default for all packaged languages; semantic fallback otherwise (can disable)",
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
                            subtitle: "Opt out of any packaged language (all default on)",
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
                    }
                }
            }
            .openCodeReadableColumn()
            .accessibilityIdentifier("settingsContent")
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
        .navigationDestination(isPresented: $showAppearance) {
            AppearanceSettingsView()
        }
        .openCodeBottomSheet(
            isPresented: recentWindowPickerBinding,
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
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.settingsRecentWindowPicker,
                            actionId: ProductAnalytics.Action.recentWindowSelectHours,
                            parameters: [ProductAnalytics.Param.hours: String(hours)]
                        )
                        homeDisplayPreferences.setRecentWindowHours(hours)
                        showRecentWindowPicker = false
                    }
                )
                .id("hours-\(hours)")
            }
        }
        .openCodeBottomSheet(isPresented: $showFollowUpPicker, title: "Follow-up send") {
            ForEach(FollowUpDelivery.allCases, id: \.self) { mode in
                OpenCodeBottomSheetEntry(
                    title: mode == .steer ? "Steer" : "Queue",
                    startDecorator: .icon(mode == .steer ? .arrowWarmUp : .arrowUpward, accessibilityLabel: nil),
                    endDecorator: mode == sessionChatDisplayPreferences.followUpDelivery
                        ? .icon(.check, accessibilityLabel: "Selected")
                        : .none,
                    action: {
                        sessionChatDisplayPreferences.setFollowUpDelivery(mode)
                        showFollowUpPicker = false
                    }
                )
            }
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.settings,
                presentation: ProductAnalytics.Presentation.route
            )
            sessionCacheSizeLabel = await Container.refreshSessionCacheSizeLabel()
        }
        .openCodeDialog(
            isPresented: $showClearSessionCacheConfirmation,
            title: "Clear session cache?",
            subtitle: "Removes cached sessions and messages. Your connection and credentials stay.",
            primaryOption: OpenCodeDialogOption(title: "Clear") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.settingsClearCacheConfirm,
                    actionId: ProductAnalytics.Action.clearCacheConfirm
                )
                Task {
                    await Container.clearSessionCache()
                    sessionCacheSizeLabel = await Container.refreshSessionCacheSizeLabel()
                }
                showClearSessionCacheConfirmation = false
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.settingsClearCacheConfirm,
                    actionId: ProductAnalytics.Action.clearCacheCancel
                )
                showClearSessionCacheConfirmation = false
            }
        )
        .openCodeDialog(
            isPresented: $showClearConfirmation,
            title: "Clear all data?",
            subtitle: "This cannot be undone.",
            primaryOption: OpenCodeDialogOption(title: "Clear") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.settingsClearAllDataConfirm,
                    actionId: ProductAnalytics.Action.clearAllDataConfirm
                )
                Container.clearAllData()
                Task {
                    sessionCacheSizeLabel = await Container.refreshSessionCacheSizeLabel()
                }
                showClearConfirmation = false
            },
            secondaryOption: OpenCodeDialogOption(title: "Cancel") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.settingsClearAllDataConfirm,
                    actionId: ProductAnalytics.Action.clearAllDataCancel
                )
                showClearConfirmation = false
            }
        )
        .navigationDestination(isPresented: $showServers) {
            ServersView(
                onAddServer: onAddServer,
                onEditProfile: onEditProfile,
                refreshToken: serversRefreshToken
            )
        }
        .task { await refreshServersSubtitle() }
        .onChange(of: showServers) { _, visible in
            if !visible {
                Task { await refreshServersSubtitle() }
            }
        }
    }

    private func refreshServersSubtitle() async {
        let profiles = await Container.shared.connectionSessionCoordinator().listProfiles()
        let active = Container.shared.connectionSessionCoordinator().currentProfile()
        let name = active?.displayName ?? "Servers"
        serversLetter = letterCharacter(for: name)
        serversLetterAccent = letterAccent(for: active?.id ?? name)
        if profiles.isEmpty {
            serversSubtitleState = "No servers saved"
        } else if let activeName = active?.displayName {
            serversSubtitleState = "\(profiles.count) saved · \(activeName) active"
        } else {
            serversSubtitleState = "\(profiles.count) saved"
        }
    }

    private var supportsFollowUpDelivery: Bool {
        Container.shared.activeConnectionManager().capabilities.supportsFollowUpDelivery
    }

    private var followUpSubtitle: String {
        if supportsFollowUpDelivery {
            let label = sessionChatDisplayPreferences.followUpDelivery == .steer ? "Steer" : "Queue"
            return "\(label) · V2 servers only. V1 does not support this."
        }
        return "Not supported on V1. This server always sends immediately."
    }

    private var followUpIcon: OpenCodeIcon {
        if supportsFollowUpDelivery, sessionChatDisplayPreferences.followUpDelivery == .steer {
            return .arrowWarmUp
        }
        return .arrowUpward
    }

    private func logSettingsAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.settings,
            actionId: actionId
        )
    }

    private var recentWindowPickerBinding: Binding<Bool> {
        Binding(
            get: { showRecentWindowPicker },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.settingsRecentWindowPicker,
                        actionId: ProductAnalytics.Action.recentWindowDismiss
                    )
                }
                showRecentWindowPicker = presented
            }
        )
    }

}

#Preview {
    NavigationStack {
        SettingsView(version: "1.0", build: "1")
    }
    .openCodeTheme()
}

#Preview("Dark") {
    NavigationStack {
        SettingsView(version: "1.0", build: "1")
    }
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
