import SwiftUI

enum MessageListTimelineDefaults {
    static let bottomAnchorHeight: CGFloat = 1
    static let pinnedTolerance: CGFloat = 8
    static let autoFollowTolerance: CGFloat = 32
    static let revealThresholdMin: CGFloat = 120
    static let messageSpacing: CGFloat = 8
    static let scrollToEndPillBottomPadding: CGFloat = 12
    static let horizontalContentPadding: CGFloat = 16
    static let topContentPadding: CGFloat = 12
    static let bottomContentPadding: CGFloat = 12
}

@Observable
@MainActor
final class MessageListTimelineState {
    let bottomAnchorID: String

    var scrollPositionID: String?
    var scrollDistanceFromBottom = CGFloat.greatestFiniteMagnitude
    var contentHeight: CGFloat = 0
    var viewportHeight: CGFloat = 0
    var isUserScrolling = false
    var isProgrammaticScrolling = false
    var autoFollowState: MessageListAutoFollowState

    init(bottomAnchorID: String, initialMessageCount: Int = 0) {
        self.bottomAnchorID = bottomAnchorID
        self.autoFollowState = MessageListAutoFollowState(initialMessageCount: initialMessageCount)
    }

    func isScrollable(messageCount: Int) -> Bool {
        contentHeight > viewportHeight + MessageListTimelineDefaults.pinnedTolerance
    }

    func isPinnedToEnd(messageCount: Int) -> Bool {
        guard isScrollable(messageCount: messageCount) else { return true }

        if #available(iOS 18, *) {
            return scrollDistanceFromBottom <= MessageListTimelineDefaults.autoFollowTolerance
        }

