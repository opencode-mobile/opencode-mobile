import SwiftUI

/// Pager position dots: the current page is a wide capsule, the others short dots.
struct OpenCodePageIndicator: View {
    let pageCount: Int
    let currentPage: Int

    init(pageCount: Int, currentPage: Int) {
        precondition(pageCount > 0, "Page Indicator needs at least one page")
        self.pageCount = pageCount
        self.currentPage = currentPage
    }

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<pageCount, id: \.self) { index in
                Capsule()
                    .fill(index == currentPage ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker)
                    .frame(width: index == currentPage ? 18 : 7, height: 7)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: currentPage)
        .accessibilityElement()
        .accessibilityLabel("Page \(currentPage + 1) of \(pageCount)")
    }
}

#Preview("Page Indicator") {
    VStack(spacing: 16) {
        ForEach(0..<3, id: \.self) { page in
            OpenCodePageIndicator(pageCount: 3, currentPage: page)
        }
    }
    .padding()
    .openCodeTheme()
}
