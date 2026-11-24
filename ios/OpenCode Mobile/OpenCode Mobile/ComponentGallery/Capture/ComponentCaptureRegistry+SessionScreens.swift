#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var sessionScreenEntries: [ComponentCaptureEntry] {
        [
            sessionListEntry, sessionListSkeletonEntry, sessionListDockEntry, dictationCardEntry, dropOverlayEntry,
            sessionBranchPillEntry, sessionStatusBarEntry, backgroundWorkPillEntry, sessionTodoItemEntry,
        ]
    }
}

private let captureSessionNowMs = Int64(Date().timeIntervalSince1970 * 1000)
private let mobileWorktree = "/Users/demo/opencode-mobile"

// MARK: - Session list, skeleton, dock

private extension ComponentCaptureRegistry {
    static func sessionList(
        rowStyle: HomeSessionRowStyle = .compact,
        selected: String = SessionListFilterTab.allId,
        load: SessionListScopeLoad? = nil,
        empty: Bool = false
    ) -> some View {
        let tabs = sessionListGalleryFilterTabs.map { tab in
            guard let load, tab.id == selected else { return tab }
            return SessionListFilterTab.project(worktree: tab.id, title: tab.title, load: load)
        }
        let sessions = selected == SessionListFilterTab.allId
            ? sessionListGallerySamples
            : sessionListGallerySamples.filter { $0.worktree == selected }
        return SessionList(
            sessions: empty || load != nil ? [] : sessions,
            filterTabs: tabs,
            selectedFilterId: selected,
            onSessionSelected: { _ in },
            onAddProject: {},
            onNewSession: {},
            rowStyle: rowStyle
        )
    }

    static var sessionListEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "session-list", states: [
            ComponentCaptureState("compact", height: 560) { sessionList() },
            ComponentCaptureState("classic", height: 560) { sessionList(rowStyle: .classic) },
            ComponentCaptureState("project-filter", height: 560) { sessionList(selected: mobileWorktree) },
            ComponentCaptureState(
                "loading",
                height: 560,
                note: "Native loading is per project: opencode-mobile is the scope that loads"
            ) { sessionList(selected: mobileWorktree, load: .loading(isSlow: false)) },
            ComponentCaptureState("empty", height: 560) { sessionList(selected: mobileWorktree, empty: true) },
        ])
    }

    static var sessionListSkeletonEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "session-list-skeleton", states: [
            ComponentCaptureState("list") { OpenCodeSessionListSkeleton(rowCount: 6, accent: .teal) },
            ComponentCaptureState("row") { OpenCodeSessionRowSkeleton(accent: .violet) },
            ComponentCaptureState("3-rows") { OpenCodeSessionListSkeleton(rowCount: 3, accent: .teal) },
            ComponentCaptureState("neutral") { OpenCodeSessionListSkeleton(rowCount: 6, accent: nil) },
            ComponentCaptureState("slow-caption") {
                OpenCodeSessionListSkeleton(rowCount: 3, accent: .teal, caption: SessionListScopeLoad.slowListCaption)
            },
        ])
    }

    static func dock(
        tabs: [SessionListFilterTab] = sessionListDockGalleryFilterTabs,
        selected: String = SessionListFilterTab.allId,
        load: SessionListScopeLoad? = nil,
        createError: Bool = false
    ) -> some View {
        let tabs = tabs.map { tab in
            guard let load, tab.id == selected else { return tab }
            return SessionListFilterTab.project(worktree: tab.id, title: tab.title, load: load)
        }
        return OpenCodeSessionListDock(
            filterTabs: tabs,
            selectedFilterId: selected,
            createError: createError ? "Could not create session. Try again." : nil,
            onOpenProjectPicker: {},
            onFilterSelected: { _ in },
            onAddProject: {},
            onNewSession: {},
            onNewSessionLongPress: tabs.count > 1 ? {} : nil
        )
    }

    static var sessionListDockEntry: ComponentCaptureEntry {
        let api = "/Users/demo/api-client"
        return ComponentCaptureEntry(componentID: "session-list-dock", states: [
            ComponentCaptureState("all-projects") { dock() },
            ComponentCaptureState("project") { dock(selected: mobileWorktree) },
            ComponentCaptureState("one-project") {
                dock(tabs: sessionListDockGalleryOneProjectFilterTabs, selected: mobileWorktree)
            },
            ComponentCaptureState("no-projects") { dock(tabs: [], selected: SessionListFilterTab.allId) },
            ComponentCaptureState("loading") { dock(selected: api, load: .loading(isSlow: false)) },
            ComponentCaptureState("slow") { dock(selected: api, load: .loading(isSlow: true)) },
            ComponentCaptureState("load-failed") { dock(selected: api, load: .failed(message: "Couldn\u{2019}t load sessions")) },
            ComponentCaptureState("create-error") { dock(createError: true) },
        ])
    }
}

// MARK: - Dictation, drop overlay, pills, status bar, todo

private extension ComponentCaptureRegistry {
    static func dictation(_ sample: DictationCardGalleryView.Sample) -> some View {
        OpenCodeDictationCard(
            variant: sample.variant(level: 0.4),
            committed: sample.showsLevel ? "Hello" : "",
            volatileText: sample == .listening ? " there" : "",
            level: 0.4,
            status: "Listening · English · on-device",
            elapsed: "0:12",
            languageLabel: "English",
            onCancel: {},
            onInsert: {},
            onUnavailableAction: {}
        )
    }

