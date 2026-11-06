package dev.opencode.hexcode.session

import kotlinx.serialization.Serializable
import java.net.URI

/** A piece of summary text with its look: [Style.Code] in the monospace on a tint, [Style.Bold] heavier. */
data class SummaryRun(
    val text: String,
    val style: Style = Style.Plain,
) {
    enum class Style { Plain, Code, Bold }
}

/** A link tile under the card: [label] and the [url] it opens. */
@Serializable
data class SummaryLink(
    val label: String,
    val url: String,
)

/**
 * The summary's inline Markdown (app-plan.md, Formatting; the prototype's `md`): text in backticks is code, `**text**`
 * is bold, `[label](url)` and `![label](url)` keep their label, and a leading `#` or `>` goes. Any other text shows as
 * written. Clipping and the Now size count the characters shown ([shown]), not the symbols.
 */
object SummaryText {
    private val CODE = Regex("`([^`]+)`")
    private val LINK = Regex("""!?\[([^\]]+)]\([^)\s]*\)""")
    private val BOLD = Regex("""\*\*([^*]+)\*\*""")
    private val LEADING = Regex("""^(#+|>)\s+""")

    /** [text] as styled runs, in order; neighbouring runs of the same style are joined, empty ones left out. */
    fun runs(text: String): List<SummaryRun> {
        val runs = mutableListOf<SummaryRun>()

        fun add(
            part: String,
            style: SummaryRun.Style,
        ) {
            if (part.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null &&
                last.style == style
            ) {
                runs[runs.lastIndex] = last.copy(text = last.text + part)
            } else {
                runs += SummaryRun(part, style)
            }
        }

        fun words(
            part: String,
            atStart: Boolean,
        ) {
            var t = LINK.replace(part) { it.groupValues[1] }
            if (atStart) t = LEADING.replaceFirst(t, "")
            var at = 0
            for (match in BOLD.findAll(t)) {
                add(t.substring(at, match.range.first), SummaryRun.Style.Plain)
                add(match.groupValues[1], SummaryRun.Style.Bold)
                at = match.range.last + 1
            }
            add(t.substring(at), SummaryRun.Style.Plain)
        }
        var at = 0
        for (match in CODE.findAll(text)) {
            words(text.substring(at, match.range.first), atStart = at == 0)
            add(match.groupValues[1], SummaryRun.Style.Code)
            at = match.range.last + 1
        }
        words(text.substring(at), atStart = at == 0)
        return runs
    }

    /** The characters [text] shows, without its Markdown symbols. */
    fun shown(text: String): String = runs(text).joinToString("") { it.text }

    /**
     * [text] clipped to [max] characters shown, as [StatusSummary.clip] cuts the shown text, with its formatting kept
     * up to the cut. Unchanged when it fits.
     */
    fun clip(
        text: String,
        max: Int,
    ): String {
        val runs = runs(text)
        val shown = runs.joinToString("") { it.text }
        val clipped = StatusSummary.clip(shown, max)
        if (clipped == shown) return text
        var left = clipped.removeSuffix("…").length
        val kept = mutableListOf<SummaryRun>()
        for (run in runs) {
            if (left <= 0) break
            kept += if (run.text.length <= left) run else run.copy(text = run.text.take(left))
            left -= run.text.length
        }
        return markdown(kept) + "…"
    }

    /** [runs] written back as the Markdown [runs] reads. */
    private fun markdown(runs: List<SummaryRun>): String =
        runs.joinToString("") {
            when (it.style) {
                SummaryRun.Style.Plain -> it.text
                SummaryRun.Style.Code -> "`${it.text}`"
                SummaryRun.Style.Bold -> "**${it.text}**"
            }
        }
}

/**
 * Where the card's link tiles come from (app-plan.md, Links): `Link: <label> | <url>` lines and links left in Now or
 * Next, which stay in the sentence as their label, or as the host for a bare URL.
 */
object SummaryLinks {
    /** At most this many `Link:` lines are used. */
    const val LINE_MAX = 2

    private val MARKDOWN_LINK = Regex("""!?\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val BARE_URL = Regex("""https?://[^\s)]+""")
    private val TRAILING = setOf('.', ',', ';', ':', '!', '?')
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0")

    /** The link a `Link:` line's [value] gives, or null when its URL isn't `http://` or `https://`. */
    fun fromLine(value: String): SummaryLink? {
        val bar = value.lastIndexOf('|')
        val label = if (bar >= 0) value.substring(0, bar).trim() else ""
        val url = (if (bar >= 0) value.substring(bar + 1) else value).trim()
        if (!isWeb(url)) return null
        return SummaryLink(label.ifEmpty { host(url) }, url)
    }

    /**
     * [sentence] with its links taken out: a Markdown link (or image) keeps its label, a bare URL becomes its host;
     * and the links, in order.
     */
    fun pull(sentence: String): Pair<String, List<SummaryLink>> {
        val found = mutableListOf<SummaryLink>()
        val labelled =
            MARKDOWN_LINK.replace(sentence) {
                found += SummaryLink(it.groupValues[1], it.groupValues[2])
                it.groupValues[1]
            }
        val text =
            BARE_URL.replace(labelled) {
                // A sentence's own full stop or comma isn't part of the URL.
                val url = it.value.trimEnd { c -> c in TRAILING }
                found += SummaryLink(host(url), url)
                host(url) + it.value.substring(url.length)
            }
        return text to found
    }

    /** [links] with each URL once, the first kept. */
    fun distinct(links: List<SummaryLink>): List<SummaryLink> = links.distinctBy { it.url }

    fun isWeb(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    /** The URL's host and port, without `www.`: `localhost:5173`, `github.com`; the URL itself if it has none. */
    fun host(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host ?: return url
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return host.removePrefix("www.") + port
    }

    /** Whether [url] points at the computer the agent runs on (`localhost`, `127.0.0.1` or `0.0.0.0`). */
    fun isLocal(url: String): Boolean = runCatching { URI(url).host }.getOrNull()?.lowercase() in LOCAL_HOSTS

    /** A local [url] on [computer] (the paired computer's address) instead, with the same port, path and query. */
    fun onComputer(
        url: String,
        computer: String,
    ): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host == null) return url
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val host = if (':' in computer && !computer.startsWith("[")) "[$computer]" else computer
        return "${uri.scheme}://$host$port${uri.rawPath.orEmpty()}$query$fragment"
    }
}
