import SwiftUI

/// Lays out one child at its ideal size but reports a width of `progress` times
/// (ideal width + `gap`). Pills in a bottom row enter and leave through it, so the
/// neighbours slide in lockstep instead of jumping. `gap` is the spacing that
/// belongs to the child; it sits on `gapEdge` and collapses with the pill.
struct OpenCodeWidthRevealLayout: Layout {
    var progress: CGFloat
    var gap: CGFloat = 0
    var gapEdge: HorizontalEdge = .trailing

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let ideal = child.sizeThatFits(.unspecified)
        return CGSize(width: max(0, (ideal.width + gap) * progress), height: ideal.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let ideal = child.sizeThatFits(.unspecified)
        let x = gapEdge == .leading ? bounds.maxX - ideal.width : bounds.minX
        child.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: .unspecified)
    }
}

/// `OpenCodeWidthRevealLayout` as a modifier, so a transition can drive `progress`.
struct OpenCodeWidthReveal: ViewModifier, Animatable {
    var progress: CGFloat
    var gap: CGFloat = 0
    var gapEdge: HorizontalEdge = .trailing

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        OpenCodeWidthRevealLayout(progress: progress, gap: gap, gapEdge: gapEdge) { content }
    }
}

extension OpenCodeFloatingPill {
    /// Duration of a pill growing in, shrinking out, or switching between full and round.
    static let widthDuration: Double = 0.26

    /// Easing for width changes: ease-out, no overshoot or reversal.
    static let widthAnimation: Animation = .easeOut(duration: widthDuration)
}

extension AnyTransition {
    /// A bottom-row pill grows from zero width on top of the fade and scale, and shrinks back
    /// to zero on the way out. Put the row spacing next to the pill as padding on `gapEdge`
    /// before applying this, so the spacing grows and collapses with it.
    static func openCodePillRow(gapEdge: HorizontalEdge) -> AnyTransition {
        .modifier(
            active: OpenCodeWidthReveal(progress: 0, gapEdge: gapEdge),
            identity: OpenCodeWidthReveal(progress: 1, gapEdge: gapEdge)
        )
        .combined(with: .opacity)
        .combined(with: .scale(scale: 0.9))
    }
}
