import SwiftUI
import UIKit

/// A message's send time as the drag reveal shows it: the clock time, and the
/// day under it when the message is not from today.
struct OpenCodeMessageTime: Equatable {
    let time: String
    let day: String?

    static let yesterday = "Yesterday"

    /// Time in the locale's 12/24-hour style. Day: nil today, "Yesterday", the
    /// weekday within the last week, otherwise a short date (with the year when
    /// it is not this year's).
    init(sentAt: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) {
        time = Self.format(sentAt, template: "jmm", calendar: calendar, locale: locale)
        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: sentAt),
            to: calendar.startOfDay(for: now)
        ).day ?? 0
        switch days {
        case ...0:
            day = nil
        case 1:
            day = Self.yesterday
        case 2 ... 6:
            day = Self.format(sentAt, template: "EEEE", calendar: calendar, locale: locale)
        default:
            let sameYear = calendar.component(.year, from: sentAt) == calendar.component(.year, from: now)
            day = Self.format(sentAt, template: sameYear ? "MMMd" : "yMMMd", calendar: calendar, locale: locale)
        }
    }

    init(sentAtEpochMs: Int64, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) {
        self.init(
            sentAt: Date(timeIntervalSince1970: TimeInterval(sentAtEpochMs) / 1_000),
            now: now,
            calendar: calendar,
            locale: locale
        )
    }

    /// What VoiceOver reads for the time: the gesture is invisible to it.
    var spokenLabel: String {
        [day, time].compactMap { $0 }.joined(separator: ", ")
    }

    private static func format(_ date: Date, template: String, calendar: Calendar, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter.string(from: date)
    }
}

/// Drag-to-reveal geometry, shared by the gesture and its tests.
enum OpenCodeTimeRevealDrag {
    /// Furthest the content moves, in points.
    static let cap: CGFloat = 72
    /// The content follows the finger at this rate up to `linearLimit`.
    static let follow: CGFloat = 0.9
    static let linearLimit: CGFloat = 52
    /// Rubber-band falloff past `linearLimit`, in points.
    static let falloff: CGFloat = 30
    /// Travel before the drag direction is decided.
    static let directionSlop: CGFloat = 8
    /// Drags that start this close to a screen edge belong to the system.
    static let systemEdgeInset: CGFloat = 20
    /// The time fades in over this offset range.
    static let fadeStart: CGFloat = 12
    static let fadeLength: CGFloat = 40

    enum Direction: Equatable {
        case reveal
        case other
    }

    /// How far the content moves left for a horizontal finger translation
    /// (negative is left). Dragging right stays at 0.
    static func offset(forTranslation translation: CGFloat) -> CGFloat {
        let travel = max(0, -translation) * follow
        guard travel > linearLimit else { return travel }
        return linearLimit + (cap - linearLimit) * (1 - exp(-(travel - linearLimit) / falloff))
    }

    static func opacity(forOffset offset: CGFloat) -> Double {
        Double(min(1, max(0, (offset - fadeStart) / fadeLength)))
    }

    /// Nil until the finger has moved `directionSlop`; then `.reveal` for a
    /// mostly horizontal drag to the left, `.other` for anything else.
    static func direction(for translation: CGSize) -> Direction? {
        guard hypot(translation.width, translation.height) > directionSlop else { return nil }
        let horizontal = abs(translation.width) > abs(translation.height) * 1.2
        return horizontal && translation.width < 0 ? .reveal : .other
    }

    static func startsInSystemEdge(x: CGFloat, width: CGFloat) -> Bool {
        x < systemEdgeInset || x > width - systemEdgeInset
    }
}

extension View {
    /// Drag the content left to reveal `time` at its trailing edge; it springs
    /// back on release. Vertical drags, the edge back-swipe, and drags starting
    /// on a horizontally scrolling child go elsewhere.
    func openCodeTimeReveal(_ time: OpenCodeMessageTime?) -> some View {
        modifier(OpenCodeTimeRevealModifier(time: time))
    }
}

private struct OpenCodeTimeRevealModifier: ViewModifier {
    let time: OpenCodeMessageTime?

    @State private var offset: CGFloat = 0
    /// Fallback gesture only: the lock decision and where it happened.
    @State private var fallbackLock: (direction: OpenCodeTimeRevealDrag.Direction, startX: CGFloat)?

    func body(content: Content) -> some View {
        if let time {
            revealing(content, time: time)
        } else {
            content
        }
    }

