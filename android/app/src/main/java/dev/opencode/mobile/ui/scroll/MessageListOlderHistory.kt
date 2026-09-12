package dev.opencode.mobile.ui.scroll

internal object MessageListOlderHistory {
    fun shouldLoadOlder(
        hasOlder: Boolean,
        isLoadingOlder: Boolean,
        oldestVisible: Boolean,
        userScrolledTowardOldest: Boolean,
    ): Boolean = hasOlder && !isLoadingOlder && oldestVisible && userScrolledTowardOldest

    /** Reverse-layout LazyColumn: higher index/offset is toward the oldest edge. */
    fun isTowardOldest(
        previousIndex: Int,
        previousOffset: Int,
        index: Int,
        offset: Int,
    ): Boolean = index > previousIndex || (index == previousIndex && offset > previousOffset)
}

/** One user drag toward the oldest edge arms one older load after first presentation. */
internal class MessageListOlderLoadIntent {
    var presentationSettled: Boolean = false
        private set
    private var inProgress: Boolean = false
    private var armed: Boolean = false
    private var consumedThisGesture: Boolean = false

    fun markPresentationSettled() {
        presentationSettled = true
    }

    fun onUserScroll(
        inProgress: Boolean,
        oldestVisible: Boolean,
        towardOldest: Boolean,
    ) {
        if (!inProgress) {
            this.inProgress = false
            armed = false
            consumedThisGesture = false
            return
        }
        val newDrag = !this.inProgress
        this.inProgress = true
        if (!presentationSettled || consumedThisGesture) return
        if ((newDrag && oldestVisible) || towardOldest) {
            armed = true
        }
    }

    fun shouldLoadOlder(
        hasOlder: Boolean,
        isLoadingOlder: Boolean,
        oldestVisible: Boolean,
    ): Boolean =
        MessageListOlderHistory.shouldLoadOlder(
            hasOlder = hasOlder,
            isLoadingOlder = isLoadingOlder,
            oldestVisible = oldestVisible,
            userScrolledTowardOldest = armed,
        )

    fun consume() {
        consumedThisGesture = true
        armed = false
    }
}
