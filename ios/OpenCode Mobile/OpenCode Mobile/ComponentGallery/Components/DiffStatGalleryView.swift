import SwiftUI

struct DiffStatGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            labeled("Mixed") {
                OpenCodeDiffStat(additions: 12, deletions: 3)
            }
            labeled("Additions only") {
                OpenCodeDiffStat(additions: 12)
            }
            labeled("Deletions only") {
                OpenCodeDiffStat(deletions: 3)
            }
            labeled("Zeros shown") {
                OpenCodeDiffStat(additions: 0, deletions: 0, separator: .split)
            }
            labeled("Disabled") {
                OpenCodeDiffStat(additions: 12, deletions: 3, enabled: false, style: .button)
            }
            labeled("File row") {
                OpenCodeDiffStat(additions: 8, deletions: 2, style: .label, separator: .split)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func labeled(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
            content()
        }
    }
}

#Preview {
    DiffStatGalleryView()
        .padding()
        .openCodeTheme()
}
