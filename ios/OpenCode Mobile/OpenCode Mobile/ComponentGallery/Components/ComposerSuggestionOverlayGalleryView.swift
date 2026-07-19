import SwiftUI

private enum ComposerSuggestionOverlayGalleryScenario: String, CaseIterable, Identifiable {
    case populated
    case empty

    var id: String { rawValue }

    var label: String {
        switch self {
        case .populated: "Populated"
        case .empty: "Empty"
        }
    }
}

struct ComposerSuggestionOverlayGalleryView: View {
    @State private var scenario: ComposerSuggestionOverlayGalleryScenario = .populated
    @State private var lastSelection = "None"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("State")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            HStack(spacing: 8) {
                ForEach(ComposerSuggestionOverlayGalleryScenario.allCases) { option in
                    OpenCodeChoiceBadge(
                        label: option.label,
                        selected: scenario == option,
                        action: { scenario = option }
                    )
                }
            }

            Text("Last selection: \(lastSelection)")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)

            Spacer(minLength: 0)

            OpenCodeComposerSuggestionOverlay(
                items: items,
                emptyMessage: "No commands",
                onSelect: { item in
                    lastSelection = item.title
                }
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var items: [OpenCodeComposerSuggestionItem] {
        switch scenario {
        case .populated:
            [
                OpenCodeComposerSuggestionItem(
                    id: "init",
                    title: "/init",
                    subtitle: "Create AGENTS.md"
                ),
                OpenCodeComposerSuggestionItem(
                    id: "my-skill",
                    title: "/my-skill",
                    subtitle: "A skill",
                    badge: "skill"
                ),
                OpenCodeComposerSuggestionItem(
                    id: "mcp-tool",
                    title: "/mcp-tool",
                    subtitle: "An MCP command",
                    badge: "mcp"
                ),
            ]
        case .empty:
            []
        }
    }
}

#Preview {
    ComposerSuggestionOverlayGalleryView()
        .padding()
        .openCodeTheme()
}
