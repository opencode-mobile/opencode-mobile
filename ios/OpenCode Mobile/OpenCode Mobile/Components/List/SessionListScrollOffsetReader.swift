import SwiftUI
import UIKit

struct SessionListScrollOffsetReader: UIViewRepresentable {
    var onChange: (_ offsetFromTop: CGFloat, _ isUserScrolling: Bool) -> Void

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.onChange = onChange
        return view
    }

    func updateUIView(_ uiView: ProbeView, context: Context) {
        uiView.onChange = onChange
    }

    final class ProbeView: UIView {
        var onChange: ((CGFloat, Bool) -> Void)?
        private var observation: NSKeyValueObservation?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            DispatchQueue.main.async { [weak self] in
                self?.attach()
            }
        }

        override func didMoveToSuperview() {
            super.didMoveToSuperview()
            DispatchQueue.main.async { [weak self] in
                self?.attach()
            }
        }

        deinit {
            observation?.invalidate()
        }

        private func attach() {
            observation?.invalidate()
            observation = nil
            guard let scrollView = enclosingScrollView() else { return }
            report(scrollView)
            observation = scrollView.observe(\.contentOffset, options: [.new]) { [weak self] scrollView, _ in
                self?.report(scrollView)
            }
        }

        private func report(_ scrollView: UIScrollView) {
            let offsetFromTop = scrollView.contentOffset.y + scrollView.adjustedContentInset.top
            let isUserScrolling = scrollView.isDragging || scrollView.isDecelerating
            onChange?(offsetFromTop, isUserScrolling)
        }

        private func enclosingScrollView() -> UIScrollView? {
            var view: UIView? = superview
            while let current = view {
                if let scrollView = current as? UIScrollView {
                    return scrollView
                }
                view = current.superview
            }
            return nil
        }
    }
}
