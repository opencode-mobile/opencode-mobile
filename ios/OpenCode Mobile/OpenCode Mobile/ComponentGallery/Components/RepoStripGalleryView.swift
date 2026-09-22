import SwiftUI

private enum RepoStripSample: String, CaseIterable {
    case dirty = "Dirty"
    case clean = "Clean"
    case defaultBranch = "On main"

    var branch: String {
        switch self {
        case .dirty: "fix/opencode-2-health-probe"
        case .clean: "feature/ope-260-branch-badge"
        case .defaultBranch: "main"
        }
    }

    var files: [OpenCodeRepoStripFile] {
        switch self {
        case .dirty: repoStripPreviewFiles
        case .clean: []
        case .defaultBranch: [OpenCodeRepoStripFile(path: "README.md", status: .modified, additions: 3, deletions: 1)]
        }
    }
}

struct RepoStripGalleryView: View {
    @State private var sample: RepoStripSample = .dirty
    @State private var expanded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Tap the strip to collapse or expand. A clean tree shows the branch row only.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(RepoStripSample.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option.rawValue,
                        selected: sample == option,
                        action: { sample = option }
                    )
                }
            }

            OpenCodeRepoStrip(
                branch: sample.branch,
                defaultBranch: "main",
                files: sample.files,
                expanded: expanded,
                onToggle: { expanded.toggle() },
                onOpenChanges: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    RepoStripGalleryView()
        .padding()
        .openCodeTheme()
}
