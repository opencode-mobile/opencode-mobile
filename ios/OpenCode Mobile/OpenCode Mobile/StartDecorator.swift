import SwiftUI

enum StartDecorator {
    case none
    case letter(Character, LetterAccent)
    case icon(OpenCodeIcon, accessibilityLabel: String? = nil)
}
