import SwiftUI

struct SessionListProjectPickerRows: View {
    let tabs: [SessionListFilterTab]
    let onSelect: (String) -> Void
    let onRemoveProject: ((String) -> Void)?
    /// Receives the added-project worktrees in their new order after a
    /// long-press drag. `nil` leaves the rows fixed.
    let onReorderProjects: (([String]) -> Void)?

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    init(
        tabs: [SessionListFilterTab],
        onSelect: @escaping (String) -> Void,
        onRemoveProject: ((String) -> Void)? = nil,
        onReorderProjects: (([String]) -> Void)? = nil
    ) {
        self.tabs = tabs
        self.onSelect = onSelect
        self.onRemoveProject = onRemoveProject
        self.onReorderProjects = onReorderProjects
    }

    var body: some View {
        OpenCodeReorderableList(
            items: tabs,
            canMove: { !$0.isAll },
            onMove: onReorderProjects.map { reorder in
                { from, to in
                    reorder(Self.reorderedWorktrees(tabs: tabs, from: from, to: to))
                }
            },
            onSelect: { onSelect($0.id) },
            onContextAction: onRemoveProject.map { remove in { remove($0.id) } },
            supportsContextAction: { !$0.isAll }
        ) { tab in
            OpenCodeListItem(
                title: tab.pickerTitle,
                subtitle: displayedSubtitle(tab),
                startDecorator: startDecorator(tab)
            ) {
                SessionListScopeCountBadge(count: tab.count)
            }
        }
    }

    static func reorderedWorktrees(tabs: [SessionListFilterTab], from: Int, to: Int) -> [String] {
        OpenCodeReorderableListMath.move(tabs, from: from, to: to)
            .filter { !$0.isAll }
            .map(\.id)
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
