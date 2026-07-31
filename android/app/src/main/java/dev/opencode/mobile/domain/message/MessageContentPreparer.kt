package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.textmate.NoOpTextMateLogbookRecorder
import dev.opencode.mobile.domain.textmate.TextMateLogbookRecorder
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownHighlightAttachment
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.markdown.OpenCodeStructureOnlyHighlighter
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.attachTextMateResult
import dev.opencode.mobile.markdown.codeFences
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

@Singleton
class MessageContentPreparer
    @Inject
    constructor(
        private val textMateEngine: TextMateHighlightingEngine,
        private val textMateLogbook: TextMateLogbookRecorder = NoOpTextMateLogbookRecorder,
    ) {
        internal var computationDispatcher: CoroutineDispatcher = Dispatchers.Default
        private val nextHighlightRevision = AtomicLong(0)

        private val cacheLock = Any()
        private val cache =
            object : LinkedHashMap<CacheKey, MarkdownDocument>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, MarkdownDocument>?): Boolean =
                    size > MAX_CACHE_ENTRIES
            }

        fun clearDocumentCache() {
            synchronized(cacheLock) { cache.clear() }
        }

        suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage> = emptyMap(),
            mode: MessagePreparationMode = MessagePreparationMode.Final,
        ): List<DisplayMessage> =
            withContext(computationDispatcher) {
                var parsedCount = 0
                var reusedCount = 0
                val startedAt = System.nanoTime()
                val prepared = ArrayList<DisplayMessage>(messages.size)
                for (message in messages) {
                    val previous = previousById[message.id]
                    val display =
                        prepareMessageOnWorker(
                            message = message,
                            previous = previous,
                            mode = mode,
                        )
                    prepared += display
                    when {
                        display.renderedMarkdown == null -> Unit
                        previous?.renderedMarkdown != null &&
                            display.renderedMarkdown === previous.renderedMarkdown -> reusedCount++
                        else -> parsedCount++
                    }
                }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                logI(
                    "prepareMessages count=${messages.size} parsed=$parsedCount reused=$reusedCount " +
                        "markdown=${prepared.count { it.renderedMarkdown != null }} " +
                        "mode=$mode ms=$elapsedMs",
                )
                prepared
            }

        suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
            attachments: List<Attachment> = emptyList(),
        ): DisplayMessage =
            prepareMessage(
                Message(
                    id = id,
                    role = MessageRole.User,
                    text = text,
                    parts =
                        buildList {
                            if (text.isNotBlank()) add(MessagePart.Text(text))
                            attachments.forEach { attachment ->
                                add(
                                    MessagePart.File(
                                        id = attachment.id,
                                        filename = attachment.filename,
                                        mime = attachment.mime,
                                        kind = attachment.kind,
                                        dataUrl = attachment.dataUrl,
                                    ),
                                )
                            }
                        },
                ),
                mode = MessagePreparationMode.Final,
            )

        suspend fun prepareMessage(
            message: Message,
            previous: DisplayMessage? = null,
            mode: MessagePreparationMode = MessagePreparationMode.Final,
            sessionKey: String = "",
        ): DisplayMessage =
            withContext(computationDispatcher) {
                prepareMessageOnWorker(message, previous, mode, sessionKey)
            }

        /**
         * Prepare Markdown for one presentation text block.
         * Writes the preparer LRU keyed by session + block + content revision + streaming.
         */
        suspend fun prepareTextBlock(
            blockID: String,
            plain: String,
            isStreaming: Boolean,
            sessionKey: String = "",
            mode: MessagePreparationMode = MessagePreparationMode.Streaming,
        ): MarkdownDocument? =
            withContext(computationDispatcher) {
                if (mode == MessagePreparationMode.Streaming &&
                    plain.length >= STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD
                ) {
                    return@withContext null
                }
                if (!OpenCodeMarkdownDetector.shouldRender(plain)) {
                    return@withContext null
                }
                parseDocumentSafely(
                    sessionKey = sessionKey,
                    blockID = blockID,
                    text = plain,
                    isStreaming = isStreaming,
                    textMateEnabled = MarkdownHighlightPreferences.textMateEnabled,
                    policyEpoch = MarkdownHighlightPreferences.epoch.toLong(),
                    intent = mode.highlightIntent(),
                )
            }

        /**
         * Format-first streaming paint: always structure when markdown is
         * detected. TextMate on → empty spans (colors later); TextMate off → full
         * semantic fallback. Never writes the preparer LRU.
         */
        suspend fun prepareStructureOnlyTextBlock(
            blockID: String,
            plain: String,
            sessionKey: String = "",
        ): MarkdownDocument? =
            withContext(computationDispatcher) {
                if (!OpenCodeMarkdownDetector.shouldRender(plain)) {
                    return@withContext null
                }
                val highlighter =
                    if (MarkdownHighlightPreferences.textMateEnabled) {
                        OpenCodeStructureOnlyHighlighter
                    } else {
                        OpenCodeSemanticFallbackHighlighter
                    }
                try {
                    OpenCodeMarkdownParser.parse(
                        markdown = plain,
                        codeHighlighter = highlighter,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logI(
                        "prepareStructureOnlyFallback blockIdHash=${blockID.hashCode()} " +
                            "sessionKeyHash=${sessionKey.hashCode()} reason=${error.javaClass.simpleName}",
                    )
                    null
                }
            }

        private suspend fun prepareMessageOnWorker(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
            sessionKey: String = "",
        ): DisplayMessage {
            val isStreaming = messageIsStreaming(message)
            val highlightEpoch = MarkdownHighlightPreferences.epoch
            val textMateEnabled = MarkdownHighlightPreferences.textMateEnabled
            if (mode == MessagePreparationMode.Streaming &&
                message.text.length >= STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD
            ) {
                return DisplayMessage(
                    message = message,
                    renderedMarkdown = null,
                    highlightEpoch = highlightEpoch,
                )
            }
            if (mode == MessagePreparationMode.HistoryPlainFirst) {
                reusableDocument(message, previous, mode)?.let { reusable ->
                    return DisplayMessage(
                        message = message,
                        renderedMarkdown = reusable,
                        awaitingTextMateUpgrade = previous?.awaitingTextMateUpgrade == true,
                        highlightEpoch = highlightEpoch,
                    )
                }
                if (!OpenCodeMarkdownDetector.shouldRender(message.text)) {
                    return DisplayMessage(
                        message = message,
                        renderedMarkdown = null,
                        highlightEpoch = highlightEpoch,
                    )
                }
                val highlighter =
                    if (textMateEnabled) {
                        OpenCodeStructureOnlyHighlighter
                    } else {
                        OpenCodeSemanticFallbackHighlighter
                    }
                val document =
                    try {
                        OpenCodeMarkdownParser.parse(
                            markdown = message.text,
                            codeHighlighter = highlighter,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logI(
                            "historyFormatFirstFallback messageIdHash=${message.id.hashCode()} " +
                                "reason=${error.javaClass.simpleName}",
                        )
                        null
                    }
                return DisplayMessage(
                    message = message,
                    renderedMarkdown = document,
                    // TextMate on: colors arrive in HistoryEnrichment / Final.
                    awaitingTextMateUpgrade = textMateEnabled && document != null,
                    highlightEpoch = highlightEpoch,
                )
            }
            if (!OpenCodeMarkdownDetector.shouldRender(message.text)) {
                return DisplayMessage(
                    message = message,
                    renderedMarkdown = null,
                    highlightEpoch = highlightEpoch,
                )
            }
            val reused = reusableDocument(message, previous, mode)
            val document =
                reused
                    ?: if (mode == MessagePreparationMode.FinalPlaceholder) {
                        previous?.renderedMarkdown
                    } else {
                        parseDocumentSafely(
                            sessionKey = sessionKey,
                            blockID = message.id,
                            text = message.text,
                            isStreaming = isStreaming,
                            textMateEnabled = textMateEnabled,
                            policyEpoch = highlightEpoch.toLong(),
                            intent = mode.highlightIntent(),
                        )
                    }
            val awaitingTextMateUpgrade =
                when {
                    !textMateEnabled -> false
                    mode == MessagePreparationMode.FinalPlaceholder && document != null ->
                        previous?.awaitingTextMateUpgrade == true || previous?.isStreaming == true
                    reused != null -> previous?.awaitingTextMateUpgrade == true
                    else -> false
                }
            return DisplayMessage(
                message = message,
                renderedMarkdown = document,
                awaitingTextMateUpgrade = awaitingTextMateUpgrade,
                highlightEpoch = highlightEpoch,
            )
        }

        private fun messageIsStreaming(message: Message): Boolean =
            message.parts.any { part -> part is MessagePart.Text && part.isStreaming }

        private suspend fun parseDocumentSafely(
            sessionKey: String,
            blockID: String,
            text: String,
            isStreaming: Boolean,
            textMateEnabled: Boolean,
            policyEpoch: Long,
            intent: HighlightIntent,
        ): MarkdownDocument? =
            try {
                parseDocument(
                    sessionKey = sessionKey,
                    blockID = blockID,
                    text = text,
                    isStreaming = isStreaming,
                    textMateEnabled = textMateEnabled,
                    policyEpoch = policyEpoch,
                    intent = intent,
                )
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logI("preparePlainFallback blockIdHash=${blockID.hashCode()} reason=${error.javaClass.simpleName}")
                null
            }

        private suspend fun parseDocument(
            sessionKey: String,
            blockID: String,
            text: String,
            isStreaming: Boolean,
            textMateEnabled: Boolean,
            policyEpoch: Long,
            intent: HighlightIntent,
        ): MarkdownDocument? {
            if (!OpenCodeMarkdownDetector.shouldRender(text)) {
                return null
            }
            val cacheKey =
                CacheKey(
                    sessionKey = sessionKey,
                    blockID = blockID,
                    textHash = stableMarkdownSourceHash(text),
                    textLength = text.length,
                    exactSource = text,
                    isStreaming = isStreaming,
                    textMateEnabled = textMateEnabled,
                    policyEpoch = policyEpoch,
                )
            synchronized(cacheLock) {
                cache[cacheKey]?.let { return it }
            }
            val fallbackDocument =
                OpenCodeMarkdownParser.parse(
                    markdown = text,
                    codeHighlighter = OpenCodeSemanticFallbackHighlighter,
                    isStreaming = isStreaming,
                    textMateEnabled = false,
                )
            val document =
                if (!textMateEnabled || isStreaming) {
                    fallbackDocument
                } else {
                    val revision = HighlightRevision(nextHighlightRevision.incrementAndGet())
                    val fences =
                        fallbackDocument.codeFences(
                            ownerKey = "${sessionKey.length}:$sessionKey:$blockID",
                            revision = revision,
                            policyEpoch = policyEpoch,
                        )
                    var upgraded = fallbackDocument
                    for (fence in fences) {
                        coroutineContext.ensureActive()
                        val result =
                            textMateEngine.highlight(
                                fence.request(
                                    intent = intent,
                                    messageCharacters = text.length,
                                    fenceCount = fences.size,
                                ),
                            )
                        textMateLogbook.submit(listOf(result.observation))
                        when (val attachment = upgraded.attachTextMateResult(fence, result)) {
                            is MarkdownHighlightAttachment.Attached -> upgraded = attachment.document
                            is MarkdownHighlightAttachment.Rejected -> Unit
                        }
                    }
                    upgraded
                }
            return synchronized(cacheLock) {
                cache[cacheKey] ?: document.also { cache[cacheKey] = it }
            }
        }

        private fun reusableDocument(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
        ): MarkdownDocument? {
            val isStreaming = messageIsStreaming(message)
            val existing = previous?.renderedMarkdown
            val highlightEpoch = MarkdownHighlightPreferences.epoch
            // Final must not settle-reuse a FinalPlaceholder that still holds
            // streaming semantic-fallback spans (`awaitingTextMateUpgrade`).
            if (existing != null &&
                !isStreaming &&
                previous.isStreaming != true &&
                !previous.awaitingTextMateUpgrade &&
                previous.highlightEpoch == highlightEpoch &&
                previous.text == message.text
            ) {
                return existing
            }
            if (isStreaming &&
                previous != null &&
                previous.isStreaming &&
                previous.highlightEpoch == highlightEpoch &&
                previous.text == message.text
            ) {
                return previous.renderedMarkdown
            }
            if (mode == MessagePreparationMode.FinalPlaceholder &&
                !isStreaming &&
                previous != null &&
                previous.isStreaming &&
                previous.highlightEpoch == highlightEpoch &&
                previous.text == message.text
            ) {
                return previous.renderedMarkdown
            }
            return null
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        companion object {
            private const val MAX_CACHE_ENTRIES = 32
            private const val TAG = "MessageContentPreparer"

            /**
             * Legacy size gate still used by disposable streaming decoration and
             * some characterization fixtures. Format-first text-lane parse no
             * longer skips on size — markdown-bearing content always formats.
             */
            const val STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD = 8_000

            /** Open histories above this markdown-candidate size as plain text first. */
            const val HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD = 4_000

            /**
             * Legacy flag: format-first history is always published immediately.
             * Enrichment still upgrades TextMate colors newest-first afterward.
             */
            const val HOLD_READY_UNTIL_NEWEST_MARKDOWN = false

            /** Newest-first enrich publishes once the open viewport is ready. */
            const val HISTORY_ENRICH_VIEWPORT_MESSAGE_COUNT = 3

            /** Character budget for the initial enrichment viewport batch. */
            const val HISTORY_ENRICH_VIEWPORT_CHARACTER_BUDGET = 12_000
        }

        private data class CacheKey(
            val sessionKey: String,
            val blockID: String,
            val textHash: Int,
            val textLength: Int,
            val exactSource: String,
            val isStreaming: Boolean,
            val textMateEnabled: Boolean,
            val policyEpoch: Long,
        )

        private fun MessagePreparationMode.highlightIntent(): HighlightIntent =
            when (this) {
                MessagePreparationMode.Final -> HighlightIntent.InteractiveFinal
                MessagePreparationMode.HistoryEnrichment -> HighlightIntent.VisibleHistory
                else -> HighlightIntent.BackgroundHistory
            }
    }
