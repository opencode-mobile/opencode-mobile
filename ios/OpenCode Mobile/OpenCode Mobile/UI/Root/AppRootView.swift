import Factory
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
    private let foregroundRestore: any ForegroundRestore
    private let appOnboardingPreferences: AppOnboardingPreferences

    init(
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        foregroundRestore: any ForegroundRestore,
        appOnboardingPreferences: AppOnboardingPreferences
    ) {
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.foregroundRestore = foregroundRestore
        self.appOnboardingPreferences = appOnboardingPreferences

        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                self?.enterHome()
            }
        }
    }

    func load() async {
        await activeConnectionManager.ensureLoaded(from: connectionProfileStore)
        let hasProfile = await connectionProfileStore.activeProfile() != nil
        if !appOnboardingPreferences.isCompleted, !hasProfile {
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
        foregroundRestore.restore()
    }
}

struct AppRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var rootViewModel = Container.makeAppRootViewModel()
    @State private var stopTask: Task<Void, Never>?

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
        .task {
            if rootViewModel.state == .loading {
                ProductAnalytics.logScreenView(
                    screenId: ProductAnalytics.Screen.appLoading,
                    presentation: ProductAnalytics.Presentation.route,
                    trigger: ProductAnalytics.Trigger.present
                )
            }
            await rootViewModel.load()
        }
        .onChange(of: scenePhase) { previousPhase, phase in
            guard rootViewModel.state == .home else { return }
            let restore = Container.shared.foregroundRestore()
            switch phase {
            case .active:
                stopTask?.cancel()
                stopTask = nil
                if previousPhase == .background {
                    ProductAnalytics.logScreenView(
                        screenId: ProductAnalytics.Screen.home,
                        presentation: ProductAnalytics.Presentation.route,
                        trigger: ProductAnalytics.Trigger.resume
                    )
                }
                restore.restore()
            case .background:
                stopTask?.cancel()
                restore.noteBackground()
                stopTask = Task {
                    try? await Task.sleep(for: .seconds(5))
                    guard !Task.isCancelled else { return }
                    restore.noteBackgroundStop()
                }
            case .inactive:
                break
            @unknown default:
                break
            }
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
