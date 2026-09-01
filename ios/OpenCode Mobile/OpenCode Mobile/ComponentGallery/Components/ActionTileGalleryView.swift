import SwiftUI

struct ActionTileGalleryView: View {
    @State private var enabled = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Two-column labeled action tile for palettes such as Commands.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            OpenCodeChoiceBadge(
                label: "Enabled",
                selected: enabled,
                action: { enabled.toggle() }
            )

            HStack(spacing: 10) {
                OpenCodeActionTile(
                    icon: .add,
                    label: "New",
                    accessibilityLabel: "Start a new session",
                    enabled: enabled,
                    action: {}
                )
                OpenCodeActionTile(
                    icon: .terminal,
                    label: "Terminal",
                    accessibilityLabel: "Open project shell",
                    enabled: enabled,
                    action: {}
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ActionTileGalleryView()
        .padding()
        .openCodeTheme()
}
