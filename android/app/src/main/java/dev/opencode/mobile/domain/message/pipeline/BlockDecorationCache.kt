package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument

/**
 * Phase 5 per-text-block decoration lookup.
 * Keyed by session + block + content revision + streaming flag (no generation).
 * Generation rejects happen in the pipeline before [put].
 */
object BlockDecorationCache {
    private const val MAX_ENTRIES = 32

    data class Key(
        val sessionKey: String,
        val blockID: String,
        val textHash: Int,
        val textLength: Int,
        val isStreaming: Boolean,
    )

    private val lock = Any()
    private val entries =
        object : LinkedHashMap<Key, MarkdownDocument>(MAX_ENTRIES + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, MarkdownDocument>?): Boolean = size > MAX_ENTRIES
        }

    fun get(
        sessionKey: String,
        blockID: String,
        textHash: Int,
        textLength: Int,
        isStreaming: Boolean,
    ): MarkdownDocument? =
        synchronized(lock) {
            entries[
                Key(
                    sessionKey = sessionKey,
                    blockID = blockID,
                    textHash = textHash,
                    textLength = textLength,
                    isStreaming = isStreaming,
                ),
            ]
        }

    fun put(
        sessionKey: String,
        blockID: String,
        textHash: Int,
        textLength: Int,
        isStreaming: Boolean,
        document: MarkdownDocument,
    ) {
        synchronized(lock) {
            entries[
                Key(
                    sessionKey = sessionKey,
                    blockID = blockID,
                    textHash = textHash,
                    textLength = textLength,
                    isStreaming = isStreaming,
                ),
            ] = document
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    /** Test / diagnostics only. */
    internal fun size(): Int = synchronized(lock) { entries.size }
}
