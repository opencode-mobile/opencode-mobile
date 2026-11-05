import SwiftUI

/// A bottom sheet on `panel`: radius 20 at the top, a grab bar, and a head with the title and
/// up to one action on each side. Shown with `.hexSheet(isPresented:)`, on the system sheet.
struct HexSheet<Leading: View, Trailing: View, Content: View>: View {
    let title: Text?
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(HexColor.line2)
                .frame(width: 36, height: 4)
                .padding(.top, 8)
                .padding(.bottom, 10)
            if let title {
                HStack {
                    leading.frame(minWidth: 70, alignment: .leading)
                    Spacer(minLength: 8)
                    title
                        .hexFont(.bodyStrong)
                        .foregroundStyle(HexColor.text1)
                        .lineLimit(1)
                        .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    trailing.frame(minWidth: 70, alignment: .trailing)
                }
                .frame(height: 32)
                .padding(.bottom, 12)
            }
            content
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

extension HexSheet where Leading == EmptyView, Trailing == EmptyView {
    init(_ title: LocalizedStringResource? = nil, @ViewBuilder content: () -> Content) {
        self.title = title.map { Text($0) }
        leading = EmptyView()
        trailing = EmptyView()
        self.content = content()
    }
}

extension View {
    /// Presents a `HexSheet` on the system sheet, with Hex's panel and radius.
    func hexSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        detents: Set<PresentationDetent> = [.medium, .large],
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        sheet(isPresented: isPresented) {
            content()
                .presentationDetents(detents)
                .presentationDragIndicator(.hidden)
                .presentationCornerRadius(HexRadius.sheet)
                .presentationBackground(HexColor.panel)
        }
    }
}
