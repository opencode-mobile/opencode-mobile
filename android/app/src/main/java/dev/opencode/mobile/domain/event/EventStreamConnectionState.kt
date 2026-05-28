package dev.opencode.mobile.domain.event

enum class EventStreamConnectionState {
    Idle,
    Connecting,
    Connected,
    Disconnected,
    Reconnecting,
}
