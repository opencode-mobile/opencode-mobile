import SwiftUI
import UIKit

/// One entry of a secondary-click menu.
/// What a secondary click on a design-system control does. It never presses the
/// control: right-clicking Allow Once or Deny must not answer a prompt.
nonisolated enum OpenCodeSecondaryClickOutcome: Equatable, Sendable {
    /// Run the control's long-press action.
    case runAction
    /// Show the control's menu at the pointer.
    case showMenu
    /// Nothing: the click is taken so the control underneath is not pressed.
    case ignore

    static func outcome(hasAction: Bool, hasMenu: Bool) -> Self {
        if hasMenu { return .showMenu }
        return hasAction ? .runAction : .ignore
    }
}

struct OpenCodeSecondaryClickMenuItem {
    let title: String
    var icon: OpenCodeIcon?
    var destructive = false
    let action: () -> Void
}

extension View {
    /// A secondary click (right-click, two-finger click) runs `action`: the same as
    /// the component's long-press. Touches and primary clicks pass straight through,
    /// so taps, long-presses, and swipes are unchanged.
    func openCodeSecondaryClick(_ action: (() -> Void)?) -> some View {
        overlay {
            if let action {
                OpenCodeSecondaryClickArea(content: .action(action))
            }
        }
    }

    /// Takes secondary clicks so they do not press this control. Applied by
    /// `openCodeKeyboardFocusable` to every design-system control; an
    /// `openCodeSecondaryClick` outside it handles the click instead.
    func openCodeIgnoresSecondaryClick() -> some View {
        overlay { OpenCodeSecondaryClickArea(content: .ignore) }
    }

    /// A secondary click shows `items` as a menu at the pointer, for actions that
    /// touch reaches another way (a swipe, an edit mode).
    func openCodeSecondaryClickMenu(_ items: [OpenCodeSecondaryClickMenuItem]) -> some View {
        overlay {
            if !items.isEmpty {
                OpenCodeSecondaryClickArea(content: .menu(items))
            }
        }
    }
}

private struct OpenCodeSecondaryClickArea: UIViewRepresentable {
    enum Content {
        case action(() -> Void)
        case menu([OpenCodeSecondaryClickMenuItem])
        case ignore

        var outcome: OpenCodeSecondaryClickOutcome {
            switch self {
            case .action: .outcome(hasAction: true, hasMenu: false)
            case let .menu(items): .outcome(hasAction: false, hasMenu: !items.isEmpty)
            case .ignore: .outcome(hasAction: false, hasMenu: false)
            }
        }
    }

    let content: Content

    func makeUIView(context: Context) -> AreaView {
        AreaView()
    }

    func updateUIView(_ view: AreaView, context: Context) {
        view.content = content
    }

    final class AreaView: UIView, UIContextMenuInteractionDelegate, UIGestureRecognizerDelegate {
        var content: Content = .menu([]) {
            didSet { configure() }
        }

        private lazy var click: UITapGestureRecognizer = {
            let recognizer = UITapGestureRecognizer(target: self, action: #selector(clicked))
            recognizer.buttonMaskRequired = .secondary
            recognizer.delegate = self
            return recognizer
        }()

        private lazy var menuInteraction = UIContextMenuInteraction(delegate: self)

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .clear
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not supported")
        }

        /// Only a pointer's secondary button lands here; everything else (touches,
        /// primary clicks, hover) goes to the SwiftUI view underneath.
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
            guard let event, event.buttonMask.contains(.secondary) else { return nil }
            return super.hitTest(point, with: event)
        }

        private func configure() {
            let hasMenu = interactions.contains { $0 === menuInteraction }
            let hasClick = gestureRecognizers?.contains(click) == true
            switch content.outcome {
            case .runAction, .ignore:
                if hasMenu { removeInteraction(menuInteraction) }
                if !hasClick { addGestureRecognizer(click) }
            case .showMenu:
                if hasClick { removeGestureRecognizer(click) }
                if !hasMenu { addInteraction(menuInteraction) }
            }
        }

        @objc private func clicked() {
            if case let .action(action) = content { action() }
        }

        /// The control's own tap (SwiftUI's recognizer on the hosting view) waits for
        /// this secondary click to fail, so a right-click never also presses it.
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }

        func contextMenuInteraction(
            _ interaction: UIContextMenuInteraction,
            configurationForMenuAtLocation location: CGPoint
        ) -> UIContextMenuConfiguration? {
            guard case let .menu(items) = content, !items.isEmpty else { return nil }
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
                UIMenu(children: items.map { item in
                    UIAction(
                        title: item.title,
                        image: item.icon.flatMap { UIImage(named: $0.rawValue) },
                        attributes: item.destructive ? .destructive : []
                    ) { _ in item.action() }
                })
            }
        }
    }
}
