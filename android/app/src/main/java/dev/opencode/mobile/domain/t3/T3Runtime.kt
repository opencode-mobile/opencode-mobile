package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.AttachmentThumbnailer
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class T3Runtime
    @Inject
    constructor(
        private val api: T3Api,
        private val socket: T3WebSocket,
        private val activeConnectionManager: ActiveConnectionManager,
        private val eventStream: ForegroundEventStream,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var connectedProfileId: String? = null
        private var frameJob: Job? = null
        private var subscribeRequestId: String? = null
        private var subscribedThreadId: String? = null

        private val _shell = MutableStateFlow<T3ShellSnapshot?>(null)
        val shell: StateFlow<T3ShellSnapshot?> = _shell.asStateFlow()

        private val messages = mutableMapOf<String, List<Message>>()
        private val conversationByThread = mutableMapOf<String, List<T3ConversationMessage>>()
        private val turnItemsByThread = mutableMapOf<String, List<T3TurnItem>>()
        private val sequences = mutableMapOf<String, Long>()
        private val messageMutex = Mutex()
        private val imagePreviews = ConcurrentHashMap<String, String>()
        private val imageAttempts = ConcurrentHashMap.newKeySet<String>()
        private val imageInflight = ConcurrentHashMap.newKeySet<String>()
        private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
        val messageLists: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

        val socketState: StateFlow<T3SocketState> = socket.state

        fun activeProfile(): ConnectionProfile? {
            val profile = activeConnectionManager.currentProfile() ?: return null
            return profile.takeIf { it.engine == ConnectionEngine.T3 }
        }

        suspend fun refreshShell(): T3ShellSnapshot {
            val profile = requireProfile()
            val snapshot = api.fetchShell(profile)
            val previous = _shell.value
            _shell.value = snapshot
            publishPermissionChanges(previous, snapshot)
            runCatching { ensureConnected(profile) }
            return snapshot
        }

        suspend fun thread(id: String): T3Thread? = (_shell.value ?: refreshShell()).threads.firstOrNull { it.id == id }

        suspend fun loadThreadMessages(threadId: String): List<Message> {
            val profile = requireProfile()
            ensureConnected(profile)
            val snapshot = api.fetchThread(profile, threadId)
            retryFailedImageLoads(T3MessageMapper.toMessages(snapshot))
            val shown = replaceTranscript(threadId, snapshot)
            subscribe(threadId, snapshot.snapshotSequence)
            return shown
        }

        fun cachedMessages(threadId: String): List<Message> = messages[threadId].orEmpty()

        suspend fun dispatch(command: JsonObject): JsonElement {
            val profile = requireProfile()
            ensureConnected(profile)
            return socket.request(T3RpcCodec.DISPATCH_COMMAND, command)
        }

        suspend fun launchThread(payload: JsonObject): String {
            val profile = requireProfile()
            ensureConnected(profile)
            val value = socket.request(T3RpcCodec.LAUNCH_THREAD, payload)
            val threadId = T3Json.launchThreadId(value)
            runCatching { refreshShell() }
            return threadId
        }

        suspend fun pair(
            baseUrl: String,
            pairingToken: String,
        ): T3PairingResult = api.pair(baseUrl, pairingToken)

        private suspend fun ensureConnected(profile: ConnectionProfile) {
            mutex.withLock {
                if (connectedProfileId == profile.id && socket.state.value == T3SocketState.Connected) {
                    return
                }
                val ticket = api.fetchWsTicket(profile)
                connectedProfileId = profile.id
                startFrameCollector()
                socket.connect(T3Urls.websocketUrl(profile.baseUrl, ticket.ticket))
                waitUntilConnected()
                publishLive(EventStreamConnectionState.Connected)
            }
        }

        private suspend fun waitUntilConnected() {
            val started = System.currentTimeMillis()
            while (socket.state.value == T3SocketState.Connecting) {
                if (System.currentTimeMillis() - started > 15_000) {
                    throw T3RpcException("Timed out connecting to T3 websocket")
                }
                kotlinx.coroutines.delay(50)
            }
            if (socket.state.value != T3SocketState.Connected) {
                throw T3RpcException("T3 websocket is not connected")
            }
        }

        private fun startFrameCollector() {
            if (frameJob?.isActive == true) return
            frameJob =
                scope.launch {
                    socket.frames.collect { frame ->
                        when (frame) {
                            is T3RpcFrame.Chunk -> handleChunks(frame)
                            is T3RpcFrame.ExitFailure -> Unit
                            else -> Unit
                        }
                    }
                }
            scope.launch {
                socket.state.collect { state ->
                    val live =
                        when (state) {
                            T3SocketState.Connected -> EventStreamConnectionState.Connected
                            T3SocketState.Connecting -> EventStreamConnectionState.Connecting
                            T3SocketState.Disconnected -> EventStreamConnectionState.Disconnected
                        }
                    publishLive(live)
                }
            }
        }

        private fun subscribe(
            threadId: String,
            afterSequence: Long,
        ) {
            if (subscribedThreadId == threadId && subscribeRequestId != null) return
            subscribedThreadId = threadId
            subscribeRequestId =
                socket.sendSubscribe(
                    T3RpcCodec.SUBSCRIBE_THREAD,
                    T3Commands.subscribeThread(threadId, afterSequence),
                )
        }

        private suspend fun handleChunks(frame: T3RpcFrame.Chunk) {
            if (frame.requestId != subscribeRequestId) return
            val threadId = subscribedThreadId ?: return
            frame.values.forEach { value -> applyStreamItem(threadId, value) }
        }

        private suspend fun applyStreamItem(
            threadId: String,
            value: JsonElement,
        ) {
            val obj = value as? JsonObject ?: return
            when (obj.string("kind")) {
                "snapshot" -> {
                    val snapshot = T3Json.threadSnapshot(obj)
                    replaceTranscript(threadId, snapshot)
                }
                "event" -> applyEvent(threadId, obj["event"] as? JsonObject ?: return)
                "synchronized" -> Unit
            }
        }

        private suspend fun applyEvent(
            threadId: String,
            event: JsonObject,
        ) {
            when (event.string("type")) {
                "message.updated" -> {
                    val payload = event["payload"] ?: return
                    val message = T3Json.conversationMessage(payload) ?: return
                    updateTranscript(threadId) { conversation, items ->
                        T3MessageMapper.upsertConversationMessage(conversation, message) to items
                    }
                }
                "turn-item.updated" -> {
                    val payload = event["payload"] ?: return
                    val item = T3Json.turnItem(payload) ?: return
                    updateTranscript(threadId) { conversation, items ->
                        conversation to T3MessageMapper.upsertTurnItem(items, item)
                    }
                }
                "run.created", "run.updated", "runtime-request.updated", "thread.status", "thread.updated" -> {
                    scope.launch {
                        runCatching { refreshShell() }
                        val running = thread(threadId)?.let(T3Activity::isRunning) == true
                        eventStream.publishSynthetic(
                            OpencodeEvent(
                                type = "session.status",
                                sessionId = threadId,
                                rawProperties =
                                    JsonObject(
                                        mapOf(
                                            "type" to kotlinx.serialization.json.JsonPrimitive(if (running) "running" else "idle"),
                                        ),
                                    ),
                            ),
                        )
                    }
                }
            }
        }

        private suspend fun replaceTranscript(
            threadId: String,
            snapshot: T3ThreadSnapshot,
        ): List<Message> {
            val (shown, pending) =
                messageMutex.withLock {
                    conversationByThread[threadId] = snapshot.messages
                    turnItemsByThread[threadId] = snapshot.turnItems
                    publishDerived(threadId, snapshot.snapshotSequence)
                }
            scheduleImagePreviews(threadId, pending)
            return shown
        }

        private suspend fun updateTranscript(
            threadId: String,
            transform: (
                List<T3ConversationMessage>,
                List<T3TurnItem>,
            ) -> Pair<List<T3ConversationMessage>, List<T3TurnItem>>,
        ) {
            val pending =
                messageMutex.withLock {
                    val (conversation, items) =
                        transform(
                            conversationByThread[threadId].orEmpty(),
                            turnItemsByThread[threadId].orEmpty(),
                        )
                    conversationByThread[threadId] = conversation
                    turnItemsByThread[threadId] = items
                    publishDerived(threadId, sequence = null).second
                }
            scheduleImagePreviews(threadId, pending)
        }

        /** Caller holds [messageMutex]. */
        private fun publishDerived(
            threadId: String,
            sequence: Long?,
        ): Pair<List<Message>, List<MessagePart.File>> {
            if (sequence != null) sequences[threadId] = sequence
            val derived =
                T3MessageMapper.transcriptMessages(
                    conversationByThread[threadId].orEmpty(),
                    turnItemsByThread[threadId].orEmpty(),
                )
            val decorated = T3MessageMapper.applyingImagePreviews(derived, imagePreviews)
            messages[threadId] = decorated
            publishMessages()
            return decorated to claimImageLoads(decorated)
        }

        private fun retryFailedImageLoads(messages: List<Message>) {
            T3MessageMapper.imageFilesNeedingPreview(messages).forEach { part ->
                if (!imagePreviews.containsKey(part.id) && !imageInflight.contains(part.id)) {
                    imageAttempts.remove(part.id)
                }
            }
        }

        private fun claimImageLoads(messages: List<Message>): List<MessagePart.File> =
            T3MessageMapper.imageFilesNeedingPreview(messages).filter { part ->
                !imagePreviews.containsKey(part.id) && imageAttempts.add(part.id)
            }

        private fun scheduleImagePreviews(
            threadId: String,
            pending: List<MessagePart.File>,
        ) {
            pending.forEach { part ->
                imageInflight.add(part.id)
                scope.launch {
                    val dataUrl =
                        try {
                            fetchImagePreview(part)
                        } catch (_: Throwable) {
                            null
                        }
                    if (dataUrl != null) imagePreviews[part.id] = dataUrl
                    imageInflight.remove(part.id)
                    if (dataUrl == null) return@launch
                    messageMutex.withLock {
                        val current = messages[threadId] ?: return@withLock
                        val shown = T3MessageMapper.applyingImagePreviews(current, imagePreviews)
                        if (shown == current) return@withLock
                        messages[threadId] = shown
                        publishMessages()
                    }
                }
            }
        }

        private suspend fun fetchImagePreview(part: MessagePart.File): String? {
            val profile = activeProfile() ?: return null
            if (socket.state.value != T3SocketState.Connected) return null
            val value =
                socket.request(
                    T3Assets.CREATE_URL,
                    T3Assets.createUrlPayload(part.id, part.filename, part.mime),
                )
            val relative = T3Assets.relativeUrl(value) ?: return null
            val bytes = api.fetchAssetBytes(T3Urls.join(profile.baseUrl, relative)) ?: return null
            val jpeg = AttachmentThumbnailer.create(bytes) ?: return null
            return AttachmentDataUrl.make(jpeg, T3Assets.PREVIEW_MIME)
        }

        private fun publishMessages() {
            _messages.value = messages.toMap()
        }

        private fun publishPermissionChanges(
            previous: T3ShellSnapshot?,
            next: T3ShellSnapshot,
        ) {
            val before = previous?.threads?.associate { it.id to T3SessionMapper.approvalId(it) }.orEmpty()
            val after = next.threads.associate { it.id to T3SessionMapper.approvalId(it) }
            for (threadId in before.keys + after.keys) {
                if (before[threadId] == after[threadId]) continue
                val type = if (after[threadId].isNullOrBlank()) "permission.replied" else "permission.asked"
                eventStream.publishSynthetic(OpencodeEvent(type = type, sessionId = threadId))
            }
        }

        private fun publishLive(state: EventStreamConnectionState) {
            if (activeProfile() == null) return
            eventStream.publishExternalConnectionState(state)
        }

        private fun requireProfile(): ConnectionProfile =
            activeProfile() ?: throw T3ProtocolException("No active T3 environment")

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
