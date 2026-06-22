package dev.opencode.mobile.markdown

import java.nio.charset.StandardCharsets
import org.jcodings.specific.UTF8Encoding
import org.joni.Option
import org.joni.Regex
import org.joni.Syntax
import org.joni.exception.JOniException
import java.util.concurrent.ConcurrentHashMap

object OpenCodeJoniRegexBackend : OpenCodeTextMateRegexBackend {
    override val name: String = "joni-oniguruma"

    override val isOnigurumaCompatible: Boolean = true

    private val regexCache = ConcurrentHashMap<String, Regex>()

    private fun cachedRegex(pattern: String): Regex? {
        regexCache[pattern]?.let { return it }
        val patternBytes = pattern.toByteArray(StandardCharsets.UTF_8)
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
        return compiled?.let { regexCache.putIfAbsent(pattern, it) ?: it }
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

    private class Utf8IndexMap(
        input: String,
    ) {
        val bytes: ByteArray = input.toByteArray(StandardCharsets.UTF_8)
        private val charToByte = IntArray(input.length + 1)
        private val byteToChar = IntArray(bytes.size + 1)

        init {
            var charIndex = 0
            var byteIndex = 0

            while (charIndex < input.length) {
                val codePoint = Character.codePointAt(input, charIndex)
                val charCount = Character.charCount(codePoint)
                val byteCount = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                val nextCharIndex = charIndex + charCount
                val nextByteIndex = byteIndex + byteCount

                for (index in charIndex until nextCharIndex) {
                    charToByte[index] = byteIndex
                }
                for (index in byteIndex until nextByteIndex) {
                    byteToChar[index] = charIndex
                }
                byteToChar[nextByteIndex] = nextCharIndex

                charIndex = nextCharIndex
                byteIndex = nextByteIndex
            }

            charToByte[input.length] = bytes.size
            byteToChar[bytes.size] = input.length
        }

        fun byteIndexFor(charIndex: Int): Int = charToByte[charIndex]

        fun charIndexFor(byteIndex: Int): Int = byteToChar[byteIndex.coerceIn(byteToChar.indices)]
    }
}
