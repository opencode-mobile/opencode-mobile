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
    private let foregroundEventStream: any ForegroundEventStream
    private let appOnboardingPreferences: AppOnboardingPreferences

    init(
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        foregroundEventStream: any ForegroundEventStream,
        appOnboardingPreferences: AppOnboardingPreferences
    ) {
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.foregroundEventStream = foregroundEventStream
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
        foregroundEventStream.stop()
        foregroundEventStream.start()
    }
}

struct AppRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var rootViewModel = Container.makeAppRootViewModel()

    var body: some View {
        Group {
            switch rootViewModel.state {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
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
            await rootViewModel.load()
        }
        .onChange(of: scenePhase) { _, phase in
            guard rootViewModel.state == .home else { return }
            let stream = Container.shared.foregroundEventStream()
            switch phase {
            case .active:
                stream.start()
            case .background:
                stream.stop()
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
            presentConnectionSetupOnAppear: presentConnectionSetupOnAppear
        )
    }
}

#Preview {
    AppRootView()
        .openCodeTheme()
}
