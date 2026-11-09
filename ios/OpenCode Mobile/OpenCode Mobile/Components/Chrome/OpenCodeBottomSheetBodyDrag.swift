import SwiftUI
import UIKit

/// What a drag on a bottom sheet's header or body does to the card.
struct BottomSheetDragActions {
    /// Whether the card is below its high stop, so a finger moving up grows
    /// it before the body scrolls.
    let canGrow: () -> Bool
    /// The finger moved by this much (pt, down positive).
    let changed: (CGFloat) -> Void
    /// The finger lifted at this speed (pt/s, down positive).
    let ended: (CGFloat) -> Void
}

/// Hands drags on a bottom sheet body to the card, the way a native sheet
/// does: a finger moving up grows the card to its high stop before the body
/// scrolls, and a finger moving down with the body at its top shrinks the
/// card, slides it down, or dismisses it. Once the card is full the same
/// drag keeps scrolling the body, and pulling the body back to its top hands
/// the drag to the card again. When the card owned the last move, the
/// release settles it with the finger's speed and the body does not fling.
///
/// Placed behind the body content. Its pan recognizer sits on the overlay's
/// root view and only takes touches inside the body: the enclosing scroll
/// view's visible area, or this view's frame for a compact card without one.
/// It recognizes alongside the body's own scroll pan, and holds the scroll
/// view's offset while the card takes a move. Drags that start in a nested
/// vertical scroller or text view stay with it.
struct BottomSheetBodyDragHost: UIViewRepresentable {
    let drag: BottomSheetDragActions

    func makeCoordinator() -> BottomSheetBodyDragHandover {
        BottomSheetBodyDragHandover()
    }

    func makeUIView(context: Context) -> BottomSheetBodyDragAnchor {
        let view = BottomSheetBodyDragAnchor(frame: .zero)
        view.handover = context.coordinator
        return view
    }

    func updateUIView(_ uiView: BottomSheetBodyDragAnchor, context: Context) {
        context.coordinator.drag = drag
        context.coordinator.anchorMoved(uiView)
    }

    static func dismantleUIView(_ uiView: BottomSheetBodyDragAnchor, coordinator: BottomSheetBodyDragHandover) {
        coordinator.detach()
    }
}

/// Invisible view that tells the handover where the body is in UIKit.
final class BottomSheetBodyDragAnchor: UIView {
    weak var handover: BottomSheetBodyDragHandover?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        handover?.anchorMoved(self)
    }
}

final class BottomSheetBodyDragHandover: NSObject, UIGestureRecognizerDelegate {
    var drag: BottomSheetDragActions?

    private weak var anchor: UIView?
    private weak var root: UIView?
    private weak var scrollView: UIScrollView?
    private var offsetObservation: NSKeyValueObservation?
    private var pan: UIPanGestureRecognizer?
    private var lastTranslation: CGFloat = 0
    /// The card moved during this drag.
    private var cardMoved = false
    /// The card took the latest move (rather than the body).
    private var cardOwnsMove = false
    private var adjustingOffset = false
    /// The view under the finger, to cancel its row's press once the card takes the drag.
    private weak var touchedView: UIView?

    /// Finds the overlay root and the body's scroll view, and attaches to them.
    func anchorMoved(_ anchor: UIView) {
        guard anchor.window != nil else {
            detach()
            return
        }
        self.anchor = anchor
        var top: UIView = anchor
        var enclosingScrollView: UIScrollView?
        while let parent = top.superview, !(parent is UIWindow) {
            if enclosingScrollView == nil, let candidate = parent as? UIScrollView {
                enclosingScrollView = candidate
            }
            top = parent
        }
        if root !== top {
            let recognizer = pan ?? makePan()
            root?.removeGestureRecognizer(recognizer)
            top.addGestureRecognizer(recognizer)
            root = top
        }
        if scrollView !== enclosingScrollView {
            observe(enclosingScrollView)
        }
    }

    func detach() {
        if let pan {
            root?.removeGestureRecognizer(pan)
        }
        root = nil
        anchor = nil
        observe(nil)
        cardMoved = false
        cardOwnsMove = false
    }

    private func makePan() -> UIPanGestureRecognizer {
        let recognizer = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        recognizer.delegate = self
        recognizer.delaysTouchesEnded = false
        pan = recognizer
        return recognizer
    }

