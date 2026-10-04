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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A thread subscription the server refused (for example, thread not found). */
data class T3ThreadFailure(
    val threadId: String,
    val error: Throwable,
)

@Singleton
class T3Runtime
    @Inject
    constructor(
        private val api: T3Api,
        private val socket: T3Socket,
        private val activeConnectionManager: ActiveConnectionManager,
        private val eventStream: ForegroundEventStream,
    ) : T3LiveSession {
        internal constructor(
            api: T3Api,
            socket: T3Socket,
            activeConnectionManager: ActiveConnectionManager,
            eventStream: ForegroundEventStream,
            scope: CoroutineScope,
            shellRefreshDebounceMs: Long = SHELL_REFRESH_DEBOUNCE_MS,
        ) : this(api, socket, activeConnectionManager, eventStream) {
            this.scope = scope
            this.shellRefreshDebounceMs = shellRefreshDebounceMs
        }

        private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var shellRefreshDebounceMs: Long = SHELL_REFRESH_DEBOUNCE_MS

        private val connectMutex = Mutex()
        private var connectedProfileId: String? = null
        private var collectorsStarted = false

        /** Guards subscription ids, the observed thread, and the debounce job. */
        private val subscriptionLock = Any()
        private var shellRequestId: String? = null
        private var threadRequestId: String? = null
        private var subscribedThreadId: String? = null

        /** The thread the UI is looking at; survives a socket drop so it is re-subscribed. */
        private var observedThreadId: String? = null
        private var shellRefreshJob: Job? = null

        @Volatile
        private var live = false
        private var liveJob: Job? = null

        private val shellLock = Any()
        private val _shell = MutableStateFlow<T3ShellSnapshot?>(null)
        val shell: StateFlow<T3ShellSnapshot?> = _shell.asStateFlow()

        private val messages = mutableMapOf<String, List<Message>>()
        private val conversationByThread = mutableMapOf<String, List<T3ConversationMessage>>()
        private val turnItemsByThread = mutableMapOf<String, List<T3TurnItem>>()
        private val sequences = ConcurrentHashMap<String, Long>()
        private val messageMutex = Mutex()
        private val imagePreviews = ConcurrentHashMap<String, String>()
        private val imageAttempts = ConcurrentHashMap.newKeySet<String>()
        private val imageInflight = ConcurrentHashMap.newKeySet<String>()
        private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
        val messageLists: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

        private val _threadFailures = MutableSharedFlow<T3ThreadFailure>(extraBufferCapacity = 16)
        val threadFailures: SharedFlow<T3ThreadFailure> = _threadFailures.asSharedFlow()

        val socketState: StateFlow<T3SocketState> = socket.state

        fun activeProfile(): ConnectionProfile? {
            val profile = activeConnectionManager.currentProfile() ?: return null
            return profile.takeIf { it.engine == ConnectionEngine.T3 }
        }

        // region T3LiveSession

        /** Foreground with a T3 profile: keep the socket up, reconnecting with backoff. */
        override fun start() {
            live = true
            if (activeProfile() == null) return
            synchronized(subscriptionLock) {
                if (liveJob?.isActive == true) return
                liveJob = scope.launch { reconnectLoop() }
            }
        }

        /** Background or profile switch: drop the socket and forget everything cached. */
        override suspend fun stop() {
            live = false
            val job: Job?
            val refresh: Job?
            synchronized(subscriptionLock) {
                job = liveJob
                liveJob = null
                refresh = shellRefreshJob
                shellRefreshJob = null
            }
            job?.cancel()
            refresh?.cancel()
            connectMutex.withLock {
                socket.disconnect()
                connectedProfileId = null
                resetSubscriptions()
            }
            clearCaches()
            publishLive(EventStreamConnectionState.Disconnected)
        }

        private suspend fun reconnectLoop() {
            var attempt = 0
            while (scope.isActive && live) {
                val profile = activeProfile() ?: return
                val connected = runCatching { ensureConnected(profile) }.isSuccess
                if (!connected) {
                    attempt += 1
                    delay(backoffMs(attempt))
                    continue
                }
                attempt = 0
                socket.state.first { it == T3SocketState.Disconnected }
                if (!live) return
                attempt = 1
                delay(backoffMs(attempt))
            }
        }

        // endregion

        suspend fun refreshShell(): T3ShellSnapshot {
            val profile = requireProfile()
            val snapshot = api.fetchShell(profile)
            val applied = applyShell(snapshot, publishLifecycle = false)
            runCatching { ensureConnected(profile) }
            return applied
        }

        suspend fun thread(id: String): T3Thread? = (_shell.value ?: refreshShell()).threads.firstOrNull { it.id == id }

        suspend fun loadThreadMessages(threadId: String): List<Message> {
            val profile = requireProfile()
            ensureConnected(profile)
            // Observed from here on: a drop during the REST load is re-subscribed on reconnect.
            synchronized(subscriptionLock) { observedThreadId = threadId }
            val snapshot = api.fetchThread(profile, threadId)
            retryFailedImageLoads(T3MessageMapper.toMessages(snapshot))
            val shown = replaceTranscript(threadId, snapshot)
            subscribeThread(threadId, snapshot.snapshotSequence)
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

        // region connection

        private suspend fun ensureConnected(profile: ConnectionProfile) {
            connectMutex.withLock {
                if (connectedProfileId == profile.id && socket.state.value == T3SocketState.Connected) {
                    return
                }
                if (connectedProfileId != null && connectedProfileId != profile.id) {
                    // Switched environments without a stop(): never show another server's data.
                    clearCaches()
                }
                val ticket = api.fetchWsTicket(profile)
                connectedProfileId = profile.id
                startCollectors()
                // Every socket is a fresh RPC namespace: old request ids never answer again.
                resetSubscriptions()
                socket.connect(T3Urls.websocketUrl(profile.baseUrl, ticket.ticket))
                waitUntilConnected()
                resubscribe()
                publishLive(EventStreamConnectionState.Connected)
            }
        }

        private suspend fun waitUntilConnected() {
            val started = System.currentTimeMillis()
            while (socket.state.value == T3SocketState.Connecting) {
                if (System.currentTimeMillis() - started > CONNECT_TIMEOUT_MS) {
                    throw T3RpcException("Timed out connecting to T3 websocket")
                }
                delay(50)
            }
            if (socket.state.value != T3SocketState.Connected) {
                throw T3RpcException("T3 websocket is not connected")
            }
        }

        private fun startCollectors() {
            synchronized(subscriptionLock) {
                if (collectorsStarted) return
                collectorsStarted = true
            }
            scope.launch {
                socket.frames.collect { frame ->
                    runCatching { handleFrame(frame) }
                }
            }
            scope.launch {
                socket.state.collect { state ->
                    val mapped =
                        when (state) {
                            T3SocketState.Connected -> EventStreamConnectionState.Connected
                            T3SocketState.Connecting -> EventStreamConnectionState.Connecting
                            T3SocketState.Disconnected ->
                                if (live) EventStreamConnectionState.Reconnecting else EventStreamConnectionState.Disconnected
                        }
                    publishLive(mapped)
                }
            }
        }

        /** Caller holds [connectMutex]. */
        private fun resetSubscriptions() {
            synchronized(subscriptionLock) {
                shellRequestId = null
                threadRequestId = null
                subscribedThreadId = null
            }
        }

        /** Caller holds [connectMutex]; the socket is open. */
        private fun resubscribe() {
            subscribeShell()
            val threadId = synchronized(subscriptionLock) { observedThreadId } ?: return
            subscribeThread(threadId, sequences[threadId])
        }

        private fun subscribeShell() {
            val after = _shell.value?.snapshotSequence?.takeIf { it > 0 }
            val id =
                runCatching {
                    socket.sendSubscribe(T3RpcCodec.SUBSCRIBE_SHELL, T3Commands.subscribeShell(after))
                }.getOrNull()
            synchronized(subscriptionLock) { shellRequestId = id }
        }

        private fun subscribeThread(
            threadId: String,
            afterSequence: Long?,
        ) {
            synchronized(subscriptionLock) {
                if (subscribedThreadId == threadId && threadRequestId != null) return
                val id =
                    runCatching {
                        socket.sendSubscribe(
                            T3RpcCodec.SUBSCRIBE_THREAD,
                            T3Commands.subscribeThread(threadId, afterSequence),
                        )
                    }.getOrNull() ?: return
                subscribedThreadId = threadId
                threadRequestId = id
            }
        }

        // endregion

        // region frames

        private suspend fun handleFrame(frame: T3RpcFrame) {
            when (frame) {
                is T3RpcFrame.Chunk -> handleChunk(frame)
                is T3RpcFrame.ExitFailure -> handleExit(frame.requestId, T3RpcException(frame.cause.toString()))
                is T3RpcFrame.ExitSuccess -> handleExit(frame.requestId, null)
                else -> Unit
            }
        }

        private suspend fun handleChunk(frame: T3RpcFrame.Chunk) {
            val (shellId, threadId) =
                synchronized(subscriptionLock) {
                    val forThread = if (frame.requestId == threadRequestId) subscribedThreadId else null
                    shellRequestId to forThread
                }
            when {
                frame.requestId == shellId -> frame.values.forEach { value -> applyShellStreamItem(value) }
                threadId != null -> frame.values.forEach { value -> applyThreadStreamItem(threadId, value) }
            }
        }

        private fun handleExit(
            requestId: String,
            error: Throwable?,
        ) {
            val failedThread =
                synchronized(subscriptionLock) {
                    when (requestId) {
                        shellRequestId -> {
                            shellRequestId = null
                            null
                        }
                        threadRequestId -> {
                            threadRequestId = null
                            subscribedThreadId.also { subscribedThreadId = null }
                        }
                        else -> return
                    }
                }
            if (error == null) return
            if (failedThread != null) {
                _threadFailures.tryEmit(T3ThreadFailure(failedThread, error))
            } else {
                // The shell stream refused; fall back to a REST pass so home still converges.
                scheduleShellRefresh()
            }
        }

        private fun applyShellStreamItem(value: JsonElement) {
            val obj = value as? JsonObject ?: return
            when (obj.string("kind")) {
                "snapshot" -> {
                    val snapshot = runCatching { T3Json.shellSnapshot(obj["snapshot"] ?: return) }.getOrNull() ?: return
                    applyShell(snapshot, publishLifecycle = true)
                }
                "thread.updated" -> {
                    val sequence = obj.long("sequence") ?: return
                    val thread = T3Json.thread(obj["thread"] ?: return) ?: return
                    mutateShell(sequence) { current ->
                        val others = current.threads.filterNot { it.id == thread.id }
                        current.copy(threads = others + thread)
                    }
                }
                "thread.removed" -> {
                    val sequence = obj.long("sequence") ?: return
                    val threadId = obj.string("threadId") ?: return
                    mutateShell(sequence) { current ->
                        current.copy(threads = current.threads.filterNot { it.id == threadId })
                    }
                }
                "project.updated" -> {
                    val sequence = obj.long("sequence") ?: return
                    val project = T3Json.project(obj["project"] ?: return) ?: return
                    mutateShell(sequence) { current ->
                        current.copy(projects = current.projects.filterNot { it.id == project.id } + project)
                    }
                }
                "project.removed" -> {
                    val sequence = obj.long("sequence") ?: return
                    val projectId = obj.string("projectId") ?: return
                    mutateShell(sequence) { current ->
                        current.copy(projects = current.projects.filterNot { it.id == projectId })
                    }
                }
                else -> Unit
            }
        }

        private fun mutateShell(
            sequence: Long,
            transform: (T3ShellSnapshot) -> T3ShellSnapshot,
        ) {
            val current = _shell.value ?: return
            if (sequence <= current.snapshotSequence) return
            applyShell(transform(current).copy(snapshotSequence = sequence), publishLifecycle = true)
        }

        /**
         * Replaces the shell and publishes the diff. REST refreshes publish status and
         * approval changes only; the shell stream also publishes thread lifecycle.
         */
        private fun applyShell(
            next: T3ShellSnapshot,
            publishLifecycle: Boolean,
        ): T3ShellSnapshot {
            val previous: T3ShellSnapshot?
            synchronized(shellLock) {
                previous = _shell.value
                if (previous != null && next.snapshotSequence > 0 && next.snapshotSequence < previous.snapshotSequence) {
                    return previous
                }
                _shell.value = next
            }
            publishShellChanges(previous, next, publishLifecycle)
            return next
        }

        private suspend fun applyThreadStreamItem(
            threadId: String,
            value: JsonElement,
        ) {
            val obj = value as? JsonObject ?: return
            when (obj.string("kind")) {
                "snapshot" -> {
                    val snapshot = T3Json.threadSnapshot(obj)
                    replaceTranscript(threadId, snapshot)
                }
                "event" -> {
                    applyEvent(threadId, obj["event"] as? JsonObject ?: return)
                    obj.long("sequence")?.let { sequence -> sequences[threadId] = sequence }
                }
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
                    // The shell stream carries the resulting status; this is the REST backstop.
                    scheduleShellRefresh()
                }
            }
        }

        /** Coalesces a burst of thread events into one REST shell pass. */
        private fun scheduleShellRefresh() {
            synchronized(subscriptionLock) {
                if (shellRefreshJob?.isActive == true) return
                shellRefreshJob =
                    scope.launch {
                        delay(shellRefreshDebounceMs)
                        if (activeProfile() == null) return@launch
                        runCatching { refreshShell() }
                    }
            }
        }

        // endregion

        // region transcript

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
            transform: (List<T3ConversationMessage>, List<T3TurnItem>) -> Pair<List<T3ConversationMessage>, List<T3TurnItem>>,
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

        /**
         * Drops every cached server fact. The published message map is left alone so an
         * open chat keeps its last transcript until the reload replaces it.
         */
        private suspend fun clearCaches() {
            synchronized(shellLock) { _shell.value = null }
            messageMutex.withLock {
                conversationByThread.clear()
                turnItemsByThread.clear()
                messages.clear()
                sequences.clear()
            }
            imagePreviews.clear()
            imageAttempts.clear()
            imageInflight.clear()
        }

        // endregion

        // region synthetic events

        private fun publishShellChanges(
            previous: T3ShellSnapshot?,
            next: T3ShellSnapshot,
            publishLifecycle: Boolean,
        ) {
            val before = previous?.threads?.associateBy { it.id }.orEmpty()
            val after = next.threads.associateBy { it.id }
            for (threadId in before.keys + after.keys) {
                val old = before[threadId]
                val new = after[threadId]
                if (old == new) continue
                if (publishLifecycle) {
                    val type =
                        when {
                            old == null -> "session.created"
                            new == null -> "session.deleted"
                            else -> "session.updated"
                        }
                    eventStream.publishSynthetic(OpencodeEvent(type = type, sessionId = threadId))
                }
                val wasRunning = old?.let(T3Activity::isRunning) == true
                val running = new?.let(T3Activity::isRunning) == true
                if (wasRunning != running) {
                    eventStream.publishSynthetic(
                        OpencodeEvent(
                            type = "session.status",
                            sessionId = threadId,
                            rawProperties = JsonObject(mapOf("type" to JsonPrimitive(if (running) "running" else "idle"))),
                        ),
                    )
                }
                val oldApproval = old?.let(T3SessionMapper::approvalId)
                val newApproval = new?.let(T3SessionMapper::approvalId)
                if (oldApproval != newApproval) {
                    val type = if (newApproval.isNullOrBlank()) "permission.replied" else "permission.asked"
                    eventStream.publishSynthetic(OpencodeEvent(type = type, sessionId = threadId))
                }
                val oldQuestion = old?.let(T3SessionMapper::questionId)
                val newQuestion = new?.let(T3SessionMapper::questionId)
                if (oldQuestion != newQuestion) {
                    val type = if (newQuestion.isNullOrBlank()) "question.replied" else "question.asked"
                    eventStream.publishSynthetic(OpencodeEvent(type = type, sessionId = threadId))
                }
            }
        }

        private fun publishLive(state: EventStreamConnectionState) {
            if (activeProfile() == null) return
            eventStream.publishExternalConnectionState(state)
        }

        // endregion

        private fun requireProfile(): ConnectionProfile =
            activeProfile() ?: throw T3ProtocolException("No active T3 environment")

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        companion object {
            const val SHELL_REFRESH_DEBOUNCE_MS = 400L
            private const val CONNECT_TIMEOUT_MS = 15_000L
            private const val MAX_BACKOFF_MS = 30_000L

            internal fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
        }
    }
