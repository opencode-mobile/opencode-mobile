package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal val t3Profile =
    ConnectionProfile(
        baseUrl = "http://127.0.0.1:8787",
        username = ConnectionEngine.T3_USERNAME,
        password = "token",
        id = "profile-t3",
        engine = ConnectionEngine.T3,
    )

internal val opencodeProfile =
    ConnectionProfile(
        baseUrl = "http://127.0.0.1:4096",
        username = "opencode",
        password = "secret",
        id = "profile-opencode",
        engine = ConnectionEngine.OPENCODE,
    )

internal class FakeProfileStore(
    var profile: ConnectionProfile?,
) : ConnectionProfileStore {
    val worktrees = mutableMapOf<String, List<String>>()
    var setWorktreeCalls = 0

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = worktrees[serverBaseUrl].orEmpty()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        worktrees[serverBaseUrl] = (addedWorktrees(serverBaseUrl) + worktree).distinct()
    }

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        worktrees[serverBaseUrl] = addedWorktrees(serverBaseUrl) - worktree
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        setWorktreeCalls += 1
        this.worktrees[serverBaseUrl] = worktrees
    }

    override suspend fun clearAll() {
        profile = null
        worktrees.clear()
    }
}

internal class RecordingEventStream : ForegroundEventStream {
    val published = mutableListOf<OpencodeEvent>()
    val states = mutableListOf<EventStreamConnectionState>()
    private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
    override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()
    private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

    override fun start() = Unit

    override suspend fun stop() = Unit

    override fun publishSynthetic(event: OpencodeEvent) {
        published += event
        _events.tryEmit(event)
    }

    override fun publishExternalConnectionState(state: EventStreamConnectionState) {
        states += state
        _connectionState.value = state
    }

    fun types(): List<String> = published.map { it.type + ":" + it.sessionId }
}

internal fun environmentJson(protocolVersion: Int? = 2): String =
    T3Json.parser.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("environmentId", "env-1")
            put("label", "Desk")
            if (protocolVersion != null) put("orchestrationProtocolVersion", protocolVersion)
        },
    )

/** Serves the environment, shell, thread, and ticket endpoints from in-memory JSON. */
internal class FakeT3Http : T3Http {
    var environmentJson: String = environmentJson()
    var shellJson: String = shellJson()
    val threadJson = mutableMapOf<String, String>()
    var environmentRequests = 0
    var shellRequests = 0
    var ticketRequests = 0

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): T3HttpResponse {
        val path = url.removePrefix(t3Profile.baseUrl)
        return when {
            path == T3Urls.ENVIRONMENT_PATH -> {
                environmentRequests += 1
                T3HttpResponse(200, environmentJson)
            }
            path == T3Urls.SHELL_PATH -> {
                shellRequests += 1
                T3HttpResponse(200, shellJson)
            }
            path.startsWith(T3Urls.THREAD_PATH + "/") -> {
                val id = path.removePrefix(T3Urls.THREAD_PATH + "/")
                threadJson[id]?.let { T3HttpResponse(200, it) } ?: T3HttpResponse(404, """{"error":"not found"}""")
            }
            else -> T3HttpResponse(404, "{}")
        }
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
    ): T3HttpResponse {
        val path = url.removePrefix(t3Profile.baseUrl)
        return if (path == T3Urls.WS_TICKET_PATH) {
            ticketRequests += 1
            T3HttpResponse(200, """{"ticket":"ticket-$ticketRequests"}""")
        } else {
            T3HttpResponse(404, "{}")
        }
    }
}

internal data class SentRequest(
    val id: String,
    val tag: String,
    val payload: JsonObject,
) {
    val threadId: String? get() = (payload["threadId"] as? JsonPrimitive)?.contentOrNull
    val afterSequence: Long? get() = (payload["afterSequence"] as? JsonPrimitive)?.longOrNull
}

/** Opens synchronously; the test pushes frames by request id. */
internal class FakeT3Socket : T3Socket {
    private val channel = Channel<T3RpcFrame>(Channel.UNLIMITED)
    override val frames: Flow<T3RpcFrame> = channel.receiveAsFlow()
    private val _state = MutableStateFlow(T3SocketState.Disconnected)
    override val state: StateFlow<T3SocketState> = _state.asStateFlow()

    val subscribes = mutableListOf<SentRequest>()
    val requests = mutableListOf<SentRequest>()
    var connectCalls = 0
    var disconnectCalls = 0
    /** Simulates the socket closing under a request: fails it the way [T3WebSocket.failPending] does. */
    var failNextRequest = false

    /** Simulates the server answering the next request with an `Exit` failure carrying this cause. */
    var rejectNextRequestWith: JsonElement? = null

