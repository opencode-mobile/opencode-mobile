import SwiftUI
import UIKit

enum OpenCodeSessionTodoItemVariant {
    case statusRow
}

enum OpenCodeSessionTodoItemStatus: CaseIterable, Hashable {
    case pending
    case inProgress
    case completed
    case cancelled

    var accessibilityPhrase: String {
        switch self {
        case .pending: "pending"
        case .inProgress: "in progress"
        case .completed: "completed"
        case .cancelled: "cancelled"
        }
    }
}

enum SessionTodoItemPreviewCase: CaseIterable {
    case pendingLight
    case inProgressDark
    case completedLight
    case cancelledDark

    private var configuration: (content: String, status: OpenCodeSessionTodoItemStatus, colorScheme: ColorScheme) {
        switch self {
        case .pendingLight: ("Summarize README", .pending, .light)
        case .inProgressDark: ("List top-level files", .inProgress, .dark)
        case .completedLight: ("Suggest a follow-up", .completed, .light)
        case .cancelledDark: ("Optional deep dive", .cancelled, .dark)
        }
    }

    var content: String { configuration.content }
    var status: OpenCodeSessionTodoItemStatus { configuration.status }
    var colorScheme: ColorScheme { configuration.colorScheme }
}

struct OpenCodeSessionTodoItem: View {
    let variant: OpenCodeSessionTodoItemVariant
    let content: String
    let status: OpenCodeSessionTodoItemStatus

    init(
        variant: OpenCodeSessionTodoItemVariant = .statusRow,
        content: String,
        status: OpenCodeSessionTodoItemStatus
    ) {
        self.variant = variant
        self.content = content
        self.status = status
    }

    var body: some View {
        HStack(alignment: .top, spacing: Self.contentSpacing) {
            OpenCodeSessionTodoItemMark(status: status)
                // Center the mark on the first body line, not the full wrapped block.
                .padding(.top, Self.markFirstLineOffset)
            Text(content)
                .font(OpenCodeTypography.body)
                .foregroundStyle(labelColor)
                .strikethrough(isFinished)
                .opacity(status == .pending ? Self.pendingContentOpacity : 1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(content), \(status.accessibilityPhrase)")
    }

    private var isFinished: Bool {
        status == .completed || status == .cancelled
    }

    private var labelColor: Color {
        isFinished ? OpenCodeColors.textBase : OpenCodeColors.textStrong
    }

    private static let contentSpacing: CGFloat = 12
    private static let paddingHorizontal: CGFloat = 20
    private static let paddingVertical: CGFloat = 10
    private static let pendingContentOpacity: CGFloat = 0.85
    private static let markSize: CGFloat = 18
    /// `(body line height − mark) / 2` for 16pt system body.
    private static let markFirstLineOffset: CGFloat = {
        let lineHeight = UIFont.systemFont(ofSize: 16, weight: .regular).lineHeight
        return max(0, (lineHeight - markSize) / 2)
    }()
}

private struct OpenCodeSessionTodoItemMark: View {
    let status: OpenCodeSessionTodoItemStatus

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                .fill(fillColor)
                .overlay(
                    RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                        .strokeBorder(borderColor, lineWidth: Self.borderWidth)
                )

            switch status {
            case .completed, .cancelled:
                OpenCodeIconImage(icon: .check, size: Self.iconSize)
                    .foregroundStyle(OpenCodeColors.textInteractiveBase)
            case .inProgress:
                Capsule()
                    .fill(OpenCodeColors.textInteractiveBase)
                    .frame(width: Self.indeterminateWidth, height: Self.indeterminateHeight)
            case .pending:
                EmptyView()
            }
        }
        .frame(width: Self.size, height: Self.size)
        .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous))
    }

    private var borderColor: Color {
        switch status {
        case .pending: OpenCodeColors.borderWeakerBase
        case .inProgress, .completed, .cancelled: OpenCodeColors.textInteractiveBase
        }
    }

    private var fillColor: Color {
        switch status {
        case .pending: Color.clear
        case .inProgress, .completed, .cancelled:
            OpenCodeColors.textInteractiveBase.opacity(Self.fillOpacity)
        }
    }

    private static let size: CGFloat = 18
    private static let cornerRadius: CGFloat = 4
    private static let borderWidth: CGFloat = 1.5
    private static let iconSize: CGFloat = 12
    private static let indeterminateWidth: CGFloat = 8
    private static let indeterminateHeight: CGFloat = 2
    private static let fillOpacity: CGFloat = 0.15
}

private struct SessionTodoItemPreview: View {
    let previewCase: SessionTodoItemPreviewCase

    var body: some View {
        OpenCodeSessionTodoItem(
            content: previewCase.content,
            status: previewCase.status
        )
        .openCodeTheme()
        .preferredColorScheme(previewCase.colorScheme)
    }
}

#Preview("Pending Light") {
    SessionTodoItemPreview(previewCase: .pendingLight)
}

#Preview("In Progress Dark") {
    SessionTodoItemPreview(previewCase: .inProgressDark)
}

#Preview("Completed Light") {
    SessionTodoItemPreview(previewCase: .completedLight)
}

#Preview("Cancelled Dark") {
    SessionTodoItemPreview(previewCase: .cancelledDark)
}
