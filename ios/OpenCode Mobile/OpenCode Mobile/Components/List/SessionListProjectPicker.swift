import SwiftUI

struct SessionListProjectPickerRows: View {
    let rows: [HomeProjectPickerRow]
    let isEditing: Bool
    let onSelect: (String) -> Void
    let onToggleExpanded: (String) -> Void
    let onToggleShowAll: (String) -> Void
    let onRemoveProject: ((String) -> Void)?
    /// Receives the project tab ids in their new order after a drag in Edit. Only project rows
    /// move. `nil` leaves the rows fixed.
    let onReorderProjects: (([String]) -> Void)?

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    init(
        rows: [HomeProjectPickerRow],
        isEditing: Bool = false,
        onSelect: @escaping (String) -> Void,
        onToggleExpanded: @escaping (String) -> Void = { _ in },
        onToggleShowAll: @escaping (String) -> Void = { _ in },
        onRemoveProject: ((String) -> Void)? = nil,
        onReorderProjects: (([String]) -> Void)? = nil
    ) {
        self.rows = rows
        self.isEditing = isEditing
        self.onSelect = onSelect
        self.onToggleExpanded = onToggleExpanded
        self.onToggleShowAll = onToggleShowAll
        self.onRemoveProject = onRemoveProject
        self.onReorderProjects = onReorderProjects
    }

    /// Plain tabs (All and projects), as the picker listed them before worktrees.
    init(
        tabs: [SessionListFilterTab],
        onSelect: @escaping (String) -> Void,
        onRemoveProject: ((String) -> Void)? = nil
    ) {
        self.init(
            rows: tabs.map { $0.isAll ? .all($0) : .project($0, pill: nil) },
            onSelect: onSelect,
            onRemoveProject: onRemoveProject
        )
    }

    var body: some View {
        OpenCodeReorderableList(
            items: rows,
            canMove: \.isProject,
            // Rows only move in Edit, so open worktrees never fight a long-press drag.
            onMove: isEditing ? onReorderProjects.map { reorder in
                { from, to in
                    reorder(HomeProjectPickerRow.reorderedProjectKeys(rows, from: from, to: to))
                }
            } : nil,
            activation: .handle,
            onSelect: select,
            onContextAction: onRemoveProject.map { remove in { remove($0.id) } },
            supportsContextAction: \.isProject
        ) { row in
            rowContent(row)
        } trailing: { row in
            trailingContent(row)
        }
    }

    /// Worktree rows sit under their project, indented past its avatar.
    static let worktreeIndent: CGFloat = 28

    private func select(_ row: HomeProjectPickerRow) {
        switch row {
        case let .all(tab), let .project(tab, _):
            guard !isEditing else { return }
            onSelect(tab.id)
        case let .worktree(tab):
            onSelect(tab.id)
        case let .showAll(projectKey, _):
            onToggleShowAll(projectKey)
        case .note:
            break
        }
    }

    @ViewBuilder
    private func rowContent(_ row: HomeProjectPickerRow) -> some View {
        switch row {
        case .note:
            Text(HomeProjectCopy.reorderNote)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .accessibilityIdentifier("projectPickerReorderNote")
        case let .all(tab):
            OpenCodeListItem(
                title: .unlocalized(tab.pickerTitle),
                subtitle: .unlocalized(tab.pickerSubtitle),
                startDecorator: .iconCircle(.widgets, accessibilityLabel: nil)
            )
        case let .project(tab, _):
            OpenCodeListItem(
                title: .verbatim(tab.pickerTitle),
                subtitle: .verbatim(compactHomePath(
                    tab.pickerSubtitle,
                    hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
                )),
                startDecorator: letterDecorator(tab)
            )
            .accessibilityIdentifier("projectPickerProject")
        case let .worktree(tab):
            OpenCodeListItem(
                title: .verbatim(tab.pickerTitle),
                subtitle: .verbatim(tab.pickerSubtitle),
                startDecorator: .icon(.callSplit, accessibilityLabel: nil)
            )
            .padding(.leading, Self.worktreeIndent)
            .accessibilityIdentifier("projectPickerWorktree")
        case let .showAll(_, title):
            Text(title)
                .font(OpenCodeTypography.body.weight(.semibold))
                .foregroundStyle(OpenCodeColors.textInteractiveBase)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .padding(.leading, Self.worktreeIndent)
                .accessibilityIdentifier("projectPickerShowAll")
        }
    }

    @ViewBuilder
    private func trailingContent(_ row: HomeProjectPickerRow) -> some View {
        switch row {
        case let .all(tab), let .worktree(tab):
            countBadge(tab)
        case let .project(tab, pill):
            HStack(spacing: 12) {
                if let pill, let projectKey = row.tab?.id {
                    OpenCodeExpandPill(
                        title: .unlocalized(pill.title),
                        isExpanded: pill.isExpanded,
                        enabled: pill.enabled,
                        accessibilityLabel: .unlocalized(pill.accessibilityLabel),
                        action: { onToggleExpanded(projectKey) }
                    )
                    .accessibilityIdentifier("projectPickerWorktreesPill")
                }
                SessionListScopeCountBadge(count: tab.count, load: tab.load)
            }
            .padding(.trailing, 16)
        case .note, .showAll:
            EmptyView()
        }
    }

    private func countBadge(_ tab: SessionListFilterTab) -> some View {
        SessionListScopeCountBadge(count: tab.count, load: tab.load)
            .padding(.trailing, 16)
    }

    private func letterDecorator(_ tab: SessionListFilterTab) -> StartDecorator {
        if let letter = tab.letter, let accent = tab.accent {
            return .letter(letter, accent)
        }
        return .iconCircle(.widgets, accessibilityLabel: nil)
    }
}
