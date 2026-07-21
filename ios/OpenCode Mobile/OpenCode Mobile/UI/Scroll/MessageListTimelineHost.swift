import SwiftUI
import UIKit

struct MessageListTimelineHost<Item: Identifiable & Equatable, ItemContent: View>: UIViewControllerRepresentable
where Item.ID == String {
    let items: [Item]
    var scrollController: TimelineScrollController
    var onRefresh: (() async -> Void)?
    var horizontalContentPadding: CGFloat = MessageListTimelineDefaults.horizontalContentPadding
    var topContentPadding: CGFloat = MessageListTimelineDefaults.topContentPadding
    var bottomContentPadding: CGFloat = MessageListTimelineDefaults.bottomContentPadding
    var interItemSpacing: CGFloat = MessageListTimelineDefaults.messageSpacing
    @ViewBuilder var itemContent: (Item) -> ItemContent

    func makeUIViewController(context: Context) -> MessageListTimelineViewController<Item, ItemContent> {
        let viewController = MessageListTimelineViewController(
            itemContent: itemContent,
            horizontalContentPadding: horizontalContentPadding,
            topContentPadding: topContentPadding,
            bottomContentPadding: bottomContentPadding,
            interItemSpacing: interItemSpacing
        )
        viewController.scrollController = scrollController
        viewController.onRefresh = onRefresh
        scrollController.attach(viewController)
        viewController.loadViewIfNeeded()
        viewController.updateRefreshControlAvailability()
        viewController.apply(items: items, animatingDifferences: false)
        return viewController
    }

    func updateUIViewController(
        _ viewController: MessageListTimelineViewController<Item, ItemContent>,
        context: Context
    ) {
        viewController.scrollController = scrollController
        viewController.onRefresh = onRefresh
        viewController.itemContent = itemContent
        scrollController.attach(viewController)
        viewController.loadViewIfNeeded()
        viewController.updateRefreshControlAvailability()
        // Keep snapshot updates unanimated for now; open-settling still races
        // estimated heights and looks like messages sliding in.
        viewController.apply(items: items, animatingDifferences: false)
    }
}

@MainActor
final class MessageListTimelineViewController<Item: Identifiable & Equatable, ItemContent: View>:
    UIViewController,
    UICollectionViewDelegate,
    TimelineScrollPerforming
