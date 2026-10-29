package dev.opencode.mobile.lab

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A report as stored: Markdown and JSON, and where and when it was written. */
data class LabSavedReport(
    val markdown: String,
    val json: String,
    val savedAtMillis: Long,
    val markdownFile: File,
    val jsonFile: File,
) {
    val savedAtLabel: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(savedAtMillis))
}

/**
 * The Lab's reports under `highlight-lab/`: `latest.md` and `latest.json`, plus a timestamped
 * copy of each run. [mirrors] get the same files (headless runs mirror to external app storage,
 * which `adb pull` reaches on builds that are not debuggable).
 */
class LabReportStore(
    private val directory: File,
    private val mirrors: List<File> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Writes both reports and returns the latest copy in [directory]. Blocking file I/O. */
    fun save(
        markdown: String,
        json: String,
    ): LabSavedReport {
        val savedAt = now()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(savedAt))
        (listOf(directory) + mirrors).forEach { dir ->
            dir.mkdirs()
            File(dir, "$LATEST.md").writeText(markdown)
            File(dir, "$LATEST.json").writeText(json)
            File(dir, "run-$stamp.md").writeText(markdown)
            File(dir, "run-$stamp.json").writeText(json)
        }
        return LabSavedReport(markdown, json, savedAt, File(directory, "$LATEST.md"), File(directory, "$LATEST.json"))
    }

    /** The latest report, or null when none was saved. Blocking file I/O. */
    fun latest(): LabSavedReport? {
        val markdownFile = File(directory, "$LATEST.md")
        val jsonFile = File(directory, "$LATEST.json")
        if (!markdownFile.isFile || !jsonFile.isFile) return null
        return runCatching {
            LabSavedReport(markdownFile.readText(), jsonFile.readText(), markdownFile.lastModified(), markdownFile, jsonFile)
        }.getOrNull()
    }

    /** Every directory written to, primary first. */
    val directories: List<File>
        get() = listOf(directory) + mirrors

    companion object {
        const val DIRECTORY = "highlight-lab"
        private const val LATEST = "latest"
    }
}
