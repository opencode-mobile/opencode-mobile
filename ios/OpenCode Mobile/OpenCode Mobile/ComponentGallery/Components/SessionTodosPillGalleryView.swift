import SwiftUI

struct SessionTodosPillGalleryView: View {
    @State private var enabled = true
    @State private var summary = SessionTodosPillGallerySummary.midProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review mid-progress, near-complete, no-preview, and enabled/disabled states.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Mid", selected: summary == .midProgress) {
                    summary = .midProgress
                }
                OpenCodeChoiceBadge(label: "Near done", selected: summary == .nearComplete) {
                    summary = .nearComplete
                }
                OpenCodeChoiceBadge(label: "No preview", selected: summary == .noPreview) {
                    summary = .noPreview
                }
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) { enabled.toggle() }
            }

            HStack {
                Spacer()
                OpenCodeSessionTodosPill(
                    completedCount: summary.completedCount,
                    totalCount: summary.totalCount,
                    activePreview: summary.activePreview,
                    enabled: enabled,
                    action: {}
                )
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private enum SessionTodosPillGallerySummary {
    case midProgress
    case nearComplete
    case noPreview

    var completedCount: Int {
        switch self {
        case .midProgress: 2
        case .nearComplete: 4
        case .noPreview: 1
        }
    }

    var totalCount: Int {
        switch self {
        case .midProgress: 5
        case .nearComplete: 5
        case .noPreview: 3
        }
    }

    var activePreview: String? {
        switch self {
        case .midProgress: "Implement auth middleware"
        case .nearComplete: "Write integration tests"
        case .noPreview: nil
        }
    }
}

#Preview {
    SessionTodosPillGalleryView()
        .padding()
        .openCodeTheme()
}