    @ViewBuilder
    private func revealing(_ content: Content, time: OpenCodeMessageTime) -> some View {
        let moved = content
            .offset(x: -offset)
            .background(alignment: .trailing) {
                if offset > 0 {
                    OpenCodeMessageTimeLabel(time: time)
                        .opacity(OpenCodeTimeRevealDrag.opacity(forOffset: offset))
                }
            }
        if #available(iOS 18.0, *) {
            moved.gesture(
                OpenCodeTimeRevealPanGesture(
                    onChange: { offset = OpenCodeTimeRevealDrag.offset(forTranslation: $0) },
                    onEnd: springBack
                )
            )
        } else {
            moved.simultaneousGesture(fallbackDrag)
        }
    }

    private func springBack() {
        withAnimation(.spring(response: 0.42, dampingFraction: 0.72)) { offset = 0 }
    }

    /// iOS 17: SwiftUI drag alongside the list scroll, with the same direction lock.
    private var fallbackDrag: some Gesture {
        DragGesture(minimumDistance: OpenCodeTimeRevealDrag.directionSlop, coordinateSpace: .global)
            .onChanged { value in
                if fallbackLock == nil,
                   let direction = OpenCodeTimeRevealDrag.direction(for: value.translation)
                {
                    let width = UIScreen.main.bounds.width
                    let fromEdge = OpenCodeTimeRevealDrag.startsInSystemEdge(x: value.startLocation.x, width: width)
                    fallbackLock = (fromEdge ? .other : direction, value.translation.width)
                }
                guard let lock = fallbackLock, lock.direction == .reveal else { return }
                offset = OpenCodeTimeRevealDrag.offset(forTranslation: value.translation.width - lock.startX)
            }
            .onEnded { _ in
                fallbackLock = nil
                springBack()
            }
    }
}

/// The revealed time: clock time, and the day under it for older messages.
private struct OpenCodeMessageTimeLabel: View {
    let time: OpenCodeMessageTime

    var body: some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(time.time)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .monospacedDigit()
            if let day = time.day {
                Text(day)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
        }
        .lineLimit(1)
        .fixedSize()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A UIKit pan that only begins for a leftward drag, so vertical drags stay with
/// the list scroll and the navigation edge swipe keeps its touches. Once it
/// begins it cancels the list's pan, like a table row swipe.
@available(iOS 18.0, *)
private struct OpenCodeTimeRevealPanGesture: UIGestureRecognizerRepresentable {
    let onChange: (CGFloat) -> Void
    let onEnd: () -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator()
    }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let recognizer = UIPanGestureRecognizer()
        recognizer.maximumNumberOfTouches = 1
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        switch recognizer.state {
        case .began:
            // Measure from where the direction locked, so the content does not jump.
            context.coordinator.lockX = recognizer.translation(in: recognizer.view).x
            context.coordinator.cancelEnclosingScroll(of: recognizer.view)
        case .changed:
            onChange(recognizer.translation(in: recognizer.view).x - context.coordinator.lockX)
        case .ended, .cancelled, .failed:
            onEnd()
        default:
            break
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var lockX: CGFloat = 0

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer, let view = pan.view else { return false }
            if let window = view.window {
                let start = pan.location(in: window).x - pan.translation(in: window).x
                if OpenCodeTimeRevealDrag.startsInSystemEdge(x: start, width: window.bounds.width) { return false }
            }
            if startsOnHorizontalScroller(pan, in: view) { return false }
            var translation = pan.translation(in: view)
            if hypot(translation.x, translation.y) <= OpenCodeTimeRevealDrag.directionSlop {
                // UIKit can ask before the slop; the velocity still says where the finger is going.
                translation = pan.velocity(in: view)
                return abs(translation.x) > abs(translation.y) * 1.2 && translation.x < 0
            }
            return OpenCodeTimeRevealDrag.direction(for: CGSize(width: translation.x, height: translation.y)) == .reveal
        }

        /// Track alongside the list scroll until the direction is known.
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            guard let scrollView = other.view as? UIScrollView, other === scrollView.panGestureRecognizer else {
                return false
            }
            return true
        }

        /// A reveal owns the drag: stop the list from scrolling under it.
        func cancelEnclosingScroll(of view: UIView?) {
            var current = view?.superview
            while let candidate = current {
                if let scrollView = candidate as? UIScrollView, scrollView.alwaysBounceVertical || scrollView.contentSize.height > scrollView.bounds.height {
                    scrollView.panGestureRecognizer.isEnabled = false
                    scrollView.panGestureRecognizer.isEnabled = true
                    return
                }
                current = candidate.superview
            }
        }

        /// A code block that scrolls sideways keeps its own drag.
        private func startsOnHorizontalScroller(_ pan: UIPanGestureRecognizer, in view: UIView) -> Bool {
            let start = pan.location(in: view) - pan.translation(in: view)
            var hit = view.hitTest(start, with: nil)
            while let current = hit, current !== view {
                if let scrollView = current as? UIScrollView,
                   scrollView.contentSize.width > scrollView.bounds.width + 1
                {
                    return true
                }
                hit = current.superview
            }
            return false
        }
    }
}

private func - (lhs: CGPoint, rhs: CGPoint) -> CGPoint {
    CGPoint(x: lhs.x - rhs.x, y: lhs.y - rhs.y)
}
