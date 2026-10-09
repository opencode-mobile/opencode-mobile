import SwiftUI

struct AppearanceSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var themeManager = ThemeManager.shared
    @State private var showThemePicker = false
    @State private var showSyntaxPicker = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Appearance",
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(spacing: 8) {
                    OpenCodeListItem(
                        title: "Theme",
                        subtitle: "\(themeManager.currentTheme.name) · \(themeBlurb(themeManager.currentTheme.id))",
                        style: .card,
                        startDecorator: .icon(.widgets, accessibilityLabel: "Theme"),
                        accessory: .disclosure,
                        action: {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.settingsAppearance,
                                actionId: ProductAnalytics.Action.settingsOpenThemePicker
                            )
                            showThemePicker = true
                        }
                    )
                    .padding(.horizontal, 16)

                    OpenCodeListItem(
                        title: "Code highlight",
                        subtitle: "\(themeManager.currentSyntaxPreset.name) · \(themeManager.currentSyntaxPreset.blurb)",
                        style: .card,
                        startDecorator: .icon(.description, accessibilityLabel: "Code highlight"),
                        accessory: .disclosure,
                        action: {
                            ProductAnalytics.logUserAction(
                                screenId: ProductAnalytics.Screen.settingsAppearance,
                                actionId: ProductAnalytics.Action.settingsOpenSyntaxPicker
                            )
                            showSyntaxPicker = true
                        }
                    )
                    .padding(.horizontal, 16)
                }
                .padding(.vertical, 16)
            }
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
}

struct ThemePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var themeManager = ThemeManager.shared

    var body: some View {
        let selectedId = themeManager.currentTheme.id
        let presets = themeManager.availablePresets()
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Theme",
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
                title: "Code highlight",
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
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name)
                            .font(OpenCodeTypography.heading)
                            .foregroundStyle(OpenCodeColors.textStrong)
                        Text(blurb)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                    }
                    Spacer(minLength: 8)
                    if selected {
                        OpenCodeIconImage(icon: .check, size: 18)
                            .foregroundStyle(OpenCodeColors.interactive)
                    }
                }
                HStack(spacing: 8) {
                    MiniThemePane(label: "Light", tokens: light)
                    MiniThemePane(label: "Dark", tokens: dark)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(selected ? OpenCodeColors.interactive : .clear, lineWidth: 2)
            }
        }
        .buttonStyle(.plain)
    }
}

private struct MiniThemePane: View {
    let label: String
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
            Text("Walk the token store.")
                .font(.system(size: 9))
                .foregroundStyle(ink)
                .lineLimit(1)
                .padding(.horizontal, 6)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .background(wash, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.leading, 28)
            Text("Refreshing once, then retry.")
                .font(.system(size: 9))
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
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(preset.name)
                            .font(OpenCodeTypography.heading)
                            .foregroundStyle(OpenCodeColors.textStrong)
                        Text(preset.blurb)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                    }
                    Spacer(minLength: 8)
                    if selected {
                        OpenCodeIconImage(icon: .check, size: 18)
                            .foregroundStyle(OpenCodeColors.interactive)
                    }
                }
                OpenCodeCodeSnippet(syntaxSnippet(tokens), surface: .base)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(selected ? OpenCodeColors.interactive : .clear, lineWidth: 2)
            }
        }
        .buttonStyle(.plain)
    }
}

private func tokenColor(_ tokens: ResolvedTokens?, _ key: String) -> Color {
    Color(hex: tokens?.tokens[key] ?? "#888888")
}

private func syntaxSnippet(_ tokens: [String: String]) -> AttributedString {
    func append(_ result: inout AttributedString, _ text: String, _ key: String) {
        var part = AttributedString(text)
        part.foregroundColor = Color(hex: tokens[key] ?? "#888888")
        result.append(part)
    }
    var result = AttributedString()
    append(&result, "// refresh before the request\n", "syntax-comment")
    append(&result, "async function ", "syntax-keyword")
    append(&result, "readToken", "syntax-primitive")
    append(&result, "() {\n", "syntax-punctuation")
    append(&result, "  if ", "syntax-keyword")
    append(&result, "(", "syntax-punctuation")
    append(&result, "store", "syntax-variable")
    append(&result, ".", "syntax-punctuation")
    append(&result, "expired", "syntax-constant")
    append(&result, "()) {\n", "syntax-punctuation")
    append(&result, "    await ", "syntax-keyword")
    append(&result, "refreshAccessToken", "syntax-primitive")
    append(&result, "();\n  }\n  ", "syntax-punctuation")
    append(&result, "return ", "syntax-keyword")
    append(&result, "store", "syntax-variable")
    append(&result, ".", "syntax-punctuation")
    append(&result, "access", "syntax-type")
    append(&result, ";\n}", "syntax-punctuation")
    return result
}

private extension ThemePreset {
    var displayBlurb: String {
        blurb.isEmpty ? themeBlurb(id) : blurb
    }
}

private func themeBlurb(_ id: String) -> String {
    switch id {
    case "porcelain": "Near-white / near-black"
    case "linen": "Warm paper / espresso"
    case "signal": "True white / true black"
    case "hotline": "Neutral chrome, loud pink"
    case "phosphor": "Neutral chrome, CRT green"
    default: id
    }
}

#Preview {
    NavigationStack {
        AppearanceSettingsView()
    }
    .openCodeTheme()
}
