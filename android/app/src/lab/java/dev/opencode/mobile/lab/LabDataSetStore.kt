package dev.opencode.mobile.lab

import dev.opencode.mobile.domain.filesystem.ProjectFileLanguage
import dev.opencode.mobile.highlight.bench.HighlightBenchCatalog
import dev.opencode.mobile.highlight.bench.HighlightBenchDataKind
import dev.opencode.mobile.highlight.bench.HighlightBenchDataSet
import dev.opencode.mobile.highlight.bench.HighlightBenchSizeClass
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled catalog plus the custom data sets the Lab brought itself (pasted text, files from
 * the File Viewer). Custom data sets live for the process only, like their routes' text would.
 */
class LabDataSetStore(
    private val catalog: List<HighlightBenchDataSet> = HighlightBenchCatalog.dataSets,
) {
    private val custom = mutableListOf<HighlightBenchDataSet>()
    private val files = mutableMapOf<String, Pair<String, HighlightBenchDataSet>>()
    private val lengths = ConcurrentHashMap<String, Int>()
    private var nextCustomId = 1

    /** Catalog order, then custom data sets in the order they were added. */
    fun all(): List<HighlightBenchDataSet> = catalog + synchronized(custom) { custom.toList() }

    fun find(id: String): HighlightBenchDataSet? = all().firstOrNull { it.id == id }

    fun filter(
        language: String?,
        sizeClass: HighlightBenchSizeClass?,
        kind: HighlightBenchDataKind?,
    ): List<HighlightBenchDataSet> =
        all().filter {
            (language == null || it.language == language) &&
                (sizeClass == null || it.sizeClass == sizeClass) &&
                (kind == null || it.kind == kind)
        }

    /** Languages any data set uses, catalog first; also what Paste offers. */
    val languages: List<String>
        get() = (HighlightBenchCatalog.languages + all().map { it.language }).distinct()

    fun addPasted(
        language: String,
        source: String,
    ): HighlightBenchDataSet = add("pasted", "Pasted $language", language, source)

    /**
     * A File Viewer file; its language comes from the extension, as in the File Viewer. Opening
     * the same path with the same text again returns the data set it already made.
     */
    fun addFile(
        path: String,
        source: String,
    ): HighlightBenchDataSet =
        synchronized(custom) {
            files[path]?.takeIf { (text, _) -> text == source }?.second
                ?: add("file", path.substringAfterLast('/'), ProjectFileLanguage.forPath(path).grammar ?: PLAIN_TEXT, source)
                    .also { files[path] = source to it }
        }

    /** Characters in [dataSet]'s text, generating it once (some are megabytes); call it off the main thread. */
    fun length(dataSet: HighlightBenchDataSet): Int = lengths.getOrPut(dataSet.id) { dataSet.generate().length }

    /** The length if [length] already measured it. */
    fun knownLength(id: String): Int? = lengths[id]

    private fun add(
        prefix: String,
        displayName: String,
        language: String,
        source: String,
    ): HighlightBenchDataSet =
        synchronized(custom) {
            val dataSet = HighlightBenchDataSet.custom("$prefix-${nextCustomId++}", displayName, language, source)
            custom += dataSet
            lengths[dataSet.id] = source.length
            dataSet
        }

    companion object {
        const val PLAIN_TEXT = "plaintext"

        /** The process-wide store the app's screens and the File Viewer action share. */
        val shared: LabDataSetStore by lazy { LabDataSetStore() }
    }
}

/** A data set as a Lab list row. */
data class LabDataSetRow(
    val id: String,
    val name: String,
    val language: String,
    val size: String,
    val kind: String,
    /** Null until measured. */
    val characters: Int?,
) {
    val subtitle: String
        get() = "$language · $size · ${characters?.let { "${LabFormat.count(it)} chars" } ?: "measuring…"}"
}

internal fun HighlightBenchDataSet.toRow(characters: Int?): LabDataSetRow =
    LabDataSetRow(
        id = id,
        name = displayName,
        language = language,
        size = sizeClass.label,
        kind = kind.label,
        characters = characters,
    )

internal val HighlightBenchDataKind.label: String
    get() =
        when (this) {
            HighlightBenchDataKind.TypicalCode -> "Typical code"
            HighlightBenchDataKind.ChatFences -> "Chat fences"
            HighlightBenchDataKind.WorstCase -> "Worst case"
            HighlightBenchDataKind.Custom -> "Custom"
        }
