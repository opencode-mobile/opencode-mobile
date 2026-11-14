package dev.opencode.mobile.domain.live

import java.util.concurrent.ConcurrentHashMap

/**
 * Home's session window size per directory (#981); absent means one page.
 *
 * A new process seeds each directory from the Session Cache, which may hold more than one
 * page (the last window after load-more). Until a server load commits, the window starts at
 * the cached row count rounded up to a whole page, capped at [MAX_SEEDED_PAGES] pages, so the
 * first live load returns as many rows as the cache painted instead of shrinking the list.
 */
class HomeSessionWindows {
    private val sizes = ConcurrentHashMap<String, Int>()
    private val committed = ConcurrentHashMap.newKeySet<String>()

    fun limit(directory: String): Int = sizes[directory] ?: HomeSessionPaging.PAGE_SIZE

    /** Raises [directory]'s window to cover [cachedCount] rows when no server load committed yet. */
    fun seedFromCache(
        directory: String,
        cachedCount: Int,
    ) {
        if (cachedCount <= 0 || committed.contains(directory)) return
        val pageSize = HomeSessionPaging.PAGE_SIZE
        val pages = ((cachedCount + pageSize - 1) / pageSize).coerceAtMost(MAX_SEEDED_PAGES)
        val seeded = pages * pageSize
        sizes.compute(directory) { _, size -> maxOf(size ?: pageSize, seeded) }
    }

    /** A server load for [directory] committed; later cache seeds no longer change the window. */
    fun markCommitted(directory: String) {
        committed.add(directory)
    }

    /** Raises [directory]'s window by one page. */
    fun grow(directory: String) {
        sizes.compute(directory) { _, size -> (size ?: HomeSessionPaging.PAGE_SIZE) + HomeSessionPaging.PAGE_SIZE }
    }

    fun clear() {
        sizes.clear()
        committed.clear()
    }

    companion object {
        const val MAX_SEEDED_PAGES = 4
    }
}
