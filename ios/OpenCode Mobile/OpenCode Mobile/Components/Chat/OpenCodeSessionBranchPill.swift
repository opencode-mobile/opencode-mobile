import SwiftUI
import UIKit

/// One uncommitted file change on the session's branch.
struct OpenCodeSessionBranchChangedFile: Equatable, Hashable {
    let path: String
    let additions: Int
    let deletions: Int

    var linesChanged: Int { additions + deletions }

    /// Counts to draw: a zero side is nil, so a binary or rename-only file shows none.
    var visibleAdditions: Int? { additions > 0 ? additions : nil }
    var visibleDeletions: Int? { deletions > 0 ? deletions : nil }

    /// "src/a.swift, 61 added"; a file without line counts reads its path only.
    var accessibilityLabel: String {
        OpenCodeSessionBranchSummary.spokenCounts(prefix: path, additions: additions, deletions: deletions)
    }
}

/// What the Session Branch Pill and its popover show: the session's branch, its base,
/// whether it is a worktree checkout, and the uncommitted file changes.
struct OpenCodeSessionBranchSummary: Equatable {
    let branch: String
    let baseBranch: String?
    let isWorktree: Bool
    let files: [OpenCodeSessionBranchChangedFile]

    init(branch: String, baseBranch: String? = nil, isWorktree: Bool = false, files: [OpenCodeSessionBranchChangedFile] = []) {
        self.branch = branch
        self.baseBranch = baseBranch
        self.isWorktree = isWorktree
        self.files = files
    }

    /// The popover lists at most this many files.
    static let topFileLimit = 3

    var additions: Int { max(0, files.reduce(0) { $0 + $1.additions }) }
    var deletions: Int { max(0, files.reduce(0) { $0 + $1.deletions }) }
    var hasChanges: Bool { !files.isEmpty }

    /// Worktree checkouts use the worktree icon from Home and Settings; other sessions the branch icon.
    var icon: OpenCodeIcon { isWorktree ? .callSplit : .gitBranch }

    /// "+128 −14" with zero sides left out, or "2 files" when the changes have no line counts.
    /// Nil without changes: the pill shows its icon only.
    var changeLabel: String? {
        guard hasChanges else { return nil }
        var parts: [String] = []
        if additions > 0 { parts.append("+\(additions)") }
        if deletions > 0 { parts.append("−\(deletions)") }
        return parts.isEmpty ? Self.fileCount(files.count) : parts.joined(separator: " ")
    }

    var pillLayout: OpenCodeFloatingPillLayout {
        if let changeLabel {
            return .iconLabel(icon: icon, label: changeLabel)
        }
        return .iconOnly(icon: icon, accessibilityLabel: accessibilityLabel)
    }

    /// "Branch feature-x, 128 added, 14 removed" or "Branch feature-x, no changes".
    var accessibilityLabel: String {
        let name = "\(isWorktree ? "Worktree branch" : "Branch") \(branch)"
        guard hasChanges else { return "\(name), no changes" }
        guard additions > 0 || deletions > 0 else { return "\(name), \(Self.uncommittedFileCount(files.count))" }
        return Self.spokenCounts(prefix: name, additions: additions, deletions: deletions)
    }

    /// The base branch when the server reports one that differs from the branch itself.
    var visibleBaseBranch: String? {
        guard let baseBranch, !baseBranch.isEmpty, baseBranch != branch else { return nil }
        return baseBranch
    }

    /// "3 uncommitted files"; nil without changes. Worded so it does not read as a diff against the base.
    var fileCountLabel: String? {
        hasChanges ? Self.uncommittedFileCount(files.count) : nil
    }

    /// Popover totals: a zero side is nil, as on the pill.
    var visibleAdditions: Int? { additions > 0 ? additions : nil }
    var visibleDeletions: Int? { deletions > 0 ? deletions : nil }

    /// "3 uncommitted files, 106 added"; nil without changes.
    var fileCountAccessibilityLabel: String? {
        fileCountLabel.map { Self.spokenCounts(prefix: $0, additions: additions, deletions: deletions) }
    }

