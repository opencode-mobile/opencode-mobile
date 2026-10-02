import SwiftUI

struct ToolCallCardGalleryView: View {
    @State private var status: OpenCodeToolCallCardStatus = .completed
    @State private var showSubtitle = true
    @State private var showDiffCounts = true
    @State private var showErrorText = false
    @State private var tappable = false

    private var subtitle: String? {
        showSubtitle ? "OpenCodeListItem.swift" : nil
    }

    private var additions: Int? {
        showDiffCounts ? 12 : nil
    }

    private var deletions: Int? {
        showDiffCounts ? 3 : nil
    }

    private var errorText: String? {
        guard showErrorText else { return nil }
        return status == .interrupted ? "Stopped" : "Request failed with status 403"
    }

    private var title: String {
        switch status {
        case .pending: "Called bash"
        case .running: "Called task"
        case .completed: "Called edit"
        case .error: "Called webfetch"
        case .interrupted: "Called shell"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Status")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    ForEach(OpenCodeToolCallCardStatus.allCases, id: \.self) { value in
                        OpenCodeChoiceBadge(
                            label: value.rawValue.capitalized,
                            selected: status == value,
                            action: {
                                status = value
                                if value == .error || value == .interrupted {
                                    showErrorText = true
                                }
                            }
                        )
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Content")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Subtitle",
                        selected: showSubtitle,
                        action: { showSubtitle.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "+/−",
                        selected: showDiffCounts,
                        action: { showDiffCounts.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Error text",
                        selected: showErrorText,
                        action: { showErrorText.toggle() }
                    )
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Interaction")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                OpenCodeChoiceBadge(
                    label: "Tappable",
                    selected: tappable,
                    action: { tappable.toggle() }
                )
            }

            OpenCodeToolCallCard(
                status: status,
                title: title,
                subtitle: subtitle,
                additions: additions,
                deletions: deletions,
                errorText: errorText,
                action: tappable ? {} : nil
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ToolCallCardGalleryView()
        .padding()
        .openCodeTheme()
}
