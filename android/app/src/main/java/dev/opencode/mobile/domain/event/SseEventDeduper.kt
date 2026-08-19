package dev.opencode.mobile.domain.event

class SseEventDeduper(
    private val capacity: Int = 256,
) {
    private val lock = Any()
    private val order = ArrayDeque<String>()
    private val seen = HashSet<String>()

    fun accept(id: String?): Boolean {
        if (id.isNullOrBlank()) return true
        synchronized(lock) {
            if (!seen.add(id)) return false
            order.addLast(id)
            if (order.size > capacity) {
                seen.remove(order.removeFirst())
            }
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            order.clear()
            seen.clear()
        }
    }
}
