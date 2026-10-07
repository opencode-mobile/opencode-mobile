package dev.opencode.mobile.domain.message

/**
 * Bounded history merge. Tail is newest-first authority for its window;
 * older pages only prepend unseen ids.
 */
object MessageWindowMerge {
    fun applyTail(
        local: List<Message>,
        tail: List<Message>,
    ): List<Message> {
        if (tail.isEmpty()) return local
        if (local.isEmpty()) return tail
        val tailIds = tail.map { it.id }.toHashSet()
        val earliestOverlap = local.indexOfFirst { it.id in tailIds }
        if (earliestOverlap < 0) return tail
        val prefix = local.subList(0, earliestOverlap)
        // Suffix starts after the latest overlapping local index. A reordered tail
        // can name an earlier row last; rows between those indexes were omitted
        // (a revert). When tail.last is absent locally, that window already
        // consumed everything past the overlap.
        val latestOverlap = local.indexOfLast { it.id in tailIds }
        val tailNewestInLocal = local.indexOfLast { it.id == tail.last().id }
        val suffix =
            if (tailNewestInLocal >= 0 && latestOverlap + 1 < local.size) {
                local.subList(latestOverlap + 1, local.size).filter { it.id !in tailIds }
            } else {
                emptyList()
            }
        return prefix + tail + suffix
    }

    fun hasOverlap(
        local: List<Message>,
        tail: List<Message>,
    ): Boolean {
        if (local.isEmpty() || tail.isEmpty()) return false
        val tailIds = tail.map { it.id }.toHashSet()
        return local.any { it.id in tailIds }
    }

    fun prependOlder(
        local: List<Message>,
        older: List<Message>,
    ): List<Message> {
        if (older.isEmpty()) return local
        val seen = local.map { it.id }.toHashSet()
        val prepended = older.filter { seen.add(it.id) }
        return prepended + local
    }

    fun isTailAcceptable(
        remote: List<Message>,
        local: List<Message>,
    ): Boolean {
        if (remote.isEmpty() || local.isEmpty()) return true
        val remoteIds = remote.map { it.id }.toHashSet()
        val oldestOverlapIndex = local.indexOfFirst { it.id in remoteIds }
        if (oldestOverlapIndex < 0) return true
        val tailNewestId = remote.last().id
        val tailNewestInLocal = local.indexOfLast { it.id == tailNewestId }
        val windowEnd = if (tailNewestInLocal >= 0) tailNewestInLocal + 1 else local.size
        // A local message inside the tail's window that the tail lacks was removed
        // on the server (a committed revert): it must not make the tail look stale.
        val localWindow = local.subList(oldestOverlapIndex, windowEnd).filter { it.id in remoteIds }
        return MessageSnapshotAuthority.isAheadOrEqual(remote = remote, local = localWindow)
    }
}
