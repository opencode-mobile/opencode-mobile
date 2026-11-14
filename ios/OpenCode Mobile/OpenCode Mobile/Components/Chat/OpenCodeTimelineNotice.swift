import SwiftUI

/// Which notice line. `moved` leads with the ↳ glyph; `goal` and `goalEnded` lead with the
/// goal mark and a stronger label; every other notice is `plain`.
enum OpenCodeTimelineNoticeVariant: String, CaseIterable, Sendable {
    case plain
    case moved
    /// The current `/goal`'s Goal set row; tapping it opens the goal.
    case goal
    /// An earlier goal's Goal set row: dimmed, with its trailing tag ("ended"), not tappable.
    case goalEnded
}

/// Whether the value sits on one tail-truncated line or wraps in full.
enum OpenCodeTimelineNoticeState: String, CaseIterable, Sendable {
    case collapsed
    case expanded

    var toggled: OpenCodeTimelineNoticeState {
        self == .collapsed ? .expanded : .collapsed
    }
}

/// Quiet one-line record of a session-level change in the timeline (moved, agent,
/// model, instructions, restart): a weak label, then the value in the info tint.
/// No card, fill or border. A row with a value toggles it between one truncated
/// line and the full text; the accessibility label always carries the full value.
struct OpenCodeTimelineNotice: View {
    let variant: OpenCodeTimelineNoticeVariant
    let label: String
    let value: String?
    let state: OpenCodeTimelineNoticeState
    let onToggle: (() -> Void)?
    /// `goal` only: opens what the row stands for instead of toggling the value.
    let onActivate: (() -> Void)?
    /// `goalEnded` only: the trailing tag, e.g. "ended".
    let tag: String?

    init(
        variant: OpenCodeTimelineNoticeVariant = .plain,
        label: String,
        value: String? = nil,
        state: OpenCodeTimelineNoticeState = .collapsed,
        onToggle: (() -> Void)? = nil,
        onActivate: (() -> Void)? = nil,
        tag: String? = nil
    ) {
        precondition(!label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.variant = variant
        self.label = label
        self.value = value.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        self.state = state
        self.onToggle = onToggle
        self.onActivate = variant == .goal ? onActivate : nil
        self.tag = variant == .goalEnded ? tag.flatMap { $0.isEmpty ? nil : $0 } : nil
    }

    private var isExpanded: Bool { state == .expanded }
    private var isGoal: Bool { variant == .goal || variant == .goalEnded }
    private var toggles: Bool { !isGoal && value != nil && onToggle != nil }
    private var activates: Bool { onActivate != nil }
    private var tapAction: (() -> Void)? { activates ? onActivate : (toggles ? onToggle : nil) }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Self.spacing) {
            if variant == .moved {
                Text(Self.movedGlyph)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .fixedSize()
            }
            if isGoal {
                OpenCodeIconImage(icon: Self.goalIcon, size: Self.iconSize)
                    .foregroundStyle(OpenCodeColors.iconBase)
                    .alignmentGuide(.firstTextBaseline) { $0[.bottom] * 0.8 }
            }
            Text(label)
                .foregroundStyle(variant == .goal ? OpenCodeColors.textStrong : OpenCodeColors.textWeak)
                .fontWeight(variant == .goal ? .semibold : nil)
                .lineLimit(isExpanded || value == nil ? nil : 1)
                .fixedSize(horizontal: isGoal, vertical: false)
                .layoutPriority(1)
            if let value {
                Text(value)
                    .foregroundStyle(isGoal ? OpenCodeColors.textBase : OpenCodeColors.info)
                    .lineLimit(isExpanded ? nil : 1)
                    .truncationMode(.tail)
                    .fixedSize(horizontal: false, vertical: isExpanded)
            }
            if let tag {
                OpenCodeTag(.localized(tag), size: .small)
            }
        }
        .font(OpenCodeTypography.caption)
        .padding(.vertical, Self.verticalPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .opacity(variant == .goalEnded ? Self.endedOpacity : 1)
        .contentShape(Rectangle())
        .onTapGesture {
            tapAction?()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(tapAction != nil ? .isButton : [])
        .accessibilityHint(toggles ? (isExpanded ? "Shows one line" : "Shows the full value") : "")
        .openCodeKeyboardFocusable(
            .insetRoundedRectangle(cornerRadius: OpenCodeRadius.small),
            enabled: tapAction != nil,
            activate: tapAction
        )
    }

    private var accessibilityText: String {
        [label, value, tag].compactMap { $0 }.joined(separator: " ")
    }

    static let movedGlyph = "\u{21B3}"
    /// The goal mark, as on the goal pill.
    static let goalIcon: OpenCodeIcon = .radioButtonChecked
    private static let iconSize: CGFloat = 14
    private static let endedOpacity = 0.6
    private static let spacing: CGFloat = 6
    private static let verticalPadding: CGFloat = 2
}

#if DEBUG
#Preview("Timeline Notice") {
    TimelineNoticeGalleryView()
        .padding()
        .openCodeTheme()
}

#Preview("Timeline Notice dark") {
    TimelineNoticeGalleryView()
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
#endif
