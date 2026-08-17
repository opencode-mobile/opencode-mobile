import SwiftUI

private let sessionListFallbackBottomSpacer: CGFloat = 168

enum SessionListStatus: Hashable {
    case idle
    case running
    case ready

    var isAttention: Bool {
        switch self {
        case .running, .ready: true
        case .idle: false
        }
    }
}

struct SessionListSession: Identifiable, Hashable {
    let id: String
    let title: String
    let worktree: String
    let letter: Character
    let letterAccent: LetterAccent
    let subtitle: String
    let status: SessionListStatus
    let caption: String
    let updatedAtEpochMs: Int64?

    init(
        id: String,
        title: String,
        worktree: String,
        letter: Character,
        letterAccent: LetterAccent,
        subtitle: String,
        status: SessionListStatus,
        caption: String,
        updatedAtEpochMs: Int64? = nil
    ) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.letter = letter
        self.letterAccent = letterAccent
        self.subtitle = subtitle
        self.status = status
        self.caption = caption
        self.updatedAtEpochMs = updatedAtEpochMs
    }
}

struct SessionListFilterTab: Identifiable, Hashable {
    static let allId = "all"
    static let allTitle = "All projects"

    let id: String
    let title: String
    let subtitle: String
    let count: Int
    let letter: Character?
    let accent: LetterAccent?

    var isAll: Bool { id == Self.allId }

    init(
        id: String,
        title: String,
        count: Int,
        subtitle: String = "",
        letter: Character? = nil,
        accent: LetterAccent? = nil
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.count = count
        self.letter = letter
        self.accent = accent
    }

    static func all(projectCount: Int, sessionCount: Int) -> SessionListFilterTab {
        SessionListFilterTab(
            id: allId,
            title: allTitle,
            count: sessionCount,
            subtitle: projectCount == 1 ? "1 project" : "\(projectCount) projects"
        )
    }

    static func project(worktree: String, title: String, count: Int) -> SessionListFilterTab {
        SessionListFilterTab(
            id: worktree,
            title: title,
            count: count,
            subtitle: worktree,
            letter: letterCharacter(for: title),
            accent: letterAccent(for: worktree)
        )
    }
}

enum SessionListProjectPickerMatching {
    static func tabs(_ tabs: [SessionListFilterTab], query: String) -> [SessionListFilterTab] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return tabs }
        return tabs.filter { tab in
            tab.title.lowercased().contains(needle)
                || tab.id.lowercased().contains(needle)
                || tab.subtitle.lowercased().contains(needle)
        }
    }
}

struct SessionList<EmptyContent: View>: View {
    let sessions: [SessionListSession]
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let onOpenProjectPicker: () -> Void
    let onSessionSelected: (SessionListSession) -> Void
    let onSessionLongPress: ((SessionListSession) -> Void)?
    let onAddProject: () -> Void
    let onNewSession: () -> Void
    let onRefresh: (() async -> Void)?
    let revealNewestToken: Int
    let showsEmptyContent: Bool
    let emptyContent: EmptyContent

    @State private var bottomSafeAreaInset: CGFloat = 0
    @State private var dockHeight: CGFloat = 0
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @State private var followingTop = true

    init(
        sessions: [SessionListSession],
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        createError: String? = nil,
        onOpenProjectPicker: @escaping () -> Void,
        onSessionSelected: @escaping (SessionListSession) -> Void,
        onSessionLongPress: ((SessionListSession) -> Void)? = nil,
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void,
        onRefresh: (() async -> Void)? = nil,
        revealNewestToken: Int = 0,
        showsEmptyContent: Bool = false,
        @ViewBuilder emptyContent: () -> EmptyContent = { EmptyView() }
    ) {
        self.sessions = sessions
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.createError = createError
        self.onOpenProjectPicker = onOpenProjectPicker
        self.onSessionSelected = onSessionSelected
        self.onSessionLongPress = onSessionLongPress
        self.onAddProject = onAddProject
        self.onNewSession = onNewSession
        self.onRefresh = onRefresh
        self.revealNewestToken = revealNewestToken
        self.showsEmptyContent = showsEmptyContent
        self.emptyContent = emptyContent()
    }

    private var visibleSessions: [SessionListSession] {
        sessions.visibleForFilter(selectedFilterId)
    }