where Item.ID == String {
    var scrollController: TimelineScrollController?
    var onRefresh: (() async -> Void)?
    var itemContent: (Item) -> ItemContent

    private let horizontalContentPadding: CGFloat
    private let topContentPadding: CGFloat
    private let bottomContentPadding: CGFloat
    private let interItemSpacing: CGFloat

    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<String, String>!
    private var itemsByID: [String: Item] = [:]
    private var orderedIDs: [String] = []
    private var currentItems: [Item] = []
    private var lastContentHeight: CGFloat = 0
    private var lastViewportHeight: CGFloat = 0
    private var hasCompletedInitialScroll = false
    private var isSettlingInitialLoad = true
    private var initialLoadSettlingTask: Task<Void, Never>?
    /// Extra top inset so short threads (new session, first message) sit on the
    /// bottom edge instead of the top of the viewport.
    private var bottomAlignmentTopInset: CGFloat = 0
    private var userScrollEndTask: Task<Void, Never>?
    private var contentSizeObservation: NSKeyValueObservation?
    private var refreshControl: UIRefreshControl?

    init(
        itemContent: @escaping (Item) -> ItemContent,
        horizontalContentPadding: CGFloat,
        topContentPadding: CGFloat,
        bottomContentPadding: CGFloat,
        interItemSpacing: CGFloat
    ) {
        self.itemContent = itemContent
        self.horizontalContentPadding = horizontalContentPadding
        self.topContentPadding = topContentPadding
        self.bottomContentPadding = bottomContentPadding
        self.interItemSpacing = interItemSpacing
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        configureCollectionView()
        configureDataSource()
        updateRefreshControlAvailability()
        observeContentSize()
    }

    func updateRefreshControlAvailability() {
        configureRefreshControl()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateBottomAlignmentInset()
        reportGeometry()
    }

    func apply(items: [Item], animatingDifferences: Bool) {
        guard isViewLoaded, dataSource != nil else { return }
        guard items != currentItems else { return }

        let previousItems = itemsByID
        let previousOrderedIDs = orderedIDs
        let previousCount = previousOrderedIDs.count
        currentItems = items
        itemsByID = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
        orderedIDs = items.map(\.id)

        var snapshot = NSDiffableDataSourceSnapshot<String, String>()
        snapshot.appendSections(["main"])
        snapshot.appendItems(orderedIDs, toSection: "main")

        let changedIDs = orderedIDs.filter { id in
            guard let previous = previousItems[id], let current = itemsByID[id] else {
                return false
            }
            return previous != current
        }
        if !changedIDs.isEmpty {
            snapshot.reconfigureItems(changedIDs)
        }

        // Difference animations are off for now — initial load and short-thread
        // bottom alignment still fight estimated heights when inserts animate.
        _ = animatingDifferences
        let applySnapshot = { [weak self] in
            guard let self else { return }
            self.dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
                guard let self else { return }
                let shouldStayPinned =
                    !self.hasCompletedInitialScroll
                    || self.isSettlingInitialLoad
                    || self.scrollController?.isPinnedToEnd == true
                    || self.scrollController?.isProgrammaticScrolling == true
                if shouldStayPinned {
                    self.pinToBottomWithoutAnimation()
                } else {
                    UIView.performWithoutAnimation {
                        self.collectionView.layoutIfNeeded()
                    }
                    self.reportGeometry()
                }

                if !self.hasCompletedInitialScroll, !self.orderedIDs.isEmpty {
                    self.hasCompletedInitialScroll = true
                    self.beginInitialLoadSettling()
                    self.scrollController?.reset(messageCount: self.orderedIDs.count)
                    self.scrollController?.requestScrollToEnd(animated: false)
                    return
                }

                if self.orderedIDs.count != previousCount {
                    self.scrollController?.updateMessageCount(self.orderedIDs.count)
                }
            }
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation(applySnapshot)
        CATransaction.commit()
    }

    func performScrollToEnd(animated: Bool) {
        collectionView.layoutIfNeeded()
        guard !orderedIDs.isEmpty else { return }
        // Match content-growth anchoring: never yank the offset under an
        // active user pan or residual deceleration.
        guard !collectionView.isDragging, !collectionView.isDecelerating else { return }

        // Never animate scroll during open settle; pill taps can animate after.
        let allowAnimation = animated && !isSettlingInitialLoad && hasCompletedInitialScroll
        let bottomOffset = bottomContentOffsetY()
        if allowAnimation {
            collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: true)
            return
        }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: false)
            collectionView.layoutIfNeeded()
        }
        CATransaction.commit()
        reportGeometry()

        // Estimated cell heights often resolve on the next turn; re-pin then.
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
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        scrollController?.beginUserScrolling()
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate {
            scrollController?.endUserScrolling()
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        scrollController?.endUserScrolling()
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        reportGeometry()
    }

    private func configureCollectionView() {
        let layout = UICollectionViewCompositionalLayout { [weak self] _, environment in
            guard let self else { return nil }

            let itemSize = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1),
                heightDimension: .estimated(72)
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
        collectionView.keyboardDismissMode = .interactive
        // Prevent self-sizing invalidations from implicitly animating cell frames
        // while estimated heights resolve on open.
        collectionView.selfSizingInvalidation = .enabledIncludingConstraints
        view.addSubview(collectionView)
        self.collectionView = collectionView
    }

    private func configureDataSource() {
        let cellRegistration = UICollectionView.CellRegistration<
            UICollectionViewCell,
            String
        > { [weak self] cell, _, itemID in
            guard let self, let item = self.itemsByID[itemID] else { return }
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
        guard let collectionView else { return }
        let viewportHeight = collectionView.bounds.height
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
        max(
            -collectionView.adjustedContentInset.top,
            collectionView.contentSize.height
                - collectionView.bounds.height
                + collectionView.adjustedContentInset.bottom
        )
    }

    private func updateBottomAlignmentInset() {
        guard collectionView.bounds.height > 0 else { return }
        collectionView.layoutIfNeeded()

        let systemTopInset = max(0, collectionView.adjustedContentInset.top - bottomAlignmentTopInset)
        let bottomInset = collectionView.adjustedContentInset.bottom
        let availableHeight = collectionView.bounds.height - systemTopInset - bottomInset
        let targetInset = max(0, availableHeight - collectionView.contentSize.height)
        guard abs(targetInset - bottomAlignmentTopInset) > 0.5 else { return }

        let keepPinned =
            !hasCompletedInitialScroll
            || scrollController?.isPinnedToEnd == true
            || scrollController?.isProgrammaticScrolling == true
            || orderedIDs.count <= 1

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
        guard !collectionView.isDragging, !collectionView.isDecelerating else { return }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            collectionView.layoutIfNeeded()
            updateBottomAlignmentInset()
            let refinedOffset = bottomContentOffsetY()
            if abs(collectionView.contentOffset.y - refinedOffset) > 0.5 {
                collectionView.setContentOffset(CGPoint(x: 0, y: refinedOffset), animated: false)
                collectionView.layoutIfNeeded()
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
            collectionView.layoutIfNeeded()
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
