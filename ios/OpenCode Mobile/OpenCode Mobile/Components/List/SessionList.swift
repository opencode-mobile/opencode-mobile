import SwiftUI

private let sessionListFallbackBottomSpacer: CGFloat = 168

enum SessionListStatus: Hashable {
    case idle
    case running
    case retrying(attempt: Int?)
    /// v1: a run finished while this device was away (local, per device).
    case ready
    /// v2: the latest run succeeded and nobody has viewed it yet (server unread).
    case done
    /// v2: the latest run failed and nobody has viewed it yet.
    case failed
    /// v2: the latest run was interrupted and nobody has viewed it yet.
    case stopped

    var isAttention: Bool {
        switch self {
        case .running, .retrying, .ready, .done, .failed, .stopped: true
        case .idle: false
        }
    }

    /// A finished run the user has not seen yet: Ready (v1) or Done / Failed / Stopped (v2).
    var isReady: Bool {
        switch self {
        case .ready, .done, .failed, .stopped: true
        case .idle, .running, .retrying: false
        }
    }

    func listItemStatus(isStale: Bool = false) -> OpenCodeListItemStatus {
        let base: OpenCodeListItemStatus
        switch self {
        case .idle:
            base = OpenCodeListItemStatus(text: "Idle", tone: .info)
        case .running:
            base = OpenCodeListItemStatus(text: "Running", tone: .interactive)
        case let .retrying(attempt):
            base = OpenCodeListItemStatus(
                text: attempt.map { "Retrying · \($0)" } ?? "Retrying",
                tone: .warning
            )
        case .ready:
            base = OpenCodeListItemStatus(text: "Ready", tone: .success)
        case .done:
            base = OpenCodeListItemStatus(text: "Done", tone: .success)
        case .failed:
            base = OpenCodeListItemStatus(text: "Failed", tone: .error)
        case .stopped:
            base = OpenCodeListItemStatus(text: "Stopped", tone: .warning)
        }
        if isStale {
            return OpenCodeListItemStatus(text: base.text, tone: .stale)
        }
        return base
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
    let statusIsStale: Bool
    let caption: String
    let updatedAtEpochMs: Int64?
    /// Directory the session runs in. `worktree` is the Home project key for filters and counts.
    let directory: String
    /// Worktree scope this row belongs to, when its project lists more than one worktree.
    let worktreeScopeID: String?
    /// Git project subtitle in the All scope (project · branch · folder). Nil keeps `subtitle`.
    let location: HomeRowLocation?
    /// Git project subtitle inside a project or worktree scope (branch · folder).
    let scopedLocation: HomeRowLocation?

    init(
        id: String,
        title: String,
        worktree: String,
        letter: Character,
        letterAccent: LetterAccent,
        subtitle: String,
        status: SessionListStatus,
        statusIsStale: Bool = false,
        caption: String,
        updatedAtEpochMs: Int64? = nil,
        directory: String? = nil,
        worktreeScopeID: String? = nil,
        location: HomeRowLocation? = nil,
        scopedLocation: HomeRowLocation? = nil
    ) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.letter = letter
        self.letterAccent = letterAccent
        self.subtitle = subtitle
        self.status = status
        self.statusIsStale = statusIsStale
        self.caption = caption
        self.updatedAtEpochMs = updatedAtEpochMs
        self.directory = directory ?? worktree
        self.worktreeScopeID = worktreeScopeID
        self.location = location
        self.scopedLocation = scopedLocation
    }

    func location(inAllScope: Bool) -> HomeRowLocation? {
        inAllScope ? location : scopedLocation
    }
}

/// A project scope whose first server load has not landed yet.
enum SessionListScopeLoad: Hashable {
    case loading(isSlow: Bool)
    case failed(message: String)

    static let loadingSubtitle = "Loading sessions\u{2026}"
    static let slowSubtitle = "Still loading\u{2026}"
    static let slowListCaption = "Still loading \u{2014} large projects can take a moment"
    static let failedListMessage = "The server didn\u{2019}t return this project\u{2019}s sessions."
    static let retryTitle = "Retry"

