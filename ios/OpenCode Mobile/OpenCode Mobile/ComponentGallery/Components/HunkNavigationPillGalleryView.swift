import SwiftUI

struct HunkNavigationPillGalleryView: View {
    @State private var position = HunkNavigationPosition.middle
    @State private var expanded = false
    @State private var contextEnabled = true
    @State private var lastAction = "No action"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review boundaries, context mode, and unavailable context.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(HunkNavigationPosition.allCases, id: \.self) { value in
                    OpenCodeChoiceBadge(label: value.label, selected: position == value) { position = value }
                }
            }
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Expanded", selected: expanded) { expanded.toggle() }
                OpenCodeChoiceBadge(label: "No context", selected: !contextEnabled) { contextEnabled.toggle() }
            }

            OpenCodeHunkNavigationPill(
                previousEnabled: position != .first,
                nextEnabled: position != .last,
                contextEnabled: contextEnabled,
                contextExpanded: expanded,
                onPrevious: { lastAction = "Previous hunk" },
                onToggleContext: {
                    expanded.toggle()
                    lastAction = expanded ? "Expand context" : "Collapse context"
                },
                onNext: { lastAction = "Next hunk" }
            )
            .frame(maxWidth: .infinity)

            Text(lastAction)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(maxWidth: .infinity, alignment: .center)
        }
    }
}
private enum HunkNavigationPosition: CaseIterable {
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
    HunkNavigationPillGalleryView().padding().openCodeTheme()
}
