#if DEBUG
import FactoryKit
import SwiftUI

enum StoreScreenshotLaunchEnvironment {
    static var serverURL: String? {
        value(forKey: "OPENCODE_STORE_SERVER_URL")
    }

    static var serverPassword: String {
        value(forKey: "OPENCODE_STORE_SERVER_PASSWORD") ?? "store-screenshots"
    }

    static var isEnabled: Bool { serverURL != nil }

    /// `XCUIApplication.launchEnvironment` is dropped when the scheme uses
    /// launch-scheme args/env, so also accept `-KEY value` launch arguments.
    static func value(forKey key: String) -> String? {
        let env = ProcessInfo.processInfo.environment[key]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let env, !env.isEmpty { return env }
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "-\(key)"),
           args.indices.contains(index + 1)
        {
            let value = args[index + 1].trimmingCharacters(in: .whitespacesAndNewlines)
            return value.isEmpty ? nil : value
        }
        return nil
    }
}

/// Wipes leftover app state, seeds a fixture Connection Profile, then shows Home.
struct StoreScreenshotSeedingHost: View {
    @State private var isReady = false

    var body: some View {
        Group {
            if isReady {
                AppRootView()
            } else {
                OpenCodeStatusStack(
                    title: "Loading sessions",
                    subtitle: "Checking server…",
                    accessibilityIdentifier: "appRootLoading"
                )
            }
        }
        .task {
            await StoreScreenshotSeeder.seed()
            isReady = true
        }
    }
}

@MainActor
enum StoreScreenshotSeeder {
    static func seed() async {
        guard let serverURL = StoreScreenshotLaunchEnvironment.serverURL else { return }
        await wipePriorState()
        ThemeManager.shared.clearAll()
        AppOnboardingPreferences.shared.markCompleted()
        AnalyticsConsentPreferences.shared.setProductAnalyticsConsented(true)

        let profile = ConnectionProfile(
            baseURL: serverURL,
            username: ConnectionProfile.defaultUsername,
            password: StoreScreenshotLaunchEnvironment.serverPassword,
            serverProtocol: nil,
            name: "Fixture server"
        )
        do {
            try await Container.shared.connectionSessionCoordinator().saveProfile(profile)
            let store = Container.shared.connectionProfileStore()
            var saved = await store.activeProfile() ?? profile
            saved.password = profile.password
            if !saved.id.isEmpty {
                try Container.shared.secureCredentialStore().writePassword(
                    serverBaseURL: saved.id,
                    password: saved.password
                )
            }
            // Keychain reload can drop the password in the UI-test host; keep it in memory.
            Container.shared.activeConnectionManager().apply(saved)
            _ = try? await Container.shared.activeConnectionManager().probeActive()
            let projects = (try? await Container.shared.projectRepository().listProjects()) ?? []
            let worktrees = projects.map(\.worktree)
            let seeded = worktrees.isEmpty
                ? [
                    "/Users/example/Projects/opencode-mobile",
                    "/Users/example/Projects/artifact-publisher",
                ]
                : worktrees
            await store.setAddedWorktrees(serverBaseURL: saved.normalizedBaseURL, worktrees: seeded)
        } catch {
            DiagnosticLog.error(.connection, "store screenshot seed failed: \(error)")
        }
    }

    /// Same surfaces as `Container.clearAllData()`, awaited so Home cannot race the wipe.
    private static func wipePriorState() async {
        await Container.shared.connectionSessionCoordinator().clearConnectionData()
        Container.shared.projectionCaches().clearAll()
        await Container.shared.sessionCache().clearAll()
        ThemeManager.shared.clearAll()
        HomeDisplayPreferences.shared.clear()
        MarkdownHighlightPreferences.shared.clear()
        AnalyticsConsentPreferences.shared.clear()
        AppOnboardingPreferences.shared.clear()
        DeveloperToolsPreferences.shared.clear()
        PerformanceOverlayPreferences.shared.clear()
        SessionChatDisplayPreferences.shared.clear()
        SplitPanePreferences.shared.clear()
        FeatureFlags.shared.clearOverrides()
        MessageContentPreparer.shared.clearDocumentCache()
        BlockDecorationCache.shared.clear()
        await TextMateLogbook.shared.clear()
        DiagnosticLogBuffer.shared.clear()
    }
}
#endif
