import SwiftUI
import UIKit

/// A picked screenshot or photo as a small rounded square (the prototype's `.att-t`): 40 pt on
/// the draft, with a small ✕ that removes it; 24 pt in Waiting to send. While the image is
/// prepared it shows a spinner (no percentage); dimmed when it won't be sent.
struct HexThumbnail: View {
    enum Size {
        /// 40 pt, radius 10: the draft.
        case regular
        /// 24 pt, radius 5: a row in Waiting to send.
        case small

        var side: CGFloat { self == .regular ? 40 : 24 }
        var radius: CGFloat { self == .regular ? HexRadius.m : HexRadius.xs }
    }

    /// Nil while preparing.
    let image: UIImage?
    var size: Size = .regular
    var dimmed = false
    /// The ✕; none when nil.
    var onRemove: (() -> Void)?
    var removeLabel: Text = Text(LocalizedStringResource("home.attach.remove", defaultValue: "Remove image"))

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size.radius, style: .continuous)
        ZStack {
            shape.fill(HexColor.tint(6))
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size.side, height: size.side)
                    .clipShape(shape)
            } else {
                HexSpinner(size: size == .regular ? HexIconSize.regular : HexIconSize.small)
            }
            shape.strokeBorder(HexColor.tint(12), lineWidth: 1)
        }
        .frame(width: size.side, height: size.side)
        .opacity(dimmed ? 0.45 : 1)
        .overlay(alignment: .topTrailing) {
            if let onRemove {
                Button(action: onRemove) {
                    HexIconView(.x, size: 8)
                        .foregroundStyle(HexColor.text2)
                        .frame(width: 17, height: 17)
                        .background(Circle().fill(HexColor.panel3))
                        .overlay(Circle().strokeBorder(HexColor.line2, lineWidth: 1))
                        // A bigger target than the mark, reaching out past the corner.
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .offset(x: 11, y: -11)
                .accessibilityLabel(removeLabel)
            }
        }
        .accessibilityElement(children: .contain)
    }
}
