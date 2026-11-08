import SwiftUI

/// A bottom sheet on `panel` in the system sheet's shape (radius 20 at the top before iOS 26), a
/// grab bar, and a head with the title and up to one action on each side. Shown with
/// `.hexSheet(isPresented:)`, on the system sheet.
///
/// A request sheet (a question or approval, the prototype's `.rq-head`) has a request head
/// instead: the title on the left in `headline`, an optional count in `mono` `text3`, and a 36 pt
/// round Hide button. An approval adds the amber edge (`.perm-sh`): a 1 pt `amber` 60 % line along
/// the top with an `amber` glow under it. The system sheet can't draw above itself, so the glow
/// sits just inside the top edge.
struct HexSheet<Leading: View, Trailing: View, Content: View>: View {
    struct RequestHead {
        var title: Text
        var count: String?
        var hideLabel: Text
        var onHide: () -> Void
    }

    let title: Text?
    @ViewBuilder let leading: Leading
    @ViewBuilder let trailing: Trailing
    @ViewBuilder let content: Content
    var request: RequestHead?
    var amberEdge = false

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(HexColor.line2)
                .frame(width: 36, height: 4)
                .padding(.top, 8)
                .padding(.bottom, request == nil ? 10 : 4)
            if let request {
                HStack(spacing: 8) {
                    request.title
                        .hexFont(.headline)
                        .foregroundStyle(HexColor.text1)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityAddTraits(.isHeader)
                    if let count = request.count {
                        Text(verbatim: count)
                            .hexFont(HexTextStyle.mono.sized(12))
                            .foregroundStyle(HexColor.text3)
                    }
                    Button(action: request.onHide) {
                        HexIconView(.chevD, size: HexIconSize.regular)
                            .foregroundStyle(HexColor.text2)
                            .frame(width: 36, height: 36)
                            .background(HexColor.tint(5), in: Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(HexPressStyle(scale: 0.94))
                    .accessibilityLabel(request.hideLabel)
                }
                .frame(minHeight: 44)
                .padding(.bottom, 4)
            } else if let title {
                HStack {
                    leading.frame(minWidth: 70, alignment: .leading)
                    Spacer(minLength: 8)
                    title
                        .hexFont(.bodyStrong)
                        .foregroundStyle(HexColor.text1)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    trailing.frame(minWidth: 70, alignment: .trailing)
                }
                .frame(minHeight: 32)
                .padding(.bottom, 12)
            }
            content
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .overlay(alignment: .top) {
            if amberEdge { HexAmberEdge() }
        }
    }
}

/// An approval sheet's amber top edge: a 1 pt `amber` 60 % line following the sheet's rounded top,
/// with a soft `amber` 25 % glow just inside it. From iOS 26 it follows the system sheet's own
/// corners, concentric with the screen's (radius 20 where no sheet is around it, as in the
/// gallery).
struct HexAmberEdge: View {
    var body: some View {
        if #available(iOS 26, *) {
            // The system doesn't say how round its corners are, so the line fades out down them.
            edge(ConcentricRectangle(corners: .concentric(minimum: .fixed(HexRadius.sheet))), corner: 40, fades: true)
        } else {
            edge(
                UnevenRoundedRectangle(topLeadingRadius: HexRadius.sheet, topTrailingRadius: HexRadius.sheet, style: .continuous),
                corner: HexRadius.sheet,
                fades: false
            )
        }
    }

    /// The glow and the line along `shape`'s top, down to the foot of its top corners (`corner`), or
    /// fading out by then.
    private func edge(_ shape: some Shape, corner: CGFloat, fades: Bool) -> some View {
        ZStack(alignment: .top) {
            LinearGradient(colors: [HexColor.amber.opacity(0.25), HexColor.amber.opacity(0)], startPoint: .top, endPoint: .bottom)
                .frame(height: 18)
            shape.stroke(HexColor.amber.opacity(0.6), lineWidth: 2)
        }
        .frame(height: corner * 2)
        .clipShape(shape)
        .mask(alignment: .top) {
            if fades {
                LinearGradient(stops: [.init(color: .black, location: 0.4), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom)
                    .frame(height: corner)
            } else {
                Rectangle().frame(height: corner + 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension HexSheet where Leading == EmptyView, Trailing == EmptyView {
    /// A request sheet: the request head, and the amber edge for an approval.
    init(request: RequestHead, amberEdge: Bool = false, @ViewBuilder content: () -> Content) {
        title = nil
        leading = EmptyView()
        trailing = EmptyView()
        self.content = content()
        self.request = request
        self.amberEdge = amberEdge
    }
}

extension HexSheet where Leading == EmptyView, Trailing == EmptyView {
    init(_ title: LocalizedStringResource? = nil, @ViewBuilder content: () -> Content) {
        request = nil
        self.title = title.map { Text($0) }
        leading = EmptyView()
        trailing = EmptyView()
        self.content = content()
    }
}

extension View {
    /// Presents a `HexSheet` on the system sheet, with Hex's panel and radius. `onDismiss` runs
    /// once it has gone, for what opens next.
    func hexSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        detents: Set<PresentationDetent> = [.medium, .large],
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            content()
                .presentationDetents(detents)
                .presentationDragIndicator(.hidden)
                .hexSheetChrome()
        }
    }

    /// A sheet as tall as its content (up to the large detent), for request sheets: one per item,
    /// kept on screen while it slides away.
    func hexFittedSheet<Item: Identifiable, SheetContent: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { item in
            HexFittedSheetContent(content: { content(item) })
        }
    }
}

extension View {
    /// A fitted sheet shown while `isPresented` is true, such as `HexConfirmSheet`.
    func hexFittedSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            HexFittedSheetContent(content: content)
        }
    }
}

/// Measures its content and asks for a detent of that height; content taller than the screen
/// scrolls.
private struct HexFittedSheetContent<Content: View>: View {
    let content: () -> Content
    @State private var height: CGFloat = 360

    var body: some View {
        ScrollView {
            content()
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = max(120, $0) }
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.height(height)])
            .presentationDragIndicator(.hidden)
            .hexSheetChrome()
    }
}

private extension View {
    /// Hex's panel behind the system sheet. From iOS 26 the system sheet floats 8 pt in from the screen's sides and
    /// foot with corners concentric with the screen's, and a corner radius of Hex's own would put radius 20
    /// on all four corners instead (hex-design-system.md, `HexSheet` (shape)), so only older systems get it.
    @ViewBuilder func hexSheetChrome() -> some View {
        if #available(iOS 26, *) {
            presentationBackground(HexColor.panel)
        } else {
            presentationCornerRadius(HexRadius.sheet)
                .presentationBackground(HexColor.panel)
        }
    }
}
