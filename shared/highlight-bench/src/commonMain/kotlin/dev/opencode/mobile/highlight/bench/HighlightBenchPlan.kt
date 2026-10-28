package dev.opencode.mobile.highlight.bench

/**
 * Which cases a run covers, see [HighlightBenchPlan.cases]. [Quick], [Basic], and [FullLanguages]
 * run cold start, 2K and 16K throughput, and chat fences, inline only; [Full] adds 128K
 * throughput, 1 MB files, and worst cases. Languages per tier: [HighlightBenchPlan.languages].
 */
enum class HighlightBenchTier {
    /** The six languages of the first baseline (seconds on a host), kept so runs stay comparable over time. */
    Quick,

    /** Javascript and the 17 Basic languages, at quick depth (about a minute with TextMate on a host). */
    Basic,

    /** The Full milestone's 38 remaining languages ([HighlightBenchCorpus.fullLanguages]), at quick depth. */
    FullLanguages,

    /** Every language at every size, plus every worst case (minutes). */
    Full,
    ;

    /** The `labRun` selection that names this tier: "quick", "basic", "full-languages", "full". */
    val selection: String
        get() =
            buildString {
                name.forEachIndexed { index, char ->
                    if (index > 0 && char.isUpperCase()) append('-')
                    append(char.lowercaseChar())
                }
            }
}

/** How a case measures its data set; also the report section, in report order. */
enum class HighlightBenchSection {
    /** First and second call per language on one fresh engine instance. */
    ColdStart,

    /** Warm median time over several runs of one input. */
    Throughput,

    /** Warm time per chat fence, plus a fixed 2,000-character fence. */
    ChatFences,

    /** A 1 MB file: one call on a fresh instance through the call guard. */
    LargeFiles,

    /** A pathological input: one call on a fresh instance through the call guard. */
    WorstCases,

    /** Text the Lab brought itself, measured like a worst case. */
    Custom,
}

/**
 * One data set measured one way: a row of a report, per engine. [isolated] cases make a single
 * call on a fresh engine instance through the platform's [HighlightBenchCallGuard]; the others
 * call a shared warm instance inline.
 */
class HighlightBenchCase internal constructor(
    /** Stable across runs, for example "throughput/typical/kotlin/16k". */
    val id: String,
    val section: HighlightBenchSection,
    val dataSet: HighlightBenchDataSet,
    internal val spec: CaseSpec,
) {
    /** Row label: the language for per-language sections, otherwise the data set's name. */
    val title: String
        get() =
            when (section) {
                HighlightBenchSection.ColdStart, HighlightBenchSection.ChatFences, HighlightBenchSection.LargeFiles -> dataSet.language
                else -> dataSet.displayName
            }

    val language: String
        get() = dataSet.language

    val isolated: Boolean
        get() = spec is CaseSpec.Timed

    override fun toString(): String = id

    companion object {
        /** How a benchmark measures [dataSet] by default: throughput, fences, or one isolated call. */
        fun measuring(dataSet: HighlightBenchDataSet): HighlightBenchCase =
            when (dataSet.kind) {
                HighlightBenchDataKind.TypicalCode ->
                    when (dataSet.sizeClass) {
                        HighlightBenchSizeClass.Huge -> timed(HighlightBenchSection.LargeFiles, dataSet)
                        else -> HighlightBenchCase("throughput/${dataSet.id}", HighlightBenchSection.Throughput, dataSet, CaseSpec.Throughput(runs(dataSet.sizeClass)))
                    }
                HighlightBenchDataKind.ChatFences ->
                    HighlightBenchCase("fences/${dataSet.id}", HighlightBenchSection.ChatFences, dataSet, CaseSpec.ChatFences(fixedSize = 2_000, fixedRuns = 50))
                HighlightBenchDataKind.WorstCase -> timed(HighlightBenchSection.WorstCases, dataSet)
                HighlightBenchDataKind.Custom -> timed(HighlightBenchSection.Custom, dataSet)
            }

        /** First call on a fresh instance (shared by a run's cold-start cases), then a second. */
        fun coldStart(dataSet: HighlightBenchDataSet): HighlightBenchCase =
            HighlightBenchCase("cold/${dataSet.id}", HighlightBenchSection.ColdStart, dataSet, CaseSpec.ColdStart)

        private fun timed(
            section: HighlightBenchSection,
            dataSet: HighlightBenchDataSet,
        ) = HighlightBenchCase("timed/${dataSet.id}", section, dataSet, CaseSpec.Timed)

        private fun runs(sizeClass: HighlightBenchSizeClass): Int =
            when (sizeClass) {
                HighlightBenchSizeClass.Small, HighlightBenchSizeClass.Chat -> 30
                HighlightBenchSizeClass.Medium -> 5
                else -> 3
            }
    }
}

internal sealed class CaseSpec {
    data object ColdStart : CaseSpec()

    class Throughput(
        val runs: Int,
    ) : CaseSpec()

    class ChatFences(
        val fixedSize: Int,
        val fixedRuns: Int,
    ) : CaseSpec()

    data object Timed : CaseSpec()
}

object HighlightBenchPlan {
    /** The languages [tier] runs, in report order. */
    fun languages(tier: HighlightBenchTier): List<String> =
        when (tier) {
            HighlightBenchTier.Quick -> HighlightBenchCorpus.quickLanguages
            HighlightBenchTier.Basic -> listOf("javascript") + HighlightBenchCorpus.basicLanguages
            HighlightBenchTier.FullLanguages -> HighlightBenchCorpus.fullLanguages
            HighlightBenchTier.Full -> HighlightBenchCorpus.languages
        }

    /** The cases a tier runs, in report order. */
    fun cases(tier: HighlightBenchTier): List<HighlightBenchCase> {
        val languages = languages(tier)
        val throughputSizes =
            listOf(HighlightBenchSizeClass.Small, HighlightBenchSizeClass.Medium) +
                if (tier == HighlightBenchTier.Full) listOf(HighlightBenchSizeClass.Large) else emptyList()
        val cases = mutableListOf<HighlightBenchCase>()
        languages.forEach { cases += HighlightBenchCase.coldStart(HighlightBenchCatalog.typical(it, HighlightBenchSizeClass.Small)) }
        languages.forEach { language ->
            throughputSizes.forEach { cases += HighlightBenchCase.measuring(HighlightBenchCatalog.typical(language, it)) }
        }
        languages.forEach { cases += HighlightBenchCase.measuring(HighlightBenchCatalog.fences(it)) }
        if (tier == HighlightBenchTier.Full) {
            languages.forEach { cases += HighlightBenchCase.measuring(HighlightBenchCatalog.typical(it, HighlightBenchSizeClass.Huge)) }
            HighlightBenchCatalog.filter(null, null, HighlightBenchDataKind.WorstCase).forEach { cases += HighlightBenchCase.measuring(it) }
        }
        return cases
    }

    /** The default case for each picked data set, ordered by report section (stable within one). */
    fun cases(dataSets: List<HighlightBenchDataSet>): List<HighlightBenchCase> =
        dataSets.map(HighlightBenchCase::measuring).sortedBy { it.section.ordinal }
}
