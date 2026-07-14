import SwiftUI

struct FileNavigationPillGalleryView: View {
    @State private var position = FileNavigationPillGalleryPosition.middle
    @State private var zeroTotals = false
    @State private var refreshing = false
    @State private var lastAction = "No action"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review first, middle, last, zero-total, and refresh-disabled states.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(FileNavigationPillGalleryPosition.allCases, id: \.self) { value in
                    OpenCodeChoiceBadge(label: value.label, selected: position == value) {
                        position = value
                    }
                }
            }

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Zero totals", selected: zeroTotals) { zeroTotals.toggle() }
                OpenCodeChoiceBadge(label: "Refreshing", selected: refreshing) { refreshing.toggle() }
            }

            HStack {
                Spacer()
                OpenCodeFileNavigationPill(
                    additions: zeroTotals ? 0 : 12,
                    deletions: zeroTotals ? 0 : 3,
                    previousEnabled: position != .first,
                    nextEnabled: position != .last,
                    enabled: !refreshing,
                    onPrevious: { lastAction = "Previous file" },
                    onNext: { lastAction = "Next file" }
                )
                Spacer()
            }

            Text(lastAction)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .center)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private enum FileNavigationPillGalleryPosition: CaseIterable {
    case first
    case middle
    case last

    var label: String {
        switch self {
        case .first: "First"
        case .middle: "Middle"
        case .last: "Last"
        }
    }
}

#Preview {
    FileNavigationPillGalleryView()
        .padding()
        .openCodeTheme()
}
