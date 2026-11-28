import SwiftUI

struct AppearanceSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var themeManager = ThemeManager.shared
    @State private var appearance = AppearancePreferences.shared
    @State private var showThemePicker = false
    @State private var showSyntaxPicker = false
    @State private var menuExpanded = false

    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(isPresented: $menuExpanded, entries: menuEntries) {
                OpenCodeTopBar(
                    title: "appearance.title",
                    leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "appearance.more_options") {
                            menuExpanded = true
                        },
                    ],
                    showDivider: true
                )
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    modeSection
                    themeSection
                    textSizeSection(.homeList)
                    AppearanceHomeSample()
                        .openCodeTextScale(.homeList)
                    textSizeSection(.sessionChat)
                    AppearanceSessionChatSample()
                        .openCodeTextScale(.sessionChat)
                        .padding(.horizontal, 16)
                    textSizeSection(.code)
                    AppearanceCodeSample()
                        .openCodeTextScale(.code)
                        .padding(.horizontal, 16)
                    note("appearance.text_code_note")
                        .padding(.top, 8)
                    note("appearance.text_session_chat_note")
                        .padding(.top, 16)
                }
                .padding(.bottom, 24)
            }
            .openCodeFocusGroup(.vertical)
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(isPresented: $showThemePicker) {
            ThemePickerView()
        }
        .navigationDestination(isPresented: $showSyntaxPicker) {
            SyntaxHighlightPickerView()
        }
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.settingsAppearance,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }

    private var modeSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeSectionHeader(title: "appearance.mode_title")
                .padding(.bottom, 4)
            OpenCodeSegmentedControl(
                options: AppearanceMode.allCases.map { mode in
                    OpenCodeSegmentedControlOption(
                        id: mode,
                        title: mode.title,
                        accessibilityIdentifier: "appearanceMode:\(mode.wireValue)"
                    )
                },
                selection: appearance.mode,
                onSelect: { mode in
                    log(
                        ProductAnalytics.Action.settingsAppearanceMode,
                        [ProductAnalytics.Param.mode: mode.wireValue]
                    )
                    appearance.setMode(mode)
                }
            )
            .padding(.horizontal, 16)
            note(appearance.mode.note)
                .padding(.top, 8)
        }
    }

    private var themeSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            OpenCodeSectionHeader(title: "appearance.theme.title")
            OpenCodeListItem(
                title: "appearance.theme.title",
                subtitle: themeSubtitle,
                style: .card,
                startDecorator: .icon(.widgets, accessibilityLabel: "appearance.theme.title"),
                accessory: .disclosure,
                action: {
                    log(ProductAnalytics.Action.settingsOpenThemePicker)
                    showThemePicker = true
                }
            )
            .padding(.horizontal, 16)

            OpenCodeListItem(
                title: "appearance.code_highlight.title",
                subtitle: syntaxSubtitle,
                style: .card,
                startDecorator: .icon(.description, accessibilityLabel: "appearance.code_highlight.title"),
                accessory: .disclosure,
                action: {
                    log(ProductAnalytics.Action.settingsOpenSyntaxPicker)
                    showSyntaxPicker = true
                }
            )
            .padding(.horizontal, 16)
        }
    }

    private func textSizeSection(_ region: AppTextRegion) -> some View {
        let size = appearance.textSize(for: region)
        return VStack(alignment: .leading, spacing: 0) {
            OpenCodeSectionHeader(title: region.sectionTitle)
                .padding(.bottom, 4)
            OpenCodeListGroup([region], id: \.self) { region in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("appearance.text_size_label")
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textStrong)
                        Spacer(minLength: 8)
                        Text(openCode: size.title)
                            .font(OpenCodeTypography.bodySecondary)
                            .foregroundStyle(OpenCodeColors.textBase)
                    }
                    .accessibilityHidden(true)
                    OpenCodeStepSlider(
                        stepCount: AppTextSize.allCases.count,
                        selectedIndex: size.stepIndex,
                        defaultIndex: AppTextSize.defaultSize.stepIndex,
                        accessibilityLabel: region.sliderAccessibilityLabel,
                        accessibilityValue: { AppTextSize.fromStepIndex($0).title },
                        onSelect: { index in
                            let next = AppTextSize.fromStepIndex(index)
                            log(
                                ProductAnalytics.Action.settingsAppearanceTextSize,
                                [
                                    ProductAnalytics.Param.textRegion: region.rawValue,
                                    ProductAnalytics.Param.textSize: next.wireValue,
                                ]
                            )
                            appearance.setTextSize(next, for: region)
                        }
                    )
                    .accessibilityIdentifier("appearanceTextSize:\(region.rawValue)")
                }
                .padding(14)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
    }

    private func note(_ text: OpenCodeText) -> some View {
        Text(openCode: text)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
    }

    private var menuEntries: [OpenCodeOverflowMenuEntry] {
        [
            OpenCodeOverflowMenuEntry(
                id: "resetTextSizes",
                title: "appearance.reset_text_sizes",
                icon: .restart,
                enabled: appearance.canResetTextSizes,
                accessibilityIdentifier: "appearanceResetTextSizes",
                action: {
                    log(ProductAnalytics.Action.settingsAppearanceResetTextSizes)
                    appearance.resetTextSizes()
                }
            ),
            OpenCodeOverflowMenuEntry(
                id: "resetAll",
                title: "appearance.reset_all",
                icon: .undo,
                enabled: appearance.canResetAll,
                destructive: true,
                accessibilityIdentifier: "appearanceResetAll",
                action: {
                    log(ProductAnalytics.Action.settingsAppearanceResetAll)
                    appearance.resetAll()
                }
            ),
        ]
    }

    private func log(_ actionId: String, _ parameters: [String: String] = [:]) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.settingsAppearance,
            actionId: actionId,
            parameters: parameters
        )
    }

    /// The preset name is theme data and stays an argument.
    private var themeSubtitle: OpenCodeText {
        let name = themeManager.currentTheme.name
        let blurb = themeBlurb(themeManager.currentTheme.id)
        return OpenCodeText(LocalizedStringResource("appearance.theme.subtitle", defaultValue: "\(name) · \(blurb)"))
    }

    /// Match theme and the built-in presets' descriptions are keyed; other preset names are data.
    private var syntaxSubtitle: OpenCodeText {
        let preset = themeManager.currentSyntaxPreset
        let name = preset.displayName
        let blurb = preset.displayBlurb
        return OpenCodeText(LocalizedStringResource("appearance.theme.subtitle", defaultValue: "\(name) · \(blurb)"))
    }
}

