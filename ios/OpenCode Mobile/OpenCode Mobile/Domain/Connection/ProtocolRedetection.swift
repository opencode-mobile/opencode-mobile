import Foundation

/// Outcome of re-running protocol detection on the active connection, e.g. after a server upgrade.
nonisolated enum ProtocolRedetection: Equatable, Sendable {
    /// A protocol answered and is now cached and saved on the profile.
    case detected(ServerProtocol)
    /// No protocol answered: the server is down or no longer serves opencode at this address.
    case unreachable
    /// Nothing learned: no connection or probe, the check was cancelled, or the credential was rejected.
    case skipped
}
