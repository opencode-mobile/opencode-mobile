import SwiftUI

enum StartDecorator {
    case none
    case letter(Character, LetterAccent, statusDot: OpenCodeListItemStatusTone? = nil, worktree: Bool = false)
    /// Smaller round letter badge for compact rows. The status dot sits on its top-right edge;
    /// `pulsing` fades it slowly while work is in progress.
    case compactLetter(Character, LetterAccent, statusDot: OpenCodeListItemStatusTone? = nil, pulsing: Bool = false)
    case icon(OpenCodeIcon, accessibilityLabel: String? = nil)
    case iconCircle(OpenCodeIcon, accessibilityLabel: String? = nil)
}
