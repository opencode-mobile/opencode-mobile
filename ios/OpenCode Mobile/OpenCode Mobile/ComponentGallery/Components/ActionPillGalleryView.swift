import SwiftUI

struct ActionPillGalleryView: View {
    @State private var enabled = true
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Floating pill for contextual overlay actions such as scroll to end.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            OpenCodeChoiceBadge(
                label: "Enabled",
                selected: enabled,
                action: { enabled.toggle() }
            )

            ForEach(Array(OpenCodeActionPillVariant.allCases.enumerated()), id: \.offset) { _, variant in
                HStack {
                    Spacer()
                    OpenCodeActionPill(variant: variant, enabled: enabled, action: {})
                    Spacer()
                }
            }

            Text("Expand pill")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Shows or hides the rows nested under a row, such as a project's worktrees.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            HStack(spacing: 12) {
                OpenCodeExpandPill(
                    title: "3 worktrees",
                    isExpanded: expanded,
                    enabled: enabled,
                    accessibilityLabel: expanded ? "Hide worktrees" : "Show worktrees",
                    action: { expanded.toggle() }
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ActionPillGalleryView()
        .padding()
        .openCodeTheme()
}
