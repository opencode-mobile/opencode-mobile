package dev.opencode.mobile.domain.event

enum class EventStreamConnectionState {
    Idle,
    Connecting,
    Connected,

    /** No server protocol answered the last check: the server is down or no longer opencode. */
    Disconnected,
    Reconnecting,
    AuthFailed,
}
