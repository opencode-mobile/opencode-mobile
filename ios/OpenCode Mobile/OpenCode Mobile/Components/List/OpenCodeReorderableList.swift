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
private let reorderableRevealAnimation = Animation.spring(response: 0.3, dampingFraction: 0.85)
private let reorderableHandleWidth: CGFloat = 28
private let reorderableHandleLeadingInset: CGFloat = 8
private let reorderableHandleIconSize: CGFloat = 18
/// Horizontal travel that commits a press to the swipe axis.
private let reorderableSwipeActivation: CGFloat = 14

/// A row mid-swipe, with `reveal` already clamped to the strip width.
private struct RowSwipe<ID: Hashable>: Equatable {
    let id: ID
    let reveal: CGFloat
}

/// Stacked rows that reorder with long-press and drag, and expose one secondary
/// row action behind a horizontal swipe.
///
/// The two gestures own separate axes, so neither can take the other's input.
/// Tap selects a row. Long-press lifts a movable row; dragging vertically
/// shifts the neighbors live and release commits `onMove`. Swiping a row left
/// reveals `contextActionLabel`; tapping that fires `onContextAction`. A
/// long-press never fires the context action, and a swipe never reorders.
///
/// Only one row stays open at a time, and a tap anywhere closes it. Rows that
/// fail `canMove` never lift or get crossed; rows that fail
/// `supportsContextAction` never reveal. Reordering is inactive until at least
/// two rows can move or `onMove` is nil.
///
/// `.handle` activation draws a drag handle on each movable row that drags at
/// once; long-press still lifts the rest of the row. `trailing` is an optional
/// trailing part of each row that keeps its own controls tappable (a tap on the
/// rest of it selects the row as usual).
struct OpenCodeReorderableList<Item: Identifiable, Row: View, Trailing: View>: View {
    let items: [Item]
    let canMove: (Item) -> Bool
    let onMove: ((Int, Int) -> Void)?
    let activation: OpenCodeReorderableListActivation
    let onSelect: (Item) -> Void
    let onContextAction: ((Item) -> Void)?
    let supportsContextAction: (Item) -> Bool
    let contextActionLabel: String
    @ViewBuilder let row: (Item) -> Row
    @ViewBuilder let trailing: (Item) -> Trailing

    @GestureState(wrappedValue: .inactive, resetTransaction: Transaction(animation: reorderableDropAnimation))
    private var session: ReorderSession<Item.ID>
    /// Live horizontal swipe. GestureState so a cancelled swipe snaps back on
    /// its own rather than leaving a row stranded half-open.
    @GestureState(wrappedValue: nil, resetTransaction: Transaction(animation: reorderableRevealAnimation))
    private var swipe: RowSwipe<Item.ID>?
    @State private var rowHeights: [Item.ID: CGFloat] = [:]
    @State private var trailingWidths: [Item.ID: CGFloat] = [:]
    @State private var lift = OpenCodeReorderableListLift<Item.ID>()
    @State private var openID: Item.ID?

    init(
        items: [Item],
        canMove: @escaping (Item) -> Bool = { _ in true },
        onMove: ((Int, Int) -> Void)?,
        activation: OpenCodeReorderableListActivation = .longPress,
        onSelect: @escaping (Item) -> Void,
        onContextAction: ((Item) -> Void)? = nil,
        supportsContextAction: @escaping (Item) -> Bool = { _ in true },
        contextActionLabel: String = "Remove",
        @ViewBuilder row: @escaping (Item) -> Row,
        @ViewBuilder trailing: @escaping (Item) -> Trailing
    ) {
        self.items = items
        self.canMove = canMove
        self.onMove = onMove
        self.activation = activation
        self.onSelect = onSelect
        self.onContextAction = onContextAction
        self.supportsContextAction = supportsContextAction
        self.contextActionLabel = contextActionLabel
        self.row = row
        self.trailing = trailing
    }

    /// How far the row is currently slid open, 0 when shut.
    private func revealWidth(for id: Item.ID) -> CGFloat {
        if let swipe, swipe.id == id {
            return swipe.reveal
        }
        return openID == id ? OpenCodeReorderableListMath.swipeRevealWidth : 0
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
        .onPreferenceChange(ReorderableTrailingWidthKey<Item.ID>.self) { widths in
            trailingWidths = widths
        }
        .onChange(of: session) { _, newValue in
            // Cancelled drags skip onEnded and leave liftedID set. Forget the
            // row so the next same-row lift can reset travel; keep the travel
            // flag itself for an onEnded that races this reset.
            if case .inactive = newValue {
                lift.releaseIdentity()
            }
        }
    }