    /// Dock subtitle in place of the project path.
    var subtitle: String {
        switch self {
        case let .loading(isSlow): isSlow ? Self.slowSubtitle : Self.loadingSubtitle
        case let .failed(message): message
        }
    }

    var isLoading: Bool {
        if case .loading = self { return true }
        return false
    }

    static func failedListTitle(projectTitle: String) -> String {
        "Couldn\u{2019}t load \(projectTitle)"
    }
}

struct SessionListFilterTab: Identifiable, Hashable {
    static let allId = "all"
    static let allTitle = "All"
    static let pickerAllTitle = "All projects"

    let id: String
    let title: String
    let subtitle: String
    let count: Int
    let letter: Character?
    let accent: LetterAccent?
    /// Set while this project's sessions are still loading or failed to load.
    let load: SessionListScopeLoad?
    /// One worktree of a multi-worktree project: an indented branch row in the picker.
    let isWorktree: Bool
    /// Picker row text when it differs from the dock (worktree rows, project worktree counts).
    let pickerTitleOverride: String?
    let pickerSubtitleOverride: String?

    var isAll: Bool { id == Self.allId }
    var pickerTitle: String { pickerTitleOverride ?? (isAll ? Self.pickerAllTitle : title) }
    var pickerSubtitle: String { pickerSubtitleOverride ?? subtitle }
    /// The dock keeps the project identity on a worktree scope; the picker names the worktree.
    var dockTitle: String { isWorktree ? title : pickerTitle }

    init(
        id: String,
        title: String,
        count: Int,
        subtitle: String = "",
        letter: Character? = nil,
        accent: LetterAccent? = nil,
        load: SessionListScopeLoad? = nil,
        isWorktree: Bool = false,
        pickerTitle: String? = nil,
        pickerSubtitle: String? = nil
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.count = count
        self.letter = letter
        self.accent = accent
        self.load = load
        self.isWorktree = isWorktree
        self.pickerTitleOverride = pickerTitle
        self.pickerSubtitleOverride = pickerSubtitle
    }

    static func all(projectCount: Int, sessionCount: Int) -> SessionListFilterTab {
        SessionListFilterTab(
            id: allId,
            title: allTitle,
            count: sessionCount,
            subtitle: projectCount == 1 ? "1 project" : "\(projectCount) projects"
        )
    }

    /// A Home project. The dock reads "All N worktrees" (or the path for one worktree); the
    /// picker reads "N worktrees" (or the path).
    static func homeProject(
        _ project: HomeProject,
        count: Int,
        load: SessionListScopeLoad? = nil
    ) -> SessionListFilterTab {
        let many = project.hasManyWorktrees
        return SessionListFilterTab(
            id: HomeScope.project(project.key).id,
            title: project.displayName,
            count: count,
            subtitle: many ? HomeProjectCopy.allWorktrees(project.worktrees.count) : project.primaryPath,
            letter: letterCharacter(for: project.displayName),
            accent: letterAccent(for: project.primaryPath),
            load: load,
            pickerSubtitle: many ? HomeProjectCopy.worktreeCount(project.worktrees.count) : project.primaryPath
        )
    }

    /// One worktree of a multi-worktree project. The dock keeps the project identity and reads
    /// "branch · folder" ("branch" for the main checkout, the folder when the branch is unknown).
    static func homeWorktree(
        _ worktree: HomeWorktree,
        of project: HomeProject,
        count: Int
    ) -> SessionListFilterTab {
        let dockSubtitle: String
        if let branch = worktree.branch {
            dockSubtitle = worktree.isMain ? branch : branch + HomeRowLocation.separator + worktree.folderName
        } else {
            dockSubtitle = worktree.folderName
        }
        return SessionListFilterTab(
            id: HomeScope.worktree(projectKey: project.key, directory: worktree.directory).id,
            title: project.displayName,
            count: count,
            subtitle: dockSubtitle,
            letter: letterCharacter(for: project.displayName),
            accent: letterAccent(for: project.primaryPath),
            isWorktree: true,
            pickerTitle: worktree.title,
            pickerSubtitle: worktree.subtitle
        )
    }

