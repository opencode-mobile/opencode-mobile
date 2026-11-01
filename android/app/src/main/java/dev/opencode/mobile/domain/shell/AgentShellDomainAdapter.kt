package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.data.remote.dto.ShellInfoDto
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.ToolStartTime
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull

/** A v2 `shell.*` event on `/api/event`. */
internal sealed interface AgentShellEvent {
    data class Created(
        val info: ShellInfoDto,
    ) : AgentShellEvent

    data class Exited(
        val id: String,
        val status: AgentShellStatus,
        val atMs: Long?,
    ) : AgentShellEvent

    data class Deleted(
        val id: String,
    ) : AgentShellEvent
}

/** Maps wire shapes of both protocols to [AgentShell] and [AgentShellOutput]. */
class AgentShellDomainAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    // v1: bash tool parts

    /** Every `bash` tool call in [messages], in call order. */
    fun shellsFromMessages(
        messages: List<Message>,
        sessionId: String,
    ): List<AgentShell> =
        messages
            .asSequence()
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<MessagePart.Tool>()
            .mapNotNull { fromBashPart(it, sessionId) }
            .toList()

    fun fromBashPart(
        part: MessagePart.Tool,
        sessionId: String,
    ): AgentShell? {
        if (!part.toolName.equals(BASH_TOOL, ignoreCase = true)) return null
        val id = part.id?.takeIf { it.isNotEmpty() } ?: return null
        val status =
            when (part.status) {
                ToolStatus.Pending, ToolStatus.Running -> AgentShellStatus.Running
                ToolStatus.Completed -> AgentShellStatus.Exited(int(part.metadata, "exit"))
                ToolStatus.Error -> if (part.isInterrupted) AgentShellStatus.Stopped else AgentShellStatus.Failed
            }
        return AgentShell(
            id = id,
            sessionId = sessionId,
            command = string(part.input, "command").orEmpty(),
            description = string(part.input, "description"),
            status = status,
            startedAtMs = ToolStartTime.normalizeEpochMs(part.timeStartEpochMs),
            finishedAtMs = if (status == AgentShellStatus.Running) null else ToolStartTime.normalizeEpochMs(part.timeEndEpochMs),
            canStop = false,
        )
    }

    /**
     * What a bash part printed: the live `metadata.output` while it runs (the server keeps the
     * latest 30,000 characters), then the final `output`.
     */
    fun outputFromBashPart(part: MessagePart.Tool): AgentShellOutput {
        val live = string(part.metadata, "output", allowEmpty = true)
        val text =
            when (part.status) {
                ToolStatus.Pending, ToolStatus.Running -> live
                ToolStatus.Completed, ToolStatus.Error -> part.output?.takeIf { it.isNotEmpty() } ?: live
            }.orEmpty()
        return AgentShellOutput.capped(text)
    }

    // v2: /api/shell and shell.* events

    /** The session a v2 shell runs for (`metadata.sessionID`, set by the shell tool). */
    fun sessionIdOf(info: ShellInfoDto): String? = string(info.metadata, "sessionID") ?: string(info.metadata, "sessionId")

    fun fromInfo(
        info: ShellInfoDto,
        canStop: Boolean,
    ): AgentShell {
        val status = v2Status(info.status, info.exit?.toInt())
        return AgentShell(
            id = info.id,
            sessionId = sessionIdOf(info).orEmpty(),
            command = info.command,
            description = string(info.metadata, "description"),
            status = status,
            startedAtMs = info.time?.started?.toLong()?.let(ToolStartTime::normalizeEpochMs),
            finishedAtMs = info.time?.completed?.toLong()?.let(ToolStartTime::normalizeEpochMs),
            canStop = canStop && status == AgentShellStatus.Running,
        )
    }

    /** `running` | `exited` | `timeout` | `killed`; anything else reads as failed. */
    fun v2Status(
        status: String?,
        exit: Int?,
    ): AgentShellStatus =
        when (status) {
            "running" -> AgentShellStatus.Running
            "exited" -> AgentShellStatus.Exited(exit)
            "timeout" -> AgentShellStatus.TimedOut
            "killed" -> AgentShellStatus.Stopped
            else -> AgentShellStatus.Failed
        }

    internal fun shellEvent(event: OpencodeEvent): AgentShellEvent? {
        val properties = event.rawProperties ?: return null
        return when (event.type) {
            SHELL_CREATED -> {
                val info = properties["info"] as? JsonObject ?: return null
                runCatching { json.decodeFromJsonElement<ShellInfoDto>(info) }.getOrNull()?.let(AgentShellEvent::Created)
            }
            SHELL_EXITED -> {
                val id = string(properties, "id") ?: return null
                AgentShellEvent.Exited(
                    id = id,
                    status = v2Status(string(properties, "status"), int(properties, "exit")),
                    atMs = (properties["created"] as? JsonPrimitive)?.doubleOrNull?.toLong()?.let(ToolStartTime::normalizeEpochMs),
                )
            }
            SHELL_DELETED -> string(properties, "id")?.let(AgentShellEvent::Deleted)
            else -> null
        }
    }

    private fun string(
        json: JsonObject?,
        key: String,
        allowEmpty: Boolean = false,
    ): String? =
        (json?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { allowEmpty || it.isNotEmpty() }

    private fun int(
        json: JsonObject?,
        key: String,
    ): Int? = (json?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.toInt()

    companion object {
        const val BASH_TOOL = "bash"
        const val SHELL_CREATED = "shell.created"
        const val SHELL_EXITED = "shell.exited"
        const val SHELL_DELETED = "shell.deleted"
    }
}
