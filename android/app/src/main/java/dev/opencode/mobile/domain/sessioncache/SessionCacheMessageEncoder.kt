package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import kotlinx.serialization.json.Json

/** How a message timeline flush is encoded ([SessionCacheCodec.encodeMessages]); a seam for tests. */
fun interface SessionCacheMessageEncoder {
    fun encode(
        messages: List<Message>,
        json: Json,
        hasThumbnail: (MessagePart.File) -> Boolean,
    ): String

    companion object {
        val Default =
            SessionCacheMessageEncoder {
                messages,
                json,
                hasThumbnail,
                ->
                SessionCacheCodec.encodeMessages(messages, json, hasThumbnail)
            }
    }
}
