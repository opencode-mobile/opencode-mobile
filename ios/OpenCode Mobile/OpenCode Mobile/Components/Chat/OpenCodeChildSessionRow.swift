import SwiftUI

/// Where a child session row's subagent is, shown beside the elapsed time.
enum OpenCodeChildSessionRowStatus: CaseIterable, Equatable {
    case running
    /// Amber: the subagent waits on a permission or question.
    case waitingForInput
    case done
    /// Error token: the subagent's call failed.
    case failed
    /// The run was stopped before the subagent finished.
    case stopped

    var label: String {
        switch self {
        case .running: "running"
        case .waitingForInput: "waiting for input"
        case .done: "done"
        case .failed: "failed"
        case .stopped: "stopped"
        }
    }

    var color: Color {
        switch self {
        case .waitingForInput: OpenCodeColors.warning
        case .failed: OpenCodeColors.error
        case .running, .done, .stopped: OpenCodeColors.textWeaker
        }
    }
}

struct OpenCodeChildSessionRow: View {
    let title: String
    let caption: String?
    let subtitle: String?
    let elapsed: String?
    let status: OpenCodeChildSessionRowStatus?
    let action: (() -> Void)?

    init(
        title: String,
        caption: String? = nil,
        subtitle: String? = nil,
        elapsed: String? = nil,
        status: OpenCodeChildSessionRowStatus? = nil,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.caption = caption
        self.subtitle = subtitle
        self.elapsed = elapsed
        self.status = status
        self.action = action
    }

    var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    rowContent
                }
                .buttonStyle(.plain)
            } else {
                rowContent
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    private var rowContent: some View {
        HStack(alignment: .center, spacing: Self.contentSpacing) {
            letterAvatar
            VStack(alignment: .leading, spacing: Self.textStackSpacing) {
                HStack(alignment: .firstTextBaseline, spacing: Self.titleRowSpacing) {
                    Text(title)
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                    if let caption, !caption.isEmpty {
                        Text(caption)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeaker)
                            .lineLimit(1)
                    }
                }
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: Self.trailingSpacing) {
                if let status {
                    Text(status.label)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(status.color)
                        .lineLimit(1)
                }
                if let elapsed, !elapsed.isEmpty {
                    Text(elapsed)
                        .font(OpenCodeTypography.caption.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
                if action != nil {
                    OpenCodeIconImage(icon: .chevronRight, size: Self.chevronSize)
                        .foregroundStyle(OpenCodeColors.iconStrongBase)
                        .accessibilityHidden(true)
                }
            }
            // Status and time stay whole; the subtitle truncates first.
            .fixedSize(horizontal: true, vertical: false)
        }
        .padding(.horizontal, Self.paddingHorizontal)
        .padding(.vertical, Self.paddingVertical)
        .background(
            RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                .fill(OpenCodeColors.surfaceBase)
                .overlay(
                    RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                        .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                )
        )
    }

    private var letterAvatar: some View {
        Text(childSessionRowLetter(from: title))
            .font(.system(size: Self.avatarGlyphSize, weight: .bold, design: .rounded))
            .foregroundStyle(OpenCodeColors.textStrong)
            .frame(width: Self.avatarSize, height: Self.avatarSize)
            .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
            .accessibilityHidden(true)
    }

    private var accessibilityLabel: String {
        buildChildSessionRowAccessibilityLabel(
            title: title,
            caption: caption,
            subtitle: subtitle,
            elapsed: elapsed,
            status: status?.label
        )
    }

    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 10
    private static let avatarSize: CGFloat = 32
    private static let avatarGlyphSize: CGFloat = 14
    private static let contentSpacing: CGFloat = 12
    private static let titleRowSpacing: CGFloat = 8
    private static let textStackSpacing: CGFloat = 2
    private static let trailingSpacing: CGFloat = 6
    private static let chevronSize: CGFloat = 16
}

func childSessionRowLetter(from title: String) -> String {
    guard let first = title.first else { return "?" }
    return String(first).uppercased()
}

func buildChildSessionRowAccessibilityLabel(
    title: String,
    caption: String?,
    subtitle: String?,
    elapsed: String?,
    status: String? = nil
) -> String {
    var parts = [title]
    if let caption, !caption.isEmpty { parts.append(caption) }
    if let subtitle, !subtitle.isEmpty { parts.append(subtitle) }
    if let status, !status.isEmpty { parts.append(status) }
    if let elapsed, !elapsed.isEmpty { parts.append(elapsed) }
    return parts.joined(separator: ", ")
}

#if DEBUG
private enum ChildSessionRowPreviewCase: String, CaseIterable, Identifiable {
    case running
    case background
    case completed
    case displayOnly

    var id: String { rawValue }

    var title: String {
        switch self {
        case .running, .background: "explore"
        case .completed: "review"
        case .displayOnly: "Task"
        }
    }

    var caption: String? {
        self == .background ? "background" : nil
    }

    var subtitle: String? {
        switch self {
        case .running: "Searching the repo"
        case .background: "Find auth bugs"
        case .completed: "Standards review of OPE-144"
        case .displayOnly: "Investigate flaky tests"
        }
    }

    var elapsed: String? {
        switch self {
        case .running: "1:04"
        case .background: "0:12"
        case .completed, .displayOnly: nil
        }
    }

    var tappable: Bool {
        self != .displayOnly
    }
}

#Preview("Child Session Row") {
    ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(ChildSessionRowPreviewCase.allCases) { preview in
                OpenCodeChildSessionRow(
                    title: preview.title,
                    caption: preview.caption,
                    subtitle: preview.subtitle,
                    elapsed: preview.elapsed,
                    action: preview.tappable ? {} : nil
                )
            }
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
