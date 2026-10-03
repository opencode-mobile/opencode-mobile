import SwiftUI

struct PageIndicatorGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ForEach([(3, 0), (3, 1), (3, 2), (5, 3)], id: \.1) { pageCount, currentPage in
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(currentPage + 1) of \(pageCount)")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                    OpenCodePageIndicator(pageCount: pageCount, currentPage: currentPage)
                }
            }
        }
    }
}

#Preview {
    PageIndicatorGalleryView()
        .padding()
        .openCodeTheme()
}
