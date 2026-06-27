package dev.opencode.mobile.markdown

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import oniguruma.ONIG_ENCODING_UTF8
import oniguruma.ONIG_OPTION_MULTILINE
import oniguruma.ONIG_OPTION_NONE
import oniguruma.ONIG_SYNTAX_RUBY
import oniguruma.OnigErrorInfo
import oniguruma.OnigRegexType
import oniguruma.onig_new
import oniguruma.onig_region_free
import oniguruma.onig_region_new
import oniguruma.onig_search
import oniguruma.opencode_onig_region_beg_at
import oniguruma.opencode_onig_region_end_at

@OptIn(ExperimentalForeignApi::class)
object OpenCodeLibOnigRegexBackend : OpenCodeTextMateRegexBackend {
    override val name: String = "libonig"

    override val isOnigurumaCompatible: Boolean = true

    private sealed class RegexCacheEntry {
        data class Compiled(
            val regex: CPointer<OnigRegexType>,
        ) : RegexCacheEntry()

        data object Failed : RegexCacheEntry()
    }

    private val regexCache = mutableMapOf<String, RegexCacheEntry>()

    private fun cachedRegex(pattern: String): CPointer<OnigRegexType>? {
        regexCache[pattern]?.let { entry ->
            return when (entry) {
                is RegexCacheEntry.Compiled -> entry.regex
                RegexCacheEntry.Failed -> null
            }
        }

        val compiled = compilePattern(pattern)
        regexCache[pattern] = compiled
        return when (compiled) {
            is RegexCacheEntry.Compiled -> compiled.regex
            RegexCacheEntry.Failed -> null
        }
    }

    private fun compilePattern(pattern: String): RegexCacheEntry {
        val patternBytes = pattern.encodeToByteArray()
        return memScoped {
            val regexOut = alloc<CPointerVar<OnigRegexType>>()
            val errorInfo = alloc<OnigErrorInfo>()
            withUtf8Range(patternBytes) { patternStart, patternEnd ->
                val compileResult =
                    onig_new(
                        regexOut.ptr,
                        patternStart,
                        patternEnd,
                        ONIG_OPTION_MULTILINE,
                        ONIG_ENCODING_UTF8,
                        ONIG_SYNTAX_RUBY,
                        errorInfo.ptr,
                    )
                if (compileResult != 0) {
                    RegexCacheEntry.Failed
                } else {
                    val compiled = regexOut.value ?: return@withUtf8Range RegexCacheEntry.Failed
                    RegexCacheEntry.Compiled(compiled)
                }
            }
        }
    }

    override fun find(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? {
        val regex = cachedRegex(pattern) ?: return null
        val indexMap = Utf8IndexMap(input)
        val inputBytes = indexMap.bytes

        return withUtf8Range(inputBytes) { stringStart, stringEnd ->
            memScoped {
                val region = onig_region_new() ?: return@withUtf8Range null
                try {
                    val searchStartByte = indexMap.byteIndexFor(startIndex.coerceIn(0, input.length))
                    val searchEndByte = indexMap.byteIndexFor(endIndex.coerceIn(0, input.length))
                    val searchStart = stringStart + searchStartByte ?: stringStart
                    val searchEnd = stringStart + searchEndByte ?: stringEnd

                    val matchStart =
                        onig_search(
                            regex,
                            stringStart,
                            stringEnd,
                            searchStart,
                            searchEnd,
                            region,
                            ONIG_OPTION_NONE,
                        )
                    if (matchStart < 0) return@withUtf8Range null

                    val regionData = region.pointed
                    val captures =
                        (0 until regionData.num_regs)
                            .mapNotNull { index ->
                                val begin = opencode_onig_region_beg_at(region, index)
                                val end = opencode_onig_region_end_at(region, index)
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

                    val matchEnd = opencode_onig_region_end_at(region, 0)
                    TextMateRegexMatch(
                        start = indexMap.charIndexFor(matchStart),
                        end = indexMap.charIndexFor(matchEnd),
                        captures = captures,
                    )
                } finally {
                    onig_region_free(region, 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private val emptyUtf8PointerAnchor = byteArrayOf(0)

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withUtf8Range(
    bytes: ByteArray,
    block: (CPointer<UByteVar>, CPointer<UByteVar>) -> T,
): T {
    if (bytes.isEmpty()) {
        return emptyUtf8PointerAnchor.usePinned { pinned ->
            val start = pinned.addressOf(0).reinterpret<UByteVar>()
            block(start, start)
        }
    }

    return bytes.usePinned { pinned ->
        val start = pinned.addressOf(0).reinterpret<UByteVar>()
        val end = start + bytes.size
        block(start, end ?: start)
    }
}
