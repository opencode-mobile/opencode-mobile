import Foundation

/// Where repositories read the active server's protocol. OpenCode Mobile's
/// `ActiveConnectionManager` probes on demand; Hex Code knows it is talking to v2.
public protocol ServerProtocolResolving: AnyObject, Sendable {
    func currentProtocolOrProbe() async -> ServerProtocol
}
