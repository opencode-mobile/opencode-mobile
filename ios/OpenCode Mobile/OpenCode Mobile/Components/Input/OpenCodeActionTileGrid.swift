import SwiftUI

struct OpenCodeActionTileGrid<Item: Hashable>: View {
    let items: [Item]
    let tile: (Item) -> OpenCodeActionTile

    @State private var availableWidth: CGFloat = 0

    var body: some View {
        let columns = OpenCodeLayout.actionTileColumnCount(availableWidth: availableWidth)
        VStack(spacing: OpenCodeLayout.actionTileGridGap) {
            ForEach(Array(stride(from: 0, to: items.count, by: columns)), id: \.self) { start in
                HStack(spacing: OpenCodeLayout.actionTileGridGap) {
                    ForEach(0 ..< columns, id: \.self) { offset in
                        let index = start + offset
                        if index < items.count {
                            tile(items[index])
                        } else {
                            Color.clear.frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, OpenCodeLayout.actionTileGridPaddingHorizontal)
        .background {
            GeometryReader { geometry in
                Color.clear.preference(key: ActionTileGridWidthKey.self, value: geometry.size.width)
            }
        }
        .onPreferenceChange(ActionTileGridWidthKey.self) { availableWidth = $0 }
    }
}

private struct ActionTileGridWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
