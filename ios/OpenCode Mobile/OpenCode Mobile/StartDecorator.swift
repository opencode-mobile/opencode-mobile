import SwiftUI

enum StartDecorator {
    case none
    case letter(Character, LetterAccent, statusDot: OpenCodeListItemStatusTone? = nil)
    case icon(OpenCodeIcon, accessibilityLabel: String? = nil)
    case iconCircle(OpenCodeIcon, accessibilityLabel: String? = nil)
}