        return scrollPositionID == bottomAnchorID
    }

    func shouldShowScrollToEnd(messageCount: Int) -> Bool {
        guard messageCount > 0, isScrollable(messageCount: messageCount) else { return false }

        if #available(iOS 18, *) {
            return ScrollToEndVisibility.shouldShow(
                scrollDistanceFromBottom: scrollDistanceFromBottom,
                viewportHeight: viewportHeight,
                isUserScrolling: isUserScrolling
            )
        }

        guard !isUserScrolling else { return false }
        return scrollPositionID != bottomAnchorID
    }

    func reset(messageCount: Int) {
        autoFollowState = MessageListAutoFollowState(initialMessageCount: messageCount)
        autoFollowState.updatePinnedState(
            isPinnedToEnd: isPinnedToEnd(messageCount: messageCount),
            messageCount: messageCount
        )
    }

    func snapToEnd(
        messageCount: Int,
        scrollTargetID: String? = nil,
        autoFollowEnabled: Bool = true,
        reason: String = "layout-snap",
        onLog: ((String) -> Void)? = nil
    ) {
        guard messageCount > 0 else { return }

        let targetID = scrollTargetID ?? bottomAnchorID
        onLog?("\(reason) snap start count=\(messageCount) target=\(targetID)")
        isProgrammaticScrolling = true
        scrollPositionID = nil
        Task { @MainActor in
            await Task.yield()
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                if let scrollTargetID, scrollTargetID != bottomAnchorID {
                    scrollPositionID = scrollTargetID
                }
            }
            if scrollTargetID != nil, scrollTargetID != bottomAnchorID {
                await Task.yield()
                await Task.yield()
            }
            withTransaction(transaction) {
                scrollPositionID = bottomAnchorID
            }
            await Task.yield()
            await Task.yield()
            isProgrammaticScrolling = false
            settleProgrammaticScroll(
                messageCount: messageCount,
                autoFollowEnabled: autoFollowEnabled,
                reason: reason,
                onLog: onLog
            )
        }
    }

    func animateToEnd(
        messageCount: Int,
        scrollTargetID: String? = nil,
        autoFollowEnabled: Bool = true,
        onLog: ((String) -> Void)? = nil
    ) {
        guard messageCount > 0 else { return }

        let targetID = scrollTargetID ?? bottomAnchorID
        onLog?("animate-to-end start count=\(messageCount) target=\(targetID)")
        isProgrammaticScrolling = true
        scrollPositionID = nil
        Task { @MainActor in
            await Task.yield()
            if let scrollTargetID, scrollTargetID != bottomAnchorID {
                withAnimation(.easeOut(duration: 0.2)) {
                    scrollPositionID = scrollTargetID
                }
                await Task.yield()
                await Task.yield()
            }
            withAnimation(.easeOut(duration: 0.2)) {
                scrollPositionID = bottomAnchorID
            }
            await Task.yield()
            await Task.yield()
            isProgrammaticScrolling = false
            settleProgrammaticScroll(
                messageCount: messageCount,
                autoFollowEnabled: autoFollowEnabled,
                reason: "animate-to-end",
                onLog: onLog
            )
        }
    }

    private func settleProgrammaticScroll(
        messageCount: Int,
        autoFollowEnabled: Bool,
        reason: String,
        onLog: ((String) -> Void)?
    ) {
        let pinned = isPinnedToEnd(messageCount: messageCount)
        if autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled: autoFollowEnabled), !pinned {
            onLog?("\(reason) snap miss count=\(messageCount) pinned=\(pinned) position=\(scrollPositionID ?? "-")")
        } else {
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: pinned)
            onLog?("\(reason) snap settled count=\(messageCount) pinned=\(pinned) position=\(scrollPositionID ?? "-")")
        }
    }

    func followLayoutChangeIfNeeded(
        messageCount: Int,
        autoFollowEnabled: Bool,
        scrollTargetID: String? = nil,
        onLog: ((String) -> Void)? = nil
    ) {
        let ensureLayoutEnd = autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled: autoFollowEnabled)
        if isUserScrolling, !ensureLayoutEnd {
            onLog?("layout-check no-op count=\(messageCount) pinned=\(isPinnedToEnd(messageCount: messageCount)) userScrolling=\(isUserScrolling)")
            autoFollowState.updatePinnedState(
                isPinnedToEnd: isPinnedToEnd(messageCount: messageCount),
                messageCount: messageCount
            )
            return
        }

        if ensureLayoutEnd {
            onLog?("layout-check ensure-end count=\(messageCount)")
            snapToEnd(
                messageCount: messageCount,
                scrollTargetID: scrollTargetID,
                autoFollowEnabled: autoFollowEnabled,
                reason: "layout-check",
                onLog: onLog
            )
            return
        }

        onLog?("layout-check update-armed count=\(messageCount) pinned=\(isPinnedToEnd(messageCount: messageCount))")
        autoFollowState.updatePinnedState(
            isPinnedToEnd: isPinnedToEnd(messageCount: messageCount),
            messageCount: messageCount
        )
    }

    func followLayoutChangeIfNeededAfterLayoutSettles(
        messageCount: Int,
        autoFollowEnabled: Bool,
        scrollTargetID: String? = nil,
        onLog: ((String) -> Void)? = nil
    ) {
        onLog?("layout-check scheduled count=\(messageCount)")
        Task { @MainActor in
            await Task.yield()
            await Task.yield()
            followLayoutChangeIfNeeded(
                messageCount: messageCount,
                autoFollowEnabled: autoFollowEnabled,
                scrollTargetID: scrollTargetID,
                onLog: onLog
            )
        }
    }
}

struct MessageListReversedTimelineStack<Item, ItemContent: View>: View {
    let items: [Item]
    let bottomAnchorID: String
    let itemID: KeyPath<Item, String>
    let onLastMessageContentSizeSettled: (() -> Void)?
    let itemContent: (Item, Bool, (() -> Void)?) -> ItemContent

    private var newestItemID: String? {
        items.last?[keyPath: itemID]
    }

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(items, id: itemID) { item in
                MessageListTimelineRow(
                    item: item,
                    itemID: itemID,
                    newestItemID: newestItemID,
                    onLastMessageContentSizeSettled: onLastMessageContentSizeSettled,
                    itemContent: itemContent
                )
            }

