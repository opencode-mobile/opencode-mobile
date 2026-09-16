import SwiftUI
import UIKit

struct MessageListTimelineHost<Item: Identifiable, ItemContent: View>: UIViewControllerRepresentable
where Item.ID == String {
    let items: [Item]
    /// Declared change/layout ID sets from `TimelineFrame`. When nil (gallery),
    /// the host diffs ID lists only and reconfigures surviving IDs — never
    /// list-wide `Item` Equatable discovery.
    var applyPacket: MessageListTimelineApplyPacket? = nil
    var scrollController: TimelineScrollController
    var onRefresh: (() async -> Void)?
    /// Composer sits outside this collection view; `keyboardDismissMode` alone
    /// does not reliably resign that first responder, so callers resign on drag.
    var onUserScrollDismissKeyboard: (() -> Void)? = nil
    var canLoadOlder: Bool = false
    var onNearOldestVisible: (() -> Void)? = nil
    var horizontalContentPadding: CGFloat = MessageListTimelineDefaults.horizontalContentPadding
    var topContentPadding: CGFloat = MessageListTimelineDefaults.topContentPadding
    var bottomContentPadding: CGFloat = MessageListTimelineDefaults.bottomContentPadding
    /// Extra scroll clearance under floating chrome (composer), measured from
    /// the host view's bottom edge. Applied via `additionalSafeAreaInsets.bottom`
    /// minus any inherited system bottom inset so home-indicator underlap does
    /// not double-count.
    var bottomOverlayInset: CGFloat = 0
    /// Extra scroll clearance under the top float stack (todos / review / sync).
    /// Applied via `additionalSafeAreaInsets.top` so the first message rests
    /// below the overlay while content can still scroll under it.
    var topOverlayInset: CGFloat = 0
    var interItemSpacing: CGFloat = MessageListTimelineDefaults.messageSpacing
    @ViewBuilder var itemContent: (Item) -> ItemContent

    func makeUIViewController(context: Context) -> MessageListTimelineViewController<Item, ItemContent> {
        let viewController = MessageListTimelineViewController(
            itemContent: itemContent,
            horizontalContentPadding: horizontalContentPadding,
            topContentPadding: topContentPadding,
            bottomContentPadding: bottomContentPadding,
            bottomOverlayInset: bottomOverlayInset,
            topOverlayInset: topOverlayInset,
            interItemSpacing: interItemSpacing
        )
        viewController.scrollController = scrollController
        viewController.onRefresh = onRefresh
        viewController.onUserScrollDismissKeyboard = onUserScrollDismissKeyboard
        viewController.canLoadOlder = canLoadOlder
        viewController.onNearOldestVisible = onNearOldestVisible
        scrollController.attach(viewController)
        viewController.loadViewIfNeeded()
        viewController.updateRefreshControlAvailability()
        viewController.apply(
            items: items,
            packet: applyPacket,
            animatingDifferences: false
        )
        return viewController
    }

    func updateUIViewController(
        _ viewController: MessageListTimelineViewController<Item, ItemContent>,
        context: Context
    ) {
        viewController.scrollController = scrollController
        viewController.onRefresh = onRefresh
        viewController.onUserScrollDismissKeyboard = onUserScrollDismissKeyboard
        viewController.canLoadOlder = canLoadOlder
        viewController.onNearOldestVisible = onNearOldestVisible
        viewController.itemContent = itemContent
        viewController.updateBottomOverlayInset(bottomOverlayInset)
        viewController.updateTopOverlayInset(topOverlayInset)
        scrollController.attach(viewController)
        viewController.loadViewIfNeeded()
        viewController.updateRefreshControlAvailability()
        // Keep snapshot updates unanimated for now; open-settling still races
        // estimated heights and looks like messages sliding in.
        viewController.apply(
            items: items,
            packet: applyPacket,
            animatingDifferences: false
        )
    }
}

@MainActor
final class MessageListTimelineViewController<Item: Identifiable, ItemContent: View>:
    UIViewController,
    UICollectionViewDelegate,
    TimelineScrollPerforming
