package dev.opencode.mobile.markdown

import java.io.File

/** Opt-in tier for the #872 baselines: `HIGHLIGHT_BASELINE=quick` or `HIGHLIGHT_BASELINE=full`. */
internal enum class BaselineTier {
    Quick,
    Full,
    ;

    companion object {
        fun fromEnvironment(): BaselineTier? =
            when (System.getenv("HIGHLIGHT_BASELINE")?.lowercase()) {
                "quick" -> Quick
                "full", "1" -> Full
                else -> null
            }
    }
}

/** Markdown report rewritten after every row, so a run in progress can be read. */
internal class BaselineReport(
    private val output: File,
    title: String,
) {
    private val text = StringBuilder()

    init {
        val runtime = Runtime.getRuntime()
        text.appendLine("# $title")
        text.appendLine()
        text.appendLine(
            "- Host: ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "${System.getProperty("os.arch")}, ${runtime.availableProcessors()} cores, " +
                "max heap ${runtime.maxMemory() / (1024 * 1024)} MB",
        )
        text.appendLine("- JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        text.appendLine("- Times are wall-clock milliseconds; \"warm\" means after the grammar loaded and the JIT ran.")
        flush()
        println("HIGHLIGHT_BASELINE report: ${output.absolutePath}")
    }

    fun section(title: String) {
        text.appendLine().appendLine("## $title").appendLine()
        println("HIGHLIGHT_BASELINE ## $title")
        flush()
    }

    fun note(line: String) {
        text.appendLine(line).appendLine()
        flush()
    }

    fun table(vararg headers: String) {
        text.appendLine(headers.joinToString(" | ", "| ", " |"))
        text.appendLine(headers.joinToString(" | ", "| ", " |") { "---" })
    }

    fun row(vararg cells: String) {
        val line = cells.joinToString(" | ", "| ", " |")
        text.appendLine(line)
        println("HIGHLIGHT_BASELINE $line")
        flush()
    }

    private fun flush() {
        output.parentFile.mkdirs()
        output.writeText(text.toString())
    }

    companion object {
        inline fun <T> measure(block: () -> T): Pair<Long, T> {
            val startedAt = System.nanoTime()
            val value = block()
            return (System.nanoTime() - startedAt) to value
        }

        fun percentile(
            samples: List<Long>,
            percent: Int,
        ): Long {
            val sorted = samples.sorted()
            return sorted[((percent / 100.0) * sorted.size).toInt().coerceAtMost(sorted.size - 1)]
        }

        fun ms(nanos: Long): String = "%.1f".format(nanos / 1_000_000.0)

        fun charsPerMs(
            characters: Int,
            nanos: Long,
        ): String = "%.0f".format(characters / (nanos / 1_000_000.0))
    }
}
