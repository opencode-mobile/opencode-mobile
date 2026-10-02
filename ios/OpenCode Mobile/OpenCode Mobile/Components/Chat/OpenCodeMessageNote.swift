import SwiftUI

/// Why an assistant reply is incomplete; each variant owns its copy and glyph.
enum OpenCodeMessageNoteVariant: String, CaseIterable, Sendable {
    /// The model hit its output limit.
    case truncated
    /// The user (or another client) stopped the run.
    case interrupted

    var label: String {
        switch self {
        case .truncated: "Response cut off (length limit)"
        case .interrupted: "Interrupted"
        }
    }

    fileprivate var icon: OpenCodeIcon {
        switch self {
        case .truncated: .collapseContent
        case .interrupted: .stopCircle
        }
    }
}

/// Small, persistent, non-interactive note under a message that ended early.
/// Muted on purpose: it explains state, it is not an error.
struct OpenCodeMessageNote: View {
    let variant: OpenCodeMessageNoteVariant

    var body: some View {
        HStack(spacing: Self.spacing) {
            OpenCodeIconImage(icon: variant.icon, size: Self.iconSize)
                .fixedSize()
            Text(variant.label)
                .font(OpenCodeTypography.caption)
        }
        .foregroundStyle(OpenCodeColors.textWeak)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(variant.label)
    }

    private static let iconSize: CGFloat = 14
    private static let spacing: CGFloat = 6
}

#if DEBUG
#Preview("Message Note") {
    VStack(alignment: .leading, spacing: 8) {
        ForEach(OpenCodeMessageNoteVariant.allCases, id: \.self) { OpenCodeMessageNote(variant: $0) }
    }
    .padding()
    .openCodeTheme()
}

#Preview("Message Note dark") {
    VStack(alignment: .leading, spacing: 8) {
        ForEach(OpenCodeMessageNoteVariant.allCases, id: \.self) { OpenCodeMessageNote(variant: $0) }
    }
    .padding()
    .openCodeTheme()
    .preferredColorScheme(.dark)
}
#endif
