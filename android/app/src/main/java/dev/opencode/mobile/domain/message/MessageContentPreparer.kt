package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class MessageContentPreparer
    @Inject
    constructor(
        private val codeHighlighter: OpenCodeCodeHighlighter,
    ) {
        internal var computationDispatcher: CoroutineDispatcher = Dispatchers.Default

        // Final/history parses may run concurrently so a cancelled, synchronous
        // history parse cannot hold up a live snapshot. The bundled TextMate
        // tokenizer owns a mutable rule cache, though, so serialize only access
        // to that highlighter. Streaming parses select the stateless semantic
        // fallback before reaching this wrapper and remain unblocked.
        private val highlighterLock = Any()
        private val serializedCodeHighlighter =
            object : OpenCodeCodeHighlighter {
                override fun supportsLanguage(languageOrAlias: String?): Boolean =
                    synchronized(highlighterLock) {
                        codeHighlighter.supportsLanguage(languageOrAlias)
                    }

                override fun highlight(
                    languageOrAlias: String?,
                    code: String,
                ) = synchronized(highlighterLock) {
                    codeHighlighter.highlight(languageOrAlias, code)
                }
            }

        private val cacheLock = Any()
        private val cache =
            object : LinkedHashMap<CacheKey, MarkdownDocument>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, MarkdownDocument>?): Boolean =
                    size > MAX_CACHE_ENTRIES
            }

        suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage> = emptyMap(),
            mode: MessagePreparationMode = MessagePreparationMode.Final,
        ): List<DisplayMessage> =
            withContext(computationDispatcher) {
                var parsedCount = 0
                var reusedCount = 0
                var elapsedMs = 0L
                val prepared =
                    buildList(messages.size) {
                        elapsedMs =
                            measureTimeMillis {
                                for (message in messages) {
                                    val previous = previousById[message.id]
                                    val display =
                                        prepareMessageOnWorker(
                                            message = message,
                                            previous = previous,
                                            mode = mode,
                                        )
                                    add(display)
                                    when {
                                        display.renderedMarkdown == null -> Unit
                                        previous?.renderedMarkdown != null &&
                                            display.renderedMarkdown === previous.renderedMarkdown -> reusedCount++
                                        else -> parsedCount++
                                    }
                                }
                            }
                    }
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
                )
            }

        private fun prepareMessageOnWorker(
            message: Message,
            previous: DisplayMessage?,
            mode: MessagePreparationMode,
            sessionKey: String = "",
        ): DisplayMessage {
            val isStreaming = messageIsStreaming(message)
            if (mode == MessagePreparationMode.Streaming &&
                message.text.length >= STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD
            ) {
                return DisplayMessage(message = message, renderedMarkdown = null)
            }
            if (mode == MessagePreparationMode.HistoryPlainFirst) {
                if (message.text.length < STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD) {
                    reusableDocument(message, previous, mode)?.let { reusable ->
                        return DisplayMessage(message = message, renderedMarkdown = reusable)
                    }
                }
                return DisplayMessage(message = message, renderedMarkdown = null)
            }
            if (!OpenCodeMarkdownDetector.shouldRender(message.text)) {
                return DisplayMessage(message = message, renderedMarkdown = null)
            }
            val document =
                reusableDocument(message, previous, mode)
                    ?: if (mode == MessagePreparationMode.FinalPlaceholder) {
                        previous?.renderedMarkdown
                    } else {
                        parseDocumentSafely(
                            sessionKey = sessionKey,
                            blockID = message.id,
                            text = message.text,
                            isStreaming = isStreaming,
                        )
                    }
            return DisplayMessage(message = message, renderedMarkdown = document)
        }

        private fun messageIsStreaming(message: Message): Boolean =
            message.parts.any { part -> part is MessagePart.Text && part.isStreaming }

        private fun parseDocumentSafely(
            sessionKey: String,
            blockID: String,
            text: String,
            isStreaming: Boolean,
        ): MarkdownDocument? =
            runCatching { parseDocument(sessionKey, blockID, text, isStreaming) }
                .onFailure { error ->
                    logI("preparePlainFallback blockIdHash=${blockID.hashCode()} reason=${error.javaClass.simpleName}")
                }.getOrNull()

        private fun parseDocument(
            sessionKey: String,
            blockID: String,
            text: String,
            isStreaming: Boolean,
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
                    isStreaming = isStreaming,
                )
            synchronized(cacheLock) {
                cache[cacheKey]?.let { return it }
            }
            val document =
                OpenCodeMarkdownParser.parse(
                    markdown = text,
                    codeHighlighter = serializedCodeHighlighter,
                    isStreaming = isStreaming,
                )
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
            if (existing != null &&
                !isStreaming &&
                previous.isStreaming != true &&
                previous.text == message.text
            ) {
                return existing
            }
            if (isStreaming &&
                previous != null &&
                previous.isStreaming &&
                previous.text == message.text
            ) {
                return previous.renderedMarkdown
            }
            if (mode == MessagePreparationMode.FinalPlaceholder &&
                !isStreaming &&
                previous != null &&
                previous.isStreaming &&
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

            /** While streaming, skip markdown above this size; full parse runs on the final frame. */
            const val STREAMING_PLAIN_TEXT_CHARACTER_THRESHOLD = 8_000

            /** Open histories above this markdown-candidate size as plain text first. */
            const val HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD = 4_000

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
            val isStreaming: Boolean,
        )
    }
