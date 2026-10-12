package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.AttachmentThumbnailer
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
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
import java.time.Instant
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

        /**
         * Profiles whose environment descriptor was re-checked on a fresh socket this
         * app session, and the ones that failed the protocol check. A failed profile
         * never reconnects until the next [stop] (profile switch or background), so a
         * mismatch cannot loop; a passed profile is not fetched again this session.
         */
        private val validatedProfileIds = mutableSetOf<String>()
        private val protocolFailures = mutableMapOf<String, T3ProtocolException>()

        /** Guards subscription ids, the observed thread, and the debounce job. */
        private val subscriptionLock = Any()
        private var shellRequestId: String? = null
        private var archiveRequestId: String? = null
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

        /** The provider catalogue of the active profile; null until first loaded. */
        private val serverConfigCache = MutableStateFlow<T3ServerConfig?>(null)
        private var serverConfigProfileId: String? = null
        private val serverConfigMutex = Mutex()

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
            if (socket.state.value != T3SocketState.Connected) publishLive(EventStreamConnectionState.Connecting)
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
            synchronized(subscriptionLock) { protocolFailures.clear() }
            clearCaches()
            DiagnosticLog.info(DiagnosticLogArea.Connection, "T3 socket released (stop)")
            publishLive(EventStreamConnectionState.Disconnected)
        }

        private suspend fun reconnectLoop() {
            var attempt = 0
            while (scope.isActive && live) {
                val profile = activeProfile() ?: return
                val failure = runCatching { ensureConnected(profile) }.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure is T3ProtocolException) {
                    // Terminal: the state and the message stream already carry it.
                    DiagnosticLog.warning(DiagnosticLogArea.Connection, "T3 reconnect stopped: protocol mismatch")
                    return
                }
                if (failure != null) {
                    attempt += 1
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Connection,
                        "T3 connect failed attempt=$attempt retryMs=${backoffMs(attempt)}",
                        failure,
                    )
                    delay(backoffMs(attempt))
                    continue
                }
                attempt = 0
                socket.state.first { it == T3SocketState.Disconnected }
                if (!live) return
                attempt = 1
                DiagnosticLog.info(DiagnosticLogArea.Connection, "T3 socket dropped; reconnecting in ${backoffMs(attempt)}ms")
                delay(backoffMs(attempt))
            }
        }

        // endregion

        suspend fun refreshShell(): T3ShellSnapshot =
            guarded {
                val profile = requireProfile()
                val snapshot = api.fetchShell(profile)
                // Apply before connecting so the shell subscription resumes after this sequence.
                var applied = applyShell(mergeArchive(snapshot, null), publishLifecycle = false)
                runCatching { ensureConnected(profile) }
                val fromSocket = runCatching { archivedThreadsFromSocket() }.getOrNull()
                if (fromSocket != null) {
                    applied = applyShell(mergeArchive(applied, fromSocket), publishLifecycle = false)
                }
                applied
            }

        /** Active list first, then the archive. */
        suspend fun thread(id: String): T3Thread? = (_shell.value ?: refreshShell()).thread(id)

        /**
         * Moves [id] into the archive list. The archive command is accepted before
         * the shell stream catches up, and the active HTTP snapshot does not carry
         * archived threads.
         */
        fun noteArchived(id: String) {
            val current = _shell.value ?: return
            val thread = current.thread(id) ?: return
            val archived = if (thread.archivedAt.isNullOrBlank()) thread.copy(archivedAt = Instant.now().toString()) else thread
            applyShell(
                current.copy(
                    threads = current.threads.filterNot { it.id == id },
                    archivedThreads = current.archivedThreads.filterNot { it.id == id } + archived,
                ),
                publishLifecycle = true,
            )
        }

        /** Moves [id] back to the active list and clears `archivedAt`. */
        fun noteUnarchived(id: String) {
            val current = _shell.value ?: return
            val thread = current.thread(id) ?: return
            applyShell(
                current.copy(
                    threads = current.threads.filterNot { it.id == id } + thread.copy(archivedAt = null),
                    archivedThreads = current.archivedThreads.filterNot { it.id == id },
                ),
                publishLifecycle = true,
            )
        }

        suspend fun loadThreadMessages(threadId: String): List<Message> =
            guarded {
                val profile = requireProfile()
                ensureConnected(profile)
                // Observed from here on: a drop during the REST load is re-subscribed on reconnect.
                synchronized(subscriptionLock) { observedThreadId = threadId }
                val snapshot = api.fetchThread(profile, threadId)
                retryFailedImageLoads(T3MessageMapper.toMessages(snapshot))
                val shown = replaceTranscript(threadId, snapshot)
                subscribeThread(threadId, snapshot.snapshotSequence)
                shown
            }

        fun cachedMessages(threadId: String): List<Message> = messages[threadId].orEmpty()

        suspend fun dispatch(command: JsonObject): JsonElement =
            try {
                guarded {
                    val profile = requireProfile()
                    ensureConnected(profile)
                    socket.request(T3RpcCodec.DISPATCH_COMMAND, command)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                DiagnosticLog.warning(
                    DiagnosticLogArea.Chat,
                    T3Errors.diagnosticMessage("T3 dispatch failed type=${command.string("type") ?: "-"}", error),
                    error,
                )
                throw error
            }

        suspend fun launchThread(payload: JsonObject): String =
            guarded {
                val profile = requireProfile()
                ensureConnected(profile)
                val value = socket.request(T3RpcCodec.LAUNCH_THREAD, payload)
                val threadId = T3Json.launchThreadId(value)
                runCatching { refreshShell() }
                threadId
            }

        /**
         * The provider catalogue (`server.getConfig`) for the active profile. Served from
         * the per-profile cache unless [forceRefresh]; a reconnect refreshes a loaded cache.
         */
        suspend fun serverConfig(forceRefresh: Boolean = false): T3ServerConfig =
            guarded {
                val profile = requireProfile()
                serverConfigMutex.withLock {
                    val cached = serverConfigCache.value
                    if (!forceRefresh && cached != null && serverConfigProfileId == profile.id) return@withLock cached
                    ensureConnected(profile)
                    val value = socket.request(T3RpcCodec.SERVER_GET_CONFIG, JsonObject(emptyMap()))
                    val config = T3Json.serverConfig(value)
                    if (activeProfile()?.id == profile.id) {
                        serverConfigProfileId = profile.id
                        serverConfigCache.value = config
                    }
                    DiagnosticLog.info(
                        DiagnosticLogArea.Connection,
                        "T3 server config harnesses=${config.harnesses.size} usable=${config.harnesses.count { it.isUsable }}",
                    )
                    config
                }
            }

        /** The cached catalogue when it belongs to the active profile. */
        fun cachedServerConfig(): T3ServerConfig? {
            val profile = activeProfile() ?: return null
            return serverConfigCache.value?.takeIf { serverConfigProfileId == profile.id }
        }

        /** Refreshes a catalogue that was already loaded; a never-loaded one stays lazy. */
        private fun scheduleServerConfigRefresh() {
            if (serverConfigCache.value == null) return
            scope.launch { runCatching { serverConfig(forceRefresh = true) } }
        }

        suspend fun pair(
            baseUrl: String,
            pairingToken: String,
        ): T3PairingResult = api.pair(baseUrl, pairingToken)

        // region connection

        private suspend fun ensureConnected(profile: ConnectionProfile) {
            connectMutex.withLock {
                synchronized(subscriptionLock) { protocolFailures[profile.id] }?.let { failure ->
                    publishLive(EventStreamConnectionState.AuthFailed)
                    throw failure
                }
                if (connectedProfileId == profile.id && socket.state.value == T3SocketState.Connected) {
                    return
                }
                if (connectedProfileId != null && connectedProfileId != profile.id) {
                    // Switched environments without a stop(): never show another server's data.
                    clearCaches()
                }
                publishLive(EventStreamConnectionState.Connecting)
                validateProtocol(profile)
                DiagnosticLog.info(DiagnosticLogArea.Connection, "T3 socket connecting profile=${profile.id}")
                val ticket = api.fetchWsTicket(profile)
                connectedProfileId = profile.id
                startCollectors()
                // Every socket is a fresh RPC namespace: old request ids never answer again.
                resetSubscriptions()
                socket.connect(T3Urls.websocketUrl(profile.baseUrl, ticket.ticket))
                waitUntilConnected()
                resubscribe()
                publishLive(EventStreamConnectionState.Connected)
                scheduleServerConfigRefresh()
            }
        }

        /**
         * Re-checks the environment descriptor before the first socket of this app
         * session for [profile]. A protocol mismatch is terminal: it is remembered,
         * shown on the connection state, and thrown so the caller's message stream
         * or send path surfaces it. Any other failure (host down, descriptor missing) is logged and
         * the connect proceeds, so an unreachable descriptor endpoint never blocks a
         * working environment.
         */
        private suspend fun validateProtocol(profile: ConnectionProfile) {
            if (synchronized(subscriptionLock) { profile.id in validatedProfileIds }) return
            try {
                val descriptor = api.validateEnvironment(profile.baseUrl)
                synchronized(subscriptionLock) { validatedProfileIds += profile.id }
                DiagnosticLog.info(
                    DiagnosticLogArea.Connection,
                    "T3 environment validated protocol=${descriptor.orchestrationProtocolVersion} env=${descriptor.environmentId}",
                )
            } catch (failure: T3ProtocolException) {
                synchronized(subscriptionLock) { protocolFailures[profile.id] = failure }
                DiagnosticLog.error(DiagnosticLogArea.Connection, "T3 protocol mismatch; not reconnecting", failure)
                publishLive(EventStreamConnectionState.AuthFailed)
                throw failure
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "T3 environment check failed; connecting anyway", failure)
            }
        }

        private suspend fun waitUntilConnected() {
            val started = System.currentTimeMillis()
            while (socket.state.value == T3SocketState.Connecting) {
                if (System.currentTimeMillis() - started > CONNECT_TIMEOUT_MS) {
                    throw T3ConnectionLostException("Timed out connecting to T3 websocket")
                }
                delay(50)
            }
            if (socket.state.value != T3SocketState.Connected) {
                throw T3ConnectionLostException("T3 websocket is not connected")
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
                    DiagnosticLog.debug(DiagnosticLogArea.Connection, "T3 socket state=$state live=$live")
                    publishLive(mapped)
                }
            }
        }

        /** Caller holds [connectMutex]. */
        private fun resetSubscriptions() {
            synchronized(subscriptionLock) {
                shellRequestId = null
                archiveRequestId = null
                threadRequestId = null
                subscribedThreadId = null
            }
        }

        /** Caller holds [connectMutex]; the socket is open. */
        private fun resubscribe() {
            subscribeShell()
            subscribeArchivedShell()
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

        private fun subscribeArchivedShell() {
            val id =
                runCatching {
                    socket.sendSubscribe(T3RpcCodec.SUBSCRIBE_ARCHIVED_SHELL, T3Commands.subscribeArchivedShell())
                }.getOrNull()
            synchronized(subscriptionLock) { archiveRequestId = id }
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
                is T3RpcFrame.ExitFailure -> handleExit(frame.requestId, T3RpcException.rejected(frame.cause))
                is T3RpcFrame.ExitSuccess -> handleExit(frame.requestId, null)
                else -> Unit
            }
        }

        private suspend fun handleChunk(frame: T3RpcFrame.Chunk) {
            val (shellId, archiveId, threadId) =
                synchronized(subscriptionLock) {
                    val forThread = if (frame.requestId == threadRequestId) subscribedThreadId else null
                    Triple(shellRequestId, archiveRequestId, forThread)
                }
            when {
                frame.requestId == shellId -> frame.values.forEach { value -> applyShellStreamItem(value) }
                frame.requestId == archiveId -> frame.values.forEach { value -> applyArchivedStreamItem(value) }
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
                        archiveRequestId -> {
                            archiveRequestId = null
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
            DiagnosticLog.warning(
                DiagnosticLogArea.Connection,
                "T3 subscription refused thread=${failedThread ?: "shell"} cause=${T3Errors.detail(error) ?: "-"}",
            )
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
                    applyShell(preservingArchive(snapshot), publishLifecycle = true)
                }
                "thread.updated" -> {
                    val sequence = obj.long("sequence") ?: return
                    val thread = T3Json.thread(obj["thread"] ?: return) ?: return
                    if (obj.string("location") == "archive") {
                        mutateArchive(sequence) { current ->
                            current.copy(
                                threads = current.threads.filterNot { it.id == thread.id },
                                archivedThreads = current.archivedThreads.filterNot { it.id == thread.id } + thread,
                            )
                        }
                    } else {
                        mutateShell(sequence) { current ->
                            current.copy(
                                threads = current.threads.filterNot { it.id == thread.id } + thread,
                                archivedThreads = current.archivedThreads.filterNot { it.id == thread.id },
                            )
                        }
                    }
                }
                "thread.removed" -> {
                    val sequence = obj.long("sequence") ?: return
                    val threadId = obj.string("threadId") ?: return
                    if (obj.string("location") == "archive") {
                        mutateArchive(sequence) { current ->
                            current.copy(archivedThreads = current.archivedThreads.filterNot { it.id == threadId })
                        }
                    } else {
                        mutateShell(sequence) { current ->
                            current.copy(threads = current.threads.filterNot { it.id == threadId })
                        }
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

        private fun applyArchivedStreamItem(value: JsonElement) {
            val obj = value as? JsonObject ?: return
            when (obj.string("kind")) {
                "snapshot" -> {
                    val raw = obj["snapshot"] ?: return
                    val threads = T3Json.optionalThreads(raw) ?: return
                    val sequence = (raw as? JsonObject)?.long("snapshotSequence") ?: return
                    val hadShell = _shell.value != null
                    val current = _shell.value ?: T3ShellSnapshot(threads = emptyList(), projects = emptyList())
                    if (sequence < current.archiveSequence) return
                    val ids = threads.mapTo(HashSet()) { it.id }
                    applyShell(
                        current.copy(
                            threads = current.threads.filterNot { it.id in ids },
                            archivedThreads = threads,
                            archiveSequence = sequence,
                        ),
                        publishLifecycle = hadShell,
                    )
                }
                "thread.updated" -> {
                    val sequence = obj.long("sequence") ?: return
                    val thread = T3Json.thread(obj["thread"] ?: return) ?: return
                    mutateArchive(sequence) { current ->
                        current.copy(
                            threads = current.threads.filterNot { it.id == thread.id },
                            archivedThreads = current.archivedThreads.filterNot { it.id == thread.id } + thread,
                        )
                    }
                }
                "thread.removed" -> {
                    val sequence = obj.long("sequence") ?: return
                    val threadId = obj.string("threadId") ?: return
                    mutateArchive(sequence) { current ->
                        current.copy(archivedThreads = current.archivedThreads.filterNot { it.id == threadId })
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

        /** Archive events share the server log but must not advance or stall the active cursor. */
        private fun mutateArchive(
            sequence: Long,
            transform: (T3ShellSnapshot) -> T3ShellSnapshot,
        ) {
            val current = _shell.value ?: return
            if (sequence <= current.archiveSequence) return
            applyShell(transform(current).copy(archiveSequence = sequence), publishLifecycle = true)
        }

        /** The active shell snapshot leaves `archivedThreads` empty. Keep the archive list. */
        private fun preservingArchive(incoming: T3ShellSnapshot): T3ShellSnapshot {
            val previous = _shell.value ?: return incoming
            if (incoming.archivedThreads.isNotEmpty()) return incoming
            return incoming.copy(
                archivedThreads = previous.archivedThreads,
                archiveSequence = previous.archiveSequence,
            )
        }

        /**
         * Prefers a real archived-shell snapshot. A payload without `threads` is not
         * one, and must not replace the list the HTTP shell or a prior stream item holds.
         */
        private suspend fun archivedThreadsFromSocket(): List<T3Thread>? {
            if (socket.state.value != T3SocketState.Connected) return null
            val value =
                socket.request(
                    T3RpcCodec.GET_ARCHIVED_SHELL_SNAPSHOT,
                    T3Commands.subscribeArchivedShell(),
                )
            return T3Json.optionalThreads(value)
        }

        private fun mergeArchive(
            http: T3ShellSnapshot,
            fromSocket: List<T3Thread>?,
        ): T3ShellSnapshot {
            val previous = _shell.value
            val archive =
                when {
                    fromSocket != null -> fromSocket
                    http.archivedThreads.isNotEmpty() -> http.archivedThreads
                    else -> previous?.archivedThreads.orEmpty()
                }
            val ids = archive.mapTo(HashSet()) { it.id }
            val sequence =
                if (fromSocket != null) {
                    previous?.archiveSequence ?: 0L
                } else {
                    previous?.archiveSequence ?: http.archiveSequence
                }
            return http.copy(
                threads = http.threads.filterNot { it.id in ids },
                archivedThreads = archive,
                archiveSequence = sequence,
            )
        }

        /**
         * Replaces the shell and publishes the diff. REST refreshes publish status and
         * approval changes only; the shell stream also publishes thread lifecycle once
         * there is a previous snapshot to diff against (the first full snapshot would
         * otherwise announce every existing thread as created).
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
            serverConfigCache.value = null
            serverConfigProfileId = null
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
            val lifecycle = publishLifecycle && previous != null
            for (threadId in before.keys + after.keys) {
                val old = before[threadId]
                val new = after[threadId]
                if (old == new) continue
                if (lifecycle) {
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

        /** Public entry points only ever throw T3 errors with user-readable messages. */
        private inline fun <T> guarded(block: () -> T): T =
            try {
                block()
            } catch (error: Throwable) {
                throw T3Errors.normalize(error)
            }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        companion object {
            const val SHELL_REFRESH_DEBOUNCE_MS = 400L
            private const val CONNECT_TIMEOUT_MS = 15_000L
            private const val MAX_BACKOFF_MS = 30_000L

            internal fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
        }
    }
