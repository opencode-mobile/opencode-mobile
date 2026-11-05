import SwiftUI
import UIKit

enum OpenCodeHaptics {
    fileprivate static weak var engineView: UIView?

    private static var selection: UISelectionFeedbackGenerator?
    private static var impact: UIImpactFeedbackGenerator?
    private static weak var boundView: UIView?

    static func prepareSelection() {
        selectionGenerator().prepare()
    }

    static func selectionChanged() {
        selectionGenerator().selectionChanged()
    }

    static func prepareContextAction() {
        impactGenerator().prepare()
    }

    static func contextAction() {
        let generator = impactGenerator()
        generator.prepare()
        generator.impactOccurred(intensity: 0.9)
    }

    private static func selectionGenerator() -> UISelectionFeedbackGenerator {
        bindIfNeeded()
        if let selection {
            return selection
        }
        let generator = UISelectionFeedbackGenerator()
        selection = generator
        return generator
    }

    private static func impactGenerator() -> UIImpactFeedbackGenerator {
        bindIfNeeded()
        if let impact {
            return impact
        }
        let generator = UIImpactFeedbackGenerator(style: .soft)
        impact = generator
        return generator
    }

    private static func bindIfNeeded() {
        guard let view = hapticView() else { return }
        guard boundView !== view else { return }
        boundView = view
        selection = UISelectionFeedbackGenerator(view: view)
        impact = UIImpactFeedbackGenerator(style: .soft, view: view)
    }

    private static func hapticView() -> UIView? {
        if let engineView, engineView.window != nil {
            return engineView
        }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeWindows = scenes
            .filter { $0.activationState == .foregroundActive }
            .flatMap(\.windows)
        return activeWindows.first(where: \.isKeyWindow)
            ?? activeWindows.first
            ?? scenes.flatMap(\.windows).first
    }
}

struct OpenCodeHapticAnchor: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.backgroundColor = .clear
        OpenCodeHaptics.engineView = view
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        OpenCodeHaptics.engineView = uiView
    }
}
