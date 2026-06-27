package dev.opencode.mobile.markdown

import java.io.OutputStream
import java.io.PrintStream
import org.jcodings.specific.UTF8Encoding
import org.joni.Option
import org.joni.Regex
import org.joni.Syntax
import org.joni.exception.JOniException
import java.util.concurrent.ConcurrentHashMap

object OpenCodeJoniRegexBackend : OpenCodeTextMateRegexBackend {
    override val name: String = "joni-oniguruma"

    override val isOnigurumaCompatible: Boolean = true

    private sealed class RegexCacheEntry {
        data class Compiled(
            val regex: Regex,
        ) : RegexCacheEntry()

        data object Failed : RegexCacheEntry()
    }

    private val regexCache = ConcurrentHashMap<String, RegexCacheEntry>()

    // Joni prints Oniguruma parse warnings to System.err for many bundled TextMate
    // patterns. Compilation is cached, but cold-start still emits thousands of lines
    // to logcat and pays stderr I/O per unique pattern.
    private val compileLock = Any()
    private val silentErr = PrintStream(OutputStream.nullOutputStream())

    private fun cachedRegex(pattern: String): Regex? =
        when (val entry = regexCache.computeIfAbsent(pattern, ::compilePattern)) {
            is RegexCacheEntry.Compiled -> entry.regex
            RegexCacheEntry.Failed -> null
        }

    private fun compilePattern(pattern: String): RegexCacheEntry {
        val patternBytes = pattern.encodeToByteArray()
        synchronized(compileLock) {
            val originalErr = System.err
            return try {
                System.setErr(silentErr)
                val compiled =
                    try {
                        Regex(
                            patternBytes,
                            0,
                            patternBytes.size,
                            Option.MULTILINE,
                            UTF8Encoding.INSTANCE,
                            Syntax.RUBY,
                        )
                    } catch (_: JOniException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                compiled?.let { RegexCacheEntry.Compiled(it) } ?: RegexCacheEntry.Failed
            } finally {
                System.setErr(originalErr)
            }
        }
    }

    override fun find(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? {
        val indexMap = Utf8IndexMap(input)
        val inputBytes = indexMap.bytes
        val regex = cachedRegex(pattern) ?: return null

        val startByte = indexMap.byteIndexFor(startIndex.coerceIn(0, input.length))
        val endByte = indexMap.byteIndexFor(endIndex.coerceIn(0, input.length))
        val matcher = regex.matcher(inputBytes)
        val result = matcher.search(startByte, endByte, Option.NONE)
        if (result < 0) return null

        val region = matcher.eagerRegion
        val captures =
            (0 until region.numRegs)
                .mapNotNull { index ->
                    val begin = region.getBeg(index)
                    val end = region.getEnd(index)
                    if (begin < 0 || end < begin) {
                        null
                    } else {
                        TextMateRegexCapture(
                            index = index,
                            start = indexMap.charIndexFor(begin),
                            end = indexMap.charIndexFor(end),
                        )
                    }
                }

        return TextMateRegexMatch(
            start = indexMap.charIndexFor(result),
            end = indexMap.charIndexFor(matcher.end),
            captures = captures,
        )
    }
}
