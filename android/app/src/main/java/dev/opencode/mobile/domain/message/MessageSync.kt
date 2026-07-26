package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.LiveStoreScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.system.measureTimeMillis

typealias MessageListFetcher = suspend (sessionId: String, directory: String?) -> List<Message>

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MessageSync(
        private val listMessages: MessageListFetcher,
        private val store: InMemoryMessageStore,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
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
                logI("observeMessages subscribe session=$sessionId directory=${directory ?: "-"}")
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

                eventStream.events.collect { event ->
                    if (event.type == "stream.reconnected" || event.type == "server.connected") {
                        fullReloadSilent(sessionId, directory, reason = event.type)?.let { reloaded ->
                            logI("observeMessages emit session=$sessionId count=${reloaded.size} source=${event.type}")
                            send(MessageListUpdate.Messages(reloaded))
                        }
                        return@collect
                    }

                    if (event.sessionId != sessionId) return@collect

                    when (event.type) {
                        "message.updated" -> {
                            val infoJson = event.rawProperties?.get("info")?.jsonObject ?: return@collect
                            store.apply(sessionId, MessagePatch.InfoUpdated(infoJson))
                        }
                        "message.part.updated" -> {
                            if (requiresFullReloadForBaseOnlyPartUpdate(event.rawProperties, sessionId)) {
                                fullReloadSilent(sessionId, directory, reason = "message.part.updated-base-only")?.let { reloaded ->
                                    logI(
                                        "observeMessages emit session=$sessionId count=${reloaded.size} " +
                                            "source=message.part.updated-base-only",
                                    )
                                    send(MessageListUpdate.Messages(reloaded))
                                }
                                return@collect
                            }
                            val partJson = event.rawProperties?.get("part")?.jsonObject ?: return@collect
                            val delta = event.rawProperties?.get("delta")?.jsonPrimitive?.contentOrNull
                            store.apply(sessionId, MessagePatch.PartUpdated(partJson, delta))
                        }
                        "message.part.delta" -> {
                            val props = event.rawProperties ?: return@collect
                            val field = props["field"]?.jsonPrimitive?.contentOrNull
                            if (field != "text") return@collect
                            val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return@collect
                            val partId = props["partID"]?.jsonPrimitive?.contentOrNull ?: return@collect
                            val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: return@collect
                            store.apply(sessionId, MessagePatch.PartDelta(messageId, partId, delta))
                        }
                        "message.removed" -> {
                            val messageId = event.rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return@collect
                            store.apply(sessionId, MessagePatch.Removed(messageId))
                        }
                        "message.part.removed" -> {
                            if (requiresFullReloadForBaseOnlyPartRemoval(event.rawProperties, sessionId)) {
                                fullReloadSilent(sessionId, directory, reason = "message.part.removed-base-only")?.let { reloaded ->
                                    logI(
                                        "observeMessages emit session=$sessionId count=${reloaded.size} " +
                                            "source=message.part.removed-base-only",
                                    )
                                    send(MessageListUpdate.Messages(reloaded))
                                }
                                return@collect
                            }
                            val messageId = event.rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return@collect
                            val partId = event.rawProperties?.get("partID")?.jsonPrimitive?.contentOrNull ?: return@collect
                            store.apply(sessionId, MessagePatch.PartRemoved(messageId, partId))
                        }
                        "session.idle" -> {
                            fullReloadSilent(sessionId, directory, reason = "session.idle")?.let { reloaded ->
                                logI("observeMessages emit session=$sessionId count=${reloaded.size} source=session.idle")
                                send(MessageListUpdate.Messages(reloaded))
                            }
                            return@collect
                        }
                        else -> return@collect
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
            }

        private fun timelinePollFlow(
            sessionId: String,
            directory: String?,
        ): Flow<MessageListUpdate> =
            channelFlow {
                while (true) {
                    delay(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS)
                    // Polling REST mid-stream publishes a behind snapshot and looks like the
                    // assistant message clearing then reprinting from the start.
                    if (store.hasStreamingText(sessionId)) continue
                    val messages = runCatching { listMessages(sessionId, directory) }.getOrNull() ?: continue
                    val fp = messageFingerprint(messages)
                    if (fp != lastFingerprints[sessionId]) {
                        store.replaceAll(sessionId, messages)
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
            val elapsedMs =
                measureTimeMillis {
                    messages = listMessages(sessionId, directory)
                }
            store.replaceAll(sessionId, messages!!)
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
            store.replaceAll(sessionId, loaded)
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

        private fun requiresFullReloadForBaseOnlyPartUpdate(
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
            return store.isBaseOnlyMessage(sessionId, messageId)
        }

        private fun requiresFullReloadForBaseOnlyPartRemoval(
            rawProperties: JsonObject?,
            sessionId: String,
        ): Boolean {
            val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return false
            return store.isBaseOnlyMessage(sessionId, messageId)
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