    static func project(
        worktree: String,
        title: String,
        count: Int,
        load: SessionListScopeLoad? = nil
    ) -> SessionListFilterTab {
        SessionListFilterTab(
            id: worktree,
            title: title,
            count: count,
            subtitle: worktree,
            letter: letterCharacter(for: title),
            accent: letterAccent(for: worktree),
            load: load
        )
    }
}

struct SessionList<EmptyContent: View>: View {
    let sessions: [SessionListSession]
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    /// Session highlighted as the open trailing pane on tablets; nil on phones.
    let selectedSessionId: String?
    let createError: String?
    let onOpenProjectPicker: () -> Void
    let onFilterSelected: (String) -> Void
    let onSessionSelected: (SessionListSession) -> Void
    let onSessionLongPress: ((SessionListSession) -> Void)?
    let onAddProject: () -> Void
    let onNewSession: () -> Void
    let onNewSessionLongPress: (() -> Void)?
    /// Retry for a project scope whose load failed; receives the project worktree.
    let onRetryScopeLoad: ((String) -> Void)?
    let onRefresh: (() async -> Void)?
    let revealNewestToken: Int
    /// Change to scroll `selectedSessionId` into view (keyboard session navigation).
    let revealSelectedSessionToken: Int
    /// Rendered sessions under the selected filter, top to bottom, whenever the
    /// rendered order changes. Empty while nothing is rendered.
    let onDisplayedSessionsChange: (([SessionListSession]) -> Void)?
    let showsEmptyContent: Bool
    let emptyContent: EmptyContent

    @State private var bottomSafeAreaInset: CGFloat = 0
    @State private var dockHeight: CGFloat = 0
    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared
    @State private var followingTop = true
    @State private var incomingFilterId: String
    @State private var feedLayers: [SessionListFeedLayer]
    @State private var incomingOpacity: CGFloat = 1
    @State private var outgoingOpacity: CGFloat = 0
    @State private var listCrossfadeTask: Task<Void, Never>?