    private func observe(_ newScrollView: UIScrollView?) {
        offsetObservation?.invalidate()
        offsetObservation = nil
        scrollView = newScrollView
        guard let newScrollView else { return }
        offsetObservation = newScrollView.observe(\.contentOffset, options: [.old, .new]) { [weak self] scrollView, change in
            guard let old = change.oldValue, let new = change.newValue else { return }
            MainActor.assumeIsolated {
                self?.contentOffsetChanged(scrollView, from: old, to: new)
            }
        }
    }

    private var isPanning: Bool {
        guard let pan else { return false }
        return pan.state == .began || pan.state == .changed
    }

    private var bodyAtTop: Bool {
        guard let scrollView else { return true }
        return scrollView.contentOffset.y <= -scrollView.adjustedContentInset.top + 0.5
    }

    /// Holds the body still while a finger moves it: no scrolling while the
    /// card can still grow, and no stretching past the top (the card takes
    /// that pull instead).
    private func contentOffsetChanged(_ scrollView: UIScrollView, from old: CGPoint, to new: CGPoint) {
        guard !adjustingOffset, let drag, scrollView.isDragging || isPanning else { return }
        let top = -scrollView.adjustedContentInset.top
        var target = new.y
        if new.y > old.y, drag.canGrow() {
            target = old.y
        }
        target = max(target, top)
        guard target != new.y else { return }
        adjustingOffset = true
        scrollView.contentOffset = CGPoint(x: new.x, y: target)
        adjustingOffset = false
    }

    @objc private func handlePan(_ recognizer: UIPanGestureRecognizer) {
        guard let drag, let root else { return }
        switch recognizer.state {
        case .began, .changed:
            if recognizer.state == .began {
                lastTranslation = 0
                cardMoved = false
                cardOwnsMove = false
            }
            let translation = recognizer.translation(in: root).y
            let dy = translation - lastTranslation
            lastTranslation = translation
            guard dy != 0 else { return }
            let cardTakes = dy < 0 ? drag.canGrow() : bodyAtTop
            cardOwnsMove = cardTakes
            if cardTakes {
                if !cardMoved { cancelTouchedPresses() }
                cardMoved = true
                drag.changed(dy)
            }
        case .ended, .cancelled, .failed:
            defer {
                cardMoved = false
                cardOwnsMove = false
            }
            guard cardMoved else { return }
            // A release while the body was scrolling leaves the card on its nearer stop.
            let velocity = recognizer.state == .ended && cardOwnsMove ? recognizer.velocity(in: root).y : 0
            drag.ended(velocity)
            if cardOwnsMove {
                stopBodyFling()
            }
        default:
            break
        }
    }

    /// A row's tap or press under the finger would fire on release, since the
    /// row moves with the card: cancel it, as a scroll view does when it
    /// starts scrolling. Scroll views keep their own pans.
    private func cancelTouchedPresses() {
        var view = touchedView
        while let current = view {
            if !(current is UIScrollView) {
                for recognizer in current.gestureRecognizers ?? [] where recognizer !== pan && recognizer.isEnabled {
                    recognizer.isEnabled = false
                    recognizer.isEnabled = true
                }
            }
            // Up to the sheet's own root; never the window's system recognizers.
            view = current === root ? nil : current.superview
        }
    }

    /// The release belongs to the card: the body must not fling or bounce.
    private func stopBodyFling() {
        guard let scrollView else { return }
        scrollView.setContentOffset(scrollView.contentOffset, animated: false)
        // The scroll view may start decelerating after this handler runs.
        DispatchQueue.main.async { [weak scrollView] in
            guard let scrollView, scrollView.isDecelerating else { return }
            scrollView.setContentOffset(scrollView.contentOffset, animated: false)
        }
    }

    // MARK: UIGestureRecognizerDelegate

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === pan, let pan, let root else { return true }
        let velocity = pan.velocity(in: root)
        return abs(velocity.y) > abs(velocity.x)
    }

    /// Alongside the body's scroll pan and the rows' own taps and presses
    /// (SwiftUI's recognizers on the hosting views), which would otherwise
    /// keep a drag that starts on a row from moving the card.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        gestureRecognizer === pan
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let area: UIView = scrollView ?? anchor, area.window != nil else { return false }
        guard area.bounds.contains(touch.location(in: area)) else { return false }
        touchedView = touch.view
        var view = touch.view
        while let current = view, current !== area {
            if let nested = current as? UIScrollView,
               nested is UITextView || nested.contentSize.height > nested.bounds.height + 1 {
                return false
            }
            view = current.superview
        }
        return true
    }
}
