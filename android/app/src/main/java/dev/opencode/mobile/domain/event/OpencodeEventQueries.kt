package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.message.ToolInterruption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Typed questions feature code asks of a live event. The server event names and payload keys
 * stay here, so Screen Models never match type strings or read raw properties.
 */

/** Server event names the typed queries below answer for. */
internal object OpencodeEventTypes {
    const val WORKTREE_UPDATED = "worktree.updated"
    const val BRANCH_UPDATED = "vcs.branch.updated"
    const val SESSION_IDLE = "session.idle"
    const val SESSION_ERROR = "session.error"
    const val SESSION_UPDATED = "session.updated"
    const val SESSION_DELETED = "session.deleted"
    const val SESSION_ARCHIVED = "session.archived"
    const val SESSION_UNARCHIVED = "session.unarchived"
    const val SESSION_REVERT_STAGED = "session.revert.staged"
    const val SESSION_REVERT_CLEARED = "session.revert.cleared"
    const val SESSION_REVERT_COMMITTED = "session.revert.committed"
    const val MESSAGE_PART_DELTA = "message.part.delta"
    val MODEL_CATALOG_UPDATES = setOf("model.updated", "provider.updated")
    val STREAM_RECONNECTS = setOf("server.connected", "stream.reconnected")
}

/** A session's archive state changed on the server. */
enum class SessionArchiveChange { Archived, Unarchived }

/** A project's worktrees changed (`worktree.updated`); [namedProjectId] says which project. */
val OpencodeEvent.isWorktreeUpdated: Boolean
    get() = type == OpencodeEventTypes.WORKTREE_UPDATED

/** The checked-out branch changed (`vcs.branch.updated`). */
val OpencodeEvent.isBranchUpdated: Boolean
    get() = type == OpencodeEventTypes.BRANCH_UPDATED

/** A run ended (v1 native `session.idle`; v2 projects `session.execution.*` onto it). */
val OpencodeEvent.isSessionRunEnd: Boolean
    get() = type == OpencodeEventTypes.SESSION_IDLE

/** A streamed text delta: frequent enough that per-event logging skips it. */
val OpencodeEvent.isMessagePartDelta: Boolean
    get() = type == OpencodeEventTypes.MESSAGE_PART_DELTA

/** The event stream (re)connected, so state read before it may be stale. */
val OpencodeEvent.isStreamReconnect: Boolean
    get() = type in OpencodeEventTypes.STREAM_RECONNECTS

/**
 * The model list should be refetched: the server announced a catalog change (a freshly started
 * server can answer the first `GET model` before its catalog is ready, then announce it), or the
 * stream (re)connected, since catalog events sent while it was down are never replayed.
 */
val OpencodeEvent.triggersModelCatalogRefresh: Boolean
    get() = type in OpencodeEventTypes.MODEL_CATALOG_UPDATES || isStreamReconnect

/** The archive state of [OpencodeEvent.sessionId] changed, or null for any other event. */
val OpencodeEvent.sessionArchiveChange: SessionArchiveChange?
    get() =
        when (type) {
            OpencodeEventTypes.SESSION_ARCHIVED -> SessionArchiveChange.Archived
            OpencodeEventTypes.SESSION_UNARCHIVED -> SessionArchiveChange.Unarchived
            else -> null
        }

/** A session left or rejoined the archived list (archived, unarchived or deleted). */
val OpencodeEvent.triggersArchivedListRefresh: Boolean
    get() = sessionArchiveChange != null || type == OpencodeEventTypes.SESSION_DELETED

/**
 * The user's own Stop/Deny (`error.type: "aborted"` / `MessageAbortedError`): the timeline
 * shows it neutrally, so it is not an error toast.
 */
val OpencodeEvent.isUserAbortSessionError: Boolean
    get() {
        if (type != OpencodeEventTypes.SESSION_ERROR) return false
        val error = rawProperties?.get("error") as? JsonObject
        return ToolInterruption.isUserAbortError(
            type = (error?.get("type") as? JsonPrimitive)?.contentOrNull,
            name = (error?.get("name") as? JsonPrimitive)?.contentOrNull,
        )
    }

/** What a `session.error` says went wrong, or null for any other event. */
fun OpencodeEvent.sessionErrorMessage(): String? {
    if (type != OpencodeEventTypes.SESSION_ERROR) return null
    val properties = rawProperties
    val error = properties?.get("error") as? JsonObject
    val data = error?.get("data") as? JsonObject
    return sequenceOf(
        (data?.get("message") as? JsonPrimitive)?.contentOrNull,
        (error?.get("message") as? JsonPrimitive)?.contentOrNull,
        (properties?.get("message") as? JsonPrimitive)?.contentOrNull,
        (error?.get("name") as? JsonPrimitive)?.contentOrNull,
        (error?.get("type") as? JsonPrimitive)?.contentOrNull,
    ).firstOrNull { !it.isNullOrBlank() } ?: "Session work failed"
}

/**
 * Title change carried by a `session.updated` event for a concrete session id.
 * Returns null when the event is not a usable title update.
 */
fun OpencodeEvent.sessionTitleUpdate(): SessionTitleUpdate? {
    if (type != OpencodeEventTypes.SESSION_UPDATED) return null
    val info = rawProperties?.get("info")?.jsonObject ?: return null

    fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    val sessionId =
        sessionId
            ?: info.string("id")
            ?: info.string("sessionID")
            ?: info.string("sessionId")
            ?: return null
    val title = info.string("title") ?: return null
    return SessionTitleUpdate(sessionId = sessionId, title = title)
}

/**
 * Pending-revert change. v1 `session.updated` carries the whole session, so an absent
 * `info.revert` means none; v2 stages, clears and commits with dedicated events. A partial
 * `info` (the v2 `session.renamed` projection) says nothing about the revert and is ignored.
 */
fun OpencodeEvent.sessionRevertUpdate(): SessionRevertUpdate? {
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    return when (type) {
        OpencodeEventTypes.SESSION_UPDATED -> {
            val info = rawProperties?.get("info") as? JsonObject ?: return null
            if (info["time"] == null) return null
            val sessionId = sessionId ?: info.string("id") ?: return null
            val messageId = (info["revert"] as? JsonObject)?.string("messageID")
            SessionRevertUpdate(sessionId = sessionId, messageId = messageId)
        }
        OpencodeEventTypes.SESSION_REVERT_STAGED -> {
            val sessionId = sessionId ?: return null
            val messageId =
                (rawProperties?.get("revert") as? JsonObject)?.string("messageID") ?: return null
            SessionRevertUpdate(sessionId = sessionId, messageId = messageId)
        }
        OpencodeEventTypes.SESSION_REVERT_CLEARED, OpencodeEventTypes.SESSION_REVERT_COMMITTED -> {
            val sessionId = sessionId ?: return null
            SessionRevertUpdate(sessionId = sessionId, messageId = null)
        }
        else -> null
    }
}

/** Session removed on the server. v1 carries the id only in `info.id`; v2 sends `sessionID`. */
fun OpencodeEvent.sessionDeletedId(): String? {
    if (type != OpencodeEventTypes.SESSION_DELETED) return null
    val info = rawProperties?.get("info") as? JsonObject

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    return sessionId
        ?: info?.string("id")
        ?: info?.string("sessionID")
        ?: info?.string("sessionId")
}
