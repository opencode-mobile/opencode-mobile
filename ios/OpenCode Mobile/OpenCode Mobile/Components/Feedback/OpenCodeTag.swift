import SwiftUI

/// Static capsule label, such as the onboarding "Unofficial" tag.
struct OpenCodeTag: View {
    let title: String

    init(_ title: String) {
        precondition(!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(OpenCodeTypography.caption)
            .fontWeight(.bold)
            .foregroundStyle(OpenCodeColors.textStrong)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(OpenCodeColors.surfaceRaisedBase, in: Capsule())
    }
}

#Preview("Tag") {
    OpenCodeTag("Unofficial")
        .padding()
        .openCodeTheme()
}
