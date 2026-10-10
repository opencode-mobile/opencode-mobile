import SwiftUI

struct BottomFadeGalleryView: View {
    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Content under the dock")
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("The fade covers the last lines without taking taps.")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: 160)
            OpenCodeBottomFade(height: 72)
        }
        .background(OpenCodeColors.backgroundBase)
    }
}

#Preview {
    BottomFadeGalleryView()
        .openCodeTheme()
}
