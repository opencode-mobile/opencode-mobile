import SwiftUI

/// Leading-aligned wrapping row: subviews flow left to right at their ideal
/// size and wrap to a new line when the proposed width runs out. Used for chip
/// rows (Tool Chips under a step paragraph).
struct OpenCodeChipFlowLayout: Layout {
    var spacing: CGFloat = 4
    var lineSpacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let placement = arrange(subviews: subviews, maxWidth: width)
        return CGSize(width: proposal.width ?? placement.width, height: placement.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let placement = arrange(subviews: subviews, maxWidth: bounds.width)
        for (index, frame) in placement.frames.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                anchor: .topLeading,
                proposal: ProposedViewSize(width: frame.width, height: frame.height)
            )
        }
    }

    private struct Arrangement {
        var frames: [CGRect]
        var width: CGFloat
        var height: CGFloat
    }

    private func arrange(subviews: Subviews, maxWidth: CGFloat) -> Arrangement {
        var frames: [CGRect] = []
        frames.reserveCapacity(subviews.count)
        var cursorX: CGFloat = 0
        var cursorY: CGFloat = 0
        var lineHeight: CGFloat = 0
        var usedWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            let itemWidth = min(size.width, maxWidth)
            if cursorX > 0, cursorX + itemWidth > maxWidth {
                cursorX = 0
                cursorY += lineHeight + lineSpacing
                lineHeight = 0
            }
            frames.append(CGRect(x: cursorX, y: cursorY, width: itemWidth, height: size.height))
            cursorX += itemWidth + spacing
            lineHeight = max(lineHeight, size.height)
            usedWidth = max(usedWidth, cursorX - spacing)
        }
        let height = frames.isEmpty ? 0 : cursorY + lineHeight
        return Arrangement(frames: frames, width: usedWidth, height: height)
    }
}
