package dev.opencode.mobile.ui.markdown

import android.util.Log
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

@Singleton
class RenderedMarkdownMapper
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

        suspend fun parse(
            messageId: String,
            text: String,
            isStreaming: Boolean,
        ): MarkdownDocument? =
            withContext(computationDispatcher) {
                parseDocument(messageId, text, isStreaming)
            }

        suspend fun enrichRows(
            rows: List<SessionChatMessageItem>,
            previousById: Map<String, SessionChatMessageItem>,
        ): List<SessionChatMessageItem> =
            withContext(computationDispatcher) {
                var parsedCount = 0
                var reusedCount = 0
                var elapsedMs = 0L
                val enriched =
                    buildList(rows.size) {
                        elapsedMs =
                            measureTimeMillis {
                                for (row in rows) {
                                    val previous = previousById[row.id]
                                    val enrichedRow = enrichRowOnWorker(row, previous)
                                    add(enrichedRow)
                                    when {
                                        enrichedRow.renderedMarkdown == null -> Unit
                                        row.renderedMarkdown != null &&
                                            enrichedRow.renderedMarkdown === row.renderedMarkdown -> reusedCount++
                                        previous?.renderedMarkdown != null &&
                                            enrichedRow.renderedMarkdown === previous.renderedMarkdown -> reusedCount++
                                        else -> parsedCount++
                                    }
                                }
                            }
                    }
                logI(
                    "enrichRows rows=${rows.size} parsed=$parsedCount reused=$reusedCount " +
                        "markdown=${enriched.count { it.renderedMarkdown != null }} ms=$elapsedMs",
                )
                enriched
            }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun enrichRowOnWorker(
            row: SessionChatMessageItem,
            previous: SessionChatMessageItem?,
        ): SessionChatMessageItem {
            if (!OpenCodeMarkdownDetector.shouldRender(row.text)) {
                return row.copy(renderedMarkdown = null)
            }
            val document =
                reusableDocument(row, previous)
                    ?: parseDocument(row.id, row.text, row.isStreaming)
            return row.copy(renderedMarkdown = document)
        }

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
            row: SessionChatMessageItem,
            previous: SessionChatMessageItem?,
        ): MarkdownDocument? {
            val existing = row.renderedMarkdown
            if (existing != null &&
                !row.isStreaming &&
                previous?.isStreaming != true
            ) {
                return existing
            }
            if (row.isStreaming &&
                previous != null &&
                previous.isStreaming &&
                previous.text == row.text
            ) {
                return previous.renderedMarkdown
            }
            return null
        }

        companion object {
            private const val MAX_CACHE_ENTRIES = 32
            private const val TAG = "MarkdownMapper"
        }

        private data class CacheKey(
            val messageId: String,
            val textHash: Int,
            val textLength: Int,
            val isStreaming: Boolean,
        )
    }