    /** Success values by RPC tag; any other tag answers `{"sequence":1}`. */
    val responses = mutableMapOf<String, JsonElement>()
    private var counter = 0

    override fun connect(url: String) {
        connectCalls += 1
        _state.value = T3SocketState.Connected
    }

    override suspend fun request(
        tag: String,
        payload: JsonObject,
    ): JsonElement {
        if (_state.value != T3SocketState.Connected) throw T3ConnectionLostException("T3 websocket is not connected")
        if (failNextRequest) {
            failNextRequest = false
            throw T3ConnectionLostException("T3 websocket closed")
        }
        rejectNextRequestWith?.let { cause ->
            rejectNextRequestWith = null
            throw T3RpcException.rejected(cause)
        }
        requests += SentRequest("req-${++counter}", tag, payload)
        return responses[tag] ?: buildJsonObject { put("sequence", 1) }
    }

    override fun sendSubscribe(
        tag: String,
        payload: JsonObject,
    ): String {
        if (_state.value != T3SocketState.Connected) throw T3ConnectionLostException("T3 websocket is not connected")
        val id = "sub-${++counter}"
        subscribes += SentRequest(id, tag, payload)
        return id
    }

    override fun disconnect() {
        disconnectCalls += 1
        _state.value = T3SocketState.Disconnected
    }

    fun drop() {
        _state.value = T3SocketState.Disconnected
    }

    fun chunk(
        requestId: String,
        vararg values: JsonElement,
    ) {
        channel.trySend(T3RpcFrame.Chunk(requestId, values.toList()))
    }

    fun exitFailure(
        requestId: String,
        cause: String,
    ) {
        channel.trySend(T3RpcFrame.ExitFailure(requestId, JsonPrimitive(cause)))
    }

    fun lastSubscribe(tag: String): SentRequest = subscribes.last { it.tag == tag }

    fun subscribes(tag: String): List<SentRequest> = subscribes.filter { it.tag == tag }
}

internal suspend fun activeManager(store: FakeProfileStore): ActiveConnectionManager =
    ActiveConnectionManager(store).also { it.ensureLoaded() }

internal fun threadJson(
    id: String,
    title: String = "Thread $id",
    activeRunId: String? = null,
    pendingRequestId: String? = null,
    pendingRequestKind: String = "approval",
    archivedAt: String? = null,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("title", title)
        put("projectId", "proj-1")
        put("status", "idle")
        if (activeRunId != null) put("activeRunId", activeRunId)
        if (archivedAt != null) put("archivedAt", archivedAt)
        if (pendingRequestId != null) {
            put(
                "pendingRuntimeRequest",
                buildJsonObject {
                    put("id", pendingRequestId)
                    put("kind", pendingRequestKind)
                },
            )
        }
    }

internal fun projectJson(
    id: String,
    root: String,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("title", id)
        put("workspaceRoot", root)
    }

internal fun shellObject(
    threads: List<JsonObject> = emptyList(),
    projects: List<JsonObject> = emptyList(),
    sequence: Long = 1,
): JsonObject =
    buildJsonObject {
        put("snapshotSequence", sequence)
        put("threads", JsonArray(threads))
        put("archivedThreads", JsonArray(emptyList()))
        put("projects", JsonArray(projects))
    }

internal fun shellJson(
    threads: List<JsonObject> = emptyList(),
    projects: List<JsonObject> = emptyList(),
    sequence: Long = 1,
): String = T3Json.parser.encodeToString(JsonObject.serializer(), shellObject(threads, projects, sequence))

internal fun threadSnapshotJson(
    sequence: Long,
    vararg messageIds: String,
): String =
    T3Json.parser.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("snapshotSequence", sequence)
            put(
                "projection",
                buildJsonObject {
                    put(
                        "messages",
                        JsonArray(
                            messageIds.map { id ->
                                buildJsonObject {
                                    put("id", id)
                                    put("role", "user")
                                    put("text", "hello $id")
                                }
                            },
                        ),
                    )
                    put("turnItems", JsonArray(emptyList()))
                },
            )
        },
    )

internal fun shellSnapshotItem(
    threads: List<JsonObject>,
    sequence: Long,
): JsonObject =
    buildJsonObject {
        put("kind", "snapshot")
        put("snapshot", shellObject(threads = threads, sequence = sequence))
    }

internal fun threadUpdatedItem(
    sequence: Long,
    thread: JsonObject,
): JsonObject =
    buildJsonObject {
        put("kind", "thread.updated")
        put("sequence", sequence)
        put("location", "active")
        put("thread", thread)
    }

