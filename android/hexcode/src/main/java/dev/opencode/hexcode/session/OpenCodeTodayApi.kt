package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.data.remote.dto.V2FormInfoDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2PermissionRequestDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
            val running = active.await()
            val childIds = children.await().map { it.id }
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
            val busy = root.await().time?.busy
            LiveSnapshot(
                busySince = if (rootId in running) busy ?: clock() else null,
                hasRun = busy != null,
                children = childIds,
                pending = pending,
                inbox = inbox.await().map { InboxEntry(it.id, it.delivery ?: "queue", it.payload?.text.orEmpty()) },
            )
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
    ): String {
        ready()
        return sessions.promptTextV2(sessionId, text, delivery).id
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

    private fun V2SessionDto.listing() =
        SessionListing(
            id = id,
            title = title,
            directory = location?.directory.orEmpty(),
            updatedAt = time?.updated ?: 0,
            busySince = time?.busy,
        )

    private fun V2PermissionRequestDto.pending() =
        PendingItem(id, sessionId, PendingItem.Kind.Approval, PendingItem.approvalText(action, resources, message))

    private fun V2FormInfoDto.pending() =
        PendingItem(
            id,
            sessionId,
            PendingItem.Kind.Question,
            PendingItem.questionText(title, fields.firstOrNull()?.title, fields.firstOrNull()?.description),
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
