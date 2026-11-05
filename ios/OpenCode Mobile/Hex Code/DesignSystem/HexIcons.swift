import SwiftUI

/// Hex Code's icons: the prototype's own set (`const P`), drawn on a 24 × 24 grid with a 1.75
/// stroke and round caps and joins. Each is a template SVG in `HexIcons.xcassets`, so it takes
/// the foreground colour. The spinner is `HexSpinner`.
enum HexIcon: String, CaseIterable, Sendable {
    case mic, clip, stop, refresh, check, x, out, undo, doc
    case chevR, chevD, chevL, chevU
    case lock, pause, share, compass, book, folder, cpu, server, bars, card, steer, send, trash
    case spark, target, ask, play, speaker, copy, gear, search, more, pencil, term, eye, info
    case img, scan, alert, moon, sun
    /// Hex Code's mark: a rounded square with three bars (stroke 1.9).
    case mark

    var image: Image { Image("hex_\(rawValue)") }
}

/// An icon at one of the `HexIconSize` sizes, in the current foreground colour.
struct HexIconView: View {
    let icon: HexIcon
    var size: CGFloat = HexIconSize.regular

    init(_ icon: HexIcon, size: CGFloat = HexIconSize.regular) {
        self.icon = icon
        self.size = size
    }

    var body: some View {
        icon.image
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
