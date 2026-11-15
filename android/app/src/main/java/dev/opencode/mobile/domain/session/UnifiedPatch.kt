package dev.opencode.mobile.domain.session

/**
 * The patch text Changes keeps for one file. A server patch longer than [PREVIEW_CHARS] is cut
 * at its last line end before the limit; a patch built from whole `before`/`after` contents is
 * written once, straight into a buffer of its final size, and stops at the same limit.
 */
object UnifiedPatch {
    /** Characters of one file's patch that Changes keeps and shows (1 MB of ASCII, as the File Viewer). */
    const val PREVIEW_CHARS: Int = 1024 * 1024

    data class Preview(
        val text: String,
        val isTruncated: Boolean,
    )

    /** [patch] as sent, or its first whole lines up to [limit] characters. */
    fun preview(
        patch: String,
        limit: Int = PREVIEW_CHARS,
    ): Preview {
        if (patch.length <= limit) return Preview(patch, isTruncated = false)
        val lastNewline = patch.lastIndexOf('\n', limit)
        return Preview(if (lastNewline > 0) patch.substring(0, lastNewline) else "", isTruncated = true)
    }

    /**
     * One hunk that deletes every line of [before] and adds every line of [after], or null when
     * either is missing or both are equal. Each input is read once to count its lines and once
     * to copy them; nothing is split or joined.
     */
    fun fromContents(
        before: CharSequence?,
        after: CharSequence?,
        limit: Int = PREVIEW_CHARS,
    ): Preview? {
        if (before == null || after == null || contentEquals(before, after)) return null
        val oldLines = lineCount(before)
        val newLines = lineCount(after)
        val header = "@@ -${if (oldLines == 0) 0 else 1},$oldLines +${if (newLines == 0) 0 else 1},$newLines @@"
        val fullLength =
            header.length.toLong() +
                (if (oldLines == 0) 0L else before.length + 1L + oldLines) +
                (if (newLines == 0) 0L else after.length + 1L + newLines)
        val builder = StringBuilder(minOf(fullLength, limit.toLong()).toInt())
        builder.append(header)
        val complete = appendLines(builder, before, oldLines, '-', limit) && appendLines(builder, after, newLines, '+', limit)
        return Preview(builder.toString(), isTruncated = !complete)
    }

    /** `\n`-separated lines; an empty text has none, and a trailing newline ends an empty last line. */
    private fun lineCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var count = 1
        for (index in 0 until text.length) if (text[index] == '\n') count++
        return count
    }

    /** Appends `\n<marker><line>` per line; false once the next line would pass [limit]. */
    private fun appendLines(
        builder: StringBuilder,
        text: CharSequence,
        lines: Int,
        marker: Char,
        limit: Int,
    ): Boolean {
        if (lines == 0) return true
        var start = 0
        while (true) {
            var end = start
            while (end < text.length && text[end] != '\n') end++
            if (builder.length + 2L + (end - start) > limit) return false
            builder.append('\n').append(marker).append(text, start, end)
            if (end >= text.length) return true
            start = end + 1
        }
    }

    private fun contentEquals(
        first: CharSequence,
        second: CharSequence,
    ): Boolean {
        if (first.length != second.length) return false
        if (first is String && second is String) return first == second
        for (index in 0 until first.length) if (first[index] != second[index]) return false
        return true
    }
}