    /// The most-changed files by lines added plus removed, ties by path.
    var topFiles: [OpenCodeSessionBranchChangedFile] {
        Array(
            files.sorted { lhs, rhs in
                lhs.linesChanged != rhs.linesChanged ? lhs.linesChanged > rhs.linesChanged : lhs.path < rhs.path
            }
            .prefix(Self.topFileLimit)
        )
    }

    private static func fileCount(_ count: Int) -> String {
        "\(count) \(count == 1 ? "file" : "files")"
    }

    private static func uncommittedFileCount(_ count: Int) -> String {
        "\(count) uncommitted \(count == 1 ? "file" : "files")"
    }

    /// "prefix, 128 added, 14 removed" with zero sides left out.
    static func spokenCounts(prefix: String, additions: Int, deletions: Int) -> String {
        var parts = [prefix]
        if additions > 0 { parts.append("\(additions) added") }
        if deletions > 0 { parts.append("\(deletions) removed") }
        return parts.joined(separator: ", ")
    }
}

/// Session Chat context pill for the session's branch: an icon and the line counts, or the
/// icon alone with no changes. Tapping it opens a card under the pill (leading edges lined up,
/// no arrow) with the branch details, the most-changed files, View all changes and Copy branch
/// name. Placement is up to the caller.
struct OpenCodeSessionBranchPill: View {
    let summary: OpenCodeSessionBranchSummary
    let onViewAllChanges: () -> Void
    /// Called after the branch name is on the pasteboard, so the host can confirm it.
    let onBranchNameCopied: () -> Void

    @State private var isPopoverPresented = false

    init(
        summary: OpenCodeSessionBranchSummary,
        onViewAllChanges: @escaping () -> Void,
        onBranchNameCopied: @escaping () -> Void = {}
    ) {
        precondition(!summary.branch.isEmpty, "Session Branch Pill requires a non-empty branch")
        self.summary = summary
        self.onViewAllChanges = onViewAllChanges
        self.onBranchNameCopied = onBranchNameCopied
    }

    var body: some View {
        OpenCodeAnchoredCardHost(isPresented: $isPopoverPresented, preferredWidth: Self.popoverWidth) {
            OpenCodeFloatingPill(
                layout: summary.pillLayout,
                state: .ready,
                accessibilityLabel: summary.accessibilityLabel,
                action: { isPopoverPresented = true }
            )
            .accessibilityHint("Shows branch details")
        } card: {
            OpenCodeSessionBranchPopoverContent(
                summary: summary,
                onViewAllChanges: {
                    isPopoverPresented = false
                    onViewAllChanges()
                },
                onCopyBranchName: {
                    isPopoverPresented = false
                    UIPasteboard.general.string = summary.branch
                    onBranchNameCopied()
                }
            )
        }
    }

    /// Popover width when the screen allows it; narrower screens keep the corner slot's inset.
    static let popoverWidth: CGFloat = 320
}

