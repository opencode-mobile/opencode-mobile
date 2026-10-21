import SwiftUI

struct SessionListProjectPickerRows: View {
    let tabs: [SessionListFilterTab]
    let onSelect: (String) -> Void
    let onRemoveProject: ((String) -> Void)?
    /// Receives the project tab ids in their new order after a long-press drag.
    /// Worktree rows never move. `nil` leaves the rows fixed.
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
            canMove: { !$0.isAll && !$0.isWorktree },
            onMove: onReorderProjects.map { reorder in
                { from, to in
                    reorder(Self.reorderedWorktrees(tabs: tabs, from: from, to: to))
                }
            },
            onSelect: { onSelect($0.id) },
            onContextAction: onRemoveProject.map { remove in { remove($0.id) } },
            supportsContextAction: { !$0.isAll && !$0.isWorktree }
        ) { tab in
            OpenCodeListItem(
                title: tab.pickerTitle,
                subtitle: displayedSubtitle(tab),
                startDecorator: startDecorator(tab)
            ) {
                SessionListScopeCountBadge(count: tab.count, load: tab.load)
            }
            .padding(.leading, tab.isWorktree ? Self.worktreeIndent : 0)
        }
    }

    /// Worktree rows sit under their project, indented past its avatar.
    static let worktreeIndent: CGFloat = 28

    static func reorderedWorktrees(tabs: [SessionListFilterTab], from: Int, to: Int) -> [String] {
        OpenCodeReorderableListMath.move(tabs, from: from, to: to)
            .filter { !$0.isAll && !$0.isWorktree }
            .map(\.id)
    }

    private func displayedSubtitle(_ tab: SessionListFilterTab) -> String {
        if tab.isAll || tab.isWorktree { return tab.pickerSubtitle }
        return compactHomePath(
            tab.pickerSubtitle,
            hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
        )
    }

    private func startDecorator(_ tab: SessionListFilterTab) -> StartDecorator {
        if tab.isWorktree {
            return .icon(.callSplit, accessibilityLabel: nil)
        }
        if let letter = tab.letter, let accent = tab.accent {
            return .letter(letter, accent)
        }
        return .iconCircle(.widgets, accessibilityLabel: nil)
    }
}
