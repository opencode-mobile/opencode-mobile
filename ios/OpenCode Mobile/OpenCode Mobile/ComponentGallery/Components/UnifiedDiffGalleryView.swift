import SwiftUI

struct UnifiedDiffGalleryView: View {
    @State private var sample = 0
    @State private var reviewed = false
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive").font(OpenCodeTypography.heading)
            HStack {
                ForEach(["Modified", "Added", "Deleted", "Unavailable"], id: \.self) { label in
                    OpenCodeChoiceBadge(label: label, selected: sample == labels.firstIndex(of: label)) {
                        sample = labels.firstIndex(of: label) ?? 0
                    }
                }
            }
            OpenCodeChoiceBadge(label: "Reviewed", selected: reviewed) { reviewed.toggle() }
            OpenCodeChoiceBadge(label: "Expanded context", selected: expanded) { expanded.toggle() }
            if let file = files[sample] {
                OpenCodeUnifiedDiff(file: file, contextMode: expanded ? .expanded : .collapsed, reviewed: reviewed, selectedHunkID: 0)
            } else {
                Text("A text preview is unavailable for assets/logo.png.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
        }
    }

    private let labels = ["Modified", "Added", "Deleted", "Unavailable"]
    private let files: [FileDiff?] = [
        FileDiff(path: "Sources/ChangesView.swift", patch: "@@ -10,3 +10,3 @@\n context\n-old value\n+let newValue = buildAnIntentionallyLongLineThatRequiresHorizontalScrollingToReviewSafely()", additions: 1, deletions: 1, status: .modified),
        FileDiff(path: "Sources/NewFile.swift", patch: "@@ -0,0 +1,2 @@\n+let value = 1\n+print(value)", additions: 2, deletions: 0, status: .added),
        FileDiff(path: "Sources/OldFile.swift", patch: "@@ -1,2 +0,0 @@\n-let value = 1\n-print(value)", additions: 0, deletions: 2, status: .deleted),
        nil,
    ]
}
