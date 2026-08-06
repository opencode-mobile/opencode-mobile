import Factory
import Observation
import SwiftUI

enum AppRootState: Equatable {
    case loading
    case home
}

@MainActor
@Observable
final class AppRootViewModel {
    private(set) var state: AppRootState = .loading

    private let connectionProfileStore: any ConnectionProfileStore
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionSessionCoordinator: ConnectionSessionCoordinator
    private let foregroundEventStream: any ForegroundEventStream

    init(
        connectionProfileStore: any ConnectionProfileStore,
        activeConnectionManager: ActiveConnectionManager,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        foregroundEventStream: any ForegroundEventStream
    ) {
        self.connectionProfileStore = connectionProfileStore
        self.activeConnectionManager = activeConnectionManager
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.foregroundEventStream = foregroundEventStream

        connectionSessionCoordinator.onProfileSaved { [weak self] in
            Task { @MainActor in
                self?.enterHome()
            }
        }
    }

    func load() async {
        await activeConnectionManager.ensureLoaded(from: connectionProfileStore)
        enterHome()
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
                OpenCodeStatusStack(
                    title: "Loading sessions",
                    subtitle: "Checking server…",
                    accessibilityIdentifier: "appRootLoading"
                )
            case .home:
                ProjectListHomeView(viewModel: Container.makeProjectListHomeViewModel())
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

#Preview {
    AppRootView()
        .openCodeTheme()
}
