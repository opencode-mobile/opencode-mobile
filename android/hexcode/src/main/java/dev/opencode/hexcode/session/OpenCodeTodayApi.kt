package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.data.remote.dto.V2FormInfoDto
import dev.opencode.mobile.data.remote.dto.V2FormReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto
import dev.opencode.mobile.data.remote.dto.V2PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.domain.connection.ServerProtocol
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Today's session over OpenCode v2.2.5's API (m2-api-notes.md); the calls themselves are in `:opencode-kit`. */
class OpenCodeTodayApi(
    private val sessions: SessionsApi,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Waits until the saved server is bound; the app binds it while home is already opening. */
    private val ready: suspend () -> Unit = {},
) : DailySessionApi,
    LiveSessionApi {
    override suspend fun sessionsPage(
        directory: String,
        search: String,
        limit: Int,
        cursor: String?,
    ): SessionListingPage {
        ready()
        val page =
            sessions.listSessionsPageV2(
                directory = directory,
                search = search,
                rootsOnly = true,
                archived = false,
                limit = limit,
                cursor = cursor,
            )
        return SessionListingPage(page.data.map { it.listing() }, page.cursor?.next?.takeIf { it.isNotBlank() })
    }

    override suspend fun create(
        title: String,
        directory: String,
        model: ModelChoice?,
    ): SessionListing {
        ready()
        return sessions.createSessionV2(directory = directory, title = title, model = model?.ref()).listing()
    }

    override suspend fun putMessageRules(sessionId: String) {
        ready()
        sessions.putInstructionEntryV2(sessionId, SessionPrompts.MESSAGE_RULES_ID, SessionPrompts.MESSAGE_RULES)
    }

    /**
     * Busy from `/api/session/active` (never from comparing `time.busy` with `time.idle`), since when from
     * `time.busy`, the subagents, the pending approvals and questions of the session and each running subagent (oldest
     * first: OpenCode's ids grow with time), and the inbox.
     */
    override suspend fun snapshot(rootId: String): LiveSnapshot =
        coroutineScope {
            ready()
            val active = async { sessions.getActiveSessionsV2() }
            val root = async { sessions.getSessionV2(rootId) }
            val children = async { sessions.listSessionsV2(parentId = rootId) }
            val inbox = async { sessions.listInboxV2(rootId) }
            val todos = async { runCatching { todos(rootId) } }
            val running = active.await()
            val childList = children.await()
            val childIds = childList.map { it.id }
            val owners = listOf(rootId) + childIds.filter { it in running }
            val pending =
                owners
                    .map { id ->
                        async {
                            sessions.listSessionPermissionsV2(id).map { it.pending() } +
                                sessions.listSessionFormsV2(id).map { it.pending() }
                        }
                    }.awaitAll()
                    .flatten()
                    .sortedBy { it.id.substringAfter('_') }
            val time = root.await().time
            val busy = time?.busy
            LiveSnapshot(
                busySince = if (rootId in running) busy ?: clock() else null,
                hasRun = busy != null,
                children = childIds,
                pending = pending,
                // A server note (a subagent's `<subagent …>`) isn't a message from the user.
                inbox =
                    inbox.await().filter { it.type != "synthetic" }.map { item ->
                        InboxEntry(
                            item.id,
                            item.delivery ?: "queue",
                            item.payload?.text.orEmpty(),
                            item.payload
                                ?.files
                                .orEmpty()
                                .map { InboxFile(it.name, it.mime) },
                        )
                    },
                runningChildren = childIds.filter { it in running },
                todos = todos.await().getOrNull().orEmpty(),
                // A list that couldn't be read keeps the one shown; the rest of the state still counts.
                todosRead = todos.await().isSuccess,
                updatedAt = time?.updated,
                childInfo = childList.associate { it.id to it.child() },
                projectId = root.await().projectId,
            )
        }

    /**
     * The agent's to-do list: the newest completed `todowrite` part among the session's assistant messages, newest
     * first, a page at a time, at most [TODO_PAGES] pages. Empty when there is none.
     */
    private suspend fun todos(sessionId: String): List<TodoItem> {
        var cursor: String? = null
        repeat(TODO_PAGES) {
            val page = sessions.listSessionMessagesPageV2(sessionId, type = "assistant", order = "desc", limit = TODO_PAGE, cursor = cursor)
            TodoList.fromMessages(page.data)?.let { return it }
            cursor = page.cursor?.next?.takeIf { it.isNotBlank() && page.data.isNotEmpty() } ?: return emptyList()
        }
        return emptyList()
    }

    override suspend fun updatedAt(sessionId: String): Long? {
        ready()
        return sessions.getSessionV2(sessionId).time?.updated
    }

    override suspend fun summarise(
        sessionId: String,
        prompt: String,
    ): String {
        ready()
        return sessions.generateSessionTextV2(sessionId, prompt, timeoutMs = SummaryRefresher.TIMEOUT_MS)
    }

    override suspend fun send(
        sessionId: String,
        text: String,
        delivery: String,
        images: List<PromptImage>,
    ): String {
        ready()
        // About 3 MB for 4 photos once encoded: small enough to build in memory (attach-notes.md, OpenCode Mobile).
        val files = withContext(Dispatchers.IO) { images.map { V2PromptFileDto(uri = it.dataUrl(), name = it.name) } }
        return sessions.promptTextV2(sessionId, text, delivery, files).id
    }

    override suspend fun cancelQueued(
        sessionId: String,
        itemId: String,
    ) {
        ready()
        sessions.cancelInboxV2(sessionId, itemId)
    }

    override suspend fun switchModel(
        sessionId: String,
        model: ModelChoice,
    ) {
        ready()
        sessions.switchSessionModelV2(sessionId, model.ref())
    }

    override suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: ApprovalDecision,
        message: String?,
    ) {
        ready()
        settling { sessions.respondToPermissionV2(sessionId, requestId, V2PermissionReplyRequestDto(decision.wire, message)) }
    }

    override suspend fun replyForm(
        sessionId: String,
        formId: String,
        answer: JsonObject,
    ) {
        ready()
        settling { sessions.replyToFormV2(sessionId, formId, V2FormReplyRequestDto(answer)) }
    }

    override suspend fun dismissForm(
        sessionId: String,
        formId: String,
        message: String,
    ) {
        ready()
        settling { sessions.cancelFormV2(sessionId, formId, message) }
    }

    override suspend fun interrupt(sessionId: String): Boolean {
        ready()
        return sessions.interruptSessionResultV2(sessionId)
    }

    override suspend fun activeSessions(): Set<String> {
        ready()
        return sessions.getActiveSessionsV2().keys
    }

    /** Today's subagents and which run (`/api/session/active`), and the agent shells of the session and its subagents. */
    override suspend fun running(
        rootId: String,
        directory: String,
    ): RunningRead =
        coroutineScope {
            ready()
            val active = async { sessions.getActiveSessionsV2().keys }
            val children = async { sessions.listSessionsV2(parentId = rootId) }
            val shells = async { sessions.listShellsV2(directory) }
            val childList = children.await()
            val owners = setOf(rootId) + childList.map { it.id }
            RunningRead(
                children = childList.associate { it.id to it.child() },
                runningChildren = childList.map { it.id }.filter { it in active.await() }.toSet(),
                shells =
                    shells
                        .await()
                        .filter { it.status.ifEmpty { "running" } == "running" }
                        .mapNotNull { shell ->
                            val owner = (shell.metadata?.get("sessionID") as? JsonPrimitive)?.contentOrNull
                            if (owner !in owners) return@mapNotNull null
                            ShellRun(shell.id, shell.command, shell.time?.started?.toLong() ?: clock())
                        },
            )
        }

    override suspend fun recent(sessionId: String): List<RecentStep> {
        ready()
        val page = sessions.listSessionMessagesPageV2(sessionId, type = "assistant", order = "desc", limit = RECENT_MESSAGES)
        return RecentSteps.from(page.data)
    }

    /** `404` (gone, or never there) and `409` (already settled) mean the request was answered elsewhere or its run ended. */
    private suspend fun settling(call: suspend () -> Unit) {
        try {
            call()
        } catch (error: ResponseException) {
            val status = error.response.status.value
            if (status == 404 || status == 409) throw RequestGoneException(error.message)
            throw error
        }
    }

    private companion object {
        /** Assistant messages Recent reads its steps from. */
        const val RECENT_MESSAGES = 20

        /** Assistant messages per page, and pages, read looking for the to-do list. */
        const val TODO_PAGE = 50
        const val TODO_PAGES = 4
    }

    private fun V2SessionDto.listing() =
        SessionListing(
            id = id,
            title = title,
            directory = location?.directory.orEmpty(),
            updatedAt = time?.updated ?: 0,
            busySince = time?.busy,
        )

    private fun V2SessionDto.child() = ChildInfo(title, agent, time?.busy)

    private fun V2PermissionRequestDto.pending() =
        PendingItem(
            id,
            sessionId,
            PendingItem.Kind.Approval,
            PendingItem.approvalText(action, resources, message),
            approval =
                ApprovalRequest(
                    action = action,
                    resources = resources,
                    save = save,
                    message = message?.ifBlank { null },
                    files = ApprovalFile.read(metadata),
                ),
        )

    private fun V2FormInfoDto.pending() =
        PendingItem(
            id,
            sessionId,
            PendingItem.Kind.Question,
            PendingItem.questionText(title, fields.firstOrNull()?.title, fields.firstOrNull()?.description),
            form =
                QuestionForm(
                    title = title?.ifBlank { null },
                    fields =
                        fields.map { field ->
                            QuestionField(
                                key = field.key,
                                title = field.title?.ifBlank { null },
                                description = field.description?.ifBlank { null },
                                type = field.type ?: QuestionField.SINGLE,
                                options =
                                    field.options.mapNotNull { option ->
                                        val label = option.label ?: option.value ?: return@mapNotNull null
                                        QuestionOption(option.value ?: label, label, option.description?.ifBlank { null })
                                    },
                                custom = field.custom == true,
                            )
                        },
                ),
        )
}

/** The session's model as the server takes it: provider, model and level (no level is the model's default). */
fun ModelChoice.ref(): V2ModelRefDto = V2ModelRefDto(id = modelId, providerId = providerId, variant = variant)

/** The event stream over `:opencode-kit`'s [EventsApi], asking only for [SessionEvent.TYPES]. */
class EventsApiTransport(
    private val events: () -> EventsApi,
    private val ready: suspend () -> Unit = {},
) : EventTransport {
    override suspend fun connect(
        onActivity: () -> Unit,
        onLine: suspend (String) -> Unit,
    ): SseAttemptOutcome {
        ready()
        return events().streamGlobalEvents(
            protocol = ServerProtocol.V2,
            onActivity = { onActivity() },
            types = SessionEvent.TYPES,
            onDataLine = onLine,
        )
    }
}
