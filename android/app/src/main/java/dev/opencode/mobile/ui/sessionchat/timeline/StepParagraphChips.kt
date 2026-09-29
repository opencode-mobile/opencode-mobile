package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.ToolStatus

/** Chips a collapsed step paragraph shows, plus how many sit behind its single `+N more`. */
data class StepParagraphChipVisibility(
    val visible: List<SessionChatTimelineBlock.ToolChip>,
    val hiddenCount: Int,
)

/**
 * Chip timeline collapse rules for one step paragraph. Pure so both the view
 * and tests share them.
 */
object StepParagraphChips {
    /**
     * Per-family visible limit over completed chips; null means the family is
     * never truncated. Explore tools fold into the paragraph's summary chip, so
     * an individual Explore chip is a lone one or a failed one and never hides.
     */
    fun familyLimit(family: ToolChipFamily): Int? =
        when (family) {
            ToolChipFamily.Change -> 3
            ToolChipFamily.Command -> 3
            ToolChipFamily.Explore -> null
            ToolChipFamily.Agent -> null
            ToolChipFamily.Skill -> null
        }

    /**
     * Failed and in-flight chips need attention: they are never hidden and do
     * not count toward any limit.
     */
    fun needsAttention(status: ToolStatus): Boolean =
        when (status) {
            ToolStatus.Error, ToolStatus.Running, ToolStatus.Pending -> true
            ToolStatus.Completed -> false
        }

    /**
     * Applies per-family limits. [expandedThrough] is the paragraph's chip
     * count when its `+N more` was tapped, or null: chips whose
     * [SessionChatTimelineBlock.ToolChip.arrivalIndex] is below it always
     * show, so a tap reveals what existed then while later arrivals still
     * collapse. A lone hidden chip is shown instead of a `+1 more`. Order of
     * the visible chips is preserved.
     */
    fun visibility(
        chips: List<SessionChatTimelineBlock.ToolChip>,
        expandedThrough: Int? = null,
    ): StepParagraphChipVisibility {
        val hidden = BooleanArray(chips.size)
        val seenByFamily = mutableMapOf<ToolChipFamily, Int>()
        chips.forEachIndexed { index, chip ->
            if (needsAttention(chip.status)) return@forEachIndexed
            if (expandedThrough != null && chip.arrivalIndex < expandedThrough) return@forEachIndexed
            val seen = seenByFamily.getOrDefault(chip.family, 0)
            seenByFamily[chip.family] = seen + 1
            val limit = familyLimit(chip.family)
            if (limit != null && seen >= limit) hidden[index] = true
        }
        val hiddenCount = hidden.count { it }
        if (hiddenCount <= 1) return StepParagraphChipVisibility(chips, hiddenCount = 0)
        return StepParagraphChipVisibility(
            visible = chips.filterIndexed { chipIndex, _ -> !hidden[chipIndex] },
            hiddenCount = hiddenCount,
        )
    }

    /**
     * True when [next] repeats [previous] closely enough to fold into it:
     * same kind, family, label, and both completed.
     */
    fun canFold(
        previous: SessionChatTimelineBlock.ToolChip,
        next: SessionChatTimelineBlock.ToolChip,
    ): Boolean =
        previous.status == ToolStatus.Completed &&
            next.status == ToolStatus.Completed &&
            previous.kind == next.kind &&
            previous.family == next.family &&
            previous.label == next.label

    /** Folds [next] into [previous]: the first run's id, the latest run's card, one more repeat. */
    fun fold(
        previous: SessionChatTimelineBlock.ToolChip,
        next: SessionChatTimelineBlock.ToolChip,
    ): SessionChatTimelineBlock.ToolChip =
        next.copy(
            id = previous.id,
            arrivalIndex = previous.arrivalIndex,
            additions = sumCounts(previous.additions, next.additions),
            deletions = sumCounts(previous.deletions, next.deletions),
            repeatCount = previous.repeatCount + next.repeatCount,
        )

    private fun sumCounts(
        first: Int?,
        second: Int?,
    ): Int? =
        when {
            first == null -> second
            second == null -> first
            else -> first + second
        }

    const val COMMAND_LABEL_MAX_LENGTH = 48
    private const val ELLIPSIS = "…"

    private const val ASSIGNMENT = """[A-Za-z_][A-Za-z0-9_]*=(?:"[^"]*"|'[^']*'|[^\s;&])*"""
    private val leadingCd = Regex("""^cd\s+[^;&]+?\s*(?:&&|;)\s*""")
    private val leadingExport = Regex("""^export\s+(?:$ASSIGNMENT\s*)+(?:&&|;)\s*""")
    private val leadingAssignment = Regex("""^$ASSIGNMENT\s+""")

    /**
     * Command chip label: first non-empty line with whitespace collapsed, then
     * leading `cd … &&`, `export VAR=… &&`, and `VAR=value ` prefixes stripped
     * (the original is kept if nothing would remain), then cut to
     * [COMMAND_LABEL_MAX_LENGTH] at a word boundary with a trailing "…". The
     * first token is never cut.
     */
    fun commandLabel(command: String): String {
        val line =
            command
                .lineSequence()
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return command
        val stripped = stripCommandPrefixes(line).ifEmpty { line }
        return truncateAtWord(stripped, COMMAND_LABEL_MAX_LENGTH)
    }

    private fun stripCommandPrefixes(line: String): String {
        var current = line
        while (true) {
            val next =
                listOf(leadingCd, leadingExport, leadingAssignment)
                    .firstNotNullOfOrNull { regex -> regex.find(current)?.let { current.substring(it.range.last + 1) } }
                    ?.trim()
                    ?: return current
            if (next.isEmpty()) return next
            current = next
        }
    }

    private fun truncateAtWord(
        text: String,
        maxLength: Int,
    ): String {
        if (text.length <= maxLength) return text
        val firstTokenEnd = text.indexOf(' ').let { if (it < 0) text.length else it }
        if (firstTokenEnd >= maxLength) {
            val firstToken = text.substring(0, firstTokenEnd)
            return if (firstTokenEnd < text.length) firstToken + ELLIPSIS else firstToken
        }
        val cut = text.lastIndexOf(' ', startIndex = maxLength)
        return text.substring(0, cut).trimEnd() + ELLIPSIS
    }
}
