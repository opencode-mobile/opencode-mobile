import FactoryKit
import Foundation

/// One open Connection Setup. It owns the view model so the cover keeps one
/// form for as long as it shows: the cover's content is rebuilt at times (the
/// host field gaining focus does it), and a view model made there starts over
/// on the instructions.
struct ConnectionSetupPresentation: Identifiable {
    let id = UUID()
    let viewModel: ConnectionSetupViewModel

    @MainActor
    init(initialProfile: ConnectionProfile?, mode: ConnectionSetupMode = .first) {
        viewModel = Container.makeConnectionSetupViewModel(initialProfile: initialProfile, mode: mode)
    }
}
