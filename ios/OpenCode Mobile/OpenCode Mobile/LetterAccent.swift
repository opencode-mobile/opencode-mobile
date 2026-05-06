import SwiftUI

enum LetterAccent: CaseIterable {
    case violet
    case teal
    case amber
    case blue
    case rose

    var badgeFill: Color {
        switch self {
        case .violet: OpenCodeColors.listItemLetterVioletFill
        case .teal: OpenCodeColors.listItemLetterTealFill
        case .amber: OpenCodeColors.listItemLetterAmberFill
        case .blue: OpenCodeColors.listItemLetterBlueFill
        case .rose: OpenCodeColors.listItemLetterRoseFill
        }
    }

    var badgeTint: Color {
        switch self {
        case .violet: OpenCodeColors.listItemLetterVioletTint
        case .teal: OpenCodeColors.listItemLetterTealTint
        case .amber: OpenCodeColors.listItemLetterAmberTint
        case .blue: OpenCodeColors.listItemLetterBlueTint
        case .rose: OpenCodeColors.listItemLetterRoseTint
        }
    }
}
