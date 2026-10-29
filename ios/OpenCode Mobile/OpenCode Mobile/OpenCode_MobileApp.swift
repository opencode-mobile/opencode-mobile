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
        _ = OpenCodeTextInputFocusMonitor.shared
        OpenCodeReleaseObservability.initialize()
        MainThreadHangWatchdog.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            launchView
                .openCodeWindowMetrics()
                .openCodeTheme()
                .background {
                    OpenCodeHapticAnchor()
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                .background {
                    OpenCodeKeyCommandBridgeAnchor()
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
        }
        .commands {
            OpenCodeKeyboardShortcutCommands()
        }
        .backgroundTask(.urlSession(DurableJSONUploader.sessionIdentifier)) {
            await DurableJSONUploader.shared.waitForBackgroundEvents()
        }
    }

    /// A `-labRun` launch argument runs the Highlighting Lab headless instead of the app.
    @ViewBuilder
    private var launchView: some View {
        #if HIGHLIGHT_LAB
        if let labRun = HighlightLabHeadless.requestedValue {
            HighlightLabHeadlessView(value: labRun)
        } else if let screen = HighlightLabScreenLaunch.requested {
            HighlightLabScreenLaunchView(screen: screen)
        } else {
            rootView
        }
        #else
        rootView
        #endif
    }

    @ViewBuilder
    private var rootView: some View {
        #if DEBUG
        if ProcessInfo.processInfo.environment["OPENCODE_UI_TEST_CHANGES"] == "1" {
            NavigationStack {
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
            }
        } else if ProcessInfo.processInfo.environment["OPENCODE_UI_TEST_REVIEW_CHANGES_PILL"] == "1" {
            ReviewChangesPillUITestView()
        } else if ProcessInfo.processInfo.environment["OPENCODE_UI_TEST_MESSAGE_LIST"] == "1" {
            MessageListGalleryUITestView()
        } else if ProcessInfo.processInfo.environment["OPENCODE_UI_TEST_BOTTOM_SHEET"] == "1" {
            BottomSheetUITestHost()
        } else if StoreScreenshotLaunchEnvironment.isEnabled {
            StoreScreenshotSeedingHost()
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
            patch: nil,
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
            patch: {
                let before = (1...8).map { index in
                    index == 6
                        ? " let horizontalScrollingFixture = \"This source line is intentionally much wider than an iPhone viewport so the unified diff can verify horizontal scrolling.\""
                        : " before \(index)"
                }.joined(separator: "\n")
                let after = (1...8).map { " after \($0)" }.joined(separator: "\n")
                return "@@ -1,17 +1,17 @@\n\(before)\n-old-app\n+new\n\(after)\n@@ -30 +30 @@\n-old-two\n+new-two"
            }(),
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
            OpenCodeReviewChangesPill(variant: .latestTurn, changedFileCount: 1, additions: 12, deletions: 3) { tapped = true }
            OpenCodeReviewChangesPill(variant: .latestTurn, changedFileCount: 4, additions: 0, deletions: 0, enabled: false, action: {})
            Text(tapped ? "Review changes tapped" : "Waiting for review")
        }
    }
}

private struct MessageListGalleryUITestView: View {
    @State private var model = MessageListGalleryModel(autoFollowEnabled: true)

    var body: some View {
        MessageListGalleryView(model: model)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)
            .accessibilityIdentifier("messageListGalleryUITest")
    }
}
#endif
