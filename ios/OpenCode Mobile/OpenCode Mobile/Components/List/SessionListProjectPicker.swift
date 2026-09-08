import SwiftUI

struct SessionListProjectPickerRows: View {
    let tabs: [SessionListFilterTab]
    let onSelect: (String) -> Void
    let onRemoveProject: ((String) -> Void)?

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        ForEach(tabs) { tab in
            OpenCodeListItem(
                title: tab.pickerTitle,
                subtitle: displayedSubtitle(tab),
                startDecorator: startDecorator(tab),
                action: { onSelect(tab.id) },
                onLongPress: tab.isAll ? nil : onRemoveProject.map { remove in { remove(tab.id) } }
            ) {
                SessionListScopeCountBadge(count: tab.count)
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
        return .iconCircle(.widgets, accessibilityLabel: nil)
    }
}
