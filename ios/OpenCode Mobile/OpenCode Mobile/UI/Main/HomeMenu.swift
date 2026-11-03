import Foundation

/// Home's top-bar ⋯ menu. Feature entries come first, in the order given;
/// Settings always closes the menu, after a divider when anything sits above it.
enum HomeMenu {
    static let settingsID = "homeMenuSettings"

    static func entries(
        features: [OpenCodeOverflowMenuEntry],
        openSettings: @escaping () -> Void
    ) -> [OpenCodeOverflowMenuEntry] {
        features + [
            OpenCodeOverflowMenuEntry(
                id: settingsID,
                title: "common.settings",
                icon: .settings,
                dividerAbove: !features.isEmpty,
                accessibilityIdentifier: settingsID,
                action: openSettings
            ),
        ]
    }
}
