package dev.opencode.mobile.highlight.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightBenchCatalogTest {
    @Test
    fun typicalCodeEndsAtALineAndStaysWithinItsSize() {
        HighlightBenchCorpus.languages.forEach { language ->
            listOf(2_000, 16_000, 128_000).forEach { size ->
                val code = HighlightBenchCorpus.code(language, size)
                assertTrue(code.endsWith("\n"), "$language $size")
                if (language == "json") {
                    assertTrue(code.length in size - 600..size + 8, "$language $size: ${code.length}")
                    assertTrue(code.startsWith("[\n") && code.endsWith("\n]\n"))
                } else {
                    assertTrue(code.length in size - 2_000..size, "$language $size: ${code.length}")
                }
            }
        }
    }

    @Test
    fun theCorpusIsDeterministic() {
        HighlightBenchCorpus.languages.forEach { language ->
            assertEquals(HighlightBenchCorpus.code(language, 16_000), HighlightBenchCorpus.code(language, 16_000))
            assertEquals(HighlightBenchCorpus.chatFences(language, 20), HighlightBenchCorpus.chatFences(language, 20))
        }
        val worst = HighlightBenchCatalog.filter(null, null, HighlightBenchDataKind.WorstCase)
        val first = worst.map { it.generate().hashCode() }
        val second = worst.map { it.generate().hashCode() }
        assertEquals(first, second)
    }

    @Test
    fun chatFencesFollowTheFixedSizeDistribution() {
        val fences = HighlightBenchCorpus.chatFences("kotlin", 150)
        val sizes = fences.map { it.length }.sorted()

        assertEquals(150, fences.size)
        assertTrue(sizes.all { it in 80..8_000 }, sizes.toString())
        assertTrue(sizes[75] in 500..1_500, "median ${sizes[75]}")
        val corpus = HighlightBenchCorpus.code("kotlin", 64_000)
        assertTrue(fences.all { fence -> corpus.indexOf(fence).let { it == 0 || corpus[it - 1] == '\n' } })
        // Every language draws the same sizes.
        assertEquals(sizes, HighlightBenchCorpus.chatFences("swift", 150).map { it.length }.sorted())
    }

    @Test
    fun worstCasesHaveTheirAdvertisedShape() {
        val cases = HighlightBenchCatalog.filter(null, null, HighlightBenchDataKind.WorstCase).associateBy { it.id.removePrefix("worst/") }

        assertEquals(15, cases.size)
        assertTrue(cases.getValue("minified-js-1mb").generate().let { it.length >= 1_000_000 && '\n' !in it })
        assertEquals(100_000, cases.getValue("typescript-line-100k").generate().length)
        assertEquals(100_000, cases.getValue("json-deep-50k").generate().length)
        assertEquals(200_000, cases.getValue("binary-200k").generate().length)
        assertTrue(cases.getValue("crlf-typescript-250k").generate().contains("\r\n"))
        assertTrue(cases.getValue("diff-long-line-1mb").generate().lines().maxOf { it.length } > 50_000)
    }

    @Test
    fun theCatalogListsAndFiltersDataSets() {
        val all = HighlightBenchCatalog.dataSets
        assertEquals(6 * 4 + 6 + 15, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)

        val kotlin = HighlightBenchCatalog.filter("kotlin", null, null)
        assertEquals(listOf("typical/kotlin/2k", "typical/kotlin/16k", "typical/kotlin/128k", "typical/kotlin/1mb", "fences/kotlin"), kotlin.map { it.id })
        val huge = HighlightBenchCatalog.filter(null, HighlightBenchSizeClass.Huge, null)
        assertTrue(huge.all { it.sizeClass == HighlightBenchSizeClass.Huge })
        assertTrue(huge.any { it.id == "worst/minified-js-1mb" } && huge.any { it.id == "typical/json/1mb" })
        assertEquals(listOf("typical/swift/16k"), HighlightBenchCatalog.filter("swift", HighlightBenchSizeClass.Medium, HighlightBenchDataKind.TypicalCode).map { it.id })

        val fences = HighlightBenchCatalog.dataSet("fences/python")!!
        assertEquals(HighlightBenchSizeClass.Chat, fences.sizeClass)
        assertEquals(150, fences.pieces().size)
        assertEquals(fences.pieces().joinToString("\n\n"), fences.generate())
    }

    @Test
    fun customDataSetsTakeTheirSizeClassFromTheText() {
        val pasted = HighlightBenchDataSet.custom("paste", "Pasted", "rust", "fn main() {}\n".repeat(10_000))
        assertEquals("custom/paste", pasted.id)
        assertEquals(HighlightBenchSizeClass.Large, pasted.sizeClass)
        assertEquals(HighlightBenchDataKind.Custom, pasted.kind)
        assertEquals(HighlightBenchSection.Custom, HighlightBenchCase.measuring(pasted).section)
    }

    @Test
    fun tierPlansCoverTheExpectedCases() {
        val quick = HighlightBenchPlan.cases(HighlightBenchTier.Quick)
        val full = HighlightBenchPlan.cases(HighlightBenchTier.Full)
        assertEquals(6 + 12 + 6, quick.size)
        assertEquals(6 + 18 + 6 + 6 + 15, full.size)
        assertEquals(full.size, full.map { it.id }.toSet().size)
        assertTrue(quick.none { it.isolated })
        assertEquals(21, full.count { it.isolated })
        assertEquals(HighlightBenchSection.entries.dropLast(1), full.map { it.section }.distinct())
    }

    @Test
    fun pickedDataSetsRunInReportOrder() {
        val picked = listOf("worst/binary-200k", "fences/json", "typical/json/2k", "typical/json/1mb").map { HighlightBenchCatalog.dataSet(it)!! }
        val sections = HighlightBenchPlan.cases(picked).map { it.section }
        assertEquals(
            listOf(HighlightBenchSection.Throughput, HighlightBenchSection.ChatFences, HighlightBenchSection.LargeFiles, HighlightBenchSection.WorstCases),
            sections,
        )
    }
}