    init(
        sessions: [SessionListSession],
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        selectedSessionId: String? = nil,
        createError: String? = nil,
        onOpenProjectPicker: @escaping () -> Void = {},
        onFilterSelected: @escaping (String) -> Void = { _ in },
        onSessionSelected: @escaping (SessionListSession) -> Void,
        onSessionLongPress: ((SessionListSession) -> Void)? = nil,
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void,
        onNewSessionLongPress: (() -> Void)? = nil,
        onRetryScopeLoad: ((String) -> Void)? = nil,
        onRefresh: (() async -> Void)? = nil,
        revealNewestToken: Int = 0,
        revealSelectedSessionToken: Int = 0,
        onDisplayedSessionsChange: (([SessionListSession]) -> Void)? = nil,
        showsEmptyContent: Bool = false,
        @ViewBuilder emptyContent: () -> EmptyContent = { EmptyView() }
    ) {
        self.sessions = sessions
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.selectedSessionId = selectedSessionId
        self.createError = createError
        self.onOpenProjectPicker = onOpenProjectPicker
        self.onFilterSelected = onFilterSelected
        self.onSessionSelected = onSessionSelected
        self.onSessionLongPress = onSessionLongPress
        self.onAddProject = onAddProject
        self.onNewSession = onNewSession
        self.onNewSessionLongPress = onNewSessionLongPress
        self.onRetryScopeLoad = onRetryScopeLoad
        self.onRefresh = onRefresh
        self.revealNewestToken = revealNewestToken
        self.revealSelectedSessionToken = revealSelectedSessionToken
        self.onDisplayedSessionsChange = onDisplayedSessionsChange
        self.showsEmptyContent = showsEmptyContent
        self.emptyContent = emptyContent()
        _incomingFilterId = State(initialValue: selectedFilterId)
        _feedLayers = State(initialValue: [SessionListFeedLayer(id: selectedFilterId, isOutgoing: false)])
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ZStack {
                ForEach(feedLayers) { layer in
                    listBody(filterId: layer.id, isCurrent: !layer.isOutgoing)
                        .background(OpenCodeColors.backgroundBase)
                        .opacity(layer.isOutgoing ? outgoingOpacity : incomingOpacity)
                        .allowsHitTesting(!layer.isOutgoing)
                        .zIndex(layer.isOutgoing ? 1 : 0)
                }
            }
            .onChange(of: selectedFilterId) { _, newId in
                scheduleListCrossfade(to: newId)
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
                onFilterSelected: onFilterSelected,
                onAddProject: onAddProject,
                onNewSession: onNewSession,
                onNewSessionLongPress: onNewSessionLongPress
            )
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear { dockHeight = geometry.size.height }
                        .onChange(of: geometry.size.height) { _, height in
                            guard abs(dockHeight - height) > 0.5 else { return }
                            dockHeight = height
                        }
                }
            }
        }
        .openCodeReadableColumn()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .accessibilityIdentifier("homeContent")
        .onDisappear { onDisplayedSessionsChange?([]) }
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
    private func listBody(filterId: String, isCurrent: Bool) -> some View {
        let visible = sessions.visibleForFilter(filterId)
        let loadingTab = visible.isEmpty ? filterTabs.first { $0.id == filterId && $0.load != nil } : nil
        Group {
            if let loadingTab, let load = loadingTab.load {
                ScrollView {
                    scopeLoadContent(tab: loadingTab, load: load)
                        .padding(.bottom, max(dockHeight, sessionListFallbackBottomSpacer))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(OpenCodeColors.backgroundBase)
                .modifier(SessionListRefreshableModifier(onRefresh: isCurrent ? onRefresh : nil))
                .onAppear {
                    if isCurrent { onDisplayedSessionsChange?([]) }
                }
                .transition(.opacity)
            } else if visible.isEmpty, showsEmptyContent {
                ScrollView {
                    emptyContent
                        .frame(maxWidth: .infinity, minHeight: 420)
                        .padding(.bottom, max(dockHeight, sessionListFallbackBottomSpacer))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(OpenCodeColors.backgroundBase)
                .modifier(SessionListRefreshableModifier(onRefresh: isCurrent ? onRefresh : nil))
                .onAppear {
                    if isCurrent { onDisplayedSessionsChange?([]) }
                }
            } else {
                SessionListStabilizedScrollView(
                    visible: visible,
                    recentWindowHours: homeDisplayPreferences.recentWindowHours,
                    revealNewestToken: isCurrent ? revealNewestToken : 0,
                    revealSessionToken: isCurrent ? revealSelectedSessionToken : 0,
                    revealSessionID: selectedSessionId,
                    bottomObscuredHeight: dockHeight,
                    followingTop: isCurrent ? $followingTop : .constant(true),
                    onRefresh: isCurrent ? onRefresh : nil,
                    onDisplayedSessionsChange: isCurrent ? onDisplayedSessionsChange : nil
                ) { section in
                    switch section.kind {
                    case .recent:
                        recentStack(section, filterId: filterId)
                    case .day, .week:
                        dayOrWeekSection(section, filterId: filterId)
                    }
                }
                .id(filterId)
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: loadingTab?.load)
    }

    @ViewBuilder
    private func scopeLoadContent(tab: SessionListFilterTab, load: SessionListScopeLoad) -> some View {
        switch load {
        case let .loading(isSlow):
            VStack(alignment: .leading, spacing: 0) {
                OpenCodeSectionHeader(title: tab.title)
                OpenCodeSessionListSkeleton(
                    accent: tab.accent,
                    caption: isSlow ? SessionListScopeLoad.slowListCaption : nil
                )
            }
        case .failed:
            OpenCodeEmptyState(
                title: SessionListScopeLoad.failedListTitle(projectTitle: tab.title),
                message: SessionListScopeLoad.failedListMessage,
                fillsAvailableSpace: false,
                primaryAction: onRetryScopeLoad.map { retry in
                    OpenCodeEmptyStateAction(title: SessionListScopeLoad.retryTitle) { retry(tab.id) }
                }
            )
            .padding(.top, 64)
            .accessibilityIdentifier("sessionListScopeLoadFailed")
        }
    }

    @ViewBuilder
    private func recentStack(_ section: HomeSessionLadderSection<SessionListSession>, filterId: String) -> some View {
        OpenCodeSectionHeader(title: "Recent")

        ForEach(Array(section.items.enumerated()), id: \.element.id) { index, session in
            sessionRow(session, showStatusDot: true, filterId: filterId)
            if sessionListShowsDivider(after: index, in: section.items, selectedSessionId: selectedSessionId) {
                Divider()
                    .overlay(OpenCodeColors.borderWeakerBase)
                    .padding(.leading, 60)
            }
        }
    }

    @ViewBuilder
    private func dayOrWeekSection(_ section: HomeSessionLadderSection<SessionListSession>, filterId: String) -> some View {
        OpenCodeSectionHeader(title: section.title)

        ForEach(Array(section.items.enumerated()), id: \.element.id) { index, session in
            sessionRow(session, showStatusDot: false, filterId: filterId)
            if sessionListShowsDivider(after: index, in: section.items, selectedSessionId: selectedSessionId) {
                Divider()
                    .overlay(OpenCodeColors.borderWeakerBase)
                    .padding(.leading, 60)
            }
        }
    }

    private func sessionRow(_ session: SessionListSession, showStatusDot: Bool, filterId: String) -> some View {
        let location = session.location(inAllScope: filterId == SessionListFilterTab.allId)
        return OpenCodeListItem(
            title: session.title,
            subtitle: location == nil
                ? compactHomePath(
                    session.subtitle,
                    hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
                )
                : nil,
            subtitleSegments: location.map(Self.subtitleSegments),
            status: listItemStatus(session.status, isStale: session.statusIsStale),
            caption: session.caption.isEmpty ? nil : session.caption,
            selected: selectedSessionId == session.id,
            startDecorator: .letter(
                session.letter,
                session.letterAccent,
                statusDot: showStatusDot ? listItemStatus(session.status, isStale: session.statusIsStale).tone : nil
            ),
            action: { onSessionSelected(session) },
            onLongPress: onSessionLongPress.map { handler in { handler(session) } }
        )
        .background {
            if selectedSessionId == session.id {
                GeometryReader { geometry in
                    Color.clear.preference(
                        key: SessionListSelectedRowFrameKey.self,
                        value: geometry.frame(in: .named(sessionListViewportSpace))
                    )
                }
            }
        }
    }

    private func listItemStatus(_ status: SessionListStatus, isStale: Bool) -> OpenCodeListItemStatus {
        status.listItemStatus(isStale: isStale)
    }

    /// Project, branch (with the branch icon), and folder, joined with " · ".
    static func subtitleSegments(_ location: HomeRowLocation) -> [OpenCodeListItemSubtitleSegment] {
        var segments: [OpenCodeListItemSubtitleSegment] = []
        for (index, part) in location.parts.enumerated() {
            let prefix = index == 0 ? "" : HomeRowLocation.separator
            switch part {
            case let .text(text):
                segments.append(OpenCodeListItemSubtitleSegment(text: prefix + text, tone: .secondary))
            case let .branch(branch):
                if !prefix.isEmpty {
                    segments.append(OpenCodeListItemSubtitleSegment(text: prefix, tone: .secondary))
                }
                segments.append(OpenCodeListItemSubtitleSegment(text: branch, tone: .secondary, icon: .callSplit))
            }
        }
        return segments
    }

    private func scheduleListCrossfade(to newId: String) {
        guard incomingFilterId != newId else { return }
        followingTop = true
        listCrossfadeTask?.cancel()
        var snap = Transaction()
        snap.disablesAnimations = true
        withTransaction(snap) {
            outgoingOpacity = max(incomingOpacity, 0.001)
            incomingOpacity = 0
            feedLayers = [
                SessionListFeedLayer(id: newId, isOutgoing: false),
                SessionListFeedLayer(id: incomingFilterId, isOutgoing: true),
            ]
            incomingFilterId = newId
        }
        listCrossfadeTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(16))
            guard !Task.isCancelled else { return }
            var fade = Transaction(
                animation: .timingCurve(
                    0.42,
                    0.0,
                    0.58,
                    1.0,
                    duration: SessionListScopePaging.listCrossfadeDuration
                )
            )
            fade.disablesAnimations = false
            withTransaction(fade) {
                incomingOpacity = 1
                outgoingOpacity = 0
            }
            try? await Task.sleep(for: .seconds(SessionListScopePaging.listCrossfadeDuration))
            guard !Task.isCancelled else { return }
            feedLayers = [SessionListFeedLayer(id: newId, isOutgoing: false)]
            outgoingOpacity = 0
        }
    }
}

