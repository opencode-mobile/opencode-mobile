import SwiftUI

struct MessageNoteGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Variants")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text("Persistent note under an assistant reply that ended early.")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            ForEach(OpenCodeMessageNoteVariant.allCases, id: \.self) { variant in
                OpenCodeMessageNote(variant: variant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    MessageNoteGalleryView()
        .padding()
        .openCodeTheme()
}
