import Foundation

/// The server a multi-request operation belongs to, with its protocol known.
nonisolated struct PinnedServerConnection: Sendable {
    let connection: ServerConnection
}

/// Requests made inside `$current.withValue` use this connection and its protocol instead of the
/// active one. Keep the scope to the request itself: tasks started inside it inherit the pin.
nonisolated enum RequestConnectionPin {
    @TaskLocal static var current: ServerConnection?
}

/// The active server changed during an operation pinned to another one. Nothing more goes out.
nonisolated struct ServerConnectionChangedError: LocalizedError {
    var errorDescription: String? { "The server changed before this was sent. Your draft is kept." }
}
