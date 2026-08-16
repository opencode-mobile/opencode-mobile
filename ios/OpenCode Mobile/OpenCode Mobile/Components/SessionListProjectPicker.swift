import SwiftUI

struct SessionListProjectPickerRows: View {
    let tabs: [SessionListFilterTab]
    let query: String
    let onSelect: (String) -> Void
    let onRemoveProject: ((String) -> Void)?

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        let matches = SessionListProjectPickerMatching.tabs(tabs, query: query)
        if matches.isEmpty {
            Text("No matches")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
        } else {
            ForEach(matches) { tab in
                OpenCodeListItem(
                    title: tab.title,
                    subtitle: displayedSubtitle(tab),
                    startDecorator: startDecorator(tab),
                    action: { onSelect(tab.id) },
                    onLongPress: tab.isAll ? nil : onRemoveProject.map { remove in { remove(tab.id) } }
                ) {
                    SessionListScopeCountBadge(count: tab.count)
                }
            }
        }
    }

    private func displayedSubtitle(_ tab: SessionListFilterTab) -> String {
        if tab.isAll { return tab.subtitle }
        return compactHomePath(
            tab.subtitle,
            hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
        )
    }

    private func startDecorator(_ tab: SessionListFilterTab) -> StartDecorator {
        if let letter = tab.letter, let accent = tab.accent {
            return .letter(letter, accent)
        }
        return .icon(.widgets, accessibilityLabel: nil)
    }
}
