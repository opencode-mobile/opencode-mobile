import SwiftUI

/// Working-copy status of one file shown in the repo strip.
enum OpenCodeRepoStripFileStatus: Equatable {
    case added
    case modified
    case deleted
}

struct OpenCodeRepoStripFile: Equatable, Identifiable {
    var id: String { path }
    let path: String
    let status: OpenCodeRepoStripFileStatus
    let additions: Int
    let deletions: Int
}

struct RepoStripSummary: Equatable {
    /// "4 files", "1 file", or "clean" when nothing is changed.
    let fileLabel: String
    let additionLabel: String?
    let deletionLabel: String?

    var isClean: Bool { additionLabel == nil && deletionLabel == nil && fileLabel == repoStripCleanLabel }
}

let repoStripCleanLabel = "clean"

func formatRepoStripSummary(files: [OpenCodeRepoStripFile]) -> RepoStripSummary {
    guard !files.isEmpty else { return RepoStripSummary(fileLabel: repoStripCleanLabel, additionLabel: nil, deletionLabel: nil) }
    let additions = files.reduce(0) { $0 + $1.additions }
    let deletions = files.reduce(0) { $0 + $1.deletions }
    return RepoStripSummary(
        fileLabel: "\(files.count) \(files.count == 1 ? "file" : "files")",
        additionLabel: "+\(additions)",
        deletionLabel: "−\(deletions)"
    )
}

func formatRepoStripAccessibilityLabel(branch: String, files: [OpenCodeRepoStripFile], expanded: Bool) -> String {
    let summary = formatRepoStripSummary(files: files)
    let changes: String
    if files.isEmpty {
        changes = "clean working tree"
    } else {
        let additions = files.reduce(0) { $0 + $1.additions }
        let deletions = files.reduce(0) { $0 + $1.deletions }
        changes = "\(summary.fileLabel) changed, \(additions) \(additions == 1 ? "addition" : "additions"), "
            + "\(deletions) \(deletions == 1 ? "deletion" : "deletions")"
    }
    let disclosure = files.isEmpty ? "" : (expanded ? ", expanded" : ", collapsed")
    return "Branch \(branch), \(changes)\(disclosure)"
}

func repoStripStatusLetter(_ status: OpenCodeRepoStripFileStatus) -> String {
    switch status {
    case .added: "A"
    case .modified: "M"
    case .deleted: "D"
    }
}

/// Collapsible summary of the current branch and uncommitted working-tree changes.
///
/// Collapsed: one row with branch, changed-file count, and line totals.
/// Expanded: the same row plus one line per changed file and an "Open in Changes" footer.
/// A clean working tree renders the row only and cannot expand.
struct OpenCodeRepoStrip: View {
    let branch: String
    let defaultBranch: String?
    let files: [OpenCodeRepoStripFile]
    let expanded: Bool
    let onToggle: () -> Void
    let onOpenChanges: () -> Void

    private var summary: RepoStripSummary { formatRepoStripSummary(files: files) }
    private var canExpand: Bool { !files.isEmpty }
    private var isExpanded: Bool { expanded && canExpand }