private struct SessionListFeedLayer: Identifiable, Equatable {
    let id: String
    let isOutgoing: Bool
}

private let sessionListTopAnchorID = "session-list-top"
private let sessionListViewportSpace = "session-list-viewport"

/// Frame of the selected row in the list viewport; nil while it is not laid out.
private struct SessionListSelectedRowFrameKey: PreferenceKey {
    static let defaultValue: CGRect? = nil

    static func reduce(value: inout CGRect?, nextValue: () -> CGRect?) {
        value = nextValue() ?? value
    }
}

/// Scrolling a keyboard-selected row into view without jumping when it is
/// already visible.
nonisolated enum SessionListReveal {
    /// `scrollTo` anchor for the selected row, or nil when it is fully visible above
    /// the bottom dock. A row above the viewport aligns to the top; one below (or
    /// under the dock) lands just above the dock; one not laid out yet is centered.
    static func anchor(rowFrame: CGRect?, viewportHeight: CGFloat, bottomObscuredHeight: CGFloat) -> UnitPoint? {
        guard viewportHeight > 0 else { return nil }
        guard let rowFrame else { return .center }
        let visibleBottom = viewportHeight - max(0, bottomObscuredHeight)
        if rowFrame.minY < 0 {
            return .top
        }
        guard rowFrame.maxY > visibleBottom else { return nil }
        // `scrollTo` lines up the same unit point of row and viewport. Pick the
        // point that puts the row's bottom edge on `visibleBottom`.
        let travel = viewportHeight - rowFrame.height
        guard travel > 0 else { return .top }
        let y = (visibleBottom - rowFrame.height) / travel
        return UnitPoint(x: 0.5, y: min(1, max(0, y)))
    }
}

