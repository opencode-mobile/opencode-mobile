package dev.opencode.mobile.domain.message

interface MessageRemote {
    suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message>

    suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    )
}
