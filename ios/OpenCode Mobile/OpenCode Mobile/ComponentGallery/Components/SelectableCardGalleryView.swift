import SwiftUI

struct SelectableCardGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            OpenCodeSelectableCard(
                title: "Porcelain",
                blurb: "Warm paper and ink.",
                selected: false,
                action: {}
            ) {
                previewSlot
            }
            OpenCodeSelectableCard(
                title: "Porcelain",
                blurb: "Warm paper and ink.",
                selected: true,
                action: {}
            ) {
                previewSlot
            }
        }
    }

    private var previewSlot: some View {
        Text("Preview")
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textBase)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(8)
            .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

#Preview {
    SelectableCardGalleryView()
        .padding()
        .openCodeTheme()
}
