import OpenCodeMarkdownCore
import SwiftUI

private let fallbackTextMateLanguages: [String] = [
    "javascript", "typescript", "python", "kotlin", "swift",
    "json", "yaml", "shellscript", "rust", "go",
]

struct TextMateLanguagesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var preferences = MarkdownHighlightPreferences.shared
    @State private var languages: [String] = []
    @State private var refreshToken = 0
    @State private var menuExpanded = false

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

    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuExpanded,
                entries: overflowMenuEntries
            ) {
                OpenCodeTopBar(
                    title: "TextMate Languages",
                    leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
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
                    OpenCodeSectionHeader(title: "Packaged")
                    ForEach(languages, id: \.self) { language in
                        languageRow(language)
                    }
                }
                .padding(.bottom, 24)
            }
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onAppear(perform: loadLanguages)
        .id(refreshToken)
    }

    private func loadLanguages() {
        let loaded = TextMatePackagedLanguageCatalog.shared.primaryCanonicalNames()
        languages = loaded.isEmpty ? fallbackTextMateLanguages : Array(loaded)
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
    private func languageRow(_ language: String) -> some View {
        let enabled = preferences.isLanguageEnabled(language)
        OpenCodeListItem(
            title: language,
            subtitle: enabled ? "Default on" : "Opted out",
            style: .card,
            startDecorator: .icon(.architecture, accessibilityLabel: language),
            accessory: .toggle(
                isOn: Binding(
                    get: { preferences.isLanguageEnabled(language) },
                    set: { enabled in
                        preferences.setLanguageEnabled(language, enabled: enabled)
                        MessageContentPreparer.shared.clearDocumentCache()
                        BlockDecorationCache.shared.clear()
                        refreshToken += 1
                    }
                ),
                accessibilityLabel: language
            )
        )
        .padding(.horizontal, 16)
    }
}
