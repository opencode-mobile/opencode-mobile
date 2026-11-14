package dev.opencode.mobile.domain.message

interface MessageRemote {
    suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message>

    suspend fun listMessagePage(
        sessionId: String,
        directory: String?,
        query: MessageHistoryQuery,
    ): MessagePage

    /** One message with all its parts (tool images included), mapped to the product type. */
    suspend fun getMessage(
        sessionId: String,
        messageId: String,
        directory: String?,
    ): Message

    suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    )

    suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    )
}
