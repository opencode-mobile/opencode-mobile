package dev.opencode.mobile.domain.home

data class HomeSessionOrderResult<T>(
    val sections: List<HomeSessionLadderSection<T>>,
    val lastAppliedAtEpochMs: Long,
    val nextApplyAtEpochMs: Long?,
)

object HomeSessionOrderStabilizer {
    const val REORDER_INTERVAL_MS: Long = 5_000L

    fun <T> stabilize(
        previousSections: List<HomeSessionLadderSection<T>>,
        previousLastAppliedAtEpochMs: Long?,
        desired: List<HomeSessionLadderSection<T>>,
        itemId: (T) -> String,
        isAttention: (T) -> Boolean,
        isReady: (T) -> Boolean,
        nowEpochMs: Long,
        reorderIntervalMs: Long = REORDER_INTERVAL_MS,
    ): HomeSessionOrderResult<T> {
        if (previousSections.isEmpty()) {
            return apply(desired, nowEpochMs)
        }

        val previousById = previousSections.flatMap { it.items }.associateBy(itemId)
        val desiredItems = desired.flatMap { it.items }
        val forceApply =
            desiredItems.any { item ->
                val previous = previousById[itemId(item)]
                previous == null ||
                    isAttention(previous) != isAttention(item) ||
                    (!isReady(previous) && isReady(item))
            }
        val intervalElapsed =
            previousLastAppliedAtEpochMs == null ||
                nowEpochMs - previousLastAppliedAtEpochMs >= reorderIntervalMs
        if (forceApply || intervalElapsed) {
            return apply(desired, nowEpochMs)
        }

        val lastAppliedAt = previousLastAppliedAtEpochMs
        val latestById = desiredItems.associateBy(itemId)
        val held =
            previousSections.mapNotNull { section ->
                val items = section.items.mapNotNull { latestById[itemId(it)] }
                if (items.isEmpty()) {
                    null
                } else {
                    section.copy(items = items)
                }
            }
        val nextApplyAt =
            if (orderSignature(held, itemId) == orderSignature(desired, itemId)) {
                null
            } else {
                lastAppliedAt + reorderIntervalMs
            }
        return HomeSessionOrderResult(
            sections = held,
            lastAppliedAtEpochMs = lastAppliedAt,
            nextApplyAtEpochMs = nextApplyAt,
        )
    }

    private fun <T> apply(
        desired: List<HomeSessionLadderSection<T>>,
        nowEpochMs: Long,
    ) = HomeSessionOrderResult(
        sections = desired,
        lastAppliedAtEpochMs = nowEpochMs,
        nextApplyAtEpochMs = null,
    )

    private fun <T> orderSignature(
        sections: List<HomeSessionLadderSection<T>>,
        itemId: (T) -> String,
    ): List<Pair<String, List<String>>> = sections.map { section -> section.id to section.items.map(itemId) }
}
