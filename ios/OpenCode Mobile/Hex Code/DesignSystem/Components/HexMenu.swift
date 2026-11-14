import SwiftUI

/// One item of a `HexMenu`: a 15 pt leading icon in its own tone, a title and a second line.
struct HexMenuItem: Identifiable {
    let id: String
    let icon: HexIcon
    var iconColor: Color = HexColor.text2
    let title: String
    let detail: String
    var enabled = true
    let action: () -> Void
}

/// A small pop-up menu (the prototype's `.dmenu`): `panel3`, a `line2` border, radius `l`, a
/// shadow, padding 4, at least 230 pt wide. Items: a 15 pt icon, a title (`callout`, `text1`) and a
/// second line (`footnote`, `text3`), padding 9 × 10, radius `s`, `hover` while pressed. A disabled
/// item is dimmed and takes no taps. The caller places it above its button.
struct HexMenu: View {
    let items: [HexMenuItem]

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        VStack(spacing: 0) {
            ForEach(items) { item in
                Button(action: item.action) {
                    HStack(alignment: .top, spacing: 10) {
                        HexIconView(item.icon, size: 15)
                            .foregroundStyle(item.iconColor)
                            .padding(.top, 1)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(verbatim: item.title)
                                .hexFont(.callout)
                                .foregroundStyle(HexColor.text1)
                            Text(verbatim: item.detail)
                                .hexFont(.footnote)
                                .foregroundStyle(HexColor.text3)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(HexRowPressStyle(radius: HexRadius.s))
                .disabled(!item.enabled)
                .opacity(item.enabled ? 1 : 0.35)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(4)
        .frame(minWidth: 230)
        .background(HexColor.panel3, in: shape)
        .overlay(shape.strokeBorder(HexColor.line2, lineWidth: 1))
        .shadow(color: HexColor.shadow, radius: 17, y: 14)
    }
}
