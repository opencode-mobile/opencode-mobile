import Foundation

public enum OpenCodeLayoutWidth: Equatable, Sendable {
    case compact
    case regular

    public static func resolve(containerWidth: CGFloat) -> OpenCodeLayoutWidth {
        containerWidth >= OpenCodeLayout.compactMaxWidth ? .regular : .compact
    }
}

public enum OpenCodeLayout {
    public static let contentMaxWidth: CGFloat = 720
    /// Shared leading and trailing edge of a full-screen layout's chrome, fields and rows.
    public static let screenGutter: CGFloat = 20
    public static let sheetMaxWidth: CGFloat = 560
    public static let compactMaxWidth: CGFloat = 600
    public static let assistantMessageMaxWidth: CGFloat = 720
    public static let actionTileGridGap: CGFloat = 10
    public static let actionTileGridPaddingHorizontal: CGFloat = 16
    public static let actionTileGridMinColumnWidth: CGFloat = 160
    public static let actionTileGridMinColumns = 2
    public static let actionTileGridMaxColumns = 4

    public static func actionTileColumnCount(availableWidth: CGFloat) -> Int {
        let inner = availableWidth - actionTileGridPaddingHorizontal * 2
        let raw = Int((inner + actionTileGridGap) / (actionTileGridMinColumnWidth + actionTileGridGap))
        return min(actionTileGridMaxColumns, max(actionTileGridMinColumns, raw))
    }
}
