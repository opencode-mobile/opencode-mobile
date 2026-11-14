import FactoryKit
import Foundation

/// Opens the New session draft (#1004) of the active Connection Profile before its chat shows.
@MainActor
enum NewSessionDraftLauncher {
    /// Keeps a draft with text or attachments (unless `forcePlace`, which only moves it), else
    /// starts at `start`. Returns the checkout the draft chat binds to.
    @discardableResult
    static func open(start: NewSessionDraft, forcePlace: Bool = false) -> String {
        guard let profile = Container.shared.activeConnectionManager().currentProfile() else {
            return start.directory
        }
        return Container.shared.newSessionDraftStore()
            .open(profile: NewSessionDraftStore.key(for: profile), start: start, forcePlace: forcePlace)
            .directory
    }
}