    @ViewBuilder
    private func reorderableRow(_ item: Item, at index: Int) -> some View {
        let isPressing = session.isPressing(item.id)
        let isDragged = session.isDragging(item.id)
        let movable = reorderEnabled && canMove(item)
        let offset = rowOffset(index: index, item: item, isDragged: isDragged)
        let canReveal = onContextAction != nil && supportsContextAction(item)
        let reveal = canReveal ? revealWidth(for: item.id) : 0

        HStack(spacing: 0) {
            if showsHandle(movable: movable) {
                OpenCodeIconImage(icon: .dragHandle, size: reorderableHandleIconSize)
                    .foregroundStyle(OpenCodeColors.iconBase)
                    .frame(width: reorderableHandleWidth)
                    .padding(.leading, reorderableHandleLeadingInset)
                    .accessibilityHidden(true)
            }
            // The row reads as one button; the trailing part keeps its own controls.
            row(item)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityActions {
                    if let onContextAction, supportsContextAction(item) {
                        // With no menu behind it any more, the action names itself
                        // rather than promising a list of choices.
                        Button(contextActionLabel) { onContextAction(item) }
                    }
                    if movable, let previous = neighborMovableIndex(from: index, step: -1) {
                        Button("Move up") { onMove?(index, previous) }
                    }
                    if movable, let next = neighborMovableIndex(from: index, step: 1) {
                        Button("Move down") { onMove?(index, next) }
                    }
                }
            if Trailing.self != EmptyView.self {
                trailing(item)
                    .contentShape(Rectangle())
                    .onTapGesture { tapRow(item) }
                    .background {
                        GeometryReader { geometry in
                            Color.clear.preference(
                                key: ReorderableTrailingWidthKey<Item.ID>.self,
                                value: [item.id: geometry.size.width]
                            )
                        }
                    }
            }
        }
        .background {
            if isPressing || isDragged {
                RoundedRectangle(cornerRadius: reorderableRowCornerRadius)
                    .fill(OpenCodeColors.surfaceRaisedBase.opacity(isDragged ? 1 : 0.92))
            }
        }
        .offset(x: -reveal)
        .animation(reorderableRevealAnimation, value: reveal)
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
            gestureLayer(item, at: index, movable: movable, canReveal: canReveal)
        }
        .overlay(alignment: .trailing) {
            if reveal > 0 {
                // Above the gesture layer. That layer covers the row's layout
                // frame — the row is only visually offset — so a strip drawn
                // behind it never receives the tap and Remove does nothing.
                contextActionStrip(item)
                    .frame(width: OpenCodeReorderableListMath.swipeRevealWidth)
                    .frame(width: reveal, alignment: .trailing)
                    .clipped()
            }
        }
    }

    @ViewBuilder
    private func contextActionStrip(_ item: Item) -> some View {
        Button {
            openID = nil
            onContextAction?(item)
        } label: {
            Text(contextActionLabel)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.surfaceBase)
                .lineLimit(1)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: reorderableRowCornerRadius)
                        .fill(OpenCodeColors.error)
                )
        }
        .buttonStyle(.plain)
        // Only hittable once settled open, so a half-revealed strip cannot be
        // tapped mid-swipe.
        .allowsHitTesting(openID == item.id)
    }

    @ViewBuilder
    private func gestureLayer(_ item: Item, at index: Int, movable: Bool, canReveal: Bool) -> some View {
        // Leave the revealed strip out of this layer once the row is open.
        // Otherwise a tap on Remove is also a tap on the row, which dismisses
        // the swipe and can select the project instead of removing it.
        HStack(spacing: 0) {
            if showsHandle(movable: movable) {
                Color.clear
                    .frame(width: reorderableHandleWidth + reorderableHandleLeadingInset)
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(handleGesture(item), including: openID == nil ? .all : .none)
            }
            Color.clear
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { tapRow(item) }
                .gesture(
                    swipeGesture(item),
                    including: canReveal && !session.isDragging(item.id) ? .all : .none
                )
                .gesture(
                    liftGesture(item, at: index, movable: movable),
                    including: movable && openID == nil ? .all : .none
                )
            if openID == item.id {
                Color.clear
                    .frame(width: OpenCodeReorderableListMath.swipeRevealWidth)
                    .allowsHitTesting(false)
            } else if let width = trailingWidths[item.id], width > 0, Trailing.self != EmptyView.self {
                // The trailing part takes its own taps (its controls, else select).
                Color.clear
                    .frame(width: width)
                    .allowsHitTesting(false)
            }
        }
    }

    /// A tap anywhere dismisses the open row instead of selecting, so the
    /// revealed action is never one stray tap from a select.
    private func tapRow(_ item: Item) {
        if openID != nil {
            withAnimation(reorderableRevealAnimation) { openID = nil }
        } else {
            onSelect(item)
        }
    }

    private func showsHandle(movable: Bool) -> Bool {
        activation == .handle && movable
    }

    /// The handle drags at once, without the long-press.
    private func handleGesture(_ item: Item) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named("openCodeReorderableList"))
            .updating($session) { value, state, _ in
                state = .dragging(item.id, translation: value.translation.height)
            }
            .onChanged { _ in
                guard let index = items.firstIndex(where: { $0.id == item.id }) else { return }
                if lift.beginLift(id: item.id, index: index) {
                    OpenCodeHaptics.contextAction()
                }
                if let targetIndex, lift.noteTarget(targetIndex) {
                    OpenCodeHaptics.selectionChanged()
                }
            }
            .onEnded { value in
                lift.finish()
                commitDrop(item, translation: value.translation.height)
            }
    }

    private func commitDrop(_ item: Item, translation: CGFloat) {
        guard let from = items.firstIndex(where: { $0.id == item.id }) else { return }
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
        }
    }

    private func swipeGesture(_ item: Item) -> some Gesture {
        DragGesture(minimumDistance: reorderableSwipeActivation, coordinateSpace: .local)
            .updating($swipe) { value, state, _ in
                // Horizontal dominance keeps a vertical drag (a scroll, or the
                // start of a lift) from ever opening a row.
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                let base = openID == item.id ? OpenCodeReorderableListMath.swipeRevealWidth : 0
                state = RowSwipe(
                    id: item.id,
                    reveal: min(
                        max(base - value.translation.width, 0),
                        OpenCodeReorderableListMath.swipeRevealWidth
                    )
                )
            }
            .onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                let base = openID == item.id ? OpenCodeReorderableListMath.swipeRevealWidth : 0
                let reveal = min(
                    max(base - value.translation.width, 0),
                    OpenCodeReorderableListMath.swipeRevealWidth
                )
                let shouldOpen = reveal >= OpenCodeReorderableListMath.swipeOpenThreshold
                if shouldOpen, openID != item.id {
                    OpenCodeHaptics.selectionChanged()
                }
                withAnimation(reorderableRevealAnimation) {
                    openID = shouldOpen ? item.id : nil
                }
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
                guard case let .second(true, drag) = value else { return }
                if lift.beginLift(id: item.id, index: index) {
                    OpenCodeHaptics.contextAction()
                }
                if movable, let targetIndex, lift.noteTarget(targetIndex) {
                    OpenCodeHaptics.selectionChanged()
                }
            }
            .onEnded { value in
                // A lift only ever commits a move; the context action lives on
                // the swipe axis, so there is nothing to arbitrate here.
                lift.finish()
                guard case let .second(true, drag) = value, movable else { return }
                commitDrop(item, translation: drag?.translation.height ?? 0)
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

extension OpenCodeReorderableList where Trailing == EmptyView {
    init(
        items: [Item],
        canMove: @escaping (Item) -> Bool = { _ in true },
        onMove: ((Int, Int) -> Void)?,
        activation: OpenCodeReorderableListActivation = .longPress,
        onSelect: @escaping (Item) -> Void,
        onContextAction: ((Item) -> Void)? = nil,
        supportsContextAction: @escaping (Item) -> Bool = { _ in true },
        contextActionLabel: String = "Remove",
        @ViewBuilder row: @escaping (Item) -> Row
    ) {
        self.init(
            items: items,
            canMove: canMove,
            onMove: onMove,
            activation: activation,
            onSelect: onSelect,
            onContextAction: onContextAction,
            supportsContextAction: supportsContextAction,
            contextActionLabel: contextActionLabel,
            row: row,
            trailing: { _ in EmptyView() }
        )
    }
}

/// How a movable row starts a reorder.
enum OpenCodeReorderableListActivation: Equatable {
    /// Long-press anywhere on the row, then drag.
    case longPress
    /// A drag handle on the leading edge drags at once; long-press still works.
    case handle
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

private struct ReorderableTrailingWidthKey<ID: Hashable>: PreferenceKey {
    static var defaultValue: [ID: CGFloat] { [:] }

    static func reduce(value: inout [ID: CGFloat], nextValue: () -> [ID: CGFloat]) {
        value.merge(nextValue()) { _, new in new }
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
