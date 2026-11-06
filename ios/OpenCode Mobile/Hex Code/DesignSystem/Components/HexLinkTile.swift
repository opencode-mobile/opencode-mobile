import SwiftUI

/// A tile under the status card (the prototype's `.fa.lnk`): an icon in a tinted square, the label
/// and, under it, a small icon and the address. 56 pt high, at most 190 pt wide.
struct HexLinkTile: View {
    let icon: HexIcon
    let label: String
    let detail: String
    var detailIcon: HexIcon?
    /// VoiceOver's label, for example "Open Login docs".
    var accessibilityText: String?
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        Button(action: action) {
            HStack(spacing: 10) {
                HexIconView(icon, size: HexIconSize.regular)
                    .foregroundStyle(HexColor.text2)
                    .frame(width: 38, height: 38)
                    .background(HexColor.tint(6), in: RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: label)
                        .hexFont(.callout)
                        .foregroundStyle(HexColor.text1)
                    HStack(spacing: 3) {
                        if let detailIcon {
                            HexIconView(detailIcon, size: 11)
                        }
                        Text(verbatim: detail)
                            .hexFont(.footnote)
                    }
                    .foregroundStyle(HexColor.text3)
                }
                .lineLimit(1)
            }
            .padding(.leading, 8)
            .padding(.trailing, 12)
            .frame(height: 56)
            .frame(maxWidth: 190, alignment: .leading)
            .fixedSize(horizontal: true, vertical: false)
            .background(HexColor.panel, in: shape)
            .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(HexPressStyle())
        .accessibilityLabel(Text(verbatim: accessibilityText ?? label))
        .accessibilityAddTraits(.isLink)
    }
}

/// One row of tiles that scrolls sideways and fades at its right edge while there is more to
/// scroll to (the prototype's `.fx-att`). Shows nothing when there are no tiles.
struct HexTileRow<Content: View>: View {
    @ViewBuilder let content: Content

    @State private var more = false

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                content
            }
        }
        .scrollClipDisabled(false)
        .onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentOffset.x + geometry.containerSize.width < geometry.contentSize.width - 4
        } action: { _, hasMore in
            more = hasMore
        }
        .mask {
            HStack(spacing: 0) {
                Color.black
                LinearGradient(colors: [.black, more ? .clear : .black], startPoint: .leading, endPoint: .trailing)
                    .frame(width: 36)
            }
        }
        .frame(height: 56)
    }
}
