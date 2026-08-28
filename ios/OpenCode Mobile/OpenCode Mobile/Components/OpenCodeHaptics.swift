import UIKit

enum OpenCodeHaptics {
    private static let selection = UISelectionFeedbackGenerator()
    private static let impact = UIImpactFeedbackGenerator(style: .soft)

    static func prepareSelection() {
        selection.prepare()
    }

    static func selectionChanged() {
        selection.selectionChanged()
    }

    static func prepareContextAction() {
        impact.prepare()
    }

    static func contextAction() {
        impact.impactOccurred(intensity: 0.9)
    }
}
