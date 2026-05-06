import SwiftUI

enum StartDecorator {
    case none
    case letter(Character, LetterAccent)
    case icon(systemName: String, accessibilityLabel: String? = nil)
}
