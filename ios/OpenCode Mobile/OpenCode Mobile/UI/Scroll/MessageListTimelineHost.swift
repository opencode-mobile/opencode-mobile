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
        viewController.apply(items: items, animatingDifferences: true)
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
        reportGeometry()
    }

    func apply(items: [Item], animatingDifferences: Bool) {
        guard isViewLoaded, dataSource != nil else { return }
        guard items != currentItems else { return }

        let previousItems = itemsByID
        let previousCount = orderedIDs.count
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

        let shouldAnimate = animatingDifferences && previousCount > 0
        dataSource.apply(snapshot, animatingDifferences: shouldAnimate) { [weak self] in
            guard let self else { return }
            self.collectionView.layoutIfNeeded()
            self.reportGeometry()

            if !self.hasCompletedInitialScroll, !self.orderedIDs.isEmpty {
                self.hasCompletedInitialScroll = true
                self.scrollController?.reset(messageCount: self.orderedIDs.count)
                self.scrollController?.requestScrollToEnd(animated: false)
                return
            }

            if self.orderedIDs.count != previousCount {
                self.scrollController?.updateMessageCount(self.orderedIDs.count)
            }
        }
    }

    func performScrollToEnd(animated: Bool) {
        collectionView.layoutIfNeeded()
        guard !orderedIDs.isEmpty else { return }

        let bottomOffset = max(
            -collectionView.adjustedContentInset.top,
            collectionView.contentSize.height
                - collectionView.bounds.height
                + collectionView.adjustedContentInset.bottom
        )
        collectionView.setContentOffset(CGPoint(x: 0, y: bottomOffset), animated: animated)

        if !animated {
            collectionView.layoutIfNeeded()
            reportGeometry()
            // Estimated cell heights often resolve on the next turn; re-pin then.
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.collectionView.layoutIfNeeded()
                let refinedOffset = max(
                    -self.collectionView.adjustedContentInset.top,
                    self.collectionView.contentSize.height
                        - self.collectionView.bounds.height
                        + self.collectionView.adjustedContentInset.bottom
                )
                if abs(self.collectionView.contentOffset.y - refinedOffset) > 0.5 {
                    self.collectionView.setContentOffset(
                        CGPoint(x: 0, y: refinedOffset),
                        animated: false
                    )
                    self.collectionView.layoutIfNeeded()
                }
                self.reportGeometry()
            }
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
            _, change in
            let newHeight = change.newValue?.height ?? 0
            let oldHeight = change.oldValue?.height ?? 0
            Task { @MainActor [weak self] in
                guard let self else { return }
                let previousHeight = self.lastContentHeight
                self.lastContentHeight = newHeight
                self.reportGeometry()
                let grew = newHeight > max(previousHeight, oldHeight) + 0.5
                guard grew, self.hasCompletedInitialScroll else { return }
                self.scrollController?.notifyContentGrew()
            }
        }
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
}
