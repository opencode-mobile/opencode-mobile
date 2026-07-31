package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Provisional Phase 6 Tier A latency samples on the JVM host. Uses synthetic
 * allowlisted grammars so measurements exercise the TextMate path without the
 * full packaged grammar pack. Physical-device Tier B remains a post-close note.
 */
class TextMatePhase6CalibrationTest {
    @Test
    fun provisionalWarmLatencyBucketsForAllowlist() {
        val languages = TextMateLanguageAllowlist.promotedCanonicalLanguages.toList().sorted()
        val rows = mutableListOf<String>()
        languages.forEach { language ->
            val highlighter = highlighterFor(language)
            listOf(2_000 to "S", 8_000 to "L").forEach { (size, bucket) ->
                val source = syntheticSource(language, size)
                val coldStarted = TimeSource.Monotonic.markNow()
                val coldResult = highlighter.highlight(language, source)
                val coldMs = coldStarted.elapsedNow().inWholeMilliseconds
                assertTrue(
                    coldResult.source == SyntaxHighlightSource.TextMate ||
                        coldResult.textMateOutcome == TextMateParseOutcome.NoSpans,
                    "$language cold path should attempt TextMate",
                )

                val warm = LongArray(30)
                repeat(30) { index ->
                    val started = TimeSource.Monotonic.markNow()
                    highlighter.highlight(language, source)
                    warm[index] = started.elapsedNow().inWholeMilliseconds
                }
                warm.sort()
                val p50 = warm[14]
                val p95 = warm[28]
                val line =
                    "TEXTMATE_BENCHMARK_PHASE6 platform=jvm-kotlinregex language=$language " +
                        "bucket=$bucket chars=$size coldMs=$coldMs warmP50Ms=$p50 warmP95Ms=$p95"
                println(line)
                rows += line
                val budget = if (bucket == "S") 50L else 150L
                assertTrue(
                    p95 <= budget * 20,
                    "$language $bucket warm p95=$p95 exceeded diagnostic bound ${budget * 20}",
                )
            }
        }
        assertTrue(rows.size == languages.size * 2)
    }

    @Test
    fun provisionalShortSoakStable() {
        val languages = listOf("typescript", "kotlin", "python", "json", "shellscript")
        val highlighters = languages.associateWith { highlighterFor(it) }
        val sources =
            languages.associateWith { language ->
                syntheticSource(language, 1_200)
            }
        repeat(5) {
            languages.forEach { language ->
                highlighters.getValue(language).highlight(language, sources.getValue(language))
            }
        }
        val started = TimeSource.Monotonic.markNow()
        var cycles = 0
        while (started.elapsedNow().inWholeSeconds < 5) {
            languages.forEach { language ->
                highlighters.getValue(language).highlight(language, sources.getValue(language))
            }
            cycles += 1
        }
        println(
            "TEXTMATE_SOAK_PHASE6 PROVISIONAL_SHORT_SOAK platform=jvm-kotlinregex " +
                "seconds=${started.elapsedNow().inWholeSeconds} cycles=$cycles",
        )
        assertTrue(cycles >= 1)
    }

    private fun highlighterFor(language: String): OpenCodeSyntaxHighlighter {
        val grammar =
            TextMateGrammar(
                name = language,
                scopeName = "source.$language",
                patterns =
                    listOf(
                        TextMatePattern(name = "keyword.control", match = "\\b(?:fun|val|let|const|def|echo|true|false)\\b"),
                        TextMatePattern(name = "string.quoted", begin = "\"", end = "\""),
                        TextMatePattern(name = "constant.numeric", match = "\\b\\d+\\b"),
                    ),
            )
        val descriptor =
            TextMateGrammarDescriptor(
                name = language,
                displayName = language,
                scopeName = grammar.scopeName,
                resourcePath = "$language.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        val payload = TextMateGrammarPayload(descriptor, grammar, GrammarId(language.hashCode() and 0xffff))
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "phase6",
                shikiVersion = "test",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = language,
                            displayName = language,
                            aliases = emptyList(),
                            grammarModule = "test",
                            grammars = listOf(descriptor),
                        ),
                    ),
            )
        val bundle = TextMateGrammarBundle.fromPreloaded(manifest, listOf(payload))
        return OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
        )
    }

    private fun syntheticSource(
        language: String,
        targetChars: Int,
    ): String {
        val seed =
            when (language) {
                "json" -> """{"id":1,"name":"alpha","nested":{"ok":true}}"""
                "yaml" -> "name: alpha\ncount: 1\nitems:\n  - one\n  - two\n"
                "shellscript" -> "#!/usr/bin/env bash\necho alpha\nexport PATH=\"/usr/bin:\$PATH\"\n"
                else -> "fun main() {\n  val alpha = 1\n  println(alpha)\n}\n"
            }
        val builder = StringBuilder(targetChars + seed.length)
        while (builder.length < targetChars) {
            builder.append(seed)
        }
        return builder.substring(0, targetChars)
    }
}
