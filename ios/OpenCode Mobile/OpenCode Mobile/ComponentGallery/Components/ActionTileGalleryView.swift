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

            Text("Badge")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Corner badge: tone-only dot, or a count pill when something needs attention.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 10) {
                OpenCodeActionTile(
                    icon: .plug,
                    label: "MCP servers",
                    enabled: enabled,
                    badge: OpenCodeActionTileBadge(tone: .success),
                    action: {}
                )
                OpenCodeActionTile(
                    icon: .plug,
                    label: "MCP servers",
                    enabled: enabled,
                    badge: OpenCodeActionTileBadge(tone: .warning),
                    action: {}
                )
                OpenCodeActionTile(
                    icon: .plug,
                    label: "MCP servers",
                    enabled: enabled,
                    badge: OpenCodeActionTileBadge(tone: .error, count: 2),
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