            Color.clear
                .frame(height: MessageListTimelineDefaults.bottomContentPadding)
                .accessibilityHidden(true)

            Color.clear
                .frame(height: MessageListTimelineDefaults.bottomAnchorHeight)
                .id(bottomAnchorID)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, MessageListTimelineDefaults.horizontalContentPadding)
        .padding(.top, MessageListTimelineDefaults.topContentPadding)
    }
}

private struct MessageListTimelineRow<Item, ItemContent: View>: View {
    let item: Item
    let itemID: KeyPath<Item, String>
    let newestItemID: String?
    let onLastMessageContentSizeSettled: (() -> Void)?
    let itemContent: (Item, Bool, (() -> Void)?) -> ItemContent

    private var isNewestMessage: Bool {
        item[keyPath: itemID] == newestItemID
    }

    var body: some View {
        itemContent(
            item,
            isNewestMessage,
            isNewestMessage ? onLastMessageContentSizeSettled : nil
        )
        .messageListTimelineItemSpacing(isNewestMessage: isNewestMessage)
    }
}

extension View {
    func messageListTimelineItemSpacing(isNewestMessage: Bool) -> some View {
        padding(.bottom, isNewestMessage ? 0 : MessageListTimelineDefaults.messageSpacing)
    }

    func messageListAnimateLastMessageSize(
        animateSizeChanges: Bool,
        onContentSizeAnimationFinished: (() -> Void)?
    ) -> some View {
        modifier(
            MessageListAnimateLastMessageSizeModifier(
                animateSizeChanges: animateSizeChanges,
                onContentSizeAnimationFinished: onContentSizeAnimationFinished
            )
        )
    }
}

private struct MessageListAnimateLastMessageSizeModifier: ViewModifier {
    let animateSizeChanges: Bool
    let onContentSizeAnimationFinished: (() -> Void)?

    @State private var contentHeight: CGFloat = 0
    @State private var settleTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        if animateSizeChanges {
            content
                .animation(.easeInOut(duration: 0.2), value: contentHeight)
                .background {
                    GeometryReader { geometry in
                        Color.clear
                            .onChange(of: geometry.size.height, initial: true) { _, height in
                                contentHeight = height
                                settleTask?.cancel()
                                settleTask = Task { @MainActor in
                                    try? await Task.sleep(for: .milliseconds(200))
                                    guard !Task.isCancelled else { return }
                                    onContentSizeAnimationFinished?()
                                }
                            }
                    }
                }
        } else {
            content
        }
    }
}

struct MessageListScrollToEndOverlay: View {
    let visible: Bool
    let onTap: () -> Void

    var body: some View {
        if visible {
            OpenCodeActionPill(variant: .scrollToEnd, action: onTap)
                .padding(.bottom, MessageListTimelineDefaults.scrollToEndPillBottomPadding)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
        }
    }
}

private struct MessageListTimelineScrollModifier: ViewModifier {
    @Bindable var timeline: MessageListTimelineState

    func body(content: Content) -> some View {
        content
            .scrollPosition(id: $timeline.scrollPositionID, anchor: .bottom)
            .scrollToEndScrollObserver(
                distanceFromBottom: $timeline.scrollDistanceFromBottom,
                isUserScrolling: $timeline.isUserScrolling,
                scrollPositionID: timeline.scrollPositionID,
                isProgrammaticScrolling: { timeline.isProgrammaticScrolling }
            )
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onChange(of: geometry.size.height, initial: true) { _, height in
                            timeline.viewportHeight = height
                        }
                }
            }
    }
}

private struct MessageListTimelineContentHeightModifier: ViewModifier {
    @Bindable var timeline: MessageListTimelineState

    func body(content: Content) -> some View {
        content
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onChange(of: geometry.size.height, initial: true) { _, height in
                            timeline.contentHeight = height
                        }
                }
            }
    }
}

