import Foundation

/// App-level return-to-foreground restore. Starts SSE and REST-authorizes
/// registered UI scopes. Cold resume after background stop is not gated on
/// stream.reconnected delivery alone.
nonisolated protocol ForegroundRestore: AnyObject, Sendable {
    /// Foreground edge after the background stop grace (or home enter).
    func restore()

    /// Background grace fired: tear down SSE and arm cold-resume catch-up.
    func noteBackgroundStop()

    func setOpenSession(sessionID: String?, directory: String?)
    func setHomeVisible(_ visible: Bool)
}

enum ForegroundRestorePhase: String, Sendable, Equatable {
    case idle
    case restoring
    case live
    case failed
}
