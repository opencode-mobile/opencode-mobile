package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.message.Message
import kotlinx.coroutines.flow.Flow

interface MessageLiveStore {
    fun observeMessages(
        sessionId: String,
        directory: String,
    ): Flow<List<Message>>
}