where Item.ID == String {
    var scrollController: TimelineScrollController?
    var onRefresh: (() async -> Void)?
    var onUserScrollDismissKeyboard: (() -> Void)?
    var canLoadOlder = false
    var onNearOldestVisible: (() -> Void)?
    var itemContent: (Item) -> ItemContent

    private let horizontalContentPadding: CGFloat
    private let topContentPadding: CGFloat
    private let bottomContentPadding: CGFloat
    private var bottomOverlayInset: CGFloat
    private var topOverlayInset: CGFloat
    private let interItemSpacing: CGFloat

    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<String, String>!
    private var itemsByID: [String: Item] = [:]
    private var orderedIDs: [String] = []
    private let rowHeightCache = MessageListTimelineRowHeightCache()
    private var lastAppliedFrameRevision: UInt64?
    private var lastContentHeight: CGFloat = 0
    private var lastViewportHeight: CGFloat = 0
    private var hasCompletedInitialScroll = false
    private var isRestoringOlderPrepend = false
    private var isSettlingInitialLoad = true
    private var initialLoadSettlingTask: Task<Void, Never>?
    /// Extra top inset so short threads (new session, first message) sit on the
    /// bottom edge instead of the top of the viewport.
    private var bottomAlignmentTopInset: CGFloat = 0
    /// Unpinned content offset to restore after a covering destination (tool
    /// group, Changes) pops. Navigation layout otherwise re-pins to the end.
    private var preservedOffsetY: CGFloat?
    private var isCoveredByNavigation = false
    private var userScrollEndTask: Task<Void, Never>?
    /// User must drag toward oldest after the first paint before older pages load.
    private var pendingOlderLoadFromUserScroll = false
    private var didFireOlderLoadThisGesture = false
    private var lastUserScrollOffsetY: CGFloat?
    private var contentSizeObservation: NSKeyValueObservation?
    private var refreshControl: UIRefreshControl?
    /// Enrich publishes coalesce here while the user is actively scrolling so
    /// plain→markdown cell reconfigure does not hitch under a pan.
    private var pendingApplyItems: [Item]?
    private var pendingApplyPacket: MessageListTimelineApplyPacket?
    private var pendingApplyAnimated = false

    /// Test seam: last reconfigure ID set passed to Diffable (declared path).
    private(set) var lastReconfiguredIDs: Set<String> = []
    /// Test seam: whether the last apply used declared IDs (vs gallery fallback).
    private(set) var lastApplyUsedDeclaredIDs = false
    /// Test seam: how many times a Diffable snapshot was submitted.
    private(set) var diffableSnapshotApplyCount = 0
    /// Test seam: current collection offset (navigation cover restore).
    var testContentOffsetY: CGFloat { collectionView.contentOffset.y }
    /// Test seam: true bottom pin offset for simulating a UIKit jump-to-end.
    var testBottomContentOffsetY: CGFloat { bottomContentOffsetY() }
    /// Test seam: composer overlay line in view coordinates.
    var testComposerLineY: CGFloat {
        collectionView.bounds.height - collectionView.adjustedContentInset.bottom
    }
    /// Test seam: last item frame in view coordinates.
    var testLastItemFrame: CGRect? {
        guard !orderedIDs.isEmpty else { return nil }
        return itemFrameInViewCoordinates(at: orderedIDs.count - 1)
    }
    /// Test seam: item frame in view coordinates for an arbitrary ID.
    func testItemFrame(id: String) -> CGRect? {
        guard let index = orderedIDs.firstIndex(of: id) else { return nil }
        return itemFrameInViewCoordinates(at: index)
    }
    /// Test seam: collection `adjustedContentInset.bottom`.
    var testAdjustedContentInsetBottom: CGFloat { collectionView.adjustedContentInset.bottom }
    /// Test seam: collection `contentSize.height`.
    var testContentHeight: CGFloat { collectionView.contentSize.height }
    /// Test seam: collection `bounds.height`.
    var testBoundsHeight: CGFloat { collectionView.bounds.height }

    private func itemFrameInViewCoordinates(at index: Int) -> CGRect? {
        guard collectionView.numberOfSections > 0,
              collectionView.numberOfItems(inSection: 0) > index,
              let attributes = collectionView.layoutAttributesForItem(
                  at: IndexPath(item: index, section: 0)
              )
        else { return nil }
        return attributes.frame.offsetBy(
            dx: -collectionView.contentOffset.x,
            dy: -collectionView.contentOffset.y
        )
    }

    func testSetContentOffsetY(_ y: CGFloat) {
        collectionView.setContentOffset(CGPoint(x: 0, y: y), animated: false)
        reportGeometry()
    }

    func testFlushPendingApply() {
        flushPendingApplyIfNeeded()
    }

    init(
        itemContent: @escaping (Item) -> ItemContent,
        horizontalContentPadding: CGFloat,
        topContentPadding: CGFloat,
        bottomContentPadding: CGFloat,
        bottomOverlayInset: CGFloat,
        topOverlayInset: CGFloat = 0,
        interItemSpacing: CGFloat
    ) {
        self.itemContent = itemContent
        self.horizontalContentPadding = horizontalContentPadding
        self.topContentPadding = topContentPadding
        self.bottomContentPadding = bottomContentPadding
        self.bottomOverlayInset = bottomOverlayInset
        self.topOverlayInset = topOverlayInset
        self.interItemSpacing = interItemSpacing
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Floating Session Chat composer clearance. Uses `additionalSafeAreaInsets`
    /// so `adjustedContentInset.bottom` grows and end-pin / manual scroll can
    /// park the newest message above the chrome. Section footers and
    /// `contentInset.bottom` alone did not expand scroll range here (LLDB).
    /// Subtract inherited system bottom inset so an edge-to-edge host does not
    /// stack the home indicator on top of the measured composer clearance.
    func updateBottomOverlayInset(_ value: CGFloat) {
        let inset = max(0, value)
        guard abs(bottomOverlayInset - inset) > 0.5 else {
            applyBottomOverlaySafeArea(repinIfNeeded: false)
            return
        }
        bottomOverlayInset = inset
        applyBottomOverlaySafeArea(repinIfNeeded: true)
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        applyBottomOverlaySafeArea(repinIfNeeded: false)
    }

    private func applyBottomOverlaySafeArea(repinIfNeeded: Bool) {
        guard isViewLoaded else { return }
        let inheritedBottom = max(0, view.safeAreaInsets.bottom - additionalSafeAreaInsets.bottom)
        let inset = max(0, bottomOverlayInset - inheritedBottom)
        if abs(additionalSafeAreaInsets.bottom - inset) > 0.5 {
            var safe = additionalSafeAreaInsets
            safe.bottom = inset
            additionalSafeAreaInsets = safe
        }
        updateBottomAlignmentInset()
        guard repinIfNeeded, scrollController?.isPinnedToEnd == true else { return }
        performScrollToEnd(animated: false)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.scrollController?.isPinnedToEnd == true else { return }
            self.pinToBottomWithoutAnimation()
        }
    }

    func updateTopOverlayInset(_ value: CGFloat) {
        let inset = max(0, value)
        guard abs(topOverlayInset - inset) > 0.5 else {
            applyTopOverlaySafeArea(repinIfNeeded: false)
            return
        }
        topOverlayInset = inset
        applyTopOverlaySafeArea(repinIfNeeded: true)
    }

    private func applyTopOverlaySafeArea(repinIfNeeded: Bool) {
        guard isViewLoaded else { return }
        let inset = topOverlayInset
        if abs(additionalSafeAreaInsets.top - inset) > 0.5 {
            var safe = additionalSafeAreaInsets
            safe.top = inset
            additionalSafeAreaInsets = safe
        }
        updateBottomAlignmentInset()
        guard repinIfNeeded, scrollController?.isPinnedToEnd == true else { return }
        performScrollToEnd(animated: false)
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.scrollController?.isPinnedToEnd == true else { return }
            self.pinToBottomWithoutAnimation()
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        configureCollectionView()
        configureDataSource()
        updateRefreshControlAvailability()
        observeContentSize()
        applyBottomOverlaySafeArea(repinIfNeeded: false)
        applyTopOverlaySafeArea(repinIfNeeded: false)
    }

    func updateRefreshControlAvailability() {
        configureRefreshControl()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        isCoveredByNavigation = true
        preserveUnpinnedScrollPositionIfNeeded()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        isCoveredByNavigation = false
        restorePreservedUnpinnedScrollPositionIfNeeded()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        restorePreservedUnpinnedScrollPositionIfNeeded()
        reportGeometry()
        // Keep the preserved offset through the appear layout turn so an
        // in-flight pin-to-end (queued while covered) cannot win the race.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.restorePreservedUnpinnedScrollPositionIfNeeded()
            self.reportGeometry()
            self.preservedOffsetY = nil
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard !isCoveredByNavigation else { return }
        restorePreservedUnpinnedScrollPositionIfNeeded()
        updateBottomAlignmentInset()
        restorePreservedUnpinnedScrollPositionIfNeeded()
        reportGeometry()
    }

    func apply(
        items: [Item],
        packet: MessageListTimelineApplyPacket?,
        animatingDifferences: Bool
    ) {
        guard isViewLoaded, dataSource != nil else { return }

        let isActivelyScrolling =
            collectionView.isDragging
            || collectionView.isDecelerating
            || scrollController?.isUserScrolling == true
        // Required applies (load / refresh / post-send) must never pend under drag.
        if isActivelyScrolling, packet?.isRequired != true {
            pendingApplyItems = items
            pendingApplyPacket = MessageListTimelineApplyPacket.coalescing(
                pendingApplyPacket,
                with: packet
            )
            pendingApplyAnimated = animatingDifferences
            return
        }

        pendingApplyItems = nil
        pendingApplyPacket = nil
        performApply(items: items, packet: packet, animatingDifferences: animatingDifferences)
    }

    private func flushPendingApplyIfNeeded() {
        guard let pendingApplyItems else { return }
        let items = pendingApplyItems
        let packet = pendingApplyPacket
        let animated = pendingApplyAnimated
        self.pendingApplyItems = nil
        self.pendingApplyPacket = nil
        performApply(items: items, packet: packet, animatingDifferences: animated)
    }

    private func performApply(
        items: [Item],
        packet: MessageListTimelineApplyPacket?,
        animatingDifferences: Bool
    ) {
        // Diffable data source asserts on duplicate identifiers. Keep last-wins
        // content and a de-duplicated id list for the snapshot.
        var uniqueItems: [Item] = []
        var seenIDs = Set<String>()
        for item in items.reversed() {
            if seenIDs.insert(item.id).inserted {
                uniqueItems.append(item)
            }
        }
        uniqueItems.reverse()
        let newOrderedIDs = uniqueItems.map(\.id)
        let previousOrderedIDs = orderedIDs
        let previousCount = previousOrderedIDs.count
        let previousIDSet = Set(previousOrderedIDs)

        // Declared packet wins. Nil packet is gallery / legacy fallback only.
        // Never synthesize reconfigure-all with an auto-bumped revision while
        // IDs are stable — that used to run on every SwiftUI
        // `updateUIViewController` (status chrome cleared the packet) and
        // froze Diffable apply in a layout→update loop.
        let resolvedPacket: MessageListTimelineApplyPacket
        if let packet {
            resolvedPacket = packet
            lastApplyUsedDeclaredIDs = true
        } else if newOrderedIDs == previousOrderedIDs {
            itemsByID = Dictionary(uniqueKeysWithValues: uniqueItems.map { ($0.id, $0) })
            lastApplyUsedDeclaredIDs = false
            lastReconfiguredIDs = []
            return
        } else {
            resolvedPacket = MessageListTimelineApplyPacket.reconfigureAllSurviving(
                frameRevision: (lastAppliedFrameRevision ?? 0) &+ 1,
                previousIDs: previousIDSet,
                nextIDs: newOrderedIDs
            )
            lastApplyUsedDeclaredIDs = false
        }

        // Skip without list-wide Item Equatable:
        // 1) Same frameRevision already applied (composer/session-info reuse keeps
        //    declared sets from the original timeline publish — do not re-apply).
        // 2) Empty declared sets + identical ID order (no-op projector frame).
        if newOrderedIDs == previousOrderedIDs {
            if let lastRevision = lastAppliedFrameRevision,
               resolvedPacket.frameRevision == lastRevision
            {
                itemsByID = Dictionary(uniqueKeysWithValues: uniqueItems.map { ($0.id, $0) })
                lastReconfiguredIDs = []
                return
            }
            if resolvedPacket.insertedRowIDs.isEmpty,
               resolvedPacket.changedRowIDs.isEmpty,
               resolvedPacket.removedRowIDs.isEmpty,
               resolvedPacket.layoutChangedRowIDs.isEmpty
            {
                itemsByID = Dictionary(uniqueKeysWithValues: uniqueItems.map { ($0.id, $0) })
                lastAppliedFrameRevision = resolvedPacket.frameRevision
                lastReconfiguredIDs = []
                return
            }
        }

        let layoutAffecting = resolvedPacket.layoutAffectingRowIDs
        let structureAffecting = resolvedPacket.structureAffectingRowIDs
        let isDecorateOnly =
            !layoutAffecting.isEmpty
            && structureAffecting.isEmpty
            && resolvedPacket.grewBottomRowIDs.isEmpty
        let isOlderPrepend =
            !previousOrderedIDs.isEmpty
            && newOrderedIDs.count > previousCount
            && previousIDSet.isSubset(of: Set(newOrderedIDs))
            && newOrderedIDs.first != previousOrderedIDs.first
        let shouldCaptureUnarmedAnchor =
            isOlderPrepend
            || (
                hasCompletedInitialScroll
                && !isSettlingInitialLoad
                && scrollController?.isPinnedToEnd != true
                && scrollController?.isProgrammaticScrolling != true
                && !layoutAffecting.isEmpty
            )
        let visibleAnchor = shouldCaptureUnarmedAnchor ? captureVisibleAnchor() : nil
        if isOlderPrepend {
            isRestoringOlderPrepend = true
        }

        itemsByID = Dictionary(uniqueKeysWithValues: uniqueItems.map { ($0.id, $0) })
        orderedIDs = newOrderedIDs
        rowHeightCache.retainOnly(ids: Set(newOrderedIDs))
        lastAppliedFrameRevision = resolvedPacket.frameRevision

        var snapshot = NSDiffableDataSourceSnapshot<String, String>()
        snapshot.appendSections(["main"])
        snapshot.appendItems(orderedIDs, toSection: "main")

        let existingIDs = Set(dataSource.snapshot().itemIdentifiers)
        let reconfigureIDs = orderedIDs.filter { id in
            existingIDs.contains(id) && resolvedPacket.reconfigureRowIDs.contains(id)
        }
        lastReconfiguredIDs = Set(reconfigureIDs)
        if !reconfigureIDs.isEmpty {
            snapshot.reconfigureItems(reconfigureIDs)
        }

        // Difference animations are off for now — initial load and short-thread
        // bottom alignment still fight estimated heights when inserts animate.
        _ = animatingDifferences
        let applySnapshot = { [weak self] in
            guard let self else { return }
            self.dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
                guard let self else { return }

                if let visibleAnchor {
                    self.restoreVisibleAnchor(visibleAnchor)
                }

                let shouldStayPinned =
                    self.preservedOffsetY == nil
                    && (
                        !self.hasCompletedInitialScroll
                        || self.isSettlingInitialLoad
                        || self.scrollController?.isPinnedToEnd == true
                        || self.scrollController?.isProgrammaticScrolling == true
                    )
                let isReconfigureOnly =
                    self.hasCompletedInitialScroll
                    && self.orderedIDs.count == previousCount
                    && layoutAffecting.isEmpty
                    && !reconfigureIDs.isEmpty
                // While heights are still settling after open, skip re-pin on
                // identity-stable updates (warm cache-hit). Re-pinning was
                // re-entering self-sizing and blocking scroll for seconds.
                let skipPinDuringSettle =
                    self.isSettlingInitialLoad
                    && self.hasCompletedInitialScroll
                    && self.orderedIDs.count == previousCount
                if isOlderPrepend {
                    self.scrollController?.noteObservedMessageCount(self.orderedIDs.count)
                } else if shouldStayPinned, visibleAnchor == nil, !isReconfigureOnly, !skipPinDuringSettle,
                   !isDecorateOnly
                {
                    self.pinToBottomWithoutAnimation()
                } else {
                    // Do not force layoutIfNeeded while reading history — enrich
                    // reconfigure of off-screen / partially visible markdown cells
                    // was hitching scrolls for multi-second MainActor layout passes.
                    // Same for warm reopen of unchanged identity rows.
                    self.reportGeometry()
                }

                if !self.hasCompletedInitialScroll, !self.orderedIDs.isEmpty, !isOlderPrepend {
                    self.hasCompletedInitialScroll = true
                    self.beginInitialLoadSettling()
                    self.scrollController?.reset(messageCount: self.orderedIDs.count)
                    self.scrollController?.requestScrollToEnd(animated: false)
                    return
                }

                if isOlderPrepend {
                    self.hasCompletedInitialScroll = true
                    self.scrollController?.noteObservedMessageCount(self.orderedIDs.count)
                    self.isRestoringOlderPrepend = false
                } else if self.orderedIDs.count != previousCount {
                    self.scrollController?.updateMessageCount(self.orderedIDs.count)
                } else if !structureAffecting.isEmpty {
                    // Declared structure growth with stable count — not decorate-only.
                    self.scrollController?.notifyContentGrew()
                }
            }
        }
        diffableSnapshotApplyCount += 1
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation(applySnapshot)
        CATransaction.commit()
    }

    // MARK: - Unarmed visible-anchor capture / restore

    private struct VisibleAnchor {
        let itemID: String
        let offsetFromViewportTop: CGFloat
    }

    private func captureVisibleAnchor() -> VisibleAnchor? {
        guard let collectionView, !orderedIDs.isEmpty else { return nil }
        let visible = collectionView.indexPathsForVisibleItems.sorted {
            $0.item < $1.item
        }
        let probe = CGPoint(
            x: collectionView.bounds.midX,
            y: collectionView.contentOffset.y + collectionView.adjustedContentInset.top + 1
        )
        let indexPath =
            visible.first
            ?? collectionView.indexPathForItem(at: probe)
            ?? IndexPath(item: 0, section: 0)
        guard indexPath.item < orderedIDs.count else { return nil }
        let itemID = orderedIDs[indexPath.item]
        guard let attributes = collectionView.layoutAttributesForItem(at: indexPath) else { return nil }
        let offsetFromViewportTop = attributes.frame.minY - collectionView.contentOffset.y
        return VisibleAnchor(itemID: itemID, offsetFromViewportTop: offsetFromViewportTop)
    }

    private func restoreVisibleAnchor(_ anchor: VisibleAnchor) {
        guard let collectionView else { return }
        guard let index = orderedIDs.firstIndex(of: anchor.itemID) else { return }
        let indexPath = IndexPath(item: index, section: 0)
        guard collectionView.numberOfSections > 0,
              collectionView.numberOfItems(inSection: 0) > indexPath.item,
              let attributes = collectionView.layoutAttributesForItem(at: indexPath)
        else { return }

        let targetY = attributes.frame.minY - anchor.offsetFromViewportTop
        let minY = -collectionView.adjustedContentInset.top
        let maxY = bottomContentOffsetY()
        let clamped = min(max(targetY, minY), maxY)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            collectionView.setContentOffset(CGPoint(x: 0, y: clamped), animated: false)
        }
        CATransaction.commit()
        reportGeometry()
    }

    private func preserveUnpinnedScrollPositionIfNeeded() {
        guard collectionView.bounds.height > 1 else { return }
        guard scrollController?.isPinnedToEnd != true else {
            preservedOffsetY = nil
            return
        }
        preservedOffsetY = collectionView.contentOffset.y
    }

    private func restorePreservedUnpinnedScrollPositionIfNeeded() {
        guard collectionView.bounds.height > 1 else { return }
        guard let y = preservedOffsetY else { return }
        let minY = -collectionView.adjustedContentInset.top
        let maxY = bottomContentOffsetY()
        let clamped = min(max(y, minY), maxY)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            collectionView.setContentOffset(CGPoint(x: 0, y: clamped), animated: false)
        }
        CATransaction.commit()
        reportGeometry()
    }

    func performScrollToEnd(animated: Bool) {
        guard preservedOffsetY == nil else { return }
        guard !orderedIDs.isEmpty else { return }
        // Match content-growth anchoring: never yank the offset under an
        // active user pan or residual deceleration.
        guard !collectionView.isDragging, !collectionView.isDecelerating else { return }

        // Never call layoutIfNeeded here. On tall self-sizing markdown histories
        // it measures off-screen hosting cells and freezes MainActor for seconds
        // (warm Large-reply reopen), which is exactly when users try to scroll.
        //
        // Always pin to true scrollable extent via bottomContentOffsetY — do not
        // prefer scrollToItem(.bottom), which targets estimated last-cell frames
        // and lands short of section bottom inset / adjustedContentInset.
        let bottomOffset = bottomContentOffsetY()
        let remaining = abs(bottomOffset - collectionView.contentOffset.y)
        // Never animate scroll during open settle; pill taps can animate after
        // only for short remaining distances. Animating through tall markdown
        // histories forces self-sizing of many huge cells mid-flight.
        let allowAnimation =
            animated
            && !isSettlingInitialLoad
            && hasCompletedInitialScroll
            && remaining <= max(collectionView.bounds.height * 2.5, 900)

        if allowAnimation {
            // Animated scrolls must not sit inside performWithoutAnimation /
            // CATransaction.disableActions or UIKit cancels the animation.
            collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: true)
        } else {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            UIView.performWithoutAnimation {
                collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: false)
                if orderedIDs.count <= 8 {
                    updateBottomAlignmentInset()
                }
            }
            CATransaction.commit()
        }
        reportGeometry()

        // Estimated cell heights often resolve on the next turn; re-pin then
        // without a full layout pass.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard !self.collectionView.isDragging, !self.collectionView.isDecelerating else {
                return
            }
            self.pinToBottomWithoutAnimation()
        }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        reportGeometry()
        guard scrollView.isDragging || scrollView.isTracking else { return }
        scrollController?.beginUserScrolling()
        scheduleUserScrollEnd()
        noteUserScrollTowardOldestIfNeeded(scrollView)
    }

    func testNotifyNearOldestIfNeeded() {
        notifyNearOldestIfNeeded()
    }

    func testArmUserScrollTowardOldest() {
        pendingOlderLoadFromUserScroll = true
        didFireOlderLoadThisGesture = false
        hasCompletedInitialScroll = true
        isSettlingInitialLoad = false
    }

    private func noteUserScrollTowardOldestIfNeeded(_ scrollView: UIScrollView) {
        guard pendingOlderLoadFromUserScroll, !didFireOlderLoadThisGesture else { return }
        let y = scrollView.contentOffset.y
        let movedTowardOldest = lastUserScrollOffsetY.map { y < $0 - 0.5 } ?? false
        lastUserScrollOffsetY = y
        guard movedTowardOldest else { return }
        notifyNearOldestIfNeeded()
    }

    private func notifyNearOldestIfNeeded() {
        guard canLoadOlder, !orderedIDs.isEmpty, let collectionView else { return }
        guard hasCompletedInitialScroll, !isSettlingInitialLoad else { return }
        guard pendingOlderLoadFromUserScroll, !didFireOlderLoadThisGesture else { return }
        let visible = collectionView.indexPathsForVisibleItems.sorted { $0.item < $1.item }
        let oldestVisible = visible.first?.item == 0
        let nearTop =
            collectionView.contentOffset.y
            <= -collectionView.adjustedContentInset.top + MessageListTimelineDefaults.autoFollowTolerance
        guard oldestVisible || nearTop else { return }
        didFireOlderLoadThisGesture = true
        onNearOldestVisible?()
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        onUserScrollDismissKeyboard?()
        scrollController?.beginUserScrolling()
        pendingOlderLoadFromUserScroll = true
        didFireOlderLoadThisGesture = false
        lastUserScrollOffsetY = scrollView.contentOffset.y
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate {
            scrollController?.endUserScrolling()
            flushPendingApplyIfNeeded()
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        scrollController?.endUserScrolling()
        flushPendingApplyIfNeeded()
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        reportGeometry()
    }

    private func configureCollectionView() {
        let layout = UICollectionViewCompositionalLayout { [weak self] _, environment in
            guard let self else { return nil }

            let itemSize = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1),
                heightDimension: .estimated(MessageListTimelineDefaults.estimatedRowHeight)
            )
            let item = NSCollectionLayoutItem(layoutSize: itemSize)
            let group = NSCollectionLayoutGroup.vertical(layoutSize: itemSize, subitems: [item])
            let section = NSCollectionLayoutSection(group: group)
            section.interGroupSpacing = self.interItemSpacing
            section.contentInsets = NSDirectionalEdgeInsets(
                top: self.topContentPadding,
                leading: self.horizontalContentPadding,
                bottom: self.bottomContentPadding,
                trailing: self.horizontalContentPadding
            )
            _ = environment
            return section
        }

        let collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .clear
        collectionView.alwaysBounceVertical = true
        collectionView.contentInsetAdjustmentBehavior = .automatic
        collectionView.delegate = self
        collectionView.keyboardDismissMode = .onDrag
        // Prefer lighter invalidation: including constraints re-measured tall
        // markdown cells aggressively during open and blocked scrolling.
        collectionView.selfSizingInvalidation = .enabled
        view.addSubview(collectionView)
        self.collectionView = collectionView
    }

    private func configureDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<
            MessageListTimelineHostCell,
            String
        > { [weak self] cell, _, itemID in
            guard let self, let item = self.itemsByID[itemID] else { return }
            cell.itemID = itemID
            cell.heightCache = self.rowHeightCache
            cell.estimatedHeightSeed = self.estimatedHeightSeed(for: item)
            cell.contentConfiguration = UIHostingConfiguration {
                self.itemContent(item)
            }
            .margins(.all, 0)
            .minSize(width: 0, height: 0)
        }

        dataSource = UICollectionViewDiffableDataSource<String, String>(
            collectionView: collectionView
        ) { collectionView, indexPath, itemID in
            collectionView.dequeueConfiguredReusableCell(
                using: cellRegistration,
                for: indexPath,
                item: itemID
            )
        }
    }

    private func estimatedHeightSeed(for item: Item) -> CGFloat {
        if let estimating = item as? any MessageListTimelineHeightEstimating {
            return estimating.timelineRowHeightEstimate
        }
        return MessageListTimelineDefaults.estimatedRowHeight
    }

    private func configureRefreshControl() {
        guard onRefresh != nil else {
            collectionView.refreshControl = nil
            refreshControl = nil
            return
        }

        if refreshControl == nil {
            let control = UIRefreshControl()
            control.addTarget(self, action: #selector(handleRefresh), for: .valueChanged)
            refreshControl = control
        }
        collectionView.refreshControl = refreshControl
    }

    @objc
    private func handleRefresh() {
        guard let onRefresh else {
            refreshControl?.endRefreshing()
            return
        }

        Task { @MainActor in
            await onRefresh()
            refreshControl?.endRefreshing()
        }
    }

    private func observeContentSize() {
        contentSizeObservation = collectionView.observe(\.contentSize, options: [.old, .new]) {
            [weak self] _, change in
            guard let self else { return }
            let newHeight = change.newValue?.height ?? 0
            let oldHeight = change.oldValue?.height ?? 0
            // Must run synchronously on the main thread. Hopping through Task
            // leaves a visible frame where the prior message slides in.
            if Thread.isMainThread {
                MainActor.assumeIsolated {
                    self.handleContentSizeChange(newHeight: newHeight, oldHeight: oldHeight)
                }
            } else {
                Task { @MainActor [weak self] in
                    self?.handleContentSizeChange(newHeight: newHeight, oldHeight: oldHeight)
                }
            }
        }
    }

    private func handleContentSizeChange(newHeight: CGFloat, oldHeight: CGFloat) {
        let previousHeight = lastContentHeight
        lastContentHeight = newHeight
        let grewBy = newHeight - max(previousHeight, oldHeight)
        if isRestoringOlderPrepend {
            reportGeometry()
            return
        }
        updateBottomAlignmentInset()
        if grewBy > 0.5 {
            // Anchor as soon as we have items, including before the first
            // scroll-to-end completion callback runs.
            if hasCompletedInitialScroll
                || (!orderedIDs.isEmpty && scrollController?.isProgrammaticScrolling == true)
            {
                anchorBottomForContentGrowth(delta: grewBy)
            } else if !orderedIDs.isEmpty {
                pinToBottomWithoutAnimation()
            }
        }
        reportGeometry()
        guard grewBy > 0.5, hasCompletedInitialScroll else { return }
        scrollController?.notifyContentGrew()
    }

    private func reportGeometry() {
        guard !isCoveredByNavigation else { return }
        guard let collectionView else { return }
        let viewportHeight = collectionView.bounds.height
        guard viewportHeight > 1 else { return }
        let contentHeight = collectionView.contentSize.height
        let distanceFromBottom = max(
            0,
            contentHeight
                - collectionView.contentOffset.y
                - viewportHeight
                + collectionView.adjustedContentInset.bottom
        )
        let previousViewportHeight = lastViewportHeight
        lastViewportHeight = viewportHeight
        scrollController?.updateGeometry(
            distanceFromBottom: distanceFromBottom,
            viewportHeight: viewportHeight,
            contentHeight: contentHeight
        )

        guard hasCompletedInitialScroll else { return }
        if previousViewportHeight > 0, viewportHeight + 0.5 < previousViewportHeight {
            scrollController?.notifyContentGrew()
        }
    }

    private func bottomContentOffsetY() -> CGFloat {
        MessageListTimelineBottomOffset.y(
            contentHeight: collectionView.contentSize.height,
            viewportHeight: collectionView.bounds.height,
            adjustedTopInset: collectionView.adjustedContentInset.top,
            adjustedBottomInset: collectionView.adjustedContentInset.bottom
        )
    }

    private func updateBottomAlignmentInset() {
        guard collectionView.bounds.height > 0 else { return }
        // Avoid layoutIfNeeded here: viewDidLayoutSubviews + pin paths already
        // call this frequently, and full self-sizing of large histories hangs UI.

        let systemTopInset = max(0, collectionView.adjustedContentInset.top - bottomAlignmentTopInset)
        let bottomInset = collectionView.adjustedContentInset.bottom
        let availableHeight = collectionView.bounds.height - systemTopInset - bottomInset
        let targetInset = max(0, availableHeight - collectionView.contentSize.height)
        guard abs(targetInset - bottomAlignmentTopInset) > 0.5 else { return }

        let keepPinned =
            preservedOffsetY == nil
            && !isRestoringOlderPrepend
            && (
                !hasCompletedInitialScroll
                || scrollController?.isPinnedToEnd == true
                || scrollController?.isProgrammaticScrolling == true
                || orderedIDs.count <= 1
            )

        bottomAlignmentTopInset = targetInset
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            collectionView.contentInset.top = targetInset
            collectionView.verticalScrollIndicatorInsets.top = targetInset
            if keepPinned,
               !orderedIDs.isEmpty,
               !collectionView.isDragging,
               !collectionView.isDecelerating
            {
                collectionView.setContentOffset(
                    CGPoint(x: 0, y: bottomContentOffsetY()),
                    animated: false
                )
            }
        }
        CATransaction.commit()
    }

    private func pinToBottomWithoutAnimation() {
        guard preservedOffsetY == nil else {
            restorePreservedUnpinnedScrollPositionIfNeeded()
            return
        }
        guard !collectionView.isDragging, !collectionView.isDecelerating else { return }
        guard !orderedIDs.isEmpty else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            // True scrollable extent — not scrollToItem(.bottom), which misses
            // section bottom padding and fights estimated last-cell frames.
            collectionView.setContentOffset(
                CGPoint(x: 0, y: bottomContentOffsetY()),
                animated: false
            )
            // Short threads still need the top inset so content sits on the bottom edge.
            if orderedIDs.count <= 8 {
                updateBottomAlignmentInset()
            }
        }
        CATransaction.commit()
        reportGeometry()
    }

    private func anchorBottomForContentGrowth(delta: CGFloat) {
        let shouldAnchor =
            !hasCompletedInitialScroll
            || scrollController?.isPinnedToEnd == true
            || scrollController?.isProgrammaticScrolling == true
        guard shouldAnchor, !collectionView.isDragging, !collectionView.isDecelerating else {
            return
        }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            collectionView.contentOffset.y += delta
        }
        CATransaction.commit()
    }

    private func scheduleUserScrollEnd() {
        userScrollEndTask?.cancel()
        userScrollEndTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            guard let collectionView,
                  !collectionView.isDragging,
                  !collectionView.isDecelerating
            else { return }
            scrollController?.endUserScrolling()
            flushPendingApplyIfNeeded()
        }
    }

    private func beginInitialLoadSettling() {
        isSettlingInitialLoad = true
        initialLoadSettlingTask?.cancel()
        initialLoadSettlingTask = Task { @MainActor in
            try? await Task.sleep(
                for: .milliseconds(MessageListTimelineDefaults.initialLoadSettlingMilliseconds)
            )
            guard !Task.isCancelled else { return }
            self.isSettlingInitialLoad = false
        }
    }
}
