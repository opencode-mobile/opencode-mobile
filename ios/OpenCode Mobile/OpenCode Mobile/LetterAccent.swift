import SwiftUI

enum LetterAccent: CaseIterable {
    case violet
    case teal
    case amber
    case blue
    case rose
    case red
    case green
    case indigo

    var badgeFill: Color {
        switch self {
        case .violet: OpenCodeColors.listItemLetterVioletFill
        case .teal: OpenCodeColors.listItemLetterTealFill
        case .amber: OpenCodeColors.listItemLetterAmberFill
        case .blue: OpenCodeColors.listItemLetterBlueFill
        case .rose: OpenCodeColors.listItemLetterRoseFill
        case .red: OpenCodeColors.listItemLetterRedFill
        case .green: OpenCodeColors.listItemLetterGreenFill
        case .indigo: OpenCodeColors.listItemLetterIndigoFill
        }
    }

    var badgeTint: Color {
        switch self {
        case .violet: OpenCodeColors.listItemLetterVioletTint
        case .teal: OpenCodeColors.listItemLetterTealTint
        case .amber: OpenCodeColors.listItemLetterAmberTint
        case .blue: OpenCodeColors.listItemLetterBlueTint
        case .rose: OpenCodeColors.listItemLetterRoseTint
        case .red: OpenCodeColors.listItemLetterRedTint
        case .green: OpenCodeColors.listItemLetterGreenTint
        case .indigo: OpenCodeColors.listItemLetterIndigoTint
        }
    }
}
