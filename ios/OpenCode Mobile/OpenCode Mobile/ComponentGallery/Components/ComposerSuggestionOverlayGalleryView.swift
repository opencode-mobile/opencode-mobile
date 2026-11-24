import SwiftUI

enum ComposerSuggestionOverlayGalleryScenario: String, CaseIterable, Identifiable {
    case populated
    /// Two rows: the overlay hugs them instead of scrolling.
    case short
    case mentions
    case empty

    var id: String { rawValue }

    var label: String {
        switch self {
        case .populated: "Populated"
        case .short: "Two rows"
        case .mentions: "Mentions"
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
                        label: .verbatim(option.label),
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

    private var items: [OpenCodeComposerSuggestionItem] { scenario.items }
}

#Preview {
    ComposerSuggestionOverlayGalleryView()
        .padding()
        .openCodeTheme()
}

extension ComposerSuggestionOverlayGalleryScenario {
    var items: [OpenCodeComposerSuggestionItem] {
        switch self {
        case .populated:
            (1 ... 8).map { index in
                OpenCodeComposerSuggestionItem(
                    id: "cmd-\(index)",
                    title: "/command-\(index)",
                    subtitle: "Description \(index)",
                    badge: index.isMultiple(of: 3) ? "skill" : (index.isMultiple(of: 2) ? "mcp" : nil)
                )
            }
        case .short:
            [
                OpenCodeComposerSuggestionItem(id: "init", title: "/init", subtitle: "Create AGENTS.md"),
                OpenCodeComposerSuggestionItem(id: "my-skill", title: "/my-skill", subtitle: "A skill", badge: "skill"),
            ]
        case .mentions:
            [
                OpenCodeComposerSuggestionItem(
                    id: "build",
                    title: "@build",
                    subtitle: "Default agent",
                    leadingIcon: .icon(.agent)
                ),
            ] + ["Package.swift", "app/build.gradle", "src/main.kt", "web/App.tsx", "README.md", "src"]
                .map { path in
                    let isFolder = !path.contains(".")
                    return OpenCodeComposerSuggestionItem(
                        id: "file:\(path)",
                        title: path,
                        subtitle: "File",
                        leadingIcon: .file(path: path, kind: isFolder ? .directory : .file)
                    )
                }
        case .empty:
            []
        }
    }
}