internal fun threadRemovedItem(
    sequence: Long,
    threadId: String,
): JsonObject =
    buildJsonObject {
        put("kind", "thread.removed")
        put("sequence", sequence)
        put("location", "active")
        put("threadId", threadId)
    }

internal fun archivedSnapshotItem(
    threads: List<JsonObject>,
    sequence: Long,
): JsonObject =
    buildJsonObject {
        put("kind", "snapshot")
        put(
            "snapshot",
            buildJsonObject {
                put("snapshotSequence", sequence)
                put("threads", JsonArray(threads))
                put("projects", JsonArray(emptyList()))
            },
        )
    }

internal fun archivedThreadUpdatedItem(
    sequence: Long,
    thread: JsonObject,
): JsonObject =
    buildJsonObject {
        put("kind", "thread.updated")
        put("sequence", sequence)
        put("thread", thread)
    }

internal fun threadEventItem(
    sequence: Long,
    event: JsonObject,
): JsonObject =
    buildJsonObject {
        put("kind", "event")
        put("sequence", sequence)
        put("event", event)
    }

internal fun messageUpdatedEvent(
    id: String,
    text: String,
): JsonObject =
    buildJsonObject {
        put("type", "message.updated")
        put(
            "payload",
            buildJsonObject {
                put("id", id)
                put("role", "assistant")
                put("text", text)
            },
        )
    }

internal fun harnessJson(
    instanceId: String,
    driver: String = instanceId.substringBefore('-'),
    displayName: String? = null,
    enabled: Boolean = true,
    installed: Boolean = true,
    status: String = "ready",
    authStatus: String = "authenticated",
    supportedRuntimeModes: List<String>? = null,
    requiresNewThreadForModelChange: Boolean? = null,
    models: List<JsonObject> = listOf(harnessModelJson("$instanceId-model", isDefault = true)),
    skills: List<JsonObject> = emptyList(),
    workspaceSnapshots: List<JsonObject> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("instanceId", instanceId)
        put("driver", driver)
        if (displayName != null) put("displayName", displayName)
        put("enabled", enabled)
        put("installed", installed)
        put("version", "1.0.0")
        put("status", status)
        put("auth", buildJsonObject { put("status", authStatus) })
        put("checkedAt", "2026-10-04T00:00:00Z")
        if (supportedRuntimeModes != null) {
            put("supportedRuntimeModes", JsonArray(supportedRuntimeModes.map(::JsonPrimitive)))
        }
        if (requiresNewThreadForModelChange != null) {
            put("requiresNewThreadForModelChange", requiresNewThreadForModelChange)
        }
        put("models", JsonArray(models))
        put("slashCommands", JsonArray(emptyList()))
        put("skills", JsonArray(skills))
        if (workspaceSnapshots.isNotEmpty()) {
            put("workspaceSnapshots", JsonArray(workspaceSnapshots))
        }
    }

internal fun skillJson(
    name: String,
    description: String? = null,
    shortDescription: String? = null,
    enabled: Boolean = true,
    userInvocable: Boolean = true,
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("path", "/skills/$name/SKILL.md")
        if (description != null) put("description", description)
        if (shortDescription != null) put("shortDescription", shortDescription)
        put("enabled", enabled)
        put("userInvocable", userInvocable)
    }

internal fun workspaceSnapshotJson(
    cwd: String,
    skills: List<JsonObject>,
): JsonObject =
    buildJsonObject {
        put("cwd", cwd)
        put("skills", JsonArray(skills))
    }

internal fun harnessModelJson(
    slug: String,
    name: String = slug,
    shortName: String? = null,
    isDefault: Boolean? = null,
    isCustom: Boolean = false,
): JsonObject =
    buildJsonObject {
        put("slug", slug)
        put("name", name)
        if (shortName != null) put("shortName", shortName)
        if (isDefault != null) put("isDefault", isDefault)
        put("isCustom", isCustom)
        put("capabilities", kotlinx.serialization.json.JsonNull)
    }

internal fun serverConfigJson(
    providers: List<JsonObject>,
    defaultInstanceId: String? = null,
    defaultModel: String? = null,
): JsonObject =
    buildJsonObject {
        put("cwd", "/repos")
        put("providers", JsonArray(providers))
        put(
            "settings",
            buildJsonObject {
                if (defaultInstanceId != null) {
                    put(
                        "defaultModelSelection",
                        buildJsonObject {
                            put("instanceId", defaultInstanceId)
                            if (defaultModel != null) put("model", defaultModel)
                        },
                    )
                } else {
                    put("defaultModelSelection", kotlinx.serialization.json.JsonNull)
                }
            },
        )
    }
