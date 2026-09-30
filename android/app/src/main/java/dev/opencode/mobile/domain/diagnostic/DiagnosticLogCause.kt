package dev.opencode.mobile.domain.diagnostic

internal object DiagnosticLogCause {
    private const val MAX_DEPTH = 4
    private const val MAX_FRAME_MESSAGE = 160

    fun summarize(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = throwable
        while (current != null && parts.size < MAX_DEPTH && seen.none { it === current }) {
            seen += current
            parts += frame(current)
            current = current.cause
        }
        return "cause=" + parts.joinToString(" <- ")
    }

    fun appendTo(
        message: String,
        throwable: Throwable?,
    ): String {
        if (throwable == null) return message
        val summary = summarize(throwable)
        return if (message.contains(summary)) message else "$message $summary"
    }

    private fun frame(error: Throwable): String {
        val name = error::class.simpleName ?: error.javaClass.simpleName
        val message = firstLine(error.message)
        return if (message.isEmpty() || message == name) name else "$name: $message"
    }

    private fun firstLine(message: String?): String {
        val line =
            message
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
        return if (line.length <= MAX_FRAME_MESSAGE) {
            line
        } else {
            line.take(MAX_FRAME_MESSAGE) + "…"
        }
    }
}