extension AppearanceMode {
    var title: OpenCodeText {
        switch self {
        case .system: "appearance.mode_system"
        case .light: "appearance.mode_light"
        case .dark: "appearance.mode_dark"
        }
    }

    var note: OpenCodeText {
        switch self {
        case .system: "appearance.mode_note_system"
        case .light: "appearance.mode_note_light"
        case .dark: "appearance.mode_note_dark"
        }
    }
}

extension AppTextSize {
    var title: OpenCodeText {
        switch self {
        case .smallest: "appearance.text_size_smallest"
        case .extraSmall: "appearance.text_size_extra_small"
        case .small: "appearance.text_size_small"
        case .default: "appearance.text_size_default"
        case .large: "appearance.text_size_large"
        case .extraLarge: "appearance.text_size_extra_large"
        }
    }
}

extension AppTextRegion {
    /// "Home list text size".
    var sliderAccessibilityLabel: OpenCodeText {
        let title = sectionTitle.resolved
        return OpenCodeText(LocalizedStringResource("appearance.text_size_accessibility", defaultValue: "\(title) size"))
    }

    var sectionTitle: OpenCodeText {
        switch self {
        case .homeList: "appearance.text_home_title"
        case .sessionChat: "appearance.text_session_chat_title"
        case .code: "appearance.text_code_title"
        }
    }
}

