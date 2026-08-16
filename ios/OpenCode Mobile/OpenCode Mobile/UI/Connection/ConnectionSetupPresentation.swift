import Foundation

struct ConnectionSetupPresentation: Identifiable {
    let id = UUID()
    let initialProfile: ConnectionProfile?
    var mode: ConnectionSetupMode = .first
}
