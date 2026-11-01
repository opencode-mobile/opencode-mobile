import CoreGraphics

/// Shared corner radii for design-system components (Android `OpenCodeRadius`).
enum OpenCodeRadius {
    static let small: CGFloat = 8
    static let medium: CGFloat = 12
    /// A floating pill's corner (half its 36 pt height), for cards anchored to a pill.
    static let large: CGFloat = 18
}
