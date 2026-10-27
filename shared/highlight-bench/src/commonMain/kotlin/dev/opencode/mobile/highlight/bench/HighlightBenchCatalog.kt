package dev.opencode.mobile.highlight.bench

enum class HighlightBenchDataKind {
    /** Realistic code repeated to a size. */
    TypicalCode,

    /** Many chat-sized fences (80..8,000 characters, median ~900) cut from typical code. */
    ChatFences,

    /** Pathological input: huge lines, unclosed constructs, binary, odd Unicode. */
    WorstCase,

    /** Text the Lab brought itself: pasted, or a file from the server. */
    Custom,
}

/** Rough size, for browsing. [label] is what a picker shows. */
enum class HighlightBenchSizeClass(
    val label: String,
) {
    Chat("Chat fences"),
    Small("2K"),
    Medium("16K"),
    Large("128K"),
    Huge("1 MB"),
    ;

    companion object {
        /** The class a text of [length] characters browses under. */
        fun of(length: Int): HighlightBenchSizeClass =
            when {
                length < 8_000 -> Small
                length < 64_000 -> Medium
                length < 512_000 -> Large
                else -> Huge
            }
    }
}

/**
 * One input a Lab can open, view highlighted, and benchmark. The text is generated on demand,
 * deterministically, and not kept: several data sets are megabytes.
 */
class HighlightBenchDataSet internal constructor(
    /** Stable across runs and platforms, for example "typical/kotlin/16k" or "worst/minified-js-1mb". */
    val id: String,
    val displayName: String,
    val language: String,
    val sizeClass: HighlightBenchSizeClass,
    val kind: HighlightBenchDataKind,
    private val generatePieces: () -> List<String>,
) {
    /** The inputs a benchmark measures: one, or one per fence for [HighlightBenchDataKind.ChatFences]. */
    fun pieces(): List<String> = generatePieces()

    /** The whole text for viewing; fences are separated by a blank line. Rebuilt on every call. */
    fun generate(): String = pieces().let { pieces -> pieces.singleOrNull() ?: pieces.joinToString("\n\n") }

    override fun toString(): String = id

    companion object {
        /** A data set for any text, for example pasted code or a file from the server. */
        fun custom(
            id: String,
            displayName: String,
            language: String,
            source: String,
        ): HighlightBenchDataSet =
            HighlightBenchDataSet(
                id = "custom/$id",
                displayName = displayName,
                language = language,
                sizeClass = HighlightBenchSizeClass.of(source.length),
                kind = HighlightBenchDataKind.Custom,
            ) { listOf(source) }
    }
}

/** Every bundled data set, listable and filterable for a Lab's browser. */
object HighlightBenchCatalog {
    /** Languages with typical code and chat fences; worst cases add javascript and markdown. */
    val languages: List<String>
        get() = HighlightBenchCorpus.languages

    /** Typical code per language at each size, chat fences per language, then the worst cases. */
    val dataSets: List<HighlightBenchDataSet> by lazy {
        val typical =
            HighlightBenchCorpus.languages.flatMap { language ->
                TYPICAL_SIZES.map { (sizeClass, size) ->
                    HighlightBenchDataSet(
                        id = "typical/$language/${sizeClass.label.lowercase().replace(" ", "")}",
                        displayName = "$language ${sizeClass.label}",
                        language = language,
                        sizeClass = sizeClass,
                        kind = HighlightBenchDataKind.TypicalCode,
                    ) { listOf(HighlightBenchCorpus.code(language, size)) }
                }
            }
        val fences =
            HighlightBenchCorpus.languages.map { language ->
                HighlightBenchDataSet(
                    id = "fences/$language",
                    displayName = "$language chat fences",
                    language = language,
                    sizeClass = HighlightBenchSizeClass.Chat,
                    kind = HighlightBenchDataKind.ChatFences,
                ) { HighlightBenchCorpus.chatFences(language, CHAT_FENCE_COUNT) }
            }
        typical + fences + HighlightBenchCorpus.worstCases()
    }

    /** Data sets matching every filter that is not null, in catalog order. */
    fun filter(
        language: String?,
        sizeClass: HighlightBenchSizeClass?,
        kind: HighlightBenchDataKind?,
    ): List<HighlightBenchDataSet> =
        dataSets.filter {
            (language == null || it.language == language) &&
                (sizeClass == null || it.sizeClass == sizeClass) &&
                (kind == null || it.kind == kind)
        }

    fun dataSet(id: String): HighlightBenchDataSet? = dataSets.firstOrNull { it.id == id }

    internal fun typical(
        language: String,
        sizeClass: HighlightBenchSizeClass,
    ): HighlightBenchDataSet = dataSet("typical/$language/${sizeClass.label.lowercase().replace(" ", "")}") ?: error("No typical $language ${sizeClass.label}")

    internal fun fences(language: String): HighlightBenchDataSet = dataSet("fences/$language") ?: error("No fences for $language")

    internal const val CHAT_FENCE_COUNT = 150

    internal val TYPICAL_SIZES =
        listOf(
            HighlightBenchSizeClass.Small to 2_000,
            HighlightBenchSizeClass.Medium to 16_000,
            HighlightBenchSizeClass.Large to 128_000,
            HighlightBenchSizeClass.Huge to HighlightBenchCorpus.ONE_MB,
        )
}
