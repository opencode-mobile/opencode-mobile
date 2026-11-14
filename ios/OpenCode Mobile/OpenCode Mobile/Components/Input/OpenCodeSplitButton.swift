import SwiftUI

/// A compact text button joined to a narrow ▾ half that opens a menu of related
/// actions, such as the permission prompt's Deny. The label half runs `action`; the
/// ▾ half, a long-press or right-click on the label half, and the label half's
/// accessibility action named `menuAccessibilityLabel` all run `menuAction`.
///
/// Both halves match `OpenCodeButton`'s `.compactText` (height, fill, type, press);
/// the outer corners are a compact button's and the joined corners are small.
/// For keyboard focus each half is its own control; a focus key set on the split
/// button names the label half.
struct OpenCodeSplitButton: View {
    let title: OpenCodeText
    let menuAccessibilityLabel: OpenCodeText
    let enabled: Bool
    let contrast: OpenCodeButtonContrast
    let action: () -> Void
    let menuAction: () -> Void

    /// Gap between the halves.
    private static let spacing: CGFloat = 2

    init(
        title: OpenCodeText,
        menuAccessibilityLabel: OpenCodeText,
        enabled: Bool = true,
        contrast: OpenCodeButtonContrast = .high,
        action: @escaping () -> Void,
        menuAction: @escaping () -> Void
    ) {
        self.title = title
        self.menuAccessibilityLabel = menuAccessibilityLabel
        self.enabled = enabled
        self.contrast = contrast
        self.action = action
        self.menuAction = menuAction
    }

    var body: some View {
        HStack(spacing: Self.spacing) {
            OpenCodeButton(
                variant: .compactText(title: title),
                enabled: enabled,
                contrast: contrast,
                longPress: menuAction,
                action: action
            )
            .segmented(.leading)
            .accessibilityAction(named: Text(openCode: menuAccessibilityLabel)) {
                guard enabled else { return }
                menuAction()
            }

            OpenCodeButton(
                variant: .iconSquare(.keyboardArrowDown, accessibilityLabel: menuAccessibilityLabel),
                enabled: enabled,
                contrast: contrast,
                action: menuAction
            )
            .segmented(.trailing)
            .openCodeFocusKeyCleared()
        }
        .fixedSize()
    }
}

#Preview("Split button") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeSplitButton(
            title: "Deny",
            menuAccessibilityLabel: "More ways to deny",
            contrast: .low,
            action: {},
            menuAction: {}
        )
        OpenCodeSplitButton(
            title: "Deny",
            menuAccessibilityLabel: "More ways to deny",
            enabled: false,
            contrast: .low,
            action: {},
            menuAction: {}
        )
        OpenCodeSplitButton(
            title: "Run",
            menuAccessibilityLabel: "More run options",
            action: {},
            menuAction: {}
        )
    }
    .padding()
    .openCodeTheme()
}
