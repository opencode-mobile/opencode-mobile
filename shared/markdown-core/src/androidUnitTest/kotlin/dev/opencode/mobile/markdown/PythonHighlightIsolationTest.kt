package dev.opencode.mobile.markdown

import kotlin.test.Test

class PythonHighlightIsolationTest {
    private fun highlighter(): OpenCodeSyntaxHighlighter =
        OpenCodeSyntaxHighlighter(
            grammarBundle = bundledGrammarBundle(),
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend),
        )

    private fun dump(
        label: String,
        code: String,
    ) {
        val result = highlighter().highlight("python", code)
        println("### $label")
        result.spans.forEach { span ->
            val t = code.substring(span.start, span.end).replace("\n", "\\n")
            println("  ${span.role} [${span.start},${span.end}) '$t'")
        }
        println()
    }

    private fun dumpRaw(
        label: String,
        code: String,
    ) {
        val bundle = bundledGrammarBundle()
        val grammar = bundle.primaryGrammarFor("python")!!
        val tokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend)
        val result = tokenizer.tokenize(grammar, code, bundle)
        println("### RAW $label")
        result.tokens.forEach { token ->
            val t = code.substring(token.start, token.end).replace("\n", "\\n")
            println("  [${token.start},${token.end}) '$t' ${token.scopes}")
        }
        println()
    }

    @Test
    fun raw() {
        dumpRaw(
            "dict ternary",
            listOf(
                "d = {",
                "    \"start_time\": a.isoformat() if a else None,",
                "}",
            ).joinToString("\n"),
        )
        dumpRaw("key colon value", "\"start_time\": value")
        dumpRaw("assign string", "x = \"start_time\"")
    }

    @Test
    fun isolate() {
        dump(
            "fstring with format spec colon",
            "x = f\"retrying in {delay}s: {str(e)}\"",
        )
        dump(
            "dict with ternary next lines",
            listOf(
                "d = {",
                "    \"start_time\": a.isoformat() if a else None,",
                "    \"end_time\": b.isoformat() if b else None,",
                "}",
            ).joinToString("\n"),
        )
        dump(
            "triple quoted docstring then code",
            listOf(
                "def f():",
                "    \"\"\"Docstring.\"\"\"",
                "    return 1",
                "",
                "@decorator(a=1, b=(X, Y))",
                "def g():",
                "    pass",
            ).joinToString("\n"),
        )
        dump(
            "single fstring line then def",
            listOf(
                "logger.warning(f\"Worker {worker_id} down\")",
                "async def get(self, id: str) -> Optional[Dict[str, Any]]:",
            ).joinToString("\n"),
        )
    }
}
