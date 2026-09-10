import Foundation

enum OpenCodeLayoutWidth: Equatable, Sendable {
    case compact
    case regular

    static func resolve(containerWidth: CGFloat) -> OpenCodeLayoutWidth {
        containerWidth >= OpenCodeLayout.compactMaxWidth ? .regular : .compact
    }
}

enum OpenCodeLayout {
    static let contentMaxWidth: CGFloat = 720
    static let sheetMaxWidth: CGFloat = 560
    static let compactMaxWidth: CGFloat = 600
    static let assistantMessageMaxWidth: CGFloat = 720
    static let actionTileGridGap: CGFloat = 10
    static let actionTileGridPaddingHorizontal: CGFloat = 16
    static let actionTileGridMinColumnWidth: CGFloat = 160
    static let actionTileGridMinColumns = 2
    static let actionTileGridMaxColumns = 4

    static func actionTileColumnCount(availableWidth: CGFloat) -> Int {
        let inner = availableWidth - actionTileGridPaddingHorizontal * 2
        let raw = Int((inner + actionTileGridGap) / (actionTileGridMinColumnWidth + actionTileGridGap))
        return min(actionTileGridMaxColumns, max(actionTileGridMinColumns, raw))
    }
}
