import SwiftUI
import UIKit

/// What a held control reports: the press, how far the finger has slid off it, and the release
/// (or a cancel, such as a scroll taking the touch).
struct HexPress {
    var began: () -> Void
    /// The finger's distance outside the control, in points; 0 while it is over it.
    var moved: (CGFloat) -> Void = { _ in }
    var ended: () -> Void

    /// How far a point is outside a frame of `size` (zero inside it), in points.
    static func distanceOutside(_ point: CGPoint, size: CGSize) -> CGFloat {
        let dx = max(0, -point.x, point.x - size.width)
        let dy = max(0, -point.y, point.y - size.height)
        return (dx * dx + dy * dy).squareRoot()
    }
}

/// A control's touches straight from UIKit, as the talk button takes them: the press starts the
/// moment the touch lands rather than when a gesture is recognised. Invisible, and hidden from
/// VoiceOver; the control gives screen readers their own actions.
struct HexHoldSurface: UIViewRepresentable {
    let press: HexPress

    func makeUIView(context: Context) -> HexHoldView {
        let view = HexHoldView()
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = false
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        return view
    }

    func updateUIView(_ view: HexHoldView, context: Context) {
        view.press = press
    }
}

final class HexHoldView: UIView {
    var press = HexPress(began: {}, ended: {})
    private var tracked: UITouch?

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard tracked == nil, let touch = touches.first else { return }
        tracked = touch
        press.began()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let tracked, touches.contains(tracked) else { return }
        press.moved(HexPress.distanceOutside(tracked.location(in: self), size: bounds.size))
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        finish(touches)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        finish(touches)
    }

    private func finish(_ touches: Set<UITouch>) {
        guard let tracked, touches.contains(tracked) else { return }
        self.tracked = nil
        press.ended()
    }
}

/// The hold ring (the prototype's `.hold-ring`): a 2 pt `red` line 3 pt outside a control,
/// following its corner radius, filling clockwise from the top centre as the hold goes on.
struct HexHoldRing: View {
    /// When the hold began; the ring fills over `duration`.
    let since: Date
    var duration: TimeInterval = HexMotion.stopHold
    /// The control's corner radius.
    var radius: CGFloat = HexRadius.l

    var body: some View {
        TimelineView(.animation) { context in
            let progress = min(1, max(0, context.date.timeIntervalSince(since) / duration))
            HoldRingShape(radius: radius + 3)
                .trim(from: 0, to: progress)
                .stroke(HexColor.red, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .padding(-3)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A rounded rectangle whose path starts at the top centre and runs clockwise, so a trim fills it
/// like a clock hand.
struct HoldRingShape: Shape {
    var radius: CGFloat

    func path(in rect: CGRect) -> Path {
        let r = min(radius, rect.width / 2, rect.height / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addArc(tangent1End: CGPoint(x: rect.maxX, y: rect.minY), tangent2End: CGPoint(x: rect.maxX, y: rect.minY + r), radius: r)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addArc(tangent1End: CGPoint(x: rect.maxX, y: rect.maxY), tangent2End: CGPoint(x: rect.maxX - r, y: rect.maxY), radius: r)
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addArc(tangent1End: CGPoint(x: rect.minX, y: rect.maxY), tangent2End: CGPoint(x: rect.minX, y: rect.maxY - r), radius: r)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addArc(tangent1End: CGPoint(x: rect.minX, y: rect.minY), tangent2End: CGPoint(x: rect.minX + r, y: rect.minY), radius: r)
        path.closeSubpath()
        return path
    }
}
