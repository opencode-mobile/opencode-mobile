package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionDecision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

object T3Commands {
    fun commandId(): String = UUID.randomUUID().toString()

    fun messageDispatch(
        threadId: String,
        text: String,
        modelSelection: T3ModelSelection?,
        running: Boolean,
        messageId: String = commandId(),
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("type", "message.dispatch")
            put("commandId", commandId)
            put("threadId", threadId)
            put("messageId", messageId)
            put("text", text)
            put("attachments", JsonArray(emptyList()))
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
        commandId: String = commandId(),
    ): JsonObject =
        buildJsonObject {
            put("commandId", commandId)
            put("projectId", projectId)
            put("title", title.ifBlank { "New thread" })
            put("modelSelection", encodeModelSelection(modelSelection))
            put("runtimeMode", "approval-required")
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

    private fun encodeModelSelection(selection: T3ModelSelection): JsonObject =
        buildJsonObject {
            put("instanceId", selection.instanceId)
            selection.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        }
}
