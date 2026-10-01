package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonObject

sealed interface MessagePatch {
    data class InfoUpdated(
        val infoJson: JsonObject,
    ) : MessagePatch

    data class PartUpdated(
        val partJson: JsonObject,
        val delta: String?,
    ) : MessagePatch

    /** Append a text delta to an existing part by ID (SSE `message.part.delta`). */
    data class PartDelta(
        val messageId: String,
        val partId: String,
        val delta: String,
    ) : MessagePatch

    data class Removed(
        val messageId: String,
    ) : MessagePatch

    /** A committed revert: drop [messageId] and every message after it. */
    data class RemovedFrom(
        val messageId: String,
    ) : MessagePatch

    data class PartRemoved(
        val messageId: String,
        val partId: String,
    ) : MessagePatch
}
