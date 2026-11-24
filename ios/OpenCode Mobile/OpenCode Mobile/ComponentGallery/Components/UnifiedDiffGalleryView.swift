import SwiftUI

struct UnifiedDiffGalleryView: View {
    @State private var sample = 0
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive").font(OpenCodeTypography.heading)
            HStack {
                ForEach(Self.labels, id: \.self) { label in
                    OpenCodeChoiceBadge(label: .verbatim(label), selected: sample == labels.firstIndex(of: label)) {
                        sample = labels.firstIndex(of: label) ?? 0
                    }
                }
            }
            OpenCodeChoiceBadge(label: "Expanded context", selected: expanded) { expanded.toggle() }
            if let file = files[sample] {
                OpenCodeUnifiedDiff(
                    file: file,
                    document: UnifiedDiffParser.parse(file.patch ?? ""),
                    contextMode: expanded ? .expanded : .collapsed,
                    selectedHunkID: 0
                )
            } else {
                Text("A text preview is unavailable for assets/logo.png.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
        }
    }

    private let labels = Self.labels
    static let labels = ["Modified", "Added", "Deleted", "Large line numbers", "Unavailable"]
    private let files = Self.files

    /// One sample per label; nil is a file without a text preview.
    static let files: [FileDiff?] = [
        FileDiff(
            path: "Sources/ChangesView.swift",
            patch: "@@ -1,8 +1,8 @@\n one\n two\n three\n four\n-old value\n+let newValue = buildAnIntentionallyLongLineThatRequiresHorizontalScrollingToReviewSafely()\n six\n seven\n eight\n@@ -20 +20 @@\n-before\n+naïve 🚀\n\\ No newline at end of file",
            additions: 2,
            deletions: 2,
            status: .modified
        ),
        FileDiff(path: "Sources/NewFile.swift", patch: "@@ -0,0 +1,2 @@\n+let value = 1\n+print(value)", additions: 2, deletions: 0, status: .added),
        FileDiff(path: "Sources/OldFile.swift", patch: "@@ -1,2 +0,0 @@\n-let value = 1\n-print(value)", additions: 0, deletions: 2, status: .deleted),
        FileDiff(
            path: "Sources/LongFile.swift",
            patch: "@@ -1204,3 +1204,3 @@\n let first = 1\n-let second = 2\n+let second = 3\n let third = 4",
            additions: 1,
            deletions: 1,
            status: .modified
        ),
        nil,
    ]
}