/// Home sample: a project header and two real session rows, drawn flat like Home.
private struct AppearanceHomeSample: View {
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OpenCodeSectionHeader(title: .verbatim(Self.project))
            ForEach(Self.sessions) { session in
                SessionListRow(
                    session: session,
                    rowStyle: homeDisplayPreferences.sessionRowStyle,
                    showStatusDot: true,
                    action: {}
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityElement(children: .contain)
    }

    private static var project: String {
        String(localized: "appearance.sample_project", defaultValue: "web-app")
    }

    private static var sessions: [SessionListSession] {
        [
            SessionListSession(
                id: "appearance-sample-1",
                title: String(localized: "appearance.sample_session_one", defaultValue: "Fix the flaky login redirect test"),
                worktree: "/Users/example/web-app",
                letter: "W",
                letterAccent: .violet,
                subtitle: "~/web-app",
                status: .running,
                caption: String(localized: "appearance.sample_time_now", defaultValue: "now"),
                projectName: project
            ),
            SessionListSession(
                id: "appearance-sample-2",
                title: String(localized: "appearance.sample_session_two", defaultValue: "Migrate settings to the new store"),
                worktree: "/Users/example/web-app",
                letter: "W",
                letterAccent: .violet,
                subtitle: "~/web-app",
                status: .idle,
                caption: String(localized: "appearance.sample_time_minutes", defaultValue: "12m"),
                projectName: project,
                worktreeLabel: String(localized: "appearance.sample_branch_two", defaultValue: "settings-store")
            ),
        ]
    }
}

/// Session Chat sample: a user bubble, an assistant paragraph with inline code, and a tool line.
private struct AppearanceSessionChatSample: View {
    @State private var reply: NativeMarkdownDocument?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            OpenCodeOutgoingMessage(
                text: String(
                    localized: "appearance.sample_user_message",
                    defaultValue: "Why does the login redirect test fail one run in five?"
                ),
                metadataLabel: ""
            )
            OpenCodeAssistantMessage(text: Self.replyText, renderedMarkdown: reply)
            OpenCodeToolCallCard(
                status: .completed,
                title: String(localized: "appearance.sample_tool_title", defaultValue: "Edited"),
                subtitle: String(localized: "appearance.sample_tool_subtitle", defaultValue: "src/auth/redirect.ts"),
                additions: 6,
                deletions: 2
            )
        }
        .padding(.vertical, 8)
        .allowsHitTesting(false)
        .task {
            let source = Self.replyText
            reply = await Task.detached(priority: .userInitiated) {
                OpenCodeRenderedMarkdownParser.parse(source, variant: .assistantMessage)
            }.value
        }
    }

    private static var replyText: String {
        String(
            localized: "appearance.sample_assistant_message",
            defaultValue: "The test reads the session cookie right after `login()` resolves, but the cookie is written one tick later."
        )
    }
}

/// Code sample: a short diff hunk with line numbers and added and removed lines.
private struct AppearanceCodeSample: View {
    var body: some View {
        OpenCodeUnifiedDiff(
            file: FileDiff(path: "src/auth/redirect.ts", patch: Self.patch, additions: 2, deletions: 1, status: .modified),
            document: Self.document,
            contextMode: .expanded
        )
        .padding(.vertical, 8)
        .allowsHitTesting(false)
    }

    private static let patch = """
    @@ -12,4 +12,5 @@
     export async function signIn(form: Credentials) {
       const session = await login(form)
    -  return redirect("/home")
    +  await session.persist()
    +  return redirect("/home")
     }
    """

    private static let document = UnifiedDiffParser.parse(patch)
}

