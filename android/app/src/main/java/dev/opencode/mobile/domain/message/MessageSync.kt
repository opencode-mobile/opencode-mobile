package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.LiveStoreScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

typealias MessageListFetcher = suspend (sessionId: String, directory: String?) -> List<Message>

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MessageSync(
    private val listMessages: MessageListFetcher,
    private val store: InMemoryMessageStore,
    private val eventStream: ForegroundEventStream,
    private val liveStoreScope: LiveStoreScope,
    private val pollIntervalMs: Long = LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS,
) {
    private val observationCache = ConcurrentHashMap<ObservationKey, SharedFlow<MessageListUpdate>>()
    private val lastFingerprints = ConcurrentHashMap<String, String>()

    fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> {
        val key = ObservationKey(sessionId, directory.orEmpty())
        return observationCache.getOrPut(key) {
            buildMessageFlow(sessionId, directory)
                .shareIn(
                    scope = liveStoreScope.coroutineScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )
        }
    }

    suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = fullReloadOrThrow(sessionId, directory, reason = "refresh")

    fun clearCache() {
        observationCache.clear()
        lastFingerprints.clear()
        store.clear()
    }

    private fun buildMessageFlow(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> =
        merge(
            incrementalMessageFlow(sessionId, directory),
            timelinePollFlow(sessionId, directory),
        )

    private fun incrementalMessageFlow(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> =
        channelFlow {
            // Subscribe before the initial REST request. SharedFlow has no replay, so
            // buffering here closes the window where an in-flight reply could lose its
            // part shell and all following deltas while history is loading.
            val eventChannel =
                eventStream.events
                    .buffer(Channel.UNLIMITED)
                    .produceIn(this)
            var serverConnectedReloadJob: Job? = null
            logI("observeMessages subscribe session=$sessionId directory=${directory ?: "-"}")
            try {
                when (val initial = fullReloadResult(sessionId, directory, reason = "initial-subscribe")) {
                    is ReloadResult.Success -> {
                        logI(
                            "observeMessages emit session=$sessionId count=${initial.messages.size} source=initial-subscribe",
                        )
                        send(MessageListUpdate.Messages(initial.messages))
                    }
                    is ReloadResult.Failure -> {
                        send(MessageListUpdate.LoadFailed(initial.error))
                    }
                }

                for (event in eventChannel) {
                    if (event.type == "stream.reconnected") {
                        serverConnectedReloadJob?.cancel()
                        serverConnectedReloadJob = null
                        fullReloadSilent(sessionId, directory, reason = event.type)?.let { reloaded ->
                            logI("observeMessages emit session=$sessionId count=${reloaded.size} source=${event.type}")
                            send(MessageListUpdate.Messages(reloaded))
                        }
                        continue
                    }
                    if (event.type == "server.connected") {
                        serverConnectedReloadJob?.cancel()
                        serverConnectedReloadJob =
                            launch {
                                delay(LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS)
                                fullReloadSilent(sessionId, directory, reason = event.type)?.let { reloaded ->
                                    logI(
                                        "observeMessages emit session=$sessionId count=${reloaded.size} source=${event.type}",
                                    )
                                    send(MessageListUpdate.Messages(reloaded))
                                }
                            }
                        continue
                    }

                    if (event.sessionId != sessionId) continue

                    when (event.type) {
                        "message.updated" -> {
                            val infoJson = event.rawProperties?.get("info")?.jsonObject ?: continue
                            store.apply(sessionId, MessagePatch.InfoUpdated(infoJson))
                        }
                        "message.part.updated" -> {
                            if (requiresFullReloadForRestSeededPartUpdate(event.rawProperties, sessionId)) {
                                fullReloadSilent(sessionId, directory, reason = "message.part.updated-rest-seeded")
                                    ?.let { reloaded ->
                                        logI(
                                            "observeMessages emit session=$sessionId count=${reloaded.size} " +
                                                "source=message.part.updated-rest-seeded",
                                        )
                                        send(MessageListUpdate.Messages(reloaded))
                                    }
                                continue
                            }
                            val partJson = event.rawProperties?.get("part")?.jsonObject ?: continue
                            val delta =
                                event.rawProperties
                                    ?.get("delta")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                            store.apply(sessionId, MessagePatch.PartUpdated(partJson, delta))
                        }
                        "message.part.delta" -> {
                            val props = event.rawProperties ?: continue
                            val field = props["field"]?.jsonPrimitive?.contentOrNull
                            if (field != "text") continue
                            val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: continue
                            val partId = props["partID"]?.jsonPrimitive?.contentOrNull ?: continue
                            val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: continue
                            store.apply(sessionId, MessagePatch.PartDelta(messageId, partId, delta))
                        }
                        "message.removed" -> {
                            val messageId =
                                event.rawProperties
                                    ?.get("messageID")
                                    ?.jsonPrimitive
                                    ?.contentOrNull ?: continue
                            store.apply(sessionId, MessagePatch.Removed(messageId))
                        }
                        "message.part.removed" -> {
                            if (requiresFullReloadForRestSeededPartRemoval(event.rawProperties, sessionId)) {
                                fullReloadSilent(sessionId, directory, reason = "message.part.removed-rest-seeded")
                                    ?.let { reloaded ->
                                        logI(
                                            "observeMessages emit session=$sessionId count=${reloaded.size} " +
                                                "source=message.part.removed-rest-seeded",
                                        )
                                        send(MessageListUpdate.Messages(reloaded))
                                    }
                                continue
                            }
                            val messageId =
                                event.rawProperties
                                    ?.get("messageID")
                                    ?.jsonPrimitive
                                    ?.contentOrNull ?: continue
                            val partId =
                                event.rawProperties
                                    ?.get("partID")
                                    ?.jsonPrimitive
                                    ?.contentOrNull ?: continue
                            store.apply(sessionId, MessagePatch.PartRemoved(messageId, partId))
                        }
                        "session.idle" -> {
                            val reloaded = fullReloadSilent(sessionId, directory, reason = "session.idle")
                            if (reloaded != null) {
                                logI("observeMessages emit session=$sessionId count=${reloaded.size} source=session.idle")
                                send(MessageListUpdate.Messages(reloaded))
                            } else {
                                store.finishStreamingText(sessionId)
                                val recovered = store.messages(sessionId)
                                if (markChanged(sessionId, recovered)) {
                                    logW("session.idle reload failed; resumed polling session=$sessionId")
                                    send(MessageListUpdate.Messages(recovered))
                                }
                            }
                            continue
                        }
                        else -> continue
                    }

                    val messages = store.messages(sessionId)
                    if (markChanged(sessionId, messages)) {
                        logD(
                            "observeMessages emit session=$sessionId count=${messages.size} " +
                                "source=sse-incremental type=${event.type}",
                        )
                        send(MessageListUpdate.Messages(messages))
                    }
                }
            } finally {
                serverConnectedReloadJob?.cancel()
                eventChannel.cancel()
            }
        }

    private fun timelinePollFlow(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> =
        channelFlow {
            while (true) {
                delay(pollIntervalMs)
                // Polling REST mid-stream publishes a behind snapshot and looks like the
                // assistant message clearing then reprinting from the start.
                if (store.hasStreamingText(sessionId)) continue
                val expectedRevision = store.revision(sessionId)
                val messages = runCatching { listMessages(sessionId, directory) }.getOrNull() ?: continue
                val fp = messageFingerprint(messages)
                if (fp != lastFingerprints[sessionId]) {
                    if (!store.replaceAllIfRevision(sessionId, messages, expectedRevision)) continue
                    val merged = store.messages(sessionId)
                    lastFingerprints[sessionId] = messageFingerprint(merged)
                    logD("observeMessages emit session=$sessionId count=${merged.size} source=poll fingerprint-changed")
                    send(MessageListUpdate.Messages(merged))
                }
            }
        }

    private sealed interface ReloadResult {
        data class Success(
            val messages: List<Message>,
        ) : ReloadResult

        data class Failure(
            val error: Throwable,
        ) : ReloadResult
    }

    private suspend fun fullReloadOrThrow(
        sessionId: String,
        directory: String?,
        reason: String,
    ): List<Message> {
        var messages: List<Message>? = null
        val expectedRevision = store.revision(sessionId)
        val elapsedMs =
            measureTimeMillis {
                messages = listMessages(sessionId, directory)
            }
        store.replaceAllIfRevision(sessionId, messages!!, expectedRevision)
        val merged = store.messages(sessionId)
        val fp = messageFingerprint(merged)
        lastFingerprints[sessionId] = fp
        logI(
            "fullReload session=$sessionId reason=$reason count=${merged.size} ms=$elapsedMs",
        )
        return merged
    }

    private suspend fun fullReloadResult(
        sessionId: String,
        directory: String?,
        reason: String,
    ): ReloadResult {
        var messages: List<Message>? = null
        var failure: Throwable? = null
        val expectedRevision = store.revision(sessionId)
        val elapsedMs =
            measureTimeMillis {
                runCatching { listMessages(sessionId, directory) }
                    .onSuccess { messages = it }
                    .onFailure { failure = it }
            }
        val loaded = messages
        if (loaded == null) {
            logW("fullReload failed session=$sessionId reason=$reason ms=$elapsedMs")
            return ReloadResult.Failure(failure ?: IllegalStateException("Message load failed"))
        }
        store.replaceAllIfRevision(sessionId, loaded, expectedRevision)
        val merged = store.messages(sessionId)
        val fp = messageFingerprint(merged)
        lastFingerprints[sessionId] = fp
        logI(
            "fullReload session=$sessionId reason=$reason count=${merged.size} ms=$elapsedMs",
        )
        return ReloadResult.Success(merged)
    }

    private suspend fun fullReloadSilent(
        sessionId: String,
        directory: String?,
        reason: String,
    ): List<Message>? =
        when (val result = fullReloadResult(sessionId, directory, reason)) {
            is ReloadResult.Success -> result.messages
            is ReloadResult.Failure -> null
        }

    private fun requiresFullReloadForRestSeededPartUpdate(
        rawProperties: JsonObject?,
        sessionId: String,
    ): Boolean {
        val messageId =
            rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("messageID")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return false
        return store.isRestSeededMessage(sessionId, messageId)
    }

    private fun requiresFullReloadForRestSeededPartRemoval(
        rawProperties: JsonObject?,
        sessionId: String,
    ): Boolean {
        val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return false
        return store.isRestSeededMessage(sessionId, messageId)
    }

    private fun messageFingerprint(messages: List<Message>): String =
        messages.joinToString("|") { message ->
            val parts =
                message.parts.joinToString(",") { part ->
                    when (part) {
                        is MessagePart.Text -> "text:${part.text.length}:${part.text.hashCode()}:${part.isStreaming}"
                        is MessagePart.Tool -> "tool:${part.callId}:${part.status}:${part.output?.hashCode()}:${part.error?.hashCode()}"
                        is MessagePart.File ->
                            "file:${part.id}:${part.filename}:${part.mime}:" +
                                "${part.dataUrl.length}:${part.dataUrl.hashCode()}"
                    }
                }
            "${message.id}:${message.text.length}:${message.text.hashCode()}:$parts"
        }

    private fun markChanged(
        sessionId: String,
        messages: List<Message>,
    ): Boolean {
        val fp = messageFingerprint(messages)
        if (fp == lastFingerprints[sessionId]) return false
        lastFingerprints[sessionId] = fp
        return true
    }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private data class ObservationKey(
        val sessionId: String,
        val directory: String,
    )

    private companion object {
        private const val TAG = "MessageSync"
    }
}