extension View {
    func messageListTimelineScrollChrome(timeline: MessageListTimelineState) -> some View {
        modifier(MessageListTimelineScrollModifier(timeline: timeline))
    }

    func messageListTimelineContentHeight(timeline: MessageListTimelineState) -> some View {
        modifier(MessageListTimelineContentHeightModifier(timeline: timeline))
    }

    func messageListTimelineBehavior(
        timeline: MessageListTimelineState,
        messageCount: Int,
        firstMessageID: String?,
        lastMessageID: String?,
        autoFollowEnabled: Bool,
        layoutSignature: Int,
        onLog: ((String) -> Void)? = nil
    ) -> some View {
        let isPinnedToEnd = timeline.isPinnedToEnd(messageCount: messageCount)

        return self
            .onAppear {
                onLog?("appear count=\(messageCount) pinned=\(isPinnedToEnd)")
                timeline.reset(messageCount: messageCount)
            }
            .task(id: "\(firstMessageID ?? "")|\(lastMessageID ?? "")") {
                guard messageCount > 0 else { return }
                onLog?("initial-scroll scheduled count=\(messageCount)")
                await Task.yield()
                await Task.yield()
                timeline.snapToEnd(
                    messageCount: messageCount,
                    scrollTargetID: lastMessageID,
                    autoFollowEnabled: autoFollowEnabled,
                    reason: "initial-scroll",
                    onLog: onLog
                )
            }
            .onChange(of: isPinnedToEnd, initial: true) { _, pinnedToEnd in
                onLog?("pinned-change count=\(messageCount) pinned=\(pinnedToEnd) userScrolling=\(timeline.isUserScrolling)")
                if timeline.isUserScrolling || pinnedToEnd {
                    timeline.autoFollowState.updatePinnedState(
                        isPinnedToEnd: pinnedToEnd,
                        messageCount: messageCount
                    )
                } else {
                    timeline.followLayoutChangeIfNeeded(
                        messageCount: messageCount,
                        autoFollowEnabled: autoFollowEnabled,
                        scrollTargetID: lastMessageID,
                        onLog: onLog
                    )
                }
            }
            .onChange(of: timeline.contentHeight) { _, _ in
                onLog?("content-height count=\(messageCount) height=\(timeline.contentHeight) viewport=\(timeline.viewportHeight)")
                timeline.followLayoutChangeIfNeeded(
                    messageCount: messageCount,
                    autoFollowEnabled: autoFollowEnabled,
                    scrollTargetID: lastMessageID,
                    onLog: onLog
                )
            }
            .onChange(of: timeline.viewportHeight) { _, _ in
                onLog?("viewport-height count=\(messageCount) height=\(timeline.contentHeight) viewport=\(timeline.viewportHeight)")
                timeline.followLayoutChangeIfNeeded(
                    messageCount: messageCount,
                    autoFollowEnabled: autoFollowEnabled,
                    scrollTargetID: lastMessageID,
                    onLog: onLog
                )
            }
            .onChange(of: layoutSignature) { _, _ in
                onLog?("layout-signature changed count=\(messageCount) signature=\(layoutSignature)")
                timeline.followLayoutChangeIfNeededAfterLayoutSettles(
                    messageCount: messageCount,
                    autoFollowEnabled: autoFollowEnabled,
                    scrollTargetID: lastMessageID,
                    onLog: onLog
                )
            }
            .onChange(of: messageCount) { _, newCount in
                onLog?("message-count changed count=\(newCount)")
                guard timeline.autoFollowState.onMessageCountChanged(
                    messageCount: newCount,
                    autoFollowEnabled: autoFollowEnabled
                ) else { return }

                onLog?("message-count follow count=\(newCount)")
                timeline.snapToEnd(
                    messageCount: newCount,
                    scrollTargetID: lastMessageID,
                    autoFollowEnabled: autoFollowEnabled,
                    reason: "message-count",
                    onLog: onLog
                )
            }
    }
}
