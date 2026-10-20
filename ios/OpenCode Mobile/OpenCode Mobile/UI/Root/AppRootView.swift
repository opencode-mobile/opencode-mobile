import FactoryKit
import Observation
import SwiftUI

enum AppRootState: Equatable {
    case loading
    case appOnboarding
    case home
}

@MainActor
@Observable
final class AppRootViewModel {
    private(set) var state: AppRootState = .loading
    private(set) var pendingPresentConnectionSetup = false

    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let worldSignals: SceneWorldSignals
    private let appOnboardingPreferences: AppOnboardingPreferences
    private let sessionChatDisplayPreferences: SessionChatDisplayPreferences

    init(
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        worldSignals: SceneWorldSignals,
        appOnboardingPreferences: AppOnboardingPreferences,
        sessionChatDisplayPreferences: SessionChatDisplayPreferences
    ) {
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.worldSignals = worldSignals
        self.appOnboardingPreferences = appOnboardingPreferences
        self.sessionChatDisplayPreferences = sessionChatDisplayPreferences

        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                self?.enterHome()
            }
        }
    }

    func load() async {
        await activeConnectionManager.ensureLoaded(from: connectionProfileStore)
        let hasProfile = await connectionProfileStore.activeProfile() != nil
        let existingInstall = appOnboardingPreferences.isCompleted || hasProfile
        sessionChatDisplayPreferences.resolveChipTimelineForInstall(existingInstall: existingInstall)
        if !existingInstall {
            state = .appOnboarding
        } else {
            enterHome()
        }
    }

    func completeAppOnboarding(presentConnectionSetup: Bool) {
        appOnboardingPreferences.markCompleted()
        pendingPresentConnectionSetup = presentConnectionSetup
        enterHome()
    }

    func consumePresentConnectionSetup() -> Bool {
        let value = pendingPresentConnectionSetup
        pendingPresentConnectionSetup = false
        return value
    }

    func enterHome() {
        state = .home
        worldSignals.onForeground()
    }
}

struct AppRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var rootViewModel = Container.makeAppRootViewModel()
    @State private var appLockPreferences = AppLockPreferences.shared
    @State private var privacyCoverVisible = false
    @State private var locked = false
    @State private var wasInBackground = false
    private let appLockAuthenticator: any AppLockAuthenticating = LocalAuthenticationAppLockAuthenticator()

    var body: some View {
        Group {
            switch rootViewModel.state {
            case .loading:
                OpenCodeStatusStack(
                    title: "Loading sessions",
                    subtitle: "Checking server…",
                    accessibilityIdentifier: "appRootLoading"
                )
            case .appOnboarding:
                AppOnboardingView(
                    onSkip: { rootViewModel.completeAppOnboarding(presentConnectionSetup: false) },
                    onConnect: { rootViewModel.completeAppOnboarding(presentConnectionSetup: true) }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .home:
                AppRootHomeHost(rootViewModel: rootViewModel)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .overlay {
            if privacyCoverVisible {
                PrivacyCoverView(locked: locked) {
                    Task { await unlock() }
                }
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.15), value: privacyCoverVisible)
        .task {
            // A cold start with App Lock on begins locked.
            if AppLockPolicy.shouldLock(enabled: appLockPreferences.enabled, wasInBackground: true) {
                locked = true
                privacyCoverVisible = true
                await unlock()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            // Privacy cover (#755): hide content before the app-switcher snapshot, and engage
            // the lock when coming back from the background.
            switch phase {
            case .inactive:
                privacyCoverVisible = true
            case .background:
                wasInBackground = true
                privacyCoverVisible = true
            case .active:
                if AppLockPolicy.shouldLock(enabled: appLockPreferences.enabled, wasInBackground: wasInBackground) {
                    locked = true
                    Task { await unlock() }
                } else if !locked {
                    privacyCoverVisible = false
                }
                wasInBackground = false
            @unknown default:
                break
            }
        }
        .task {
            if rootViewModel.state == .loading {
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.appLoading,
                    presentation: ProductAnalytics.Presentation.route,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
            Container.shared.worldSignals().register()
            await rootViewModel.load()
        }
        .onChange(of: scenePhase) { previousPhase, phase in
            guard rootViewModel.state == .home else { return }
            let world = Container.shared.worldSignals()
            switch phase {
            case .active:
                if previousPhase == .background {
                    ProductAnalytics.logScreenView(
                        screenId: ProductAnalytics.Screen.home,
                        presentation: ProductAnalytics.Presentation.route,
                        trigger: ProductAnalytics.Trigger.resume
                    )
                }
                world.onForeground()
            case .background:
                world.onBackground()
            case .inactive:
                world.onInactive()
            @unknown default:
                break
            }
        }
    }
}

extension AppRootView {
    @MainActor
    fileprivate func unlock() async {
        guard locked else { return }
        if await appLockAuthenticator.authenticate(reason: "Unlock OpenCode Mobile") {
            locked = false
            privacyCoverVisible = AppLockPolicy.coverVisible(isForeground: scenePhase == .active, locked: false)
        }
    }
}

/// Isolates one-shot Connect→setup so SwiftUI body re-renders cannot consume the flag twice.
private struct AppRootHomeHost: View {
    let rootViewModel: AppRootViewModel
    @State private var presentConnectionSetupOnAppear: Bool

    init(rootViewModel: AppRootViewModel) {
        self.rootViewModel = rootViewModel
        _presentConnectionSetupOnAppear = State(
            initialValue: rootViewModel.consumePresentConnectionSetup()
        )
    }

    var body: some View {
        ProjectListHomeView(
            viewModel: Container.makeProjectListHomeViewModel(),
            presentConnectionSetupOnAppear: $presentConnectionSetupOnAppear
        )
    }
}

#Preview {
    AppRootView()
        .openCodeTheme()
}
