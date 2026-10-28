package dev.opencode.mobile.highlight.core

import kotlin.random.Random

/** Random inputs for property tests and the randomized run: plausible JSON and diffs, damaged freely. */
internal object HighlightGenerators {
    val languages = listOf("json", "diff", "plaintext")

    private val jsonPieces =
        listOf(
            "{", "}", "[", "]", ":", ",", " ", " ", "\n", "\n", "\r\n", "\"key\"", "\"str\\\"esc\\u00e9\"", "'s'", "\"open",
            "123", "-1.5e3", "0x1F", ".5", "true", "null", "NaN", "bare", "// note", "/* c */", "/* open", "*/", "\\",
            "😀", "\uD83D", "\uDE00", "​", "\r", "\t", "@",
        )

    private val diffLines =
        listOf(
            "diff --git a/x b/x", "index 1a2b..3c4d 100644", "--- a/x", "+++ b/x", "@@ -1,3 +1,4 @@ fun f()", "@@ -1 +1 @@",
            "@@ -0,0 +1,2 @@", "@@", "@@@ -1 -1 +1 @@@", "+added", "-removed", " context", "", "\\ No newline at end of file",
            "--- removed comment", "+++ added", "new file mode 100644", "rename from a", "> right", "< left", "plain words",
            "@@ -1,99999 +1,2 @@", "+😀\uD83D",
        )

    fun text(
        language: String,
        random: Random,
        size: Int,
    ): String =
        when (random.nextInt(10)) {
            0 -> soup(random, size)
            else -> if (language == "diff") diff(random, size) else json(random, size)
        }

    fun json(
        random: Random,
        size: Int,
    ): String = buildString { while (length < size) append(jsonPieces[random.nextInt(jsonPieces.size)]) }

    fun diff(
        random: Random,
        size: Int,
    ): String =
        buildString {
            while (length < size) {
                append(diffLines[random.nextInt(diffLines.size)])
                append(if (random.nextInt(8) == 0) "\r\n" else "\n")
            }
        }

    /** Any UTF-16 units: binary, lone surrogates, control characters, line breaks. */
    fun soup(
        random: Random,
        size: Int,
    ): String =
        CharArray(size) {
            when (random.nextInt(12)) {
                0 -> '\n'
                1 -> '\r'
                2 -> random.nextInt(0xD800, 0xE000).toChar()
                3 -> "{}[]:,\"'\\/*-+@ ".random(random)
                else -> random.nextInt(0, 0x10000).toChar()
            }
        }.concatToString()

    /** Splits [text] into 1 to 8 random chunks, sometimes splitting a CRLF or a surrogate pair. */
    fun chunks(
        text: String,
        random: Random,
    ): List<String> {
        if (text.isEmpty()) return listOf("")
        val cuts = List(random.nextInt(0, 8)) { random.nextInt(0, text.length + 1) }.sorted()
        val result = mutableListOf<String>()
        var at = 0
        for (cut in cuts + text.length) {
            result += text.substring(at, cut)
            at = cut
        }
        return result
    }
}
