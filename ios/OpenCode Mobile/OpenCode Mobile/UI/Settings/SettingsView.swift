import FactoryKit
import SwiftUI
import UIKit

/// Settings sub-screen shown in the trailing pane of the tablet split view.
enum SettingsDetail: Hashable {
    case servers
    case appearance
    case componentGallery
    case featureFlags
    case diagnosticLogs
    #if HIGHLIGHT_LAB
    case highlightLab
    #endif

    /// Whether the screen lives in the Developer Tools section that Clear All Data locks.
    var requiresDeveloperTools: Bool {
        switch self {
        case .servers, .appearance, .diagnosticLogs:
            false
        case .componentGallery, .featureFlags:
            true
        #if HIGHLIGHT_LAB
        case .highlightLab:
            true
        #endif
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
    var splitGate = OpenCodeSplitLayoutGate()
    /// Trailing-pane sub-screen in the regular-width split view. Unused on compact.
    @State private var selectedDetail: SettingsDetail = .servers
    @State private var showComponentGallery = false
    /// The presented Session Chat Playground; SwiftUI clears it when the cover is dismissed.
    @State private var sessionChatPlayground: SessionChatPlaygroundModel?
    /// The same playground, kept until `onDismiss` closes it: teardown follows the cover's
    /// dismissal, not any disappear (a cover the chat shows over it fires those too).
    @State private var openSessionChatPlayground: SessionChatPlaygroundModel?
    @State private var showDiagnosticLogs = false
    @State private var showFeatureFlags = false
    #if HIGHLIGHT_LAB
    @State private var showHighlightLab = false
    #endif
    @State private var showClearConfirmation = false
    @State private var showClearSessionCacheConfirmation = false
    @State private var sessionCacheSizeLabel = String(localized: "settings.session_cache.empty", defaultValue: "Empty")
    @State private var showAppearance = false
    @State private var showRecentWindowPicker = false
    @State private var showFollowUpPicker = false
    @State private var showTimelineStylePicker = false
    @State private var showSessionRowsPicker = false
    @State private var themeManager = ThemeManager.shared
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @State private var analyticsConsentPreferences = AnalyticsConsentPreferences.shared
    @State private var appLockPreferences = AppLockPreferences.shared
    private let appLockAuthenticator: any AppLockAuthenticating = LocalAuthenticationAppLockAuthenticator()
    @State private var sessionChatDisplayPreferences = SessionChatDisplayPreferences.shared
    @State private var developerToolsPreferences = DeveloperToolsPreferences.shared
    @State private var performanceOverlayPreferences = PerformanceOverlayPreferences.shared
    @State private var showServers = false
    @State private var serversSubtitleState: OpenCodeText = "settings.servers_summary.none"
    @State private var serversLetter: Character = "S"
    @State private var serversLetterAccent: LetterAccent = .blue
    /// False while another screen is pushed over Settings.
    @State private var isOnScreen = false
    /// Report-a-problem sheet (#721); set to open it.
    @State private var feedbackModel: FeedbackViewModel?

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
        .feedbackSheet(model: $feedbackModel)
        .onAppear { isOnScreen = true }
        .onDisappear { isOnScreen = false }
        .navigationDestination(isPresented: $showComponentGallery) {
            ComponentGalleryHomeView()
        }
        // Full screen, also in the split layout: it hosts the whole Session Chat screen.
        .fullScreenCover(item: $sessionChatPlayground, onDismiss: closeSessionChatPlayground) { model in
            SessionChatPlaygroundView(model: model)
        }
        .navigationDestination(isPresented: $showFeatureFlags) {
            FeatureFlagsView()
        }
        #if HIGHLIGHT_LAB
        .navigationDestination(isPresented: $showHighlightLab) {
            HighlightLabHomeView()
        }
        #endif
        .navigationDestination(isPresented: $showDiagnosticLogs) {
            DiagnosticLogsView()
        }
        .navigationDestination(isPresented: $showAppearance) {
            AppearanceSettingsView()
        }
        .typeErased()
        .openCodeBottomSheet(
            isPresented: recentWindowPickerBinding,
            title: "settings.recent_window.title",
            scrollTargetID: "hours-\(homeDisplayPreferences.recentWindowHours)"
        ) {
            Text("settings.recent_window.message")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .openCodeBottomSheetContentInset()
                .padding(.bottom, 8)

            ForEach(HomeSessionLadder.allowedWindowHours, id: \.self) { hours in
                OpenCodeBottomSheetEntry(
                    title: Self.recentWindowHoursLabel(hours),
                    selected: hours == homeDisplayPreferences.recentWindowHours,
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
        .openCodeBottomSheet(isPresented: $showTimelineStylePicker, title: "settings.timeline_style.title") {
            ForEach(sessionChatDisplayPreferences.availableTimelineStyles, id: \.self) { style in
                OpenCodeBottomSheetEntry(
                    title: style.settingsTitle,
                    subtitle: style.settingsSubtitle,
                    selected: style == sessionChatDisplayPreferences.effectiveTimelineStyle,
                    action: {
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.settings,
                            actionId: style == .chips
                                ? ProductAnalytics.Action.settingsToggleChipTimeline
                                : ProductAnalytics.Action.settingsTimelineStyle,
                            parameters: [ProductAnalytics.Param.mode: style.wireValue]
                        )
                        sessionChatDisplayPreferences.setTimelineStyle(style)
                        showTimelineStylePicker = false
                    }
                )
            }
        }
        .openCodeBottomSheet(isPresented: $showSessionRowsPicker, title: "settings.session_rows.title") {
            ForEach(HomeSessionRowStyle.allCases, id: \.self) { style in
                OpenCodeBottomSheetEntry(
                    title: style.settingsTitle,
                    subtitle: style.settingsSubtitle,
                    selected: style == homeDisplayPreferences.sessionRowStyle,
                    action: {
                        ProductAnalytics.logUserAction(
                            screenId: ProductAnalytics.Screen.settings,
                            actionId: ProductAnalytics.Action.settingsSessionRows,
                            parameters: [ProductAnalytics.Param.mode: style.wireValue]
                        )
                        homeDisplayPreferences.setSessionRowStyle(style)
                        showSessionRowsPicker = false
                    }
                )
            }
        }
        .openCodeBottomSheet(isPresented: $showFollowUpPicker, title: "settings.follow_up.title") {
            ForEach(FollowUpDelivery.allCases, id: \.self) { mode in
                OpenCodeBottomSheetEntry(
                    title: mode == .steer ? "settings.follow_up.steer" : "settings.follow_up.queue",
                    startDecorator: .icon(mode == .steer ? .arrowWarmUp : .arrowUpward),
                    selected: mode == sessionChatDisplayPreferences.followUpDelivery,
                    action: {
                        sessionChatDisplayPreferences.setFollowUpDelivery(mode)
                        showFollowUpPicker = false
                    }
                )
            }
        }
        .typeErased()
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.settings,
                presentation: ProductAnalytics.Presentation.route
            )
            sessionCacheSizeLabel = await Container.refreshSessionCacheSizeLabel()
        }
        .openCodeDialog(
            isPresented: $showClearSessionCacheConfirmation,
            title: "settings.clear_session_cache.dialog_title",
            subtitle: "settings.clear_session_cache.dialog_message",
            primaryOption: OpenCodeDialogOption(title: "settings.clear") {
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
            secondaryOption: OpenCodeDialogOption(title: "common.cancel") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.settingsClearCacheConfirm,
                    actionId: ProductAnalytics.Action.clearCacheCancel
                )
                showClearSessionCacheConfirmation = false
            }
        )
        .openCodeDialog(
            isPresented: $showClearConfirmation,
            title: "settings.clear_all_data.dialog_title",
            subtitle: "settings.clear_all_data.dialog_message",
            primaryOption: OpenCodeDialogOption(title: "settings.clear") {
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
            secondaryOption: OpenCodeDialogOption(title: "common.cancel") {
                ProductAnalytics.logUserAction(
                    screenId: ProductAnalytics.Screen.settingsClearAllDataConfirm,
                    actionId: ProductAnalytics.Action.clearAllDataCancel
                )
                showClearConfirmation = false
            }
        )
        .typeErased()
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

    private var appLockSubtitle: OpenCodeText {
        appLockAuthenticator.canAuthenticate()
            ? "settings.app_lock.subtitle_available"
            : "settings.app_lock.subtitle_unavailable"
    }

    private var serverSection: some View {
        Group {
            OpenCodeSectionHeader(title: "settings.section.server")

            OpenCodeListItem(
                title: "servers.title",
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

            OpenCodeSectionHeader(title: "settings.section.help_feedback")

            OpenCodeListItem(
                title: "settings.send_feedback.title",
                subtitle: "settings.send_feedback.subtitle",
                style: .card,
                startDecorator: .icon(.edit, accessibilityLabel: "settings.send_feedback.title"),
                action: {
                    // No capture here: the screen behind is Settings.
                    // "+ Screenshot" picks from Photos instead.
                    feedbackModel = Container.makeFeedbackViewModel(screen: .settings, screenshot: nil)
                }
            )
            .padding(.horizontal, 16)
            .accessibilityIdentifier("settingsSendFeedback")
        }
        .typeErased()
    }

    private var appearanceSection: some View {
        Group {
            OpenCodeSectionHeader(title: "settings.section.appearance")

            OpenCodeListItem(
                title: "appearance.title",
                subtitle: appearanceSubtitle,
                selected: isDetailSelected(.appearance),
                style: .card,
                startDecorator: .icon(.widgets, accessibilityLabel: "appearance.title"),
                accessory: .disclosure,
                action: {
                    logSettingsAction(ProductAnalytics.Action.settingsOpenAppearance)
                    open(.appearance)
                }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.hide_user_directory.title",
                subtitle: "settings.hide_user_directory.subtitle",
                style: .card,
                startDecorator: .icon(.folder, accessibilityLabel: "settings.hide_user_directory.title"),
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
                    accessibilityLabel: "settings.hide_user_directory.title"
                )
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.recent_window.title",
                subtitle: Self.recentWindowHoursLabel(homeDisplayPreferences.recentWindowHours),
                style: .card,
                startDecorator: .icon(.list, accessibilityLabel: "settings.recent_window.title"),
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
                title: "settings.collapse_tool_calls.title",
                subtitle: "settings.collapse_tool_calls.subtitle",
                style: .card,
                startDecorator: .icon(.collapseContent, accessibilityLabel: "settings.collapse_tool_calls.title"),
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
                    accessibilityLabel: "settings.collapse_tool_calls.title"
                )
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.timeline_style.title",
                subtitle: sessionChatDisplayPreferences.effectiveTimelineStyle.settingsTitle,
                style: .card,
                startDecorator: .icon(.widgets, accessibilityLabel: "settings.timeline_style.title"),
                accessory: .disclosure,
                action: { showTimelineStylePicker = true }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.follow_up.title",
                subtitle: followUpSubtitle,
                enabled: supportsFollowUpDelivery,
                style: .card,
                startDecorator: .icon(
                    followUpIcon,
                    accessibilityLabel: "settings.follow_up.title"
                ),
                accessory: supportsFollowUpDelivery ? .disclosure : nil,
                action: supportsFollowUpDelivery ? { showFollowUpPicker = true } : nil
            )
            .padding(.horizontal, 16)

            if let loadError = themeManager.loadError {
                // ThemeManager's own message; it moves to a key with the theme code.
                OpenCodeInlineNotice(message: loadError, tone: .error)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
            }
        }
        .typeErased()
    }

    private var homeSection: some View {
        Group {
            OpenCodeSectionHeader(title: "settings.section.home")

            OpenCodeListItem(
                title: "settings.worktrees.title",
                subtitle: "settings.worktrees.subtitle",
                style: .card,
                startDecorator: .icon(.callSplit, accessibilityLabel: "settings.worktrees.title"),
                accessory: .toggle(
                    isOn: Binding(
                        get: { homeDisplayPreferences.showsWorktrees },
                        set: { newValue in
                            logSettingsAction(ProductAnalytics.Action.settingsToggleWorktrees)
                            homeDisplayPreferences.setShowsWorktrees(newValue)
                        }
                    ),
                    accessibilityLabel: "settings.worktrees.title"
                )
            )
            .padding(.horizontal, 16)
            .accessibilityIdentifier("settingsWorktreesToggle")

            OpenCodeListItem(
                title: "settings.session_rows.title",
                subtitle: homeDisplayPreferences.sessionRowStyle.settingsTitle,
                style: .card,
                startDecorator: .icon(.list, accessibilityLabel: "settings.session_rows.title"),
                accessory: .disclosure,
                action: {
                    logSettingsAction(ProductAnalytics.Action.settingsOpenSessionRowsPicker)
                    showSessionRowsPicker = true
                }
            )
            .padding(.horizontal, 16)
            .accessibilityIdentifier("settingsSessionRows")

            OpenCodeSectionHeader(title: "settings.section.session_cache")

            OpenCodeListItem(
                title: "settings.session_cache.size",
                // Already localized by SessionCacheSizeFormatter.
                subtitle: .verbatim(sessionCacheSizeLabel),
                style: .card,
                startDecorator: .icon(.archive, accessibilityLabel: "settings.session_cache.size_label")
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.clear_session_cache.title",
                subtitle: "settings.clear_session_cache.subtitle",
                style: .card,
                startDecorator: .icon(.close, accessibilityLabel: "settings.clear_session_cache.title"),
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
        }
        .typeErased()
    }

    private var privacySection: some View {
        Group {
            OpenCodeSectionHeader(title: "settings.section.privacy")

            OpenCodeListItem(
                title: "settings.app_lock.title",
                subtitle: appLockSubtitle,
                style: .card,
                startDecorator: .icon(.lock, accessibilityLabel: "settings.app_lock.title"),
                accessory: .toggle(
                    isOn: Binding(
                        get: { appLockPreferences.enabled },
                        set: { newValue in
                            guard !newValue || appLockAuthenticator.canAuthenticate() else { return }
                            appLockPreferences.setEnabled(newValue)
                            logSettingsAction(ProductAnalytics.Action.settingsToggleAppLock)
                        }
                    ),
                    accessibilityLabel: "settings.app_lock.title"
                )
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.privacy_policy.title",
                subtitle: "settings.privacy_policy.subtitle",
                style: .card,
                startDecorator: .icon(.description, accessibilityLabel: "settings.privacy_policy.title"),
                accessory: .disclosure,
                action: {
                    logSettingsAction(ProductAnalytics.Action.settingsOpenPrivacyPolicy)
                    openURL(PrivacyPolicy.url)
                }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.analytics.title",
                subtitle: "settings.analytics.subtitle",
                style: .card,
                startDecorator: .icon(.description, accessibilityLabel: "settings.analytics.label"),
                accessory: .toggle(
                    isOn: Binding(
                        get: { analyticsConsentPreferences.productAnalyticsConsented },
                        set: { newValue in
                            // Log after applying consent: opting out is
                            // dropped by the gate rather than reported. Goes through
                            // the observability seam so Crashlytics and automatic
                            // Analytics follow the switch right away.
                            OpenCodeReleaseObservability
                                .setProductAnalyticsConsented(newValue)
                            logSettingsAction(
                                ProductAnalytics.Action.settingsToggleProductAnalytics
                            )
                        }
                    ),
                    accessibilityLabel: "settings.analytics.label"
                )
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "diagnostics.title",
                subtitle: "settings.diagnostic_logs.subtitle",
                selected: isDetailSelected(.diagnosticLogs),
                style: .card,
                startDecorator: .icon(.description, accessibilityLabel: "diagnostics.title"),
                action: { open(.diagnosticLogs) }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.clear_all_data.title",
                subtitle: "settings.clear_all_data.subtitle",
                style: .card,
                startDecorator: .icon(.close, accessibilityLabel: "settings.clear_all_data.title"),
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
        }
        .typeErased()
    }

    private var aboutSection: some View {
        Group {
            OpenCodeSectionHeader(title: "settings.section.about")

            OpenCodeListItem(
                title: "settings.version",
                subtitle: .verbatim(version),
                style: .card,
                startDecorator: .icon(.description, accessibilityLabel: "settings.version"),
                action: { UIPasteboard.general.string = version }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.build",
                subtitle: .verbatim(build),
                style: .card,
                startDecorator: .icon(.settings, accessibilityLabel: "settings.build"),
                action: { UIPasteboard.general.string = build }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "settings.source_code.title",
                subtitle: "settings.source_code.subtitle",
                style: .card,
                startDecorator: .icon(.code, accessibilityLabel: "settings.source_code.title"),
                accessory: .disclosure,
                action: { openURL(SourceCode.url) }
            )
            .padding(.horizontal, 16)
        }
        .typeErased()
    }

    private var developerToolsSection: some View {
        Group {
            if developerToolsPreferences.unlocked {
                // Developer Tools rows are developer-only and stay English.
                OpenCodeSectionHeader(title: .verbatim("Developer Tools"))

                OpenCodeListItem(
                    title: .verbatim("Component Gallery"),
                    subtitle: .verbatim("Design-system foundations and reusable components"),
                    selected: isDetailSelected(.componentGallery),
                    style: .card,
                    startDecorator: .icon(.architecture, accessibilityLabel: .verbatim("Component Gallery")),
                    action: { open(.componentGallery) }
                )
                .padding(.horizontal, 16)

                OpenCodeListItem(
                    title: .verbatim("Session Chat Playground"),
                    subtitle: .verbatim("The real chat over fake scenarios, no server"),
                    style: .card,
                    startDecorator: .icon(.widgets, accessibilityLabel: .verbatim("Session Chat Playground")),
                    accessory: .disclosure,
                    action: {
                        let model = SessionChatPlaygroundModel()
                        openSessionChatPlayground = model
                        sessionChatPlayground = model
                    }
                )
                .padding(.horizontal, 16)
                .accessibilityIdentifier("settings.sessionChatPlayground")

                #if HIGHLIGHT_LAB
                OpenCodeListItem(
                    title: .verbatim("Highlighting Lab"),
                    subtitle: .verbatim("Data sets and on-device benchmarks"),
                    selected: isDetailSelected(.highlightLab),
                    style: .card,
                    startDecorator: .icon(.code, accessibilityLabel: .verbatim("Highlighting Lab")),
                    action: { open(.highlightLab) }
                )
                .padding(.horizontal, 16)
                .accessibilityIdentifier("settings.highlightLab")
                #endif

                OpenCodeListItem(
                    title: .verbatim("Feature Flags"),
                    subtitle: .verbatim("Remote flags with device overrides"),
                    selected: isDetailSelected(.featureFlags),
                    style: .card,
                    startDecorator: .icon(.settings, accessibilityLabel: .verbatim("Feature Flags")),
                    action: { open(.featureFlags) }
                )
                .padding(.horizontal, 16)

                OpenCodeListItem(
                    title: .verbatim("Performance Overlay"),
                    subtitle: .verbatim("Live FPS and frame-time spikes"),
                    style: .card,
                    startDecorator: .icon(.settings, accessibilityLabel: .verbatim("Performance Overlay")),
                    accessory: .toggle(
                        isOn: Binding(
                            get: { performanceOverlayPreferences.enabled },
                            set: { performanceOverlayPreferences.setEnabled($0) }
                        ),
                        accessibilityLabel: .verbatim("Performance Overlay")
                    )
                )
                .padding(.horizontal, 16)
            }
        }
        .typeErased()
    }

    private var settingsPane: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "settings.title",
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
                    serverSection
                    appearanceSection
                    homeSection
                    privacySection
                    aboutSection
                    developerToolsSection
                }
            }
            .openCodeFocusGroup(.vertical)
            .openCodeReadableColumn()
            .accessibilityIdentifier("settingsContent")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }

