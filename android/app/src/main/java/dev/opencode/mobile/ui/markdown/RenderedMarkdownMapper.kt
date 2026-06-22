package dev.opencode.mobile.ui.markdown

import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeCodeHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class RenderedMarkdownMapper
    @Inject
    constructor(
        private val codeHighlighter: OpenCodeCodeHighlighter,
    ) {
        internal var computationDispatcher: CoroutineDispatcher = Dispatchers.Default
        private val cacheMutex = Mutex()
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
                if (!OpenCodeMarkdownDetector.shouldRender(text)) {
                    return@withContext null
                }
                val cacheKey = CacheKey(messageId, text, isStreaming)
                cacheMutex.withLock { cache[cacheKey] }?.let { return@withContext it }
                val document =
                    OpenCodeMarkdownParser.parse(
                        markdown = text,
                        codeHighlighter = codeHighlighter,
                        isStreaming = isStreaming,
                    )
                cacheMutex.withLock {
                    cache[cacheKey] ?: document.also { cache[cacheKey] = it }
                }
            }

        suspend fun enrichRows(
            rows: List<SessionChatMessageItem>,
            previousById: Map<String, SessionChatMessageItem>,
        ): List<SessionChatMessageItem> =
            withContext(computationDispatcher) {
                buildList(rows.size) {
                    for (row in rows) {
                        add(enrichRow(row, previousById[row.id]))
                    }
                }
            }

        private suspend fun enrichRow(
            row: SessionChatMessageItem,
            previous: SessionChatMessageItem?,
        ): SessionChatMessageItem {
            if (!OpenCodeMarkdownDetector.shouldRender(row.text)) {
                return row.copy(renderedMarkdown = null)
            }
            val document = reusableDocument(row, previous) ?: parse(row.id, row.text, row.isStreaming)
            return row.copy(renderedMarkdown = document)
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
        }

        private data class CacheKey(
            val messageId: String,
            val text: String,
            val isStreaming: Boolean,
        )
    }
