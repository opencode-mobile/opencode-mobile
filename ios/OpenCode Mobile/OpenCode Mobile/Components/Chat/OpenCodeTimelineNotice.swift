import SwiftUI

/// Which notice line. `moved` leads with the ↳ glyph; every other notice is `plain`.
enum OpenCodeTimelineNoticeVariant: String, CaseIterable, Sendable {
    case plain
    case moved
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

    init(
        variant: OpenCodeTimelineNoticeVariant = .plain,
        label: String,
        value: String? = nil,
        state: OpenCodeTimelineNoticeState = .collapsed,
        onToggle: (() -> Void)? = nil
    ) {
        precondition(!label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.variant = variant
        self.label = label
        self.value = value.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        self.state = state
        self.onToggle = onToggle
    }

    private var isExpanded: Bool { state == .expanded }
    private var toggles: Bool { value != nil && onToggle != nil }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Self.spacing) {
            if variant == .moved {
                Text(Self.movedGlyph)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .fixedSize()
            }
            Text(label)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(isExpanded || value == nil ? nil : 1)
                .layoutPriority(1)
            if let value {
                Text(value)
                    .foregroundStyle(OpenCodeColors.info)
                    .lineLimit(isExpanded ? nil : 1)
                    .truncationMode(.tail)
                    .fixedSize(horizontal: false, vertical: isExpanded)
            }
        }
        .font(OpenCodeTypography.caption)
        .padding(.vertical, Self.verticalPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture {
            guard toggles else { return }
            onToggle?()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(toggles ? .isButton : [])
        .accessibilityHint(toggles ? (isExpanded ? "Shows one line" : "Shows the full value") : "")
        .openCodeKeyboardFocusable(.insetRoundedRectangle(cornerRadius: OpenCodeRadius.small), enabled: toggles, activate: onToggle)
    }

    private var accessibilityText: String {
        guard let value else { return label }
        return "\(label) \(value)"
    }

    static let movedGlyph = "\u{21B3}"
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