    private var isSplitLayout: Bool {
        splitGate.usesSplitLayout
    }

    /// Selected detail after the Developer Tools lock; what the trailing pane renders.
    private var visibleDetail: SettingsDetail {
        selectedDetail.resolved(developerToolsUnlocked: developerToolsPreferences.unlocked)
    }

    private func isDetailSelected(_ detail: SettingsDetail) -> Bool {
        isSplitLayout && visibleDetail == detail
    }

    private func closeSessionChatPlayground() {
        openSessionChatPlayground?.close()
        openSessionChatPlayground = nil
    }

    /// The split swaps the trailing pane; stacked pushes as before.
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
        case .featureFlags: showFeatureFlags = true
        case .diagnosticLogs: showDiagnosticLogs = true
        #if HIGHLIGHT_LAB
        case .highlightLab: showHighlightLab = true
        #endif
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
        .openCodeKeyboardShortcut(.toggleSidebar, action: isOnScreen ? control.toggle : nil)
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
        case .featureFlags:
            FeatureFlagsView(detailControl: control)
        case .diagnosticLogs:
            DiagnosticLogsView(detailControl: control)
        #if HIGHLIGHT_LAB
        case .highlightLab:
            HighlightLabHomeView(detailControl: control)
        #endif
        }
    }

    private func refreshServersSubtitle() async {
        let profiles = await Container.shared.connectionSessionCoordinator().listProfiles()
        let active = Container.shared.connectionSessionCoordinator().currentProfile()
        let name = active?.displayName ?? "Servers"
        serversLetter = letterCharacter(for: name)
        serversLetterAccent = letterAccent(for: active?.id ?? name)
        serversSubtitleState = Self.serversSubtitle(count: profiles.count, activeName: active?.displayName)
    }

    private var supportsFollowUpDelivery: Bool {
        Container.shared.activeConnectionManager().capabilities.supportsFollowUpDelivery
    }

    private var followUpSubtitle: OpenCodeText {
        guard supportsFollowUpDelivery else { return "settings.follow_up.unsupported" }
        return sessionChatDisplayPreferences.followUpDelivery == .steer
            ? "settings.follow_up.subtitle_steer"
            : "settings.follow_up.subtitle_queue"
    }

    /// Preset names are theme data, shown as is.
    private var appearanceSubtitle: OpenCodeText {
        let names = [themeManager.currentTheme.name, themeManager.currentSyntaxPreset.name]
        return .verbatim(names.joined(separator: " · "))
    }

    /// The active profile name is user content and stays an argument.
    static func serversSubtitle(count: Int, activeName: String?) -> OpenCodeText {
        guard count > 0 else { return "settings.servers_summary.none" }
        if let activeName {
            return OpenCodeText(LocalizedStringResource(
                "settings.servers_summary.saved_active",
                defaultValue: "\(count) saved · \(activeName) active"
            ))
        }
        return OpenCodeText(LocalizedStringResource(
            "settings.servers_summary.saved",
            defaultValue: "\(count) saved"
        ))
    }

    /// "4 hours" for the Recent Window row and picker.
    static func recentWindowHoursLabel(_ hours: Int) -> OpenCodeText {
        let sanitized = HomeSessionLadder.sanitizeWindowHours(hours)
        return OpenCodeText(LocalizedStringResource(
            "settings.recent_window.hours",
            defaultValue: "\(sanitized) hours"
        ))
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

extension SessionChatTimelineStyle {
    var settingsTitle: OpenCodeText {
        switch self {
        case .standard: "settings.timeline_style.standard.title"
        case .chips: "settings.timeline_style.chips.title"
        case .stepRail: "settings.timeline_style.step_rail.title"
        case .activityCard: "settings.timeline_style.activity_card.title"
        }
    }

    var settingsSubtitle: OpenCodeText {
        switch self {
        case .standard: "settings.timeline_style.standard.subtitle"
        case .chips: "settings.timeline_style.chips.subtitle"
        case .stepRail: "settings.timeline_style.step_rail.subtitle"
        case .activityCard: "settings.timeline_style.activity_card.subtitle"
        }
    }
}

extension HomeSessionRowStyle {
    var settingsTitle: OpenCodeText {
        switch self {
        case .compact: "settings.session_rows.compact.title"
        case .classic: "settings.session_rows.classic.title"
        }
    }

    var settingsSubtitle: OpenCodeText {
        switch self {
        case .compact: "settings.session_rows.compact.subtitle"
        case .classic: "settings.session_rows.classic.subtitle"
        }
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

private extension View {
    /// Ends a long modifier chain. Debug builds overflow the stack while the
    /// Swift runtime resolves one deeply nested generic view type.
    func typeErased() -> AnyView { AnyView(self) }
}
