import SwiftUI
import UIKit

/// The countdown card while the stop button is held (the prototype's `.stopwarn`): `stopBackground`,
/// a 1 pt `red` 50 % border, radius `xl`, padding 12 × 14, a shadow. A 46 pt ring drains over the
/// hold around the number (3, 2, 1, `code` at 21 semibold in `red`, popping from 1.25× each
/// second), the title and detail, and a 2 pt `red` bar filling along the bottom. Everything runs
/// from `since`, so a redraw never restarts it. With Reduce Motion the number doesn't pop; the
/// ring, bar and countdown still run, since they carry the time.
struct HexStopWarning: View {
    let since: Date
    var duration: TimeInterval = HexMotion.stopHold
    /// "Stopping the agent in 3".
    let title: (Int) -> String
    /// "Let go to keep it running".
    let detail: String
    /// A fixed moment for the gallery: elapsed seconds instead of the clock.
    var frozenAt: TimeInterval?
    /// The gallery's Reduce Motion sample; nil follows the system setting.
    var reducedMotion: Bool?

    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    private var reduceMotion: Bool { reducedMotion ?? systemReduceMotion }

    var body: some View {
        TimelineView(.animation(paused: frozenAt != nil)) { context in
            let elapsed = frozenAt ?? max(0, context.date.timeIntervalSince(since))
            card(elapsed: elapsed)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(title(Self.secondsLeft(after: frozenAt ?? 0, of: duration))). \(detail)"))
    }

    /// The countdown's number: 3, 2, then 1.
    static func secondsLeft(after elapsed: TimeInterval, of duration: TimeInterval) -> Int {
        max(1, Int((duration - elapsed).rounded(.up)))
    }

    private func card(elapsed: TimeInterval) -> some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.xl, style: .continuous)
        let progress = min(1, elapsed / duration)
        let left = Self.secondsLeft(after: elapsed, of: duration)
        // Each second the number pops from 1.25× back to 1 over its first 0.3 s.
        let intoSecond = elapsed - elapsed.rounded(.down)
        let pop = reduceMotion ? 1 : 1 + 0.25 * max(0, 1 - intoSecond / 0.3)
        return HStack(spacing: 12) {
            ZStack {
                Circle()
                    .stroke(HexColor.red.opacity(0.18), lineWidth: 2.5)
                Circle()
                    .trim(from: progress, to: 1)
                    .stroke(HexColor.red, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(verbatim: "\(left)")
                    .hexFont(HexTextStyle.code.weight(.semibold).sized(21))
                    .foregroundStyle(HexColor.red)
                    .scaleEffect(pop)
            }
            .padding(1.25)
            .frame(width: 46, height: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title(left))
                    .hexFont(.bodyStrong)
                    .foregroundStyle(HexColor.text1)
                Text(verbatim: detail)
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text2)
            }
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(HexColor.stopBackground, in: shape)
        .overlay(alignment: .bottomLeading) {
            GeometryReader { proxy in
                Rectangle()
                    .fill(HexColor.red)
                    .frame(width: proxy.size.width * progress, height: 2)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .clipShape(shape)
        .overlay(shape.strokeBorder(HexColor.red.opacity(0.5), lineWidth: 1))
        .shadow(color: HexColor.shadow, radius: 18, y: 14)
    }
}

/// The red screen edge while the stop button is held (the prototype's `.stopedge`): a 2 pt `red`
/// 60 % line inside the screen's edge on its corner radius, with a `red` 20 % glow about 90 pt deep,
/// pulsing over 1 s. Still with Reduce Motion.
struct HexStopEdge: View {
    /// The screen's corner radius; `HexStopEdge.screenRadius` for the device's own.
    var cornerRadius: CGFloat = HexStopEdge.screenRadius

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            // The prototype's `pulse`: full, down to 35 % at the half second, back to full.
            let phase = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1)
            let opacity = reduceMotion ? 1 : 1 - 0.65 * (1 - abs(phase - 0.5) * 2)
            edge.opacity(opacity)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var edge: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return ZStack {
            // The glow: a wide soft line blurred inward, clipped to the screen.
            shape
                .stroke(HexColor.red.opacity(0.2), lineWidth: 90)
                .blur(radius: 40)
                .clipShape(shape)
            shape.strokeBorder(HexColor.red.opacity(0.6), lineWidth: 2)
        }
        .ignoresSafeArea()
    }

    /// Apps can't read the display's corner radius; phones without a home button are close to 50 pt.
    @MainActor
    static var screenRadius: CGFloat {
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        let bottom = scene?.keyWindow?.safeAreaInsets.bottom ?? 0
        return bottom > 0 ? 50 : 0
    }
}

extension HexTextStyle {
    /// The same step at another size, for the one-off sizes a component spells out (the stop
    /// countdown's 21 pt number).
    func sized(_ size: CGFloat) -> HexTextStyle {
        var style = self
        style.size = size
        return style
    }
}
