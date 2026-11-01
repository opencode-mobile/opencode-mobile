import SwiftUI

struct OpenCodeListGroup<Data: RandomAccessCollection, ID: Hashable, RowContent: View>: View {
    private let items: [Data.Element]
    private let itemID: KeyPath<Data.Element, ID>
    private let rowContent: (Data.Element) -> RowContent

    init(
        _ data: Data,
        id: KeyPath<Data.Element, ID>,
        @ViewBuilder rowContent: @escaping (Data.Element) -> RowContent
    ) {
        items = Array(data)
        itemID = id
        self.rowContent = rowContent
    }

    var body: some View {
        if !items.isEmpty {
            VStack(spacing: 0) {
                ForEach(items, id: itemID) { item in
                    rowContent(item)
                    if items.last?[keyPath: itemID] != item[keyPath: itemID] {
                        Divider().overlay(OpenCodeColors.borderWeakerBase)
                    }
                }
            }
            .background(OpenCodeColors.surfaceBase)
            .clipShape(RoundedRectangle(cornerRadius: Self.cornerRadius))
            .overlay {
                RoundedRectangle(cornerRadius: Self.cornerRadius)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            }
        }
    }

    private static var cornerRadius: CGFloat { 12 }
}

#Preview("List Group") {
    OpenCodeListGroup(["Sources/App.swift", "README.md", "Tests/AppTests.swift"], id: \.self) { path in
        OpenCodeListItem(title: .verbatim(path), accessory: .disclosure)
    }
    .padding()
    .openCodeTheme()
}