enum SessionListStabilizedOrder {
    struct Snapshot: Equatable {
        var sections: [HomeSessionLadderSection<SessionListSession>]
        var lastAppliedAtEpochMs: Int64?
        var nextApplyAtEpochMs: Int64?
        var newcomerId: String?
        var seenIds: Set<String>
    }

    static func apply(
        visible: [SessionListSession],
        recentWindowHours: Int,
        previousSections: [HomeSessionLadderSection<SessionListSession>],
        previousLastAppliedAtEpochMs: Int64?,
        previousSeenIds: Set<String>,
        nowEpochMs: Int64
    ) -> Snapshot {
        let desired = HomeSessionLadder.group(
            sessions: visible,
            updatedAtEpochMs: \.updatedAtEpochMs,
            isAttention: \.status.isAttention,
            recentWindowHours: recentWindowHours,
            nowEpochMs: nowEpochMs
        )
        let result = HomeSessionOrderStabilizer.stabilize(
            previousSections: previousSections,
            previousLastAppliedAtEpochMs: previousLastAppliedAtEpochMs,
            desired: desired,
            itemId: \.id,
            isAttention: \.status.isAttention,
            isReady: \.status.isReady,
            nowEpochMs: nowEpochMs
        )
        let displayedIds = result.sections.flatMap { $0.items.map(\.id) }
        var seenIds = previousSeenIds
        let newcomerId = displayedIds.first { !seenIds.contains($0) }
        seenIds.formUnion(displayedIds)
        return Snapshot(
            sections: result.sections,
            lastAppliedAtEpochMs: result.lastAppliedAtEpochMs,
            nextApplyAtEpochMs: result.nextApplyAtEpochMs,
            newcomerId: newcomerId,
            seenIds: seenIds
        )
    }
}

