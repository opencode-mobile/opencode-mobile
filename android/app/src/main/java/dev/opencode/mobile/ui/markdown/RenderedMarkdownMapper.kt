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
import kotlinx.coroutines.withContext

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
                buildList(rows.size) {
                    for (row in rows) {
                        add(enrichRowOnWorker(row, previousById[row.id]))
                    }
                }
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
            val cacheKey = CacheKey(messageId, text, isStreaming)
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
        }

        private data class CacheKey(
            val messageId: String,
            val text: String,
            val isStreaming: Boolean,
        )
    }
