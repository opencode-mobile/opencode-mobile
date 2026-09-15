import CoreGraphics

/// Pure geometry for long-press drag reordering of stacked rows.
///
/// Shared meaning with Android `ReorderableListMath`: the dragged row settles
/// at the first neighbor whose midpoint it has crossed, never crosses a row
/// that cannot move, and displaced rows shift by the dragged row's height.
enum OpenCodeReorderableListMath {
    /// Vertical translation below which a lift-and-release counts as a
    /// context action instead of a move.
    static let contextActionTranslationLimit: CGFloat = 6

    static func targetIndex(
        from index: Int,
        translation: CGFloat,
        rowHeights: [CGFloat],
        canMove: (Int) -> Bool
    ) -> Int {
        guard rowHeights.indices.contains(index) else { return index }
        var target = index
        var passed: CGFloat = 0
        if translation > 0 {
            var candidate = index + 1
            while candidate < rowHeights.count, canMove(candidate) {
                let height = rowHeights[candidate]
                guard translation >= passed + height / 2 else { break }
                target = candidate
                passed += height
                candidate += 1
            }
        } else if translation < 0 {
            var candidate = index - 1
            while candidate >= 0, canMove(candidate) {
                let height = rowHeights[candidate]
                guard -translation >= passed + height / 2 else { break }
                target = candidate
                passed += height
                candidate -= 1
            }
        }
        return target
    }

    static func displacement(
        forRowAt index: Int,
        draggedIndex: Int,
        targetIndex: Int,
        draggedHeight: CGFloat
    ) -> CGFloat {
        guard index != draggedIndex else { return 0 }
        if draggedIndex < index, index <= targetIndex {
            return -draggedHeight
        }
        if targetIndex <= index, index < draggedIndex {
            return draggedHeight
        }
        return 0
    }

    /// Keeps the lifted row inside the span of rows it is allowed to cross.
    static func clampedTranslation(
        _ translation: CGFloat,
        from index: Int,
        rowHeights: [CGFloat],
        canMove: (Int) -> Bool
    ) -> CGFloat {
        guard rowHeights.indices.contains(index) else { return translation }
        var upward: CGFloat = 0
        var candidate = index - 1
        while candidate >= 0, canMove(candidate) {
            upward += rowHeights[candidate]
            candidate -= 1
        }
        var downward: CGFloat = 0
        candidate = index + 1
        while candidate < rowHeights.count, canMove(candidate) {
            downward += rowHeights[candidate]
            candidate += 1
        }
        return min(max(translation, -upward), downward)
    }

    static func move<Element>(_ items: [Element], from: Int, to: Int) -> [Element] {
        guard items.indices.contains(from), items.indices.contains(to), from != to else { return items }
        var result = items
        let element = result.remove(at: from)
        result.insert(element, at: to)
        return result
    }
}
