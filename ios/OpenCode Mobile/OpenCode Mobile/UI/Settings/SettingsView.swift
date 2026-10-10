import Factory
import SwiftUI
import UIKit

/// Settings sub-screen shown in the trailing pane of the tablet split view.
enum SettingsDetail: Hashable {
    case servers
    case appearance
    case componentGallery
    case textMateLanguages
    case textMateLogbook
    case featureFlags
    case diagnosticLogs

    /// Whether the screen lives in the Developer Tools section that Clear All Data locks.
    var requiresDeveloperTools: Bool {
        switch self {
        case .servers, .appearance, .diagnosticLogs:
            false
        case .componentGallery, .textMateLanguages, .textMateLogbook, .featureFlags:
            true
        }
    }

    /// The detail to show: a locked Developer Tools screen falls back to Servers.
    func resolved(developerToolsUnlocked: Bool) -> SettingsDetail {
        requiresDeveloperTools && !developerToolsUnlocked ? .servers : self
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
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// Trailing-pane sub-screen in the regular-width split view. Unused on compact.
    @State private var selectedDetail: SettingsDetail = .servers
    @State private var showComponentGallery = false
    @State private var showTextMateLogbook = false
    @State private var showDiagnosticLogs = false
    @State private var showTextMateLanguages = false
    @State private var showFeatureFlags = false
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
        Group {
            if isSplitLayout {
                OpenCodeSplitPane(
                    screen: .settings,
                    onCollapsedChange: { _ in
                        logSettingsAction(ProductAnalytics.Action.splitPaneToggleSidebar)
                    }
                ) {
                    settingsPane
                } trailing: { control in
                    settingsDetailPane(control)
                }
            } else {
                settingsPane
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
        .navigationDestination(isPresented: $showFeatureFlags) {
            FeatureFlagsView()
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
        .onChange(of: visibleDetail) { previous, _ in
            if previous == .servers {
                Task { await refreshServersSubtitle() }
            }
        }
        .onChange(of: isSplitLayout) { _, split in
            // Keep the open sub-screen when the window narrows to compact. The
            // selection stays so widening again shows the same detail.
            if !split {
                push(visibleDetail)
            }
        }
    }

    private var settingsPane: some View {
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
                    OpenCodeSectionHeader(title: "Server")

                    OpenCodeListItem(
                        title: "Servers",
                        subtitle: serversSubtitleState,
                        selected: isDetailSelected(.servers),
                        style: .card,
                        startDecorator: .letter(serversLetter, serversLetterAccent),
                        accessory: .disclosure,
                        action: {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.settings,
                                actionId: ProductAnalytics.Action.settingsOpenConnection
                            )
                            open(.servers)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeSectionHeader(title: "Appearance")

                    OpenCodeListItem(
                        title: "Appearance",
                        subtitle: "\(themeManager.currentTheme.name) · \(themeManager.currentSyntaxPreset.name)",
                        selected: isDetailSelected(.appearance),
                        style: .card,
                        startDecorator: .icon(.widgets, accessibilityLabel: "Appearance"),
                        accessory: .disclosure,
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsOpenAppearance)
                            open(.appearance)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Hide User Directory",
                        subtitle: "Show ~/Projects/… instead of /Users/you/Projects/…",
                        style: .card,
                        startDecorator: .icon(.folder, accessibilityLabel: "Hide User Directory"),
                        accessory: .toggle(
                            isOn: Binding(
                                get: { homeDisplayPreferences.hideUserDirectoryPrefix },
                                set: { newValue in
                                    logSettingsAction(
                                        ProductAnalytics.Action.settingsToggleHideUserDirectory
                                    )
                                    homeDisplayPreferences.setHideUserDirectoryPrefix(newValue)
                                }
                            ),
                            accessibilityLabel: "Hide User Directory"
                        )
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
                        title: "Collapse Consecutive Tool Calls",
                        subtitle: "Group back-to-back tools into one summary card",
                        style: .card,
                        startDecorator: .icon(.collapseContent, accessibilityLabel: "Collapse Consecutive Tool Calls"),
                        accessory: .toggle(
                            isOn: Binding(
                                get: { sessionChatDisplayPreferences.collapseConsecutiveToolCalls },
                                set: { newValue in
                                    logSettingsAction(
                                        ProductAnalytics.Action.settingsToggleCollapseConsecutiveToolCalls
                                    )
                                    sessionChatDisplayPreferences.setCollapseConsecutiveToolCalls(newValue)
                                }
                            ),
                            accessibilityLabel: "Collapse Consecutive Tool Calls"
                        )
                    )
                    .padding(.horizontal, 16)

                    if sessionChatDisplayPreferences.chipTimelineAvailable {
                        OpenCodeListItem(
                            title: "Chip Timeline",
                            subtitle: "Show the tools an agent used as chips under its narration",
                            style: .card,
                            startDecorator: .icon(.widgets, accessibilityLabel: "Chip Timeline"),
                            accessory: .toggle(
                                isOn: Binding(
                                    get: { sessionChatDisplayPreferences.chipTimeline },
                                    set: { newValue in
                                        logSettingsAction(
                                            ProductAnalytics.Action.settingsToggleChipTimeline
                                        )
                                        sessionChatDisplayPreferences.setChipTimeline(newValue)
                                    }
                                ),
                                accessibilityLabel: "Chip Timeline"
                            )
                        )
                        .padding(.horizontal, 16)
                    }

                    OpenCodeListItem(
                        title: "Follow-up send",
                        subtitle: followUpSubtitle,
                        enabled: supportsFollowUpDelivery,
                        style: .card,
                        startDecorator: .icon(
                            followUpIcon,
                            accessibilityLabel: "Follow-up send"
                        ),
                        accessory: supportsFollowUpDelivery ? .disclosure : nil,
                        action: supportsFollowUpDelivery ? { showFollowUpPicker = true } : nil
                    )
                    .padding(.horizontal, 16)

                    if let loadError = themeManager.loadError {
                        OpenCodeInlineNotice(message: loadError, tone: .error)
                            .padding(.horizontal, 16)
                            .padding(.top, 4)
                    }

                    OpenCodeSectionHeader(title: "Session Cache")

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

                    OpenCodeSectionHeader(title: "Privacy")

                    OpenCodeListItem(
                        title: "Privacy Policy",
                        subtitle: "How the apps handle data",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Privacy Policy"),
                        accessory: .disclosure,
                        action: {
                            logSettingsAction(ProductAnalytics.Action.settingsOpenPrivacyPolicy)
                            openURL(PrivacyPolicy.url)
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Product Analytics",
                        subtitle: "Allow optional product usage events. Crash reporting is unchanged.",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Product Analytics"),
                        accessory: .toggle(
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
                            ),
                            accessibilityLabel: "Product Analytics"
                        )
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Diagnostic Logs",
                        subtitle: "Share a redacted rolling runtime log window",
                        selected: isDetailSelected(.diagnosticLogs),
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Diagnostic Logs"),
                        action: { open(.diagnosticLogs) }
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

                    OpenCodeSectionHeader(title: "About")

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
                        OpenCodeSectionHeader(title: "Developer Tools")

                        OpenCodeListItem(
                            title: "Component Gallery",
                            subtitle: "Design-system foundations and reusable components",
                            selected: isDetailSelected(.componentGallery),
                            style: .card,
                            startDecorator: .icon(.architecture, accessibilityLabel: "Component Gallery"),
                            action: { open(.componentGallery) }
                        )
                        .padding(.horizontal, 16)

                        OpenCodeListItem(
                            title: "TextMate Highlighting",
                            subtitle: "On by default for all packaged languages; semantic fallback otherwise (can disable)",
                            style: .card,
                            startDecorator: .icon(.description, accessibilityLabel: "TextMate Highlighting"),
                            accessory: .toggle(
                                isOn: Binding(
                                    get: { markdownHighlightPreferences.textMateEnabled },
                                    set: { newValue in
                                        markdownHighlightPreferences.setTextMateEnabled(newValue)
                                        MessageContentPreparer.shared.clearDocumentCache()
                                        BlockDecorationCache.shared.clear()
                                    }
                                ),
                                accessibilityLabel: "TextMate Highlighting"
                            )
                        )
                        .padding(.horizontal, 16)

                        OpenCodeListItem(
                            title: "TextMate Languages",
                            subtitle: "Opt out of any packaged language (all default on)",
                            selected: isDetailSelected(.textMateLanguages),
                            style: .card,
                            startDecorator: .icon(.architecture, accessibilityLabel: "TextMate Languages"),
                            action: { open(.textMateLanguages) }
                        )
                        .padding(.horizontal, 16)

                        OpenCodeListItem(
                            title: "TextMate Logbook",
                            subtitle: "Persisted code-fence parsing measurements",
                            selected: isDetailSelected(.textMateLogbook),
                            style: .card,
                            startDecorator: .icon(.description, accessibilityLabel: "TextMate Logbook"),
                            action: { open(.textMateLogbook) }
                        )
                        .padding(.horizontal, 16)

                        OpenCodeListItem(
                            title: "Feature Flags",
                            subtitle: "Remote flags with device overrides",
                            selected: isDetailSelected(.featureFlags),
                            style: .card,
                            startDecorator: .icon(.settings, accessibilityLabel: "Feature Flags"),
                            action: { open(.featureFlags) }
                        )
                        .padding(.horizontal, 16)
                    }
                }
            }
            .openCodeReadableColumn()
            .accessibilityIdentifier("settingsContent")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }

    private var isSplitLayout: Bool {
        OpenCodeLayoutWidth.resolve(sizeClass: horizontalSizeClass) == .regular
    }

    /// Selected detail after the Developer Tools lock; what the trailing pane renders.
    private var visibleDetail: SettingsDetail {
        selectedDetail.resolved(developerToolsUnlocked: developerToolsPreferences.unlocked)
    }

    private func isDetailSelected(_ detail: SettingsDetail) -> Bool {
        isSplitLayout && visibleDetail == detail
    }

    /// Regular width swaps the trailing pane; compact pushes as before.
    private func open(_ detail: SettingsDetail) {
        if isSplitLayout {
            selectedDetail = detail
            return
        }
        push(detail)
    }

    private func push(_ detail: SettingsDetail) {
        switch detail {
        case .servers: showServers = true
        case .appearance: showAppearance = true
        case .componentGallery: showComponentGallery = true
        case .textMateLanguages: showTextMateLanguages = true
        case .textMateLogbook: showTextMateLogbook = true
        case .featureFlags: showFeatureFlags = true
        case .diagnosticLogs: showDiagnosticLogs = true
        }
    }

    /// Trailing pane root. Its own stack keeps deeper pushes (theme and syntax
    /// pickers, gallery details) inside the detail pane.
    private func settingsDetailPane(_ control: OpenCodeSplitPaneDetailControl) -> some View {
        NavigationStack {
            settingsDetail(control)
                .toolbar(.hidden, for: .navigationBar)
        }
        .id(visibleDetail)
    }

    @ViewBuilder
    private func settingsDetail(_ control: OpenCodeSplitPaneDetailControl) -> some View {
        switch visibleDetail {
        case .servers:
            ServersView(
                onAddServer: onAddServer,
                onEditProfile: onEditProfile,
                refreshToken: serversRefreshToken,
                detailControl: control
            )
        case .appearance:
            AppearanceSettingsView(detailControl: control)
        case .componentGallery:
            ComponentGalleryHomeView(detailControl: control)
        case .textMateLanguages:
            TextMateLanguagesView(detailControl: control)
        case .textMateLogbook:
            TextMateLogbookView(detailControl: control)
        case .featureFlags:
            FeatureFlagsView(detailControl: control)
        case .diagnosticLogs:
            DiagnosticLogsView(detailControl: control)
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