    var body: some View {
        VStack(spacing: 0) {
            Button(action: onToggle) {
                HStack(spacing: 8) {
                    OpenCodeIconImage(icon: .callSplit, size: 14)
                        .foregroundStyle(OpenCodeColors.iconBase)
                    Text(branch)
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if summary.isClean {
                        Text(summary.fileLabel)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                    } else {
                        Text(summary.fileLabel)
                            .font(OpenCodeTypography.caption.weight(.medium))
                            .foregroundStyle(OpenCodeColors.textStrong)
                        if let additionLabel = summary.additionLabel {
                            Text(additionLabel)
                                .font(OpenCodeTypography.caption.weight(.medium))
                                .foregroundStyle(OpenCodeColors.diffAdd)
                        }
                        if let deletionLabel = summary.deletionLabel {
                            Text(deletionLabel)
                                .font(OpenCodeTypography.caption.weight(.medium))
                                .foregroundStyle(OpenCodeColors.diffDelete)
                        }
                        OpenCodeIconImage(icon: .keyboardArrowDown, size: 16)
                            .foregroundStyle(OpenCodeColors.iconWeakBase)
                            .rotationEffect(.degrees(isExpanded ? 180 : 0))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!canExpand)
            .accessibilityLabel(formatRepoStripAccessibilityLabel(branch: branch, files: files, expanded: isExpanded))
            .accessibilityAddTraits(.isButton)

            if isExpanded {
                Divider().overlay(OpenCodeColors.borderWeakerBase)
                ForEach(Array(files.enumerated()), id: \.element.id) { index, file in
                    if index > 0 {
                        Divider().overlay(OpenCodeColors.borderWeakerBase)
                    }
                    RepoStripFileRow(file: file)
                }
                Divider().overlay(OpenCodeColors.borderWeakerBase)
                Button(action: onOpenChanges) {
                    HStack {
                        Text("Open in Changes")
                            .font(OpenCodeTypography.caption.weight(.medium))
                            .foregroundStyle(OpenCodeColors.interactive)
                        Spacer(minLength: 8)
                        if let defaultBranch, defaultBranch != branch {
                            Text("\(branch) → \(defaultBranch)")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(OpenCodeColors.textWeak)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .animation(.easeInOut(duration: 0.2), value: isExpanded)
    }
}

private struct RepoStripFileRow: View {
    let file: OpenCodeRepoStripFile

    private var badgeColor: Color {
        switch file.status {
        case .added: OpenCodeColors.diffAdd
        case .modified: OpenCodeColors.warning
        case .deleted: OpenCodeColors.diffDelete
        }
    }

    private var splitPath: (directory: String, name: String) {
        guard let separator = file.path.lastIndex(of: "/") else { return ("", file.path) }
        return (String(file.path[...separator]), String(file.path[file.path.index(after: separator)...]))
    }

    var body: some View {
        let parts = splitPath
        HStack(spacing: 10) {
            Text(repoStripStatusLetter(file.status))
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(badgeColor)
                .frame(width: 20, height: 20)
                .background(badgeColor.opacity(0.16), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            (Text(parts.directory).foregroundStyle(OpenCodeColors.textWeak)
                + Text(parts.name).foregroundStyle(OpenCodeColors.textStrong))
                .font(OpenCodeTypography.caption)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("+\(file.additions)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.diffAdd)
            Text("−\(file.deletions)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.diffDelete)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

let repoStripPreviewFiles: [OpenCodeRepoStripFile] = [
    OpenCodeRepoStripFile(path: "android/app/src/main/java/dev/opencode/mobile/data/remote/HealthApi.kt", status: .modified, additions: 42, deletions: 7),
    OpenCodeRepoStripFile(path: "ios/OpenCode Mobile/OpenCode Mobile/Data/Remote/HealthAPIClient.swift", status: .modified, additions: 38, deletions: 5),
    OpenCodeRepoStripFile(path: "android/app/src/test/java/dev/opencode/mobile/HealthProbeTest.kt", status: .added, additions: 61, deletions: 0),
    OpenCodeRepoStripFile(path: "docs/features/health-probe.md", status: .deleted, additions: 0, deletions: 19),
]

#Preview("Collapsed") {
    OpenCodeRepoStrip(
        branch: "fix/opencode-2-health-probe",
        defaultBranch: "main",
        files: repoStripPreviewFiles,
        expanded: false,
        onToggle: {},
        onOpenChanges: {}
    )
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}

#Preview("Expanded") {
    OpenCodeRepoStrip(
        branch: "fix/opencode-2-health-probe",
        defaultBranch: "main",
        files: repoStripPreviewFiles,
        expanded: true,
        onToggle: {},
        onOpenChanges: {}
    )
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}

#Preview("Clean") {
    OpenCodeRepoStrip(
        branch: "main",
        defaultBranch: "main",
        files: [],
        expanded: true,
        onToggle: {},
        onOpenChanges: {}
    )
    .padding()
    .openCodeTheme()
}
