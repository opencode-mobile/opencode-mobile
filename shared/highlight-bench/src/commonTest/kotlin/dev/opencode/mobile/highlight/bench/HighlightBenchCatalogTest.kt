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

        assertEquals(28, cases.size)
        assertTrue(cases.getValue("minified-js-1mb").generate().let { it.length >= 1_000_000 && '\n' !in it })
        assertEquals(100_000, cases.getValue("typescript-line-100k").generate().length)
        assertEquals(100_000, cases.getValue("json-deep-50k").generate().length)
        assertEquals(200_000, cases.getValue("binary-200k").generate().length)
        assertTrue(cases.getValue("crlf-typescript-250k").generate().contains("\r\n"))
        assertTrue(cases.getValue("diff-long-line-1mb").generate().lines().maxOf { it.length } > 50_000)

        // The Basic languages' worst cases: the construct opens once, at the start, and never closes.
        fun text(id: String) = cases.getValue(id).generate()
        fun count(
            text: String,
            token: String,
        ) = generateSequence(text.indexOf(token).takeIf { it >= 0 }) { at -> text.indexOf(token, at + 1).takeIf { it >= 0 } }.count()
        mapOf(
            "markdown-unclosed-fence-1mb" to ("```" to 1),
            "shell-unclosed-heredoc-1mb" to ("NEVER_ENDS" to 1),
            "ruby-unclosed-heredoc-1mb" to ("NEVER_ENDS" to 1),
            "php-unclosed-heredoc-1mb" to ("NEVER_ENDS" to 1),
            "html-unclosed-script-1mb" to ("</script" to 0),
            "rust-unclosed-raw-string-1mb" to ("\"##" to 0),
            "cpp-unclosed-raw-string-1mb" to (")never\"" to 0),
            "csharp-unclosed-raw-string-1mb" to ("\"\"\"\"" to 1),
        ).forEach { (id, expected) ->
            val text = text(id)
            assertTrue(text.length >= 998_000, "$id: ${text.length}")
            assertEquals(expected.second, count(text, expected.first), id)
        }
        listOf("css-minified-1mb", "sql-in-list-1mb").forEach { id ->
            assertTrue(text(id).trimEnd().let { it.length >= 1_000_000 && '\n' !in it }, id)
        }
        assertTrue(text("sql-in-list-1mb").startsWith("SELECT * FROM sessions WHERE id IN ('ses_1', 'ses_2', "))
        assertEquals(350_000, text("xml-deep-50k").length)
        assertTrue(text("yaml-deep-1k").lines().maxOf { it.length } >= 2_000)
        assertTrue(text("c-byte-array-1mb").let { it.length in 999_000..1_000_100 && it.endsWith(";\n") && it.lines()[2].startsWith("  0x") })
    }

    @Test
    fun everyLanguageHasTheFourTypicalSizesAndChatFences() {
        assertEquals(17, HighlightBenchCorpus.basicLanguages.size)
        assertEquals(24, HighlightBenchCatalog.languages.size)
        HighlightBenchCatalog.languages.forEach { language ->
            val typical = HighlightBenchCatalog.filter(language, null, HighlightBenchDataKind.TypicalCode)
            assertEquals(listOf("2k", "16k", "128k", "1mb").map { "typical/$language/$it" }, typical.map { it.id })
            assertEquals(HighlightBenchCatalog.TYPICAL_SIZES.map { it.first }, typical.map { it.sizeClass })
            if (language in HighlightBenchCorpus.quickLanguages) return@forEach
            val huge = typical.last().generate()
            assertTrue(huge.length in HighlightBenchCorpus.ONE_MB - 2_000..HighlightBenchCorpus.ONE_MB && huge.endsWith("\n"), "$language ${huge.length}")
            assertEquals(150, HighlightBenchCatalog.fences(language).pieces().size)
        }
    }

    @Test
    fun basicTemplatesRepeatWithVaryingIdentifiers() {
        assertEquals(HighlightBenchCatalog.languages - HighlightBenchCorpus.quickLanguages.toSet(), HighlightBenchCorpusTemplates.basic.keys.toList())
        HighlightBenchCorpusTemplates.basic.forEach { (language, template) ->
            assertTrue(template.length in 800..2_500, "$language ${template.length}")
            assertTrue(Regex("\\{n\\}").findAll(template).count() >= 3, language)
            val code = HighlightBenchCorpus.code(language, 16_000)
            assertTrue("{n}" !in code, language)
            // The sixth copy is in 16K, with its own identifiers.
            assertTrue(template.replace("{n}", "5") in code, language)
        }
    }

    @Test
    fun theCatalogListsAndFiltersDataSets() {
        val all = HighlightBenchCatalog.dataSets
        assertEquals(24 * 4 + 24 + 28, all.size)
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
        val basic = HighlightBenchPlan.cases(HighlightBenchTier.Basic)
        val full = HighlightBenchPlan.cases(HighlightBenchTier.Full)
        assertEquals(6 + 12 + 6, quick.size)
        assertEquals(18 + 36 + 18, basic.size)
        assertEquals(24 + 72 + 24 + 24 + 28, full.size)
        assertEquals(full.size, full.map { it.id }.toSet().size)
        assertTrue(quick.none { it.isolated } && basic.none { it.isolated })
        assertEquals(52, full.count { it.isolated })
        assertEquals(listOf("typescript", "kotlin", "swift", "python", "json", "diff"), HighlightBenchPlan.languages(HighlightBenchTier.Quick))
        assertEquals(listOf("javascript") + HighlightBenchCorpus.basicLanguages, HighlightBenchPlan.languages(HighlightBenchTier.Basic))
        assertEquals(HighlightBenchPlan.languages(HighlightBenchTier.Basic), basic.map { it.language }.distinct())
        assertTrue(full.map { it.id }.containsAll(quick.map { it.id } + basic.map { it.id }))
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
