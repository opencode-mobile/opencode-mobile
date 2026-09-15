import SwiftUI

private let reorderableRowCornerRadius: CGFloat = 8
private let reorderableRowPressedScale: CGFloat = 0.965
private let reorderableRowLiftedScale: CGFloat = 1.03
private let reorderableRowFallbackHeight: CGFloat = 60
private let reorderableLongPressDuration: TimeInterval = 0.42
private let reorderableLongPressMaximumDistance: CGFloat = 18
private let reorderablePressAnimation = Animation.easeOut(duration: 0.12)
private let reorderableLiftAnimation = Animation.spring(response: 0.28, dampingFraction: 0.62)
private let reorderableDisplacementAnimation = Animation.spring(response: 0.32, dampingFraction: 0.78)
private let reorderableDropAnimation = Animation.spring(response: 0.36, dampingFraction: 0.8)

/// Stacked rows that reorder with long-press and drag.
///
/// Tap selects a row. Long-press lifts a movable row; dragging shifts the
/// neighbors live and release commits `onMove`. Releasing a lifted row without
/// moving it fires `onContextAction`, so a row can keep one long-press action
/// alongside reordering. Rows that fail `canMove` never lift or get crossed.
/// Reordering is inactive until at least two rows can move or `onMove` is nil.
struct OpenCodeReorderableList<Item: Identifiable, Row: View>: View {
    let items: [Item]
    let canMove: (Item) -> Bool
    let onMove: ((Int, Int) -> Void)?
    let onSelect: (Item) -> Void
    let onContextAction: ((Item) -> Void)?
    let supportsContextAction: (Item) -> Bool
    @ViewBuilder let row: (Item) -> Row

    @GestureState(wrappedValue: .inactive, resetTransaction: Transaction(animation: reorderableDropAnimation))
    private var session: ReorderSession<Item.ID>
    @State private var rowHeights: [Item.ID: CGFloat] = [:]
    @State private var liftedID: Item.ID?
    @State private var announcedTargetIndex: Int?

    init(
        items: [Item],
        canMove: @escaping (Item) -> Bool = { _ in true },
        onMove: ((Int, Int) -> Void)?,
        onSelect: @escaping (Item) -> Void,
        onContextAction: ((Item) -> Void)? = nil,
        supportsContextAction: @escaping (Item) -> Bool = { _ in true },
        @ViewBuilder row: @escaping (Item) -> Row
    ) {
        self.items = items
        self.canMove = canMove
        self.onMove = onMove
        self.onSelect = onSelect
        self.onContextAction = onContextAction
        self.supportsContextAction = supportsContextAction
        self.row = row
    }

    private var reorderEnabled: Bool {
        onMove != nil && items.filter(canMove).count >= 2
    }

    private var heights: [CGFloat] {
        items.map { rowHeights[$0.id] ?? reorderableRowFallbackHeight }
    }

    private var draggedIndex: Int? {
        guard case let .dragging(id, _) = session else { return nil }
        return items.firstIndex { $0.id == id }
    }

    private var draggedTranslation: CGFloat {
        guard case let .dragging(_, translation) = session, let draggedIndex else { return 0 }
        return OpenCodeReorderableListMath.clampedTranslation(
            translation,
            from: draggedIndex,
            rowHeights: heights,
            canMove: { canMove(items[$0]) }
        )
    }

