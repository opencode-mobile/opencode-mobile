package dev.opencode.mobile.domain.connection

/**
 * OpenCode HTTP API generation for an active server connection.
 *
 * Detection prefers `/global/health` with `healthy: true` (v1), else
 * `/api/info` or `/api/health` with numeric `pid` (v2; `healthy` may be absent).
 */
enum class ServerProtocol(
    val wireValue: String,
) {
    V1("v1"),
    V2("v2"),
    ;

    val shortLabel: String get() = wireValue

    val apiCaption: String get() = "API $wireValue"

    val reachableMessage: String get() = "Server reachable · API $wireValue"
}

/**
 * Product capabilities derived from [ServerProtocol]. Screen Models read these
 * flags instead of branching on protocol themselves.
 *
 * v2 session archive, per-turn diff and side questions depend on the server release, not the protocol:
 * pass [forProtocol]'s `v2Features` from the server's API contract probe.
 */
data class ServerCapabilities(
    val supportsSessionMessageDiff: Boolean,
    val supportsSessionArchive: Boolean,
    val supportsFollowUpDelivery: Boolean,
    /** Server tracks per-session read state (`time.idle`/`time.viewed`, `session.viewed`). */
    val supportsSessionViewed: Boolean = false,
    /** Server moves a session to another worktree (`POST /api/session/{id}/move`, v2). */
    val supportsSessionMove: Boolean = false,
    /** The command route keeps a client message id (v1 `messageID`); v2 `…/command` takes none. */
    val commandKeepsClientMessageId: Boolean = false,
    /**
     * `/btw` side questions: `POST /api/session/{id}/generate` answers from the session's context
     * without adding to it (v2 releases that declare it). v1 has no equivalent.
     */
    val supportsSideQuestion: Boolean = false,
    /** Attachments stream to the server (`POST /api/experimental/fs/write` plus `paths.tmp`, v2). */
    val supportsAttachmentUpload: Boolean = false,
) {
    companion object {
        fun forProtocol(
            protocol: ServerProtocol,
            v2Features: V2ServerFeatures = V2ServerFeatures(),
        ): ServerCapabilities =
            when (protocol) {
                ServerProtocol.V1 ->
                    ServerCapabilities(
                        supportsSessionMessageDiff = true,
                        supportsSessionArchive = true,
                        supportsFollowUpDelivery = false,
                        supportsSessionViewed = false,
                        commandKeepsClientMessageId = true,
                    )
                ServerProtocol.V2 ->
                    ServerCapabilities(
                        supportsSessionMessageDiff = v2Features.sessionTurnDiff,
                        supportsSessionArchive = v2Features.sessionArchive,
                        supportsFollowUpDelivery = true,
                        supportsSessionViewed = true,
                        supportsSessionMove = true,
                        supportsSideQuestion = v2Features.sessionGenerate,
                        supportsAttachmentUpload = v2Features.attachmentUpload,
                    )
            }
    }
}