    private var ladderSections: [HomeSessionLadderSection<SessionListSession>] {
        HomeSessionLadder.group(
            sessions: visibleSessions,
            updatedAtEpochMs: \.updatedAtEpochMs,
            isAttention: \.status.isAttention,
            recentWindowHours: homeDisplayPreferences.recentWindowHours,
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if visibleSessions.isEmpty, showsEmptyContent {
                ScrollView {
                    emptyContent
                        .frame(maxWidth: .infinity, minHeight: 420)
                        .padding(.bottom, max(dockHeight, sessionListFallbackBottomSpacer))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(OpenCodeColors.backgroundBase)
                .modifier(SessionListRefreshableModifier(onRefresh: onRefresh))
            } else {
                SessionListScrollView(
                    ladderSections: ladderSections,
                    newestSessionId: visibleSessions.first?.id,
                    revealNewestToken: revealNewestToken,
                    followingTop: $followingTop,
                    onRefresh: onRefresh
                ) { section in
                    switch section.kind {
                    case .recent:
                        recentStack(section)
                    case .day, .week:
                        dayOrWeekSection(section)
                    }
                }
            }

            OpenCodeBottomFade(
                height: max(dockHeight + OpenCodeBottomFade.overhang, OpenCodeBottomFade.overhang)
                    + bottomSafeAreaInset
            )
            .offset(y: bottomSafeAreaInset)
            .ignoresSafeArea(edges: .bottom)

            OpenCodeSessionListDock(
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                createError: createError,
                onOpenProjectPicker: onOpenProjectPicker,
                onAddProject: onAddProject,
                onNewSession: onNewSession
            )
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear { dockHeight = geometry.size.height }
                        .onChange(of: geometry.size.height) { _, height in
                            dockHeight = height
                        }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { bottomSafeAreaInset = geometry.safeAreaInsets.bottom }
                    .onChange(of: geometry.safeAreaInsets.bottom) { _, inset in
                        bottomSafeAreaInset = inset
                    }
            }
        }
    }

    @ViewBuilder
    private func recentStack(_ section: HomeSessionLadderSection<SessionListSession>) -> some View {
        Text("RECENT")
            .font(OpenCodeTypography.caption.weight(.semibold))
            .foregroundStyle(OpenCodeColors.textBase)
            .tracking(0.5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 6)

        ForEach(Array(section.items.enumerated()), id: \.element.id) { index, session in
            sessionRow(session, showStatusDot: true)
            if index < section.items.count - 1 {
                Divider()
                    .overlay(OpenCodeColors.borderWeakerBase)
                    .padding(.leading, 60)
            }
        }
    }

    @ViewBuilder
    private func dayOrWeekSection(_ section: HomeSessionLadderSection<SessionListSession>) -> some View {
        Text(section.title.uppercased())
            .font(OpenCodeTypography.caption.weight(.semibold))
            .foregroundStyle(OpenCodeColors.textBase)
            .tracking(0.5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 6)

        ForEach(Array(section.items.enumerated()), id: \.element.id) { index, session in
            sessionRow(session, showStatusDot: false)
            if index < section.items.count - 1 {
                Divider()
                    .overlay(OpenCodeColors.borderWeakerBase)
                    .padding(.leading, 60)
            }
        }
    }

    private func sessionRow(_ session: SessionListSession, showStatusDot: Bool) -> some View {
        OpenCodeListItem(
            title: session.title,
            subtitle: compactHomePath(
                session.subtitle,
                hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
            ),
            status: listItemStatus(session.status),
            caption: session.caption.isEmpty ? nil : session.caption,
            startDecorator: .letter(
                session.letter,
                session.letterAccent,
                statusDot: showStatusDot ? listItemStatus(session.status).tone : nil
            ),
            action: { onSessionSelected(session) },
            onLongPress: onSessionLongPress.map { handler in { handler(session) } }
        )
    }

    private func listItemStatus(_ status: SessionListStatus) -> OpenCodeListItemStatus {
        switch status {
        case .idle:
            OpenCodeListItemStatus(text: "Idle", tone: .info)
        case .running:
            OpenCodeListItemStatus(text: "Running", tone: .interactive)
        case .ready:
            OpenCodeListItemStatus(text: "Ready", tone: .success)
        }
    }
}

private let sessionListTopAnchorID = "session-list-top"

private struct SessionListScrollView<SectionContent: View>: View {
    let ladderSections: [HomeSessionLadderSection<SessionListSession>]
    let newestSessionId: String?
    let revealNewestToken: Int
    @Binding var followingTop: Bool
    let onRefresh: (() async -> Void)?
    let sectionContent: (HomeSessionLadderSection<SessionListSession>) -> SectionContent

