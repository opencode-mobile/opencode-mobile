import OpenCodeMarkdownCore
import SwiftUI

private let promotedTextMateLanguages: Set<String> = [
    "javascript", "typescript", "python", "kotlin", "swift",
    "json", "yaml", "shellscript", "rust", "go",
]

struct TextMateLanguagesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var preferences = MarkdownHighlightPreferences.shared
    @State private var languages: [String] = []
    @State private var refreshToken = 0
    @State private var menuExpanded = false

    private var promoted: [String] {
        languages.filter { promotedTextMateLanguages.contains($0) }
    }

    private var experimental: [String] {
        languages.filter { !promotedTextMateLanguages.contains($0) }
    }

    private var overflowMenuEntries: [OpenCodeOverflowMenuEntry] {
        [
            OpenCodeOverflowMenuEntry(title: "Reset", icon: nil) {
                resetLanguages()
            },
            OpenCodeOverflowMenuEntry(title: "Select all", icon: nil) {
                selectAllLanguages()
            },
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: overflowMenuEntries
            ) {
                OpenCodeTopBar(
                    title: "TextMate Languages",
                    leading: .back(action: { dismiss() }),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options") {
                            menuExpanded = true
                        },
                    ],
                    showDivider: true
                )
            }

            ScrollView {
                LazyVStack(spacing: 8) {
                    sectionHeader("Promoted")
                    ForEach(promoted, id: \.self) { language in
                        languageRow(language, promoted: true)
                    }

                    sectionHeader("Packaged (experimental)")
                    ForEach(experimental, id: \.self) { language in
                        languageRow(language, promoted: false)
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onAppear(perform: loadLanguages)
        .id(refreshToken)
    }

    private func loadLanguages() {
        let loaded = TextMatePackagedLanguageCatalog.shared.primaryCanonicalNames()
        languages = loaded.isEmpty ? promotedTextMateLanguages.sorted() : Array(loaded)
    }

    private func resetLanguages() {
        preferences.resetLanguageOverrides()
        clearCachesAndRefresh()
    }

    private func selectAllLanguages() {
        for language in languages {
            preferences.setLanguageEnabled(language, enabled: true)
        }
        clearCachesAndRefresh()
    }

    private func clearCachesAndRefresh() {
        MessageContentPreparer.shared.clearDocumentCache()
        BlockDecorationCache.shared.clear()
        refreshToken += 1
    }

    @ViewBuilder
    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 4)
    }

    @ViewBuilder
    private func languageRow(_ language: String, promoted: Bool) -> some View {
        OpenCodeListItem(
            title: language,
            subtitle: promoted ? "Allowlist default on" : "Force-enable to use TextMate",
            style: .card,
            startDecorator: .icon(.architecture, accessibilityLabel: language),
            trailing: {
                Toggle(
                    language,
                    isOn: Binding(
                        get: { preferences.isLanguageEnabled(language) },
                        set: { enabled in
                            preferences.setLanguageEnabled(language, enabled: enabled)
                            MessageContentPreparer.shared.clearDocumentCache()
                            BlockDecorationCache.shared.clear()
                            refreshToken += 1
                        }
                    )
                )
                .labelsHidden()
                .tint(OpenCodeColors.interactive)
            }
        )
        .padding(.horizontal, 16)
    }
}
