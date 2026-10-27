package dev.opencode.mobile.highlight.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightBenchHeadlessTest {
    @Test
    fun aTierAloneUsesDefaults() {
        val request = HighlightBenchHeadless.parse("quick")

        assertEquals(HighlightBenchTier.Quick, request.tier)
        assertEquals(emptyList(), request.engineIds)
        assertEquals(HighlightBenchSettings.standard, request.settings)
        assertEquals(HighlightBenchPlan.cases(HighlightBenchTier.Quick).map { it.id }, request.cases().map { it.id })
        assertEquals(HighlightBenchTier.Full, HighlightBenchHeadless.parse(" full ").tier)
    }

    @Test
    fun dataSetsAndOptionsParse() {
        val request = HighlightBenchHeadless.parse("worst/binary-200k,typical/kotlin/16k:engines=b,a:limitMs=5000:graceMs=500:whileStuck=run")

        assertNull(request.tier)
        assertEquals(listOf("worst/binary-200k", "typical/kotlin/16k"), request.dataSetIds)
        assertEquals(listOf("throughput/typical/kotlin/16k", "timed/worst/binary-200k"), request.cases().map { it.id })
        assertEquals(listOf("b", "a"), request.engineIds)
        assertEquals(HighlightBenchSettings(5_000, 500, HighlightBenchStuckPolicy.RunAnyway), request.settings)
        val a = FakeEngine("a") { _, source, _ -> highlighted(source) }
        val b = FakeEngine("b") { _, source, _ -> highlighted(source) }
        assertEquals(listOf("b", "a"), request.engines(listOf(a, b)).map { it.id })
        assertEquals(listOf("a", "b"), HighlightBenchHeadless.parse("quick").engines(listOf(a, b)).map { it.id })
    }

    @Test
    fun invalidValuesNameTheProblem() {
        mapOf(
            "" to "needs a selection",
            "typical/cobol/2k" to "Unknown data set: typical/cobol/2k",
            "quick:speed=fast" to "Unknown option: speed",
            "quick:engines=" to "needs a value",
            "quick:limitMs=0" to "limitMs must be a positive number",
            "quick:whileStuck=wait" to "whileStuck must be skip or run",
        ).forEach { (value, message) ->
            val error = assertFailsWith<IllegalArgumentException>(value) { HighlightBenchHeadless.parse(value) }
            assertTrue(error.message!!.contains(message), "$value: ${error.message}")
        }
        val error =
            assertFailsWith<IllegalArgumentException> {
                HighlightBenchHeadless.parse("quick:engines=new").engines(listOf(fast))
            }
        assertEquals("Unknown engine: new (available: fake)", error.message)
    }

    @Test
    fun runReturnsMarkdownAndAJsonRowPerCaseAndEngine() {
        val a = FakeEngine("a") { _, source, _ -> highlighted(source) }
        val b = FakeEngine("b") { _, source, _ -> highlighted(source) }

        val result = HighlightBenchHeadless.run("typical/json/2k,worst/open-parens-20k:engines=b,a", listOf(a, b), HighlightBenchInlineGuard, listOf("Device: test"), onProgress = {})

        assertTrue(result.markdown.startsWith("# Highlighting Lab run: typical/json/2k,worst/open-parens-20k (B, A)\n\n- Device: test\n"))
        assertTrue("## Comparison" in result.markdown)
        assertEquals(4, result.report.results.size)
        assertTrue(result.json.startsWith("{\"engines\":[{\"id\":\"b\",\"displayName\":\"B\"},{\"id\":\"a\",\"displayName\":\"A\"}],\"settings\":{\"callLimitMs\":10000,"))
        assertTrue("\"complete\":true" in result.json)
        assertEquals(4, Regex("\"case\":").findAll(result.json).count())
        assertTrue(
            "{\"case\":\"timed/worst/open-parens-20k\",\"section\":\"WorstCases\",\"dataSet\":\"worst/open-parens-20k\",\"language\":\"typescript\",\"engine\":\"a\",\"status\":\"Completed\"" in result.json,
            result.json,
        )
        assertTrue("\"callStatus\":\"Completed\",\"ignoredCancel\":false,\"slowed\":false,\"note\":null}" in result.json)
    }

    @Test
    fun jsonNumbersAndStringsAreStable() {
        assertEquals("12.5", HighlightBenchJson.number(12.5))
        assertEquals("0.001", HighlightBenchJson.number(0.0012))
        assertEquals("12000000", HighlightBenchJson.number(1.2e7))
        assertEquals("-3", HighlightBenchJson.number(-3.0))
        assertEquals("null", HighlightBenchJson.number(Double.NaN))
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", HighlightBenchJson.string("a\"b\\c\nd\u0001"))
    }
}