private struct SessionListFlushTaskID: Hashable {
    let deadlineEpochMs: Int64
    let visible: [SessionListSession]
    let recentWindowHours: Int
}

private struct SessionListStabilizedScrollView<SectionContent: View>: View {
    let visible: [SessionListSession]
    let recentWindowHours: Int
    let revealNewestToken: Int
    let revealSessionToken: Int
    let revealSessionID: String?
    let bottomObscuredHeight: CGFloat
    @Binding var followingTop: Bool
    let onRefresh: (() async -> Void)?
    let onDisplayedSessionsChange: (([SessionListSession]) -> Void)?
    let sectionContent: (HomeSessionLadderSection<SessionListSession>) -> SectionContent

    @State private var previousSections: [HomeSessionLadderSection<SessionListSession>]
    @State private var lastAppliedAtEpochMs: Int64?
    @State private var displayedSections: [HomeSessionLadderSection<SessionListSession>]
    @State private var nextApplyAtEpochMs: Int64?
    @State private var newcomerId: String?
    @State private var seenIds: Set<String>

    init(
        visible: [SessionListSession],
        recentWindowHours: Int,
        revealNewestToken: Int,
        revealSessionToken: Int,
        revealSessionID: String?,
        bottomObscuredHeight: CGFloat,
        followingTop: Binding<Bool>,
        onRefresh: (() async -> Void)?,
        onDisplayedSessionsChange: (([SessionListSession]) -> Void)?,
        @ViewBuilder sectionContent: @escaping (HomeSessionLadderSection<SessionListSession>) -> SectionContent
    ) {
        self.visible = visible
        self.recentWindowHours = recentWindowHours
        self.revealNewestToken = revealNewestToken
        self.revealSessionToken = revealSessionToken
        self.revealSessionID = revealSessionID
        self.bottomObscuredHeight = bottomObscuredHeight
        _followingTop = followingTop
        self.onRefresh = onRefresh
        self.onDisplayedSessionsChange = onDisplayedSessionsChange
        self.sectionContent = sectionContent
        let seeded = SessionListStabilizedOrder.apply(
            visible: visible,
            recentWindowHours: recentWindowHours,
            previousSections: [],
            previousLastAppliedAtEpochMs: nil,
            previousSeenIds: [],
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        _displayedSections = State(initialValue: seeded.sections)
        _previousSections = State(initialValue: seeded.sections)
        _lastAppliedAtEpochMs = State(initialValue: seeded.lastAppliedAtEpochMs)
        _nextApplyAtEpochMs = State(initialValue: seeded.nextApplyAtEpochMs)
        _newcomerId = State(initialValue: seeded.newcomerId)
        _seenIds = State(initialValue: seeded.seenIds)
    }

    var body: some View {
        SessionListScrollView(
            ladderSections: displayedSections,
            newestSessionId: newcomerId,
            revealNewestToken: revealNewestToken,
            revealSessionToken: revealSessionToken,
            revealSessionID: revealSessionID,
            bottomObscuredHeight: bottomObscuredHeight,
            followingTop: $followingTop,
            onRefresh: onRefresh,
            sectionContent: sectionContent
        )
        .onAppear { ingest() }
        .onChange(of: visible) { _, _ in ingest() }
        .onChange(of: recentWindowHours) { _, _ in ingest() }
        .task(id: SessionListFlushTaskID(
            deadlineEpochMs: nextApplyAtEpochMs ?? 0,
            visible: visible,
            recentWindowHours: recentWindowHours
        )) {
            guard let nextApplyAtEpochMs else { return }
            let wait = nextApplyAtEpochMs - Int64(Date().timeIntervalSince1970 * 1000)
            if wait > 0 {
                try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
            }
            guard !Task.isCancelled else { return }
            ingest()
        }
    }

    private func ingest() {
        let snapshot = SessionListStabilizedOrder.apply(
            visible: visible,
            recentWindowHours: recentWindowHours,
            previousSections: previousSections,
            previousLastAppliedAtEpochMs: lastAppliedAtEpochMs,
            previousSeenIds: seenIds,
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        displayedSections = snapshot.sections
        previousSections = snapshot.sections
        lastAppliedAtEpochMs = snapshot.lastAppliedAtEpochMs
        nextApplyAtEpochMs = snapshot.nextApplyAtEpochMs
        newcomerId = snapshot.newcomerId
        seenIds = snapshot.seenIds
        onDisplayedSessionsChange?(snapshot.sections.flatMap(\.items))
    }
}

private struct SessionListScrollView<SectionContent: View>: View {
    let ladderSections: [HomeSessionLadderSection<SessionListSession>]
    let newestSessionId: String?
    let revealNewestToken: Int
    let revealSessionToken: Int
    let revealSessionID: String?
    let bottomObscuredHeight: CGFloat
    @Binding var followingTop: Bool
    let onRefresh: (() async -> Void)?
    let sectionContent: (HomeSessionLadderSection<SessionListSession>) -> SectionContent

    @State private var viewportHeight: CGFloat = 0
    @State private var selectedRowFrame: CGRect?

    init(
        ladderSections: [HomeSessionLadderSection<SessionListSession>],
        newestSessionId: String?,
        revealNewestToken: Int,
        revealSessionToken: Int,
        revealSessionID: String?,
        bottomObscuredHeight: CGFloat,
        followingTop: Binding<Bool>,
        onRefresh: (() async -> Void)?,
        @ViewBuilder sectionContent: @escaping (HomeSessionLadderSection<SessionListSession>) -> SectionContent
    ) {
        self.ladderSections = ladderSections
        self.newestSessionId = newestSessionId
        self.revealNewestToken = revealNewestToken
        self.revealSessionToken = revealSessionToken
        self.revealSessionID = revealSessionID
        self.bottomObscuredHeight = bottomObscuredHeight
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
            .coordinateSpace(name: sessionListViewportSpace)
            .onPreferenceChange(SessionListSelectedRowFrameKey.self) { frame in
                selectedRowFrame = frame
            }
            .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { height in
                viewportHeight = height
            }
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
            .onChange(of: revealSessionToken) { _, token in
                guard token > 0, let revealSessionID else { return }
                // Away from the top, stop following it so a newcomer does not scroll
                // the selected row back out of view.
                if ladderSections.first?.items.first?.id != revealSessionID {
                    followingTop = false
                }
                // Let the newly selected row report its frame first.
                Task { @MainActor in
                    await Task.yield()
                    guard let anchor = SessionListReveal.anchor(
                        rowFrame: selectedRowFrame,
                        viewportHeight: viewportHeight,
                        bottomObscuredHeight: bottomObscuredHeight
                    ) else { return }
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(revealSessionID, anchor: anchor)
                    }
                }
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
        return filter { $0.worktree == selectedFilterId || $0.worktreeScopeID == selectedFilterId }
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
        onSessionSelected: { _ in },
        onAddProject: {},
        onNewSession: {}
    )
    .openCodeTheme()
}
#endif

/// Whether the hairline divider after row `index` is drawn. Skipped after the last
/// row and on either side of the selected row so it never butts against the pill.
func sessionListShowsDivider(
    after index: Int,
    in items: [SessionListSession],
    selectedSessionId: String?
) -> Bool {
    guard index >= 0, index < items.count - 1 else { return false }
    guard let selectedSessionId else { return true }
    return items[index].id != selectedSessionId && items[index + 1].id != selectedSessionId
}
