package dev.opencode.mobile.domain.event

internal class SseEventIngress(
    private val adapter: EventDomainAdapter,
) {
    private val lock = Any()
    private val deduper = SseEventDeduper()
    private var emittedConnected = false

    fun reset() {
        synchronized(lock) {
            deduper.clear()
            emittedConnected = false
        }
    }

    data class Result(
        val parsed: List<OpencodeEvent>,
        val toEmit: List<OpencodeEvent>,
        val parseFailed: Boolean,
    )

    fun accept(dataLine: String): Result {
        val parsed = adapter.parseEvents(dataLine)
        if (parsed.isEmpty()) {
            val failed = dataLine.isNotBlank() && !adapter.recognizes(dataLine)
            return Result(
                parsed = emptyList(),
                toEmit = if (failed) listOf(OpencodeEvent(type = "sse.parse.failed")) else emptyList(),
                parseFailed = failed,
            )
        }
        val toEmit =
            synchronized(lock) {
                val emit = ArrayList<OpencodeEvent>(parsed.size)
                for (event in parsed) {
                    when (event.type) {
                        "server.connected" -> {
                            if (!emittedConnected) {
                                emittedConnected = true
                                emit += event
                            }
                        }
                        "server.heartbeat" -> Unit
                        else -> {
                            if (deduper.accept(event.id)) {
                                emit += event
                            }
                        }
                    }
                }
                emit
            }
        return Result(parsed = parsed, toEmit = toEmit, parseFailed = false)
    }
}
