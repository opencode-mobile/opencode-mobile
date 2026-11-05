import SwiftUI

/// Corner radii.
enum HexRadius {
    /// 5: badges.
    static let xs: CGFloat = 5
    /// 8: small buttons, icon boxes.
    static let s: CGFloat = 8
    /// 10: the search field, segment tracks, chunk rows.
    static let m: CGFloat = 10
    /// 12: primary buttons, panels, fields, picker rows.
    static let l: CGFloat = 12
    /// 14: cards and toasts.
    static let xl: CGFloat = 14
    /// 20: the top of a sheet.
    static let sheet: CGFloat = 20
}

/// Screen edges.
enum HexSpacing {
    /// First run, pickers and Settings.
    static let screen: CGFloat = 18
    /// Home.
    static let screenHome: CGFloat = 22
}

/// Fixed sizes.
enum HexSize {
    /// Minimum row height.
    static let row: CGFloat = 48
    /// Project and folder rows.
    static let pickerRow: CGFloat = 62
    /// Model rows.
    static let modelRow: CGFloat = 46
    /// Minimum touch target.
    static let touch: CGFloat = 44
    /// The Hex Code mark on the welcome screen.
    static let mark: CGFloat = 52
}

/// Icon sizes.
enum HexIconSize {
    /// In small buttons.
    static let small: CGFloat = 13
    /// Rows, the talk button.
    static let regular: CGFloat = 16
    /// Icon buttons, picker rows.
    static let large: CGFloat = 19
}

/// Motion: one easing and four durations. Respecting Reduce Motion comes in M6.
enum HexMotion {
    static let press: TimeInterval = 0.12
    static let fade: TimeInterval = 0.2
    static let sheet: TimeInterval = 0.28
    static let stopHold: TimeInterval = 3

    /// cubic-bezier(0.25, 0.46, 0.45, 0.94).
    static func ease(_ duration: TimeInterval) -> Animation {
        .timingCurve(0.25, 0.46, 0.45, 0.94, duration: duration)
    }
}