    private var targetIndex: Int? {
        guard let draggedIndex else { return nil }
        return OpenCodeReorderableListMath.targetIndex(
            from: draggedIndex,
            translation: draggedTranslation,
            rowHeights: heights,
            canMove: { canMove(items[$0]) }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                reorderableRow(item, at: index)
            }
        }
        .coordinateSpace(name: "openCodeReorderableList")
        .onPreferenceChange(ReorderableRowHeightKey<Item.ID>.self) { heights in
            rowHeights = heights
        }
    }

    @ViewBuilder
    private func reorderableRow(_ item: Item, at index: Int) -> some View {
        let isPressing = session.isPressing(item.id)
        let isDragged = session.isDragging(item.id)
        let movable = reorderEnabled && canMove(item)
        let offset = rowOffset(index: index, item: item, isDragged: isDragged)
        let liftsOnLongPress = movable || (onContextAction != nil && supportsContextAction(item))

        row(item)
            .background {
                if isPressing || isDragged {
                    RoundedRectangle(cornerRadius: reorderableRowCornerRadius)
                        .fill(OpenCodeColors.surfaceRaisedBase.opacity(isDragged ? 1 : 0.92))
                }
            }
            .scaleEffect(rowScale(isPressing: isPressing, isDragged: isDragged))
            .shadow(
                color: .black.opacity(isDragged ? 0.18 : 0),
                radius: isDragged ? 14 : 0,
                y: isDragged ? 6 : 0
            )
            .offset(y: offset)
            .zIndex(isDragged ? 1 : 0)
            .animation(reorderablePressAnimation, value: isPressing)
            .animation(reorderableLiftAnimation, value: isDragged)
            .animation(isDragged ? nil : reorderableDisplacementAnimation, value: offset)
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(
                        key: ReorderableRowHeightKey<Item.ID>.self,
                        value: [item.id: geometry.size.height]
                    )
                }
            }
            .overlay {
                gestureLayer(item, at: index, liftsOnLongPress: liftsOnLongPress, movable: movable)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityActions {
                if let onContextAction, supportsContextAction(item) {
                    Button("Show actions") { onContextAction(item) }
                }
                if movable, let previous = neighborMovableIndex(from: index, step: -1) {
                    Button("Move up") { onMove?(index, previous) }
                }
                if movable, let next = neighborMovableIndex(from: index, step: 1) {
                    Button("Move down") { onMove?(index, next) }
                }
            }
    }

    @ViewBuilder
    private func gestureLayer(_ item: Item, at index: Int, liftsOnLongPress: Bool, movable: Bool) -> some View {
        if liftsOnLongPress {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { onSelect(item) }
                .gesture(liftGesture(item, at: index, movable: movable))
        } else {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { onSelect(item) }
        }
    }

    private func liftGesture(_ item: Item, at index: Int, movable: Bool) -> some Gesture {
        LongPressGesture(minimumDuration: reorderableLongPressDuration, maximumDistance: reorderableLongPressMaximumDistance)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named("openCodeReorderableList")))
            .updating($session) { value, state, _ in
                switch value {
                case .first(true):
                    state = .pressing(item.id)
                case let .second(true, drag):
                    state = .dragging(item.id, translation: movable ? (drag?.translation.height ?? 0) : 0)
                default:
                    state = .inactive
                }
            }
            .onChanged { value in
                guard case .second(true, _) = value else { return }
                if liftedID != item.id {
                    liftedID = item.id
                    announcedTargetIndex = index
                    OpenCodeHaptics.contextAction()
                }
                if movable, let targetIndex, targetIndex != announcedTargetIndex {
                    announcedTargetIndex = targetIndex
                    OpenCodeHaptics.selectionChanged()
                }
            }
            .onEnded { value in
                defer {
                    liftedID = nil
                    announcedTargetIndex = nil
                }
                guard case let .second(true, drag) = value else { return }
                let translation = drag?.translation.height ?? 0
                guard movable, let from = items.firstIndex(where: { $0.id == item.id }) else {
                    if let onContextAction, supportsContextAction(item) {
                        onContextAction(item)
                    }
                    return
                }
                let currentHeights = heights
                let clamped = OpenCodeReorderableListMath.clampedTranslation(
                    translation,
                    from: from,
                    rowHeights: currentHeights,
                    canMove: { canMove(items[$0]) }
                )
                let target = OpenCodeReorderableListMath.targetIndex(
                    from: from,
                    translation: clamped,
                    rowHeights: currentHeights,
                    canMove: { canMove(items[$0]) }
                )
                if target != from {
                    OpenCodeHaptics.selectionChanged()
                    withAnimation(reorderableDropAnimation) {
                        onMove?(from, target)
                    }
                } else if abs(translation) < OpenCodeReorderableListMath.contextActionTranslationLimit,
                          let onContextAction, supportsContextAction(item) {
                    onContextAction(item)
                }
            }
    }

    private func rowScale(isPressing: Bool, isDragged: Bool) -> CGFloat {
        if isDragged { return reorderableRowLiftedScale }
        if isPressing { return reorderableRowPressedScale }
        return 1
    }

    private func rowOffset(index: Int, item: Item, isDragged: Bool) -> CGFloat {
        if isDragged { return draggedTranslation }
        guard let draggedIndex, let targetIndex else { return 0 }
        return OpenCodeReorderableListMath.displacement(
            forRowAt: index,
            draggedIndex: draggedIndex,
            targetIndex: targetIndex,
            draggedHeight: heights[draggedIndex]
        )
    }

    private func neighborMovableIndex(from index: Int, step: Int) -> Int? {
        let candidate = index + step
        guard items.indices.contains(candidate), canMove(items[candidate]) else { return nil }
        return candidate
    }
}

private enum ReorderSession<ID: Hashable>: Equatable {
    case inactive
    case pressing(ID)
    case dragging(ID, translation: CGFloat)

    func isPressing(_ id: ID) -> Bool {
        if case let .pressing(pressed) = self { return pressed == id }
        return false
    }

    func isDragging(_ id: ID) -> Bool {
        if case let .dragging(dragged, _) = self { return dragged == id }
        return false
    }
}

private struct ReorderableRowHeightKey<ID: Hashable>: PreferenceKey {
    static var defaultValue: [ID: CGFloat] { [:] }

    static func reduce(value: inout [ID: CGFloat], nextValue: () -> [ID: CGFloat]) {
        value.merge(nextValue()) { _, new in new }
    }
}

#if DEBUG
private struct ReorderablePreviewItem: Identifiable {
    let id: String
    let title: String
    let pinned: Bool
}

#Preview("Reorderable List") {
    struct Host: View {
        @State private var items: [ReorderablePreviewItem] = [
            .init(id: "all", title: "All projects", pinned: true),
            .init(id: "a", title: "opencode-mobile", pinned: false),
            .init(id: "b", title: "playground", pinned: false),
            .init(id: "c", title: "website", pinned: false),
        ]

        var body: some View {
            OpenCodeReorderableList(
                items: items,
                canMove: { !$0.pinned },
                onMove: { from, to in items = OpenCodeReorderableListMath.move(items, from: from, to: to) },
                onSelect: { _ in },
                onContextAction: { _ in },
                supportsContextAction: { !$0.pinned }
            ) { item in
                OpenCodeListItem(
                    title: item.title,
                    subtitle: item.pinned ? "3 projects" : "~/Projects/\(item.title)",
                    startDecorator: item.pinned ? .iconCircle(.widgets) : .letter(item.title.first ?? "?", .violet)
                )
            }
            .padding()
            .openCodeTheme()
        }
    }
    return Host()
}
#endif