    init(
        ladderSections: [HomeSessionLadderSection<SessionListSession>],
        newestSessionId: String?,
        revealNewestToken: Int,
        followingTop: Binding<Bool>,
        onRefresh: (() async -> Void)?,
        @ViewBuilder sectionContent: @escaping (HomeSessionLadderSection<SessionListSession>) -> SectionContent
    ) {
        self.ladderSections = ladderSections
        self.newestSessionId = newestSessionId
        self.revealNewestToken = revealNewestToken
        _followingTop = followingTop
        self.onRefresh = onRefresh
        self.sectionContent = sectionContent
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: []) {
                    Color.clear
                        .frame(height: 0)
                        .id(sessionListTopAnchorID)
                    ForEach(ladderSections, id: \.id) { section in
                        sectionContent(section)
                    }
                }
                Color.clear.frame(height: sessionListFallbackBottomSpacer)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)
            .overlay {
                SessionListScrollOffsetReader { offsetFromTop, isUserScrolling in
                    let next = SessionListScrollPin.followingTop(
                        currentlyFollowing: followingTop,
                        isUserScrolling: isUserScrolling,
                        isAtTop: SessionListScrollPin.isAtTop(offsetFromTop: offsetFromTop)
                    )
                    if next != followingTop {
                        followingTop = next
                    }
                }
                .frame(width: 0, height: 0)
                .allowsHitTesting(false)
            }
            .modifier(SessionListRefreshableModifier(onRefresh: onRefresh))
            .onChange(of: newestSessionId) { _, _ in
                pinToTopIfNeeded(
                    proxy: proxy,
                    newestIdentityChanged: true,
                    forceRevealNewest: false
                )
            }
            .onChange(of: revealNewestToken) { _, token in
                guard token > 0 else { return }
                followingTop = true
                pinToTopIfNeeded(
                    proxy: proxy,
                    newestIdentityChanged: false,
                    forceRevealNewest: true
                )
            }
        }
    }

    private func pinToTopIfNeeded(
        proxy: ScrollViewProxy,
        newestIdentityChanged: Bool,
        forceRevealNewest: Bool
    ) {
        guard SessionListScrollPin.shouldScrollToTrueTop(
            followingTop: followingTop,
            newestIdentityChanged: newestIdentityChanged,
            forceRevealNewest: forceRevealNewest
        ) else { return }
        proxy.scrollTo(sessionListTopAnchorID, anchor: .top)
    }
}

func compactHomePath(_ path: String, hideUserDirectoryPrefix: Bool) -> String {
    let home = NSHomeDirectory()
    if path == home { return "~" }
    if path.hasPrefix(home + "/") {
        return "~" + path.dropFirst(home.count)
    }
    guard hideUserDirectoryPrefix else { return path }
    if let remoteHome = remoteUnixHomePrefix(path) {
        if path == remoteHome { return "~" }
        if path.hasPrefix(remoteHome + "/") {
            return "~" + path.dropFirst(remoteHome.count)
        }
    }
    return path
}

/// `/Users/<name>` or `/home/<name>` prefix when the path is a remote server worktree.
private func remoteUnixHomePrefix(_ path: String) -> String? {
    let parts = path.split(separator: "/", maxSplits: 3, omittingEmptySubsequences: false)
    guard parts.count >= 3, parts[0].isEmpty else { return nil }
    let root = parts[1]
    guard root == "Users" || root == "home" else { return nil }
    let user = parts[2]
    guard !user.isEmpty else { return nil }
    return "/\(root)/\(user)"
}

extension Array where Element == SessionListSession {
    func visibleForFilter(_ selectedFilterId: String) -> [SessionListSession] {
        if selectedFilterId == SessionListFilterTab.allId {
            return self
        }
        return filter { $0.worktree == selectedFilterId }
    }
}

private struct SessionListRefreshableModifier: ViewModifier {
    let onRefresh: (() async -> Void)?

    func body(content: Content) -> some View {
        if let onRefresh {
            content.refreshable { await onRefresh() }
        } else {
            content
        }
    }
}

#if DEBUG
#Preview {
    SessionList(
        sessions: [
            SessionListSession(
                id: "1",
                title: "Refactor Navigation",
                worktree: "/Users/demo/opencode-mobile",
                letter: "O",
                letterAccent: .violet,
                subtitle: "/Users/demo/opencode-mobile",
                status: .running,
                caption: "now",
                updatedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
            ),
            SessionListSession(
                id: "2",
                title: "Bug: Header Clipping",
                worktree: "/Users/demo/playground",
                letter: "P",
                letterAccent: .teal,
                subtitle: "/Users/demo/playground",
                status: .idle,
                caption: "14m ago",
                updatedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000) - 14 * 60_000
            ),
        ],
        filterTabs: [
            .all(projectCount: 2, sessionCount: 2),
            .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 1),
            .project(worktree: "/Users/demo/playground", title: "playground", count: 1),
        ],
        selectedFilterId: SessionListFilterTab.allId,
        onOpenProjectPicker: {},
        onSessionSelected: { _ in },
        onAddProject: {},
        onNewSession: {}
    )
    .openCodeTheme()
}
#endif