    static var dictationCardEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "dictation-card",
            states: [
                ComponentCaptureState("listening") { dictation(.listening) },
                ComponentCaptureState("finishing") { dictation(.finishing) },
                ComponentCaptureState("permission-denied") { dictation(.permissionDenied) },
                ComponentCaptureState("model-not-installed") { dictation(.modelNotInstalled) },
                ComponentCaptureState("downloading") { dictation(.modelDownloading) },
                ComponentCaptureState("download-starting") { dictation(.modelDownloadStarting) },
                ComponentCaptureState("download-failed") { dictation(.modelDownloadFailed) },
                ComponentCaptureState("recognizer-error") { dictation(.recognizerError) },
            ],
            skipped: [
                ComponentCaptureSkip(id: "stopped-android", reason: "Android only"),
                ComponentCaptureSkip(id: "queued-android", reason: "Android only"),
            ]
        )
    }

    static var dropOverlayEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "drop-overlay", states: [
            ComponentCaptureState("accept-images", height: 320) { DropOverlayGalleryView.acceptImages() },
            ComponentCaptureState("accept-mixed", height: 320) { DropOverlayGalleryView.acceptMixed() },
            ComponentCaptureState("reject", height: 320) { DropOverlayGalleryView.reject() },
        ])
    }

    static func branchPill(_ sample: SessionBranchPillPreviewCase) -> some View {
        OpenCodeSessionBranchPill(summary: sample.summary, onViewAllChanges: {})
    }

    static var sessionBranchPillEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "session-branch-pill", states: [
            ComponentCaptureState("changes") { branchPill(.changes) },
            ComponentCaptureState("no-changes") { branchPill(.noChanges) },
            ComponentCaptureState("worktree") { branchPill(.worktree) },
            ComponentCaptureState("many-files") { branchPill(.manyFiles) },
            ComponentCaptureState("popover", note: "The pill above its popover card, drawn in place") {
                VStack(alignment: .leading, spacing: OpenCodeAnchoredCardLayout.gap) {
                    branchPill(.changes)
                    SessionBranchPillGalleryView.popover(summary: SessionBranchPillPreviewCase.changes.summary)
                }
            },
        ])
    }

    static var sessionStatusBarEntry: ComponentCaptureEntry {
        func bar(_ fraction: Double?) -> some View {
            OpenCodeSessionStatusBar(contextUsageFraction: fraction, onContextClick: {})
        }
        return ComponentCaptureEntry(componentID: "session-status-bar", states: [
            ComponentCaptureState("0") { bar(0) },
            ComponentCaptureState("50") { bar(0.5) },
            ComponentCaptureState("70-warning") { bar(0.7) },
            ComponentCaptureState("85-error") { bar(0.85) },
            ComponentCaptureState("unavailable") { bar(nil) },
        ])
    }

    static func subagent(_ id: String, _ agent: String, _ title: String, _ status: SessionChatSubagentStatus) -> SessionChatSubagent {
        BackgroundWorkPillGalleryView.row(id, agent, title, status, startedAgoMs: 42_000, now: captureSessionNowMs)
    }

    static func workPill(waiting: Int, running: Int, shells: Bool, compact: Bool = false) -> some View {
        let titles = ["Find the session store", "Map the settings screens", "Run the test suite", "Review the diff"]
        let waitingRows = (0 ..< waiting).map { subagent("wait-\($0)", "general", titles[$0], .waitingForInput) }
        let runningRows = (0 ..< running).map { subagent("run-\($0)", "explore", titles[$0], .running) }
        let shellRows = shells ? BackgroundWorkPillGalleryView.sampleShells(openedAtMs: captureSessionNowMs) : []
        return Group {
            if let pill = SessionChatBackgroundWork.pill(for: waitingRows + runningRows, shells: shellRows) {
                SessionChatBackgroundWorkPillView(pill: pill, compact: compact) {}
            }
        }
    }

    static var backgroundWorkPillEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "background-work-pill", alignment: .center, states: [
            ComponentCaptureState("subagents") { workPill(waiting: 0, running: 3, shells: false) },
            ComponentCaptureState("subagents-shells") { workPill(waiting: 0, running: 3, shells: true) },
            ComponentCaptureState("shells") { workPill(waiting: 0, running: 0, shells: true) },
            ComponentCaptureState("needs-input") { workPill(waiting: 1, running: 0, shells: false) },
            ComponentCaptureState("needs-input-running") { workPill(waiting: 1, running: 2, shells: true) },
            ComponentCaptureState("several-need-input") { workPill(waiting: 2, running: 3, shells: false) },
            ComponentCaptureState("compact") { workPill(waiting: 0, running: 3, shells: false, compact: true) },
            ComponentCaptureState("compact-needs-input") { workPill(waiting: 1, running: 0, shells: false, compact: true) },
        ])
    }

    static func todo(_ status: OpenCodeSessionTodoItemStatus, content: String? = nil) -> some View {
        OpenCodeSessionTodoItem(content: content ?? status.galleryContent, status: status)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
    }

    static var sessionTodoItemEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "session-todo-item", states: [
            ComponentCaptureState("pending") { todo(.pending) },
            ComponentCaptureState("in-progress") { todo(.inProgress) },
            ComponentCaptureState("completed") { todo(.completed) },
            ComponentCaptureState("cancelled") { todo(.cancelled) },
            ComponentCaptureState("long") {
                todo(
                    .inProgress,
                    content: "Trace every caller of the session store and list the ones that still read the legacy cache before the migration runs"
                )
            },
        ])
    }
}
#endif
