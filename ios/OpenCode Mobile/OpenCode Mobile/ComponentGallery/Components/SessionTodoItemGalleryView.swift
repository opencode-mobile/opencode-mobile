import SwiftUI

struct SessionTodoItemGalleryView: View {
    @State private var status = OpenCodeSessionTodoItemStatus.inProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Interactive")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Review pending, in progress, completed, and cancelled Session Todo rows.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)

            HStack(spacing: 8) {
                ForEach(OpenCodeSessionTodoItemStatus.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(
                        label: option.galleryLabel,
                        selected: status == option
                    ) {
                        status = option
                    }
                }
            }

            OpenCodeSessionTodoItem(
                content: status.galleryContent,
                status: status
            )
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension OpenCodeSessionTodoItemStatus {
    var galleryLabel: String {
        switch self {
        case .pending: "Pending"
        case .inProgress: "Active"
        case .completed: "Done"
        case .cancelled: "Cancel"
        }
    }

    var galleryContent: String {
        switch self {
        case .pending: "Summarize what the README says in 2 sentences"
        case .inProgress: "List the top-level files in this repo"
        case .completed: "Suggest one small follow-up improvement"
        case .cancelled: "Optional deep dive that was cancelled"
        }
    }
}

#Preview {
    SessionTodoItemGalleryView()
        .padding()
        .openCodeTheme()
}
