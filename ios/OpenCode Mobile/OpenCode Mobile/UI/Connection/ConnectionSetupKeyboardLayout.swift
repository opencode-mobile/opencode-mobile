import Foundation

/// Where Add/Edit server puts Connect while the software keyboard is up.
///
/// iOS 26 draws `ToolbarItem(placement: .keyboard)` as a floating Done pill that
/// covers the primary action. This screen does not add that toolbar. Dismiss by
/// tapping empty space or scrolling. Connect stays in the scroll content while
/// typing so it sits with the focused field, above the keys, instead of pinned
/// in the keyboard overlap.
enum ConnectionSetupKeyboardLayout {
    static let urlFieldID = "url"
    static let nameFieldID = "name"
    static let usernameFieldID = "username"
    static let passwordFieldID = "password"
    static let pairingFieldID = "pairing"
    static let actionsScrollID = "connection-setup-actions"

    static func pinsPrimaryActions(keyboardVisible: Bool) -> Bool {
        !keyboardVisible
    }

    /// Re-scroll whenever the keyboard appears or changes height while up. Moving
    /// into the password field swaps to a keyboard of another height (no predictive
    /// bar, a Passwords bar instead) after focus has already scrolled, which left
    /// Connect behind the keys.
    static func needsRescroll(oldOverlap: Double, newOverlap: Double) -> Bool {
        newOverlap > 0 && abs(newOverlap - oldOverlap) > 0.5
    }

    struct ScrollTarget: Equatable {
        let id: String
        /// `UnitPoint` y. `1` keeps the target at the bottom of the visible scroll area.
        let anchorY: Double
    }

    /// Lower fields sit against the keyboard. Scroll the in-flow actions to the
    /// bottom so the focused field and Connect both stay above the keys.
    static func scrollTarget(fieldID: String, keyboardVisible: Bool) -> ScrollTarget {
        let lowerField = fieldID == usernameFieldID
            || fieldID == passwordFieldID
            || fieldID == pairingFieldID
        if keyboardVisible, lowerField {
            return ScrollTarget(id: actionsScrollID, anchorY: 1)
        }
        return ScrollTarget(id: fieldID, anchorY: keyboardVisible ? 0.35 : 0.15)
    }
}
