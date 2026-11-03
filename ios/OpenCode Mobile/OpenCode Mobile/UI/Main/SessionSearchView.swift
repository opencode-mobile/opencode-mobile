import SwiftUI

/// Session search over Home's pane (#929): the field with a back button, the scope chip, then
/// the hint, Home's rows for the matches, or the empty state.
struct SessionSearchView: View {
    let model: SessionSearchModel
    let rowStyle: HomeSessionRowStyle
    /// Session open in the trailing pane on split layouts; nil on phones.
    let selectedSessionId: String?
    let onOpen: (SessionListSession) -> Void
    let onLongPress: (SessionListSession) -> Void

    @State private var fieldFocused = false
    @State private var scopeMenuPresented = false

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            scopeRow
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .accessibilityIdentifier("sessionSearch")
        // Opening and ⌘F focus the field; coming back from a pushed chat does not.
        .onChange(of: model.focusRequest, initial: true) { _, _ in
            Task { @MainActor in
                // The field takes focus once it is in the window.
                try? await Task.sleep(for: .milliseconds(50))
                fieldFocused = true
            }
        }
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            OpenCodeButton(
                variant: .iconCircle(.arrowBack, accessibilityLabel: "top_bar.back"),
                contrast: .low,
                action: close
            )
            .accessibilityIdentifier("sessionSearchBack")
            OpenCodeTextInput(
                text: Binding(get: { model.query }, set: { model.updateQuery($0) }),
                hint: Self.placeholder(model.scopeTitle),
                kind: .search,
                isFocused: $fieldFocused,
                onSubmit: { fieldFocused = false },
                busy: model.busy
            )
            .accessibilityIdentifier("sessionSearchField")
        }
        .padding(.leading, 12)
        .padding(.trailing, 16)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }

    private var scopeRow: some View {
        let scope = model.selectedScope
        return HStack {
            OpenCodeAnchoredOverflowHost(
                isPresented: $scopeMenuPresented,
                entries: scopeEntries,
                placement: .below
            ) {
                OpenCodeMenuChip(
                    title: .verbatim(scope.title),
                    leading: scopeChipLeading(scope),
                    selected: !scope.isAll,
                    accessibilityLabel: Self.placeholder(scope.title),
                    action: { scopeMenuPresented = true }
                )
                .accessibilityIdentifier("sessionSearchScope")
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var scopeEntries: [OpenCodeOverflowMenuEntry] {
        // Selection rows: the current scope carries the check, the others no icon.
        model.scopeOptions.map { option in
            let current = option.id == model.scope
            return OpenCodeOverflowMenuEntry(
                id: option.id,
                title: .verbatim(option.title),
                icon: current ? .check : nil,
                accessibilityLabel: current ? Self.selectedScopeLabel(option.title) : nil,
                accessibilityIdentifier: "sessionSearchScopeOption",
                action: { model.selectScope(option.id) }
            )
        }
    }

    private func scopeChipLeading(_ scope: SessionSearchScopeOption) -> OpenCodeMenuChipLeading {
        if let letter = scope.letter, let accent = scope.accent {
            return .letter(letter, accent)
        }
        return .icon(.widgets)
    }

    @ViewBuilder
    private var content: some View {
        switch model.content {
        case let .hint(scopeTitle):
            centeredNote(Self.hint(scopeTitle))
        case .searching:
            centeredNote("session_search.searching")
        case let .results(results):
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    OpenCodeSectionHeader(title: Self.resultsTitle(results.count))
                    ForEach(results) { result in
                        SessionListRow(
                            session: result.row,
                            rowStyle: rowStyle,
                            showStatusDot: true,
                            selected: selectedSessionId == result.row.id,
                            titleHighlight: result.matchRange,
                            action: { open(result.row) },
                            onLongPress: { onLongPress(result.row) }
                        )
                        .accessibilityIdentifier("sessionSearchResult")
                    }
                }
                .padding(.bottom, 24)
                .openCodeReadableColumn()
            }
            .scrollDismissesKeyboard(.immediately)
        case let .noMatch(query, scopeTitle, offerAllProjects):
            ScrollView {
                OpenCodeEmptyState(
                    title: Self.noMatchTitle(query),
                    message: Self.noMatchMessage(scopeTitle),
                    fillsAvailableSpace: false,
                    primaryAction: offerAllProjects
                        ? OpenCodeEmptyStateAction(title: "session_search.search_all_projects") {
                            model.searchAllProjects()
                        }
                        : nil
                )
                .padding(.top, 64)
                .openCodeReadableColumn()
                .accessibilityIdentifier("sessionSearchNoMatch")
            }
            .scrollDismissesKeyboard(.immediately)
        }
    }

    private func centeredNote(_ text: OpenCodeText) -> some View {
        VStack {
            Text(openCode: text)
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.top, 64)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .openCodeReadableColumn()
        .accessibilityIdentifier("sessionSearchNote")
    }

    private func open(_ row: SessionListSession) {
        fieldFocused = false
        onOpen(row)
    }

    private func close() {
        fieldFocused = false
        scopeMenuPresented = false
        model.close()
    }

    // MARK: Copy (project names and the query stay arguments)

    static func placeholder(_ scopeTitle: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("session_search.placeholder", defaultValue: "Search in \(scopeTitle)"))
    }

    static func hint(_ scopeTitle: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("session_search.hint", defaultValue: "Matches session titles in \(scopeTitle)."))
    }

    static func selectedScopeLabel(_ scopeTitle: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("session_search.scope_selected", defaultValue: "\(scopeTitle), selected"))
    }

    static func resultsTitle(_ count: Int) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("session_search.results", defaultValue: "\(count) results"))
    }

    static func noMatchTitle(_ query: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource(
            "session_search.no_match_title",
            defaultValue: "No sessions match \u{201C}\(query)\u{201D}"
        ))
    }

    static func noMatchMessage(_ scopeTitle: String) -> OpenCodeText {
        OpenCodeText(LocalizedStringResource("session_search.no_match_message", defaultValue: "in \(scopeTitle)"))
    }
}
