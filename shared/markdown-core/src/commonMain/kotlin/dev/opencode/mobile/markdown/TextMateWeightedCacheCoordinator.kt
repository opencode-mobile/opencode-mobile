package dev.opencode.mobile.markdown

/**
 * Coordinates weighted admission and eviction for grammar programs, resolved rule
 * plans, and compiled regexes. Eviction changes performance only; leased entries
 * are never disposed.
 */
class TextMateWeightedCacheCoordinator(
    private val evictionBudgetBytes: Long = DEFAULT_EVICTION_BUDGET_BYTES,
    private val admissionBudgetBytes: Long = DEFAULT_ADMISSION_BUDGET_BYTES,
    private val clockMs: () -> Long = { 0L },
) {
    enum class Category {
        GrammarProgram,
        RulePlan,
        CompiledRegex,
    }

    data class Snapshot(
        val retainedBytes: Long,
        val entryCount: Int,
        val leasedCount: Int,
        val evictionCount: Long,
        val rejectedPromotionCount: Long,
    )

    private data class Entry(
        val key: String,
        val category: Category,
        val estimatedBytes: Long,
        var lastUsedMs: Long,
        var leaseCount: Int = 0,
        var pinned: Boolean = false,
        val dispose: () -> Unit,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var evictionCount: Long = 0
    private var rejectedPromotionCount: Long = 0

    fun retainedBytes(): Long = entries.values.sumOf { it.estimatedBytes }

    fun snapshot(): Snapshot =
        Snapshot(
            retainedBytes = retainedBytes(),
            entryCount = entries.size,
            leasedCount = entries.values.count { it.leaseCount > 0 || it.pinned },
            evictionCount = evictionCount,
            rejectedPromotionCount = rejectedPromotionCount,
        )

    fun contains(key: String): Boolean = key in entries

    fun touch(key: String) {
        entries[key]?.let { entry ->
            entry.lastUsedMs = clockMs()
        }
    }

    fun lease(key: String): Boolean {
        val entry = entries[key] ?: return false
        entry.leaseCount += 1
        entry.lastUsedMs = clockMs()
        return true
    }

    fun release(key: String) {
        val entry = entries[key] ?: return
        entry.leaseCount = (entry.leaseCount - 1).coerceAtLeast(0)
    }

    fun setPinned(
        key: String,
        pinned: Boolean,
    ) {
        entries[key]?.pinned = pinned
    }

    /**
     * Registers or replaces an entry. Returns false when the entry cannot be
     * admitted even after eviction attempts.
     */
    fun promote(
        key: String,
        category: Category,
        estimatedBytes: Long,
        dispose: () -> Unit = {},
    ): Boolean {
        entries.remove(key)?.dispose?.invoke()
        evictUntil(admissionBudgetBytes - estimatedBytes)
        if (retainedBytes() + estimatedBytes > admissionBudgetBytes) {
            rejectedPromotionCount += 1
            return false
        }
        entries[key] =
            Entry(
                key = key,
                category = category,
                estimatedBytes = estimatedBytes.coerceAtLeast(1L),
                lastUsedMs = clockMs(),
                dispose = dispose,
            )
        if (retainedBytes() >= evictionBudgetBytes) {
            evictUntil(evictionBudgetBytes - 1)
        }
        return true
    }

    fun remove(key: String) {
        entries.remove(key)?.dispose?.invoke()
    }

    fun removeIfUnleased(key: String): Boolean {
        val entry = entries[key] ?: return false
        if (entry.leaseCount > 0 || entry.pinned) return false
        entries.remove(key)
        entry.dispose()
        evictionCount += 1
        return true
    }

    fun trim(level: MemoryPressure) {
        when (level) {
            MemoryPressure.Normal -> Unit
            MemoryPressure.Moderate -> {
                evictCategory(Category.CompiledRegex, requireUnleased = true)
                evictCategory(Category.RulePlan, requireUnleased = true)
            }
            MemoryPressure.Critical -> {
                evictCategory(Category.CompiledRegex, requireUnleased = true)
                evictCategory(Category.RulePlan, requireUnleased = true)
                evictCategory(Category.GrammarProgram, requireUnleased = true)
            }
        }
    }

    private fun evictUntil(targetRetainedBytes: Long) {
        while (retainedBytes() > targetRetainedBytes) {
            val victim =
                entries.values
                    .asSequence()
                    .filter { it.leaseCount == 0 && !it.pinned }
                    .sortedWith(
                        compareBy<Entry> { categoryRank(it.category) }
                            .thenBy { it.lastUsedMs },
                    ).firstOrNull()
                    ?: break
            entries.remove(victim.key)
            victim.dispose()
            evictionCount += 1
        }
    }

    private fun evictCategory(
        category: Category,
        requireUnleased: Boolean,
    ) {
        val victims =
            entries.values
                .filter { entry ->
                    entry.category == category &&
                        (!requireUnleased || (entry.leaseCount == 0 && !entry.pinned))
                }.sortedBy { it.lastUsedMs }
                .toList()
        victims.forEach { victim ->
            entries.remove(victim.key)
            victim.dispose()
            evictionCount += 1
        }
    }

    private fun categoryRank(category: Category): Int =
        when (category) {
            Category.CompiledRegex -> 0
            Category.RulePlan -> 1
            Category.GrammarProgram -> 2
        }

    companion object {
        const val DEFAULT_EVICTION_BUDGET_BYTES: Long = 24L * 1024L * 1024L
        const val DEFAULT_ADMISSION_BUDGET_BYTES: Long = 32L * 1024L * 1024L

        fun estimateRegexBytes(pattern: String): Long = pattern.encodeToByteArray().size + 256L
    }
}

interface TextMateCacheTrimmable {
    fun trimCaches(level: MemoryPressure)
}
