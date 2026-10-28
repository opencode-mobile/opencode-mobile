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
 * Product capabilities for the active engine. OpenCode still derives them from
 * [ServerProtocol]. T3 has its own preset: the protocol probe reports v1, and
 * that fallback must not turn OpenCode-only controls back on.
 *
 * Screen Models read these flags instead of branching on protocol themselves.
 *
 * v2 session archive, per-turn diff and side questions depend on the server release, not the protocol:
 * pass [forProtocol]'s `v2Features` from the server's API contract probe.
 */
data class ServerCapabilities(
    val supportsSessionMessageDiff: Boolean,
    val supportsSessionArchive: Boolean,
    val supportsFollowUpDelivery: Boolean,
    /**
     * The pending-prompt strip can steer or remove a message that is waiting
     * for the agent. OpenCode v2 does this through the inbox. T3 does it
     * through the queued-run commands. The composer follow-up toggle stays on
     * [supportsFollowUpDelivery].
     */
    val supportsPendingPrompts: Boolean = false,
    val supportsTerminal: Boolean = true,
    val supportsRevert: Boolean = true,
    val supportsMentions: Boolean = true,
    val supportsSlashCommands: Boolean = true,
    val supportsDocumentAttachments: Boolean = true,
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
        val t3: ServerCapabilities =
            ServerCapabilities(
                supportsSessionMessageDiff = false,
                supportsSessionArchive = true,
                supportsFollowUpDelivery = false,
                supportsPendingPrompts = true,
                supportsTerminal = false,
                supportsRevert = false,
                supportsMentions = false,
                supportsSlashCommands = false,
                supportsDocumentAttachments = false,
            )

        /** [supportsDocumentAttachments] is true only when this environment advertised `fileAttachments`. */
        fun t3(supportsDocumentAttachments: Boolean): ServerCapabilities =
            if (supportsDocumentAttachments) t3.copy(supportsDocumentAttachments = true) else t3

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
                        supportsPendingPrompts = true,
                        supportsSessionViewed = true,
                        supportsSessionMove = true,
                        supportsSideQuestion = v2Features.sessionGenerate,
                        supportsAttachmentUpload = v2Features.attachmentUpload,
                    )
            }

        fun forEngine(
            engine: ConnectionEngine,
            protocol: ServerProtocol?,
        ): ServerCapabilities =
            if (engine == ConnectionEngine.T3) {
                t3
            } else {
                forProtocol(protocol ?: ServerProtocol.V1)
            }
    }
}
