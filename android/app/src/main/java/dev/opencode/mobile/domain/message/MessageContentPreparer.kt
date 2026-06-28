package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

@Singleton
class MessageContentPreparer
    @Inject
    constructor(
        private val codeHighlighter: OpenCodeCodeHighlighter,
    ) {
        internal var computationDispatcher: CoroutineDispatcher = Dispatchers.Default

        private val cacheLock = Any()
        private val cache =
            object : LinkedHashMap<CacheKey, MarkdownDocument>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, MarkdownDocument>?): Boolean =
                    size > MAX_CACHE_ENTRIES
            }

        suspend fun prepareMessages(
            messages: List<Message>,
            previousById: Map<String, DisplayMessage> = emptyMap(),
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
                                    val display = prepareMessageOnWorker(message, previous)
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
                        "markdown=${prepared.count { it.renderedMarkdown != null }} ms=$elapsedMs",
                )
                prepared
            }

        suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
        ): DisplayMessage =
            prepareMessage(
                Message(
                    id = id,
                    role = MessageRole.User,
                    text = text,
                ),
            )

        suspend fun prepareMessage(
            message: Message,
            previous: DisplayMessage? = null,
        ): DisplayMessage =
            withContext(computationDispatcher) {
                prepareMessageOnWorker(message, previous)
            }

        private fun prepareMessageOnWorker(
            message: Message,
            previous: DisplayMessage?,
        ): DisplayMessage {
            if (!OpenCodeMarkdownDetector.shouldRender(message.text)) {
                return DisplayMessage(message = message, renderedMarkdown = null)
            }
            val document =
                reusableDocument(message, previous)
                    ?: parseDocument(message.id, message.text, messageIsStreaming(message))
            return DisplayMessage(message = message, renderedMarkdown = document)
        }

        private fun messageIsStreaming(message: Message): Boolean =
            message.parts.any { part -> part is MessagePart.Text && part.isStreaming }

        private fun parseDocument(
            messageId: String,
            text: String,
            isStreaming: Boolean,
        ): MarkdownDocument? {
            if (!OpenCodeMarkdownDetector.shouldRender(text)) {
                return null
            }
            val cacheKey =
                CacheKey(
                    messageId = messageId,
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
                    codeHighlighter = codeHighlighter,
                    isStreaming = isStreaming,
                )
            return synchronized(cacheLock) {
                cache[cacheKey] ?: document.also { cache[cacheKey] = it }
            }
        }

        private fun reusableDocument(
            message: Message,
            previous: DisplayMessage?,
        ): MarkdownDocument? {
            val isStreaming = messageIsStreaming(message)
            val existing = previous?.renderedMarkdown
            if (existing != null &&
                !isStreaming &&
                previous.isStreaming != true
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
            return null
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        companion object {
            private const val MAX_CACHE_ENTRIES = 32
            private const val TAG = "MessageContentPreparer"
        }

        private data class CacheKey(
            val messageId: String,
            val textHash: Int,
            val textLength: Int,
            val isStreaming: Boolean,
        )
    }
