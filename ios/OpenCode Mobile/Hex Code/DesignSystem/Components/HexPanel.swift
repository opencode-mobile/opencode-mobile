import SwiftUI

/// A `panel` card with a `line` border and radius 12. Translucent over the camera.
struct HexPanel<Content: View>: View {
    var padding: CGFloat = 16
    var translucent = false
    @ViewBuilder let content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background {
                let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
                if translucent {
                    shape.fill(.ultraThinMaterial)
                        .overlay(shape.fill(HexColor.panel.opacity(0.92)))
                } else {
                    shape.fill(HexColor.panel)
                }
            }
            .overlay(
                RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
                    .strokeBorder(HexColor.line, lineWidth: 1)
            )
    }
}
