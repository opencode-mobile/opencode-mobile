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
        val droppedType: String? = null,
    )

    fun accept(dataLine: String): Result {
        if (dataLine.isBlank()) {
            return Result(emptyList(), emptyList(), parseFailed = false)
        }
        return when (val outcome = adapter.parse(dataLine)) {
            EventParseOutcome.Unrecognized ->
                Result(
                    parsed = emptyList(),
                    toEmit = listOf(OpencodeEvent(type = "sse.parse.failed")),
                    parseFailed = true,
                )
            is EventParseOutcome.Dropped ->
                Result(
                    parsed = emptyList(),
                    toEmit = emptyList(),
                    parseFailed = false,
                    droppedType = outcome.type,
                )
            is EventParseOutcome.Events -> {
                val parsed = outcome.events
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
                Result(parsed = parsed, toEmit = toEmit, parseFailed = false)
            }
        }
    }
}