struct ThemePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var themeManager = ThemeManager.shared

    var body: some View {
        let selectedId = themeManager.currentTheme.id
        let presets = themeManager.availablePresets()
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "appearance.theme.title",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(presets) { preset in
                        let preview = themeManager.resolvedPreview(themeId: preset.id)
                        ThemePreviewCard(
                            name: preset.name,
                            blurb: preset.displayBlurb,
                            selected: preset.id == selectedId,
                            light: preview?.0,
                            dark: preview?.1,
                            action: {
                                ProductAnalytics.logUserAction(
                                    screenId: ProductAnalytics.Screen.settingsThemePicker,
                                    actionId: ProductAnalytics.Action.themePickerSelectPreset,
                                    parameters: [ProductAnalytics.Param.themePreset: preset.id]
                                )
                                themeManager.applyPreset(id: preset.id)
                            }
                        )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 16)
            }
            .openCodeFocusGroup(.vertical)
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.settingsThemePicker,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }
}

struct SyntaxHighlightPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var themeManager = ThemeManager.shared

    var body: some View {
        let selectedId = themeManager.currentSyntaxPreset.id
        let presets = themeManager.availableSyntaxPresets()
        let isDark = colorScheme == .dark
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "appearance.code_highlight.title",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(presets) { preset in
                        SyntaxPreviewCard(
                            preset: preset,
                            selected: preset.id == selectedId,
                            tokens: themeManager.syntaxTokensForPreview(preset: preset, isDark: isDark),
                            action: {
                                ProductAnalytics.logUserAction(
                                    screenId: ProductAnalytics.Screen.settingsSyntaxPicker,
                                    actionId: ProductAnalytics.Action.syntaxPickerSelectPreset,
                                    parameters: [ProductAnalytics.Param.syntaxPreset: preset.id]
                                )
                                themeManager.applySyntaxPreset(id: preset.id)
                            }
                        )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 16)
            }
            .openCodeFocusGroup(.vertical)
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .task {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.settingsSyntaxPicker,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }
}

private struct ThemePreviewCard: View {
    let name: String
    let blurb: String
    let selected: Bool
    let light: ResolvedTokens?
    let dark: ResolvedTokens?
    let action: () -> Void

    var body: some View {
        // Preset names are theme data; the blurb is localized when it is the app's own.
        OpenCodeSelectableCard(title: .verbatim(name), blurb: .localized(blurb), selected: selected, action: action) {
            HStack(spacing: 8) {
                MiniThemePane(label: "appearance.preview.light", tokens: light)
                MiniThemePane(label: "appearance.preview.dark", tokens: dark)
            }
        }
    }
}

private struct MiniThemePane: View {
    let label: LocalizedStringKey
    let tokens: ResolvedTokens?