/// Body of the branch popover. Public so the Component Gallery can show it open.
struct OpenCodeSessionBranchPopoverContent: View {
    let summary: OpenCodeSessionBranchSummary
    let onViewAllChanges: () -> Void
    let onCopyBranchName: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, Self.paddingHorizontal)
                .padding(.vertical, Self.sectionPaddingVertical)
            if summary.hasChanges {
                divider
                changes
                    .padding(.horizontal, Self.paddingHorizontal)
                    .padding(.vertical, Self.sectionPaddingVertical)
            }
            divider
            VStack(alignment: .leading, spacing: 0) {
                if summary.hasChanges {
                    actionRow(title: "View all changes", icon: .description, action: onViewAllChanges)
                }
                actionRow(title: "Copy branch name", icon: .contentCopy, action: onCopyBranchName)
            }
            .openCodeFocusGroup(.vertical)
            .padding(.vertical, Self.actionsPaddingVertical)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: Self.iconSpacing) {
            OpenCodeIconImage(icon: summary.icon, size: Self.iconSize)
                .foregroundStyle(OpenCodeColors.iconStrongBase)
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
            VStack(alignment: .leading, spacing: 2) {
                Text(summary.branch)
                    .font(OpenCodeTypography.code)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
                if let base = summary.visibleBaseBranch {
                    Text("Based on \(base)")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var changes: some View {
        VStack(alignment: .leading, spacing: Self.fileRowSpacing) {
            if let fileCountLabel = summary.fileCountLabel {
                HStack(spacing: 8) {
                    Text(fileCountLabel)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                    Spacer(minLength: 8)
                    OpenCodeDiffStat(additions: summary.visibleAdditions, deletions: summary.visibleDeletions, style: .caption)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(summary.fileCountAccessibilityLabel ?? fileCountLabel)
            }
            ForEach(summary.topFiles, id: \.path) { file in
                HStack(spacing: 8) {
                    Text(file.path)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.head)
                    Spacer(minLength: 8)
                    OpenCodeDiffStat(additions: file.visibleAdditions, deletions: file.visibleDeletions, style: .caption)
                        .fixedSize()
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(file.accessibilityLabel)
            }
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(OpenCodeColors.borderWeakBase)
            .frame(height: 1)
    }

    private func actionRow(title: String, icon: OpenCodeIcon, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Self.iconSpacing) {
                OpenCodeIconImage(icon: icon, size: Self.iconSize)
                    .foregroundStyle(OpenCodeColors.iconStrongBase)
                Text(title)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Self.paddingHorizontal)
            .frame(minHeight: Self.actionMinHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: OpenCodeRadius.small, horizontalInset: 4), activate: action)
    }

    private static let paddingHorizontal: CGFloat = 16
    private static let sectionPaddingVertical: CGFloat = 12
    private static let actionsPaddingVertical: CGFloat = 4
    private static let actionMinHeight: CGFloat = 44
    private static let fileRowSpacing: CGFloat = 8
    private static let iconSize: CGFloat = 16
    private static let iconSpacing: CGFloat = 10
}

enum SessionBranchPillPreviewCase: CaseIterable {
    case changes
    case noChanges
    case worktree
    case manyFiles

    var summary: OpenCodeSessionBranchSummary {
        switch self {
        case .changes:
            OpenCodeSessionBranchSummary(branch: "feature/session-branch", baseBranch: "main", files: Self.sampleFiles)
        case .noChanges:
            OpenCodeSessionBranchSummary(branch: "feature/session-branch", baseBranch: "main")
        case .worktree:
            OpenCodeSessionBranchSummary(
                branch: "feature/worktree-checkout",
                baseBranch: "main",
                isWorktree: true,
                files: Array(Self.sampleFiles.prefix(2))
            )
        case .manyFiles:
            OpenCodeSessionBranchSummary(
                branch: "feature/ope-48-session-branch-pill-with-a-very-long-name",
                baseBranch: "main",
                files: Self.sampleFiles + [
                    .init(path: "README.md", additions: 3, deletions: 0),
                    .init(path: "docs/notes.md", additions: 0, deletions: 4),
                ]
            )
        }
    }

    static let sampleFiles: [OpenCodeSessionBranchChangedFile] = [
        .init(path: "Sources/App/Session/BranchPill.swift", additions: 96, deletions: 4),
        .init(path: "Sources/App/Session/BranchPopover.swift", additions: 24, deletions: 2),
        .init(path: "Tests/BranchPillTests.swift", additions: 8, deletions: 8),
        .init(path: "Sources/App/Legacy/OldBranchRow.swift", additions: 0, deletions: 6),
        .init(path: "Resources/branch-icon.png", additions: 0, deletions: 0),
    ]
}

#Preview("Pills") {
    VStack(alignment: .leading, spacing: 12) {
        ForEach(SessionBranchPillPreviewCase.allCases, id: \.self) { previewCase in
            OpenCodeSessionBranchPill(summary: previewCase.summary, onViewAllChanges: {})
        }
    }
    .padding()
    .openCodeTheme()
}

#Preview("Popover") {
    VStack(spacing: 16) {
        OpenCodeSessionBranchPopoverContent(summary: SessionBranchPillPreviewCase.changes.summary, onViewAllChanges: {}, onCopyBranchName: {})
        OpenCodeSessionBranchPopoverContent(summary: SessionBranchPillPreviewCase.noChanges.summary, onViewAllChanges: {}, onCopyBranchName: {})
    }
    .padding()
    .openCodeTheme()
}
