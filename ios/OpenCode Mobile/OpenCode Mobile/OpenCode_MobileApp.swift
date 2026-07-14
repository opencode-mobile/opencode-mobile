//
//  OpenCode_MobileApp.swift
//  OpenCode Mobile
//
//  Created by Ivan Fenenko on 04.05.26.
//

import Foundation
import SwiftUI

@main
struct OpenCode_MobileApp: App {
    init() {
        _ = ThemeManager.shared
    }

    var body: some Scene {
        WindowGroup {
            rootView
                .openCodeTheme()
        }
    }

    @ViewBuilder
    private var rootView: some View {
        #if DEBUG
        if ProcessInfo.processInfo.environment["OPENCODE_UI_TEST_CHANGES"] == "1" {
            ChangesView(
                sessionID: "ui-test-session",
                worktree: "/ui-test",
                messageID: "ui-test-message",
                viewModel: ChangesViewModel(
                    sessionID: "ui-test-session",
                    worktree: "/ui-test",
                    messageID: "ui-test-message",
                    initialSource: .workingTree,
                    files: Self.changesUITestFiles
                )
            )
        } else if ProcessInfo.processInfo.environment["OPENCODE_UI_TEST_REVIEW_CHANGES_PILL"] == "1" {
            ReviewChangesPillUITestView()
        } else {
            AppRootView()
        }
        #else
        AppRootView()
        #endif
    }

    #if DEBUG
    private static let changesUITestFiles = [
        FileDiff(
            path: "Sources/Removed.swift",
            patch: "@@ -1,1 +1,1 @@\n-old-removed\n+new",
            additions: 1,
            deletions: 2,
            status: .deleted
        ),
        FileDiff(
            path: "Sources/New.swift",
            patch: "@@ -1,1 +1,1 @@\n-old-new\n+new",
            additions: 2,
            deletions: 0,
            status: .added
        ),
        FileDiff(
            path: "Sources/App.swift",
            patch: "@@ -1,1 +1,1 @@\n-old-app\n+new",
            additions: 1,
            deletions: 1,
            status: .modified
        ),
    ]
    #endif
}

#if DEBUG
private struct ReviewChangesPillUITestView: View {
    @State private var tapped = false

    var body: some View {
        VStack(spacing: 16) {
            OpenCodeReviewChangesPill(variant: .latestTurn, changedFileCount: 1) { tapped = true }
            OpenCodeReviewChangesPill(variant: .latestTurn, changedFileCount: 4, enabled: false, action: {})
            Text(tapped ? "Review changes tapped" : "Waiting for review")
        }
    }
}
#endif
