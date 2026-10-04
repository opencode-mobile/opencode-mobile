package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionDecision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object T3Commands {
    /** Supervised: every tool call needs approval. The default for a new thread. */
    const val DEFAULT_RUNTIME_MODE = "approval-required"

    /** `OrchestrationV2Actor` for commands the user typed; required on `message.dispatch`. */
    const val CREATED_BY_USER = "user"

    /** `OrchestrationV2CreationSource` identifying this client; required on `message.dispatch`. */
    const val CREATION_SOURCE_MOBILE = "mobile"

    fun commandId(): String = UUID.randomUUID().toString()

    fun messageDispatch(
        threadId: String,
        text: String,
        modelSelection: T3ModelSelection?,
        running: Boolean,
        attachments: List<T3Attachment> = emptyList(),
        messageId: String = commandId(),
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "message.dispatch")
            put("createdBy", CREATED_BY_USER)
            put("creationSource", CREATION_SOURCE_MOBILE)
            put("commandId", commandId)
            put("threadId", threadId)
            put("messageId", messageId)
            put("text", text)
            put("attachments", JsonArray(attachments.map(::encodeAttachment)))
            put(
                "dispatchMode",
                buildJsonObject {
                    put("type", if (running) "queue_after_active" else "start_immediately")
                },
            )
            if (modelSelection != null) {
                put("modelSelection", encodeModelSelection(modelSelection))
            }
        }

    fun runInterrupt(
        threadId: String,
        runId: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "run.interrupt")
            put("commandId", commandId)
            put("threadId", threadId)
            put("runId", runId)
        }

    fun threadSettle(
        threadId: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.settle")
            put("commandId", commandId)
            put("threadId", threadId)
        }

    fun threadArchive(
        threadId: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.archive")
            put("commandId", commandId)
            put("threadId", threadId)
        }

    fun threadUnarchive(
        threadId: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.unarchive")
            put("commandId", commandId)
            put("threadId", threadId)
        }

    /** Re-activates a settled thread; the contract only allows the `user` reason from clients. */
    fun threadUnsettle(
        threadId: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.unsettle")
            put("commandId", commandId)
            put("threadId", threadId)
            put("reason", "user")
        }

    fun threadRuntimeModeSet(
        threadId: String,
        runtimeMode: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.runtime-mode.set")
            put("commandId", commandId)
            put("threadId", threadId)
            put("runtimeMode", runtimeMode)
        }

    fun threadModelSelectionSet(
        threadId: String,
        modelSelection: T3ModelSelection,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.model-selection.set")
            put("commandId", commandId)
            put("threadId", threadId)
            put("modelSelection", encodeModelSelection(modelSelection))
        }

    fun threadDelete(
        threadId: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.delete")
            put("commandId", commandId)
            put("threadId", threadId)
        }

    fun threadMetadataUpdate(
        threadId: String,
        title: String,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "thread.metadata.update")
            put("commandId", commandId)
            put("threadId", threadId)
            put("title", title)
        }

    fun launchThread(
        projectId: String,
        title: String,
        modelSelection: T3ModelSelection,
        runtimeMode: String = DEFAULT_RUNTIME_MODE,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("commandId", commandId)
            put("projectId", projectId)
            put("title", title.ifBlank { "New thread" })
            put("modelSelection", encodeModelSelection(modelSelection))
            put("runtimeMode", runtimeMode)
            put("interactionMode", "default")
            put("workspaceStrategy", buildJsonObject { put("type", "root") })
        }

    fun runtimeRequestRespond(
        threadId: String,
        requestId: String,
        decision: PermissionDecision,
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "runtime-request.respond")
            put("commandId", commandId)
            put("threadId", threadId)
            put("requestId", requestId)
            put("decision", approvalWire(decision))
        }

    fun approvalWire(decision: PermissionDecision): String =
        when (decision) {
            PermissionDecision.AllowOnce -> "accept"
            PermissionDecision.AlwaysAllow -> "acceptForSession"
            PermissionDecision.Deny -> "decline"
        }

    /** A null [afterSequence] asks the server for a full snapshot before live events. */
    fun subscribeThread(
        threadId: String,
        afterSequence: Long?,
    ): JsonObject =
        buildJsonObject {
            put("threadId", threadId)
            if (afterSequence != null) put("afterSequence", afterSequence)
            put("requestCompletionMarker", true)
        }

    /** A null [afterSequence] asks the server for a full shell snapshot before live events. */
    fun subscribeShell(afterSequence: Long?): JsonObject =
        buildJsonObject {
            if (afterSequence != null) put("afterSequence", afterSequence)
            put("requestCompletionMarker", true)
        }

    /** `ChatAttachment` as persisted by the server: a stored id plus its metadata. */
    private fun encodeAttachment(attachment: T3Attachment): JsonObject =
        buildJsonObject {
            put("type", attachment.type)
            put("id", attachment.id)
            put("name", attachment.name)
            put("mimeType", attachment.mimeType)
            put("sizeBytes", attachment.sizeBytes)
        }

    private fun encodeModelSelection(selection: T3ModelSelection): JsonObject =
        buildJsonObject {
            put("instanceId", selection.instanceId)
            selection.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        }
}
