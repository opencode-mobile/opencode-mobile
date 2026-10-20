import SwiftUI

enum OpenCodeTagSize {
    /// Standalone label, such as the onboarding "Unofficial" tag.
    case regular
    /// Inline metadata next to a title, such as a folder's "git" tag.
    case small
}

/// Static capsule label.
struct OpenCodeTag: View {
    let title: String
    let size: OpenCodeTagSize

    init(_ title: String, size: OpenCodeTagSize = .regular) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
        self.size = size
    }

    var body: some View {
        switch size {
        case .regular:
            Text(title)
                .font(OpenCodeTypography.caption)
                .fontWeight(.bold)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
        case .small:
            Text(title)
                .font(OpenCodeTypography.caption)
                .fontWeight(.semibold)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 6)
                .padding(.vertical, 1)
                .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
        }
    }
}

#Preview("Tag") {
    VStack(spacing: 12) {
        OpenCodeTag("Unofficial")
        OpenCodeTag("git", size: .small)
    }
    .padding()
    .openCodeTheme()
}
