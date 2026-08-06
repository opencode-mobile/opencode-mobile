package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session

data class SessionListSnapshot(
    val sessions: List<Session>,
    val isFromCache: Boolean = false,
)