    var body: some View {
        let background = tokenColor(tokens, "background-base")
        let raised = tokenColor(tokens, "surface-raised-base")
        let wash = tokenColor(tokens, "surface-interactive-weak")
        let ink = tokenColor(tokens, "text-strong")
        VStack(alignment: .leading, spacing: 5) {
            Text(label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(ink)
            Text("appearance.preview.sample_message")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(ink)
                .lineLimit(1)
                .padding(.horizontal, 6)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .background(wash, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.leading, 28)
            Text("appearance.preview.sample_reply")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(ink)
                .lineLimit(2)
                .padding(.horizontal, 6)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(raised, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(background, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct SyntaxPreviewCard: View {
    let preset: SyntaxHighlightPreset
    let selected: Bool
    let tokens: [String: String]
    let action: () -> Void

    var body: some View {
        // Match theme and built-in descriptions are keyed (resolved strings); other names are preset data.
        OpenCodeSelectableCard(
            title: .localized(preset.displayName),
            blurb: .localized(preset.displayBlurb),
            selected: selected,
            action: action
        ) {
            OpenCodeCodeSnippet(syntaxSnippet(tokens), surface: .syntaxPreset(tokens))
        }
    }
}

private func tokenColor(_ tokens: ResolvedTokens?, _ key: String) -> Color {
    Color(hex: tokens?.tokens[key] ?? "#888888")
}

/// A short sample with every colored role, drawn through the same role table as code in chat.
private let syntaxSample: [(String, NativeCodeTokenRole)] = [
    ("// refresh before the request\n", .comment),
    ("async function ", .keyword),
    ("read", .function),
    ("(", .punctuation),
    ("s", .variable),
    (": ", .punctuation),
    ("Store", .type),
    (") {\n  ", .punctuation),
    ("if ", .keyword),
    ("(", .punctuation),
    ("s", .variable),
    (".", .punctuation),
    ("expired", .function),
    ("()) {\n    ", .punctuation),
    ("await ", .keyword),
    ("refresh", .function),
    ("(", .punctuation),
    ("\"access\"", .string),
    (", ", .punctuation),
    ("3", .number),
    (");\n  }\n  ", .punctuation),
    ("return ", .keyword),
    ("s", .variable),
    (".", .punctuation),
    ("token", .property),
    (";\n}", .punctuation),
]

private func syntaxSnippet(_ tokens: [String: String]) -> AttributedString {
    var result = AttributedString()
    for (text, role) in syntaxSample {
        var part = AttributedString(text)
        // Match theme previews carry only syntax tokens; plain text then uses the theme's own.
        part.foregroundColor = OpenCodeCodeColors.hex(for: role, in: tokens).map(Color.init(hex:))
            ?? OpenCodeColors.textStrong
        result.append(part)
    }
    return result
}

// Preset names such as Porcelain or Nocturne are proper names from the preset files and show
// as is. Match theme and the built-in presets' descriptions are app text, keyed by preset id;
// a preset the app does not know keeps the text from its file.

extension ThemePreset {
    /// The theme's description, keyed for the built-in presets.
    var displayBlurb: String {
        builtInThemeBlurb(id) ?? (blurb.isEmpty ? id : blurb)
    }
}

extension SyntaxHighlightPreset {
    /// "Match theme" is keyed; other names are preset data.
    var displayName: String {
        isMatch ? String(localized: "appearance.syntax_name.match", defaultValue: "Match theme") : name
    }

    /// The syntax preset's description, keyed for the built-in presets.
    var displayBlurb: String {
        switch id {
        case Self.matchID: String(localized: "appearance.syntax_blurb.match", defaultValue: "Use the UI theme’s own tokens")
        case "nocturne": String(localized: "appearance.syntax_blurb.nocturne", defaultValue: "One Dark family")
        case "citrus": String(localized: "appearance.syntax_blurb.citrus", defaultValue: "Solarized family")
        case "paper": String(localized: "appearance.syntax_blurb.paper", defaultValue: "GitHub family")
        case "neon": String(localized: "appearance.syntax_blurb.neon", defaultValue: "Dracula family")
        default: blurb
        }
    }
}

/// A built-in theme's description by id, or the id itself for a theme the app does not know.
private func themeBlurb(_ id: String) -> String {
    builtInThemeBlurb(id) ?? id
}

private func builtInThemeBlurb(_ id: String) -> String? {
    switch id {
    case "porcelain": String(localized: "appearance.theme_blurb.porcelain", defaultValue: "Near-white / near-black")
    case "linen": String(localized: "appearance.theme_blurb.linen", defaultValue: "Warm paper / espresso")
    case "signal": String(localized: "appearance.theme_blurb.signal", defaultValue: "True white / true black")
    case "hotline": String(localized: "appearance.theme_blurb.hotline", defaultValue: "Neutral chrome, loud pink")
    case "phosphor": String(localized: "appearance.theme_blurb.phosphor", defaultValue: "Neutral chrome, CRT green")
    default: nil
    }
}

#Preview {
    NavigationStack {
        AppearanceSettingsView()
    }
    .openCodeTheme()
}
