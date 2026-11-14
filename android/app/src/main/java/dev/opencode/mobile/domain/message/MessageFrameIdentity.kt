package dev.opencode.mobile.domain.message

/** O(1) published-frame identity. Engine revision already changes on every applied mutation. */
data class MessageFrameIdentity(
    val count: Int,
    val engineRevision: Long,
    val lastMessageId: String?,
    val lastUpdatedMs: Long?,
)

/** Cheap tail confirm: id + timestamp + part count + full tool images for the fetched page. */
data class MessageTailIdentity(
    val items: List<Item>,
) {
    data class Item(
        val messageId: String,
        val updatedMs: Long?,
        val partCount: Int,
        /**
         * Tool images that carry their full data URL. The session cache never stores data URLs
         * (only metadata and an on-disk thumbnail), so a cached copy counts 0 here and can never
         * confirm a server page whose tools returned images; the page is merged instead and the
         * images show. User file parts are left out on purpose: their blanked data URLs are
         * hydrated on confirm, and counting them would defeat the cheap confirm for every
         * session with a sent photo.
         */
        val fullToolImageCount: Int = 0,
    )
}

fun Message.tailTimestampMs(): Long? = completedAtEpochMs ?: sentAtEpochMs

fun Message.isSettled(): Boolean {
    if (role == MessageRole.Assistant && completedAtEpochMs == null) return false
    return parts.none { part ->
        when (part) {
            is MessagePart.Text -> part.isStreaming
            is MessagePart.Reasoning -> part.isStreaming
            is MessagePart.Tool -> part.status == ToolStatus.Pending || part.status == ToolStatus.Running
            else -> false
        }
    }
}

fun List<Message>.isSettledTail(): Boolean = all { it.isSettled() }

fun List<Message>.frameIdentity(engineRevision: Long): MessageFrameIdentity {
    val last = lastOrNull()
    return MessageFrameIdentity(
        count = size,
        engineRevision = engineRevision,
        lastMessageId = last?.id,
        lastUpdatedMs = last?.tailTimestampMs(),
    )
}

fun List<Message>.tailIdentity(): MessageTailIdentity =
    MessageTailIdentity(
        items =
            map { message ->
                MessageTailIdentity.Item(
                    messageId = message.id,
                    updatedMs = message.tailTimestampMs(),
                    partCount = message.parts.size,
                    fullToolImageCount = ToolResultImages.fullImageCount(message),
                )
            },
    )

fun List<Message>.matchesTailIdentity(tail: List<Message>): Boolean {
    if (tail.isEmpty()) return isEmpty()
    val localTail = takeLast(tail.size)
    // A streaming tail can never be confirmed unchanged.
    if (!localTail.isSettledTail() || !tail.isSettledTail()) return false
    return localTail.tailIdentity() == tail.tailIdentity()
}
