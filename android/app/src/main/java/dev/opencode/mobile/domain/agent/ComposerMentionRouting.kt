package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.domain.command.ComposerSubmitRouting
import dev.opencode.mobile.domain.filesystem.ProjectFileReference

object ComposerMentionRouting {
    fun atSuggestionQuery(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
        fileMentions: List<FileMention> = emptyList(),
    ): String? {
        val clamped = cursor.coerceIn(0, text.length)
        val before = text.substring(0, clamped)
        val atIndex = before.lastIndexOf('@')
        if (atIndex < 0) return null
        if (atIndex > 0) {
            val previous = before[atIndex - 1]
            if (!previous.isWhitespace()) return null
        }
        val token = before.substring(atIndex + 1)
        if (token.any { it.isWhitespace() }) return null
        // A completed mention at the caret is not an open @ query. A caret inside a
        // file path (which may contain spaces) is still that mention.
        if (mentions.any { it.start == atIndex && it.end == clamped }) return null
        if (fileMentions.any { clamped > it.start && clamped <= it.end }) return null
        return token
    }

    fun suggestionMode(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
        fileMentions: List<FileMention> = emptyList(),
    ): ComposerSuggestionMode {
        val atQuery = atSuggestionQuery(text, cursor, mentions, fileMentions)
        if (atQuery != null) return ComposerSuggestionMode.At(query = atQuery)
        val slashQuery = ComposerSubmitRouting.slashSuggestionQuery(text)
        if (slashQuery != null) return ComposerSuggestionMode.Slash(query = slashQuery)
        return ComposerSuggestionMode.None
    }

    fun filterAgents(
        agents: List<MentionableAgent>,
        query: String,
    ): List<MentionableAgent> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return agents
        return agents.filter { agent ->
            agent.name.lowercase().contains(needle) ||
                agent.description?.lowercase()?.contains(needle) == true
        }
    }

    fun insertMention(
        text: String,
        cursor: Int,
        agentName: String,
        mentionId: String,
        existingMentions: List<AgentMention> = emptyList(),
        existingFileMentions: List<FileMention> = emptyList(),
    ): MentionInsertResult {
        val inserted =
            insertToken(
                text = text,
                cursor = cursor,
                token = "@$agentName",
            )
        return MentionInsertResult(
            text = inserted.text,
            cursor = inserted.cursor,
            mentions =
                (
                    shiftedAgents(existingMentions, inserted) +
                        AgentMention(
                            id = mentionId,
                            name = agentName,
                            start = inserted.atIndex,
                            end = inserted.atIndex + inserted.token.length,
                        )
                ).sortedBy { it.start },
            fileMentions = shiftedFiles(existingFileMentions, inserted),
        )
    }

    fun insertFileMention(
        text: String,
        cursor: Int,
        path: String,
        mentionId: String,
        existingMentions: List<AgentMention> = emptyList(),
        existingFileMentions: List<FileMention> = emptyList(),
    ): MentionInsertResult {
        val inserted = insertToken(text = text, cursor = cursor, token = "@$path")
        return MentionInsertResult(
            text = inserted.text,
            cursor = inserted.cursor,
            mentions = shiftedAgents(existingMentions, inserted),
            fileMentions =
                (
                    shiftedFiles(existingFileMentions, inserted) +
                        FileMention(
                            id = mentionId,
                            path = path,
                            start = inserted.atIndex,
                            end = inserted.atIndex + inserted.token.length,
                        )
                ).sortedBy { it.start },
        )
    }

    fun reconcileAfterEdit(
        previousText: String,
        previousMentions: List<AgentMention>,
        proposedText: String,
        proposedCursor: Int,
        previousFileMentions: List<FileMention> = emptyList(),
    ): MentionReconcileResult {
        val reconciled =
            reconcileSpans(
                previousText = previousText,
                previousSpans = spans(previousMentions, previousFileMentions),
                proposedText = proposedText,
                proposedCursor = proposedCursor,
            )
        return MentionReconcileResult(
            text = reconciled.text,
            cursor = reconciled.cursor,
            mentions = reconciled.spans.agents(),
            fileMentions = reconciled.spans.files(),
        )
    }

    fun agentParts(
        text: String,
        mentions: List<AgentMention>,
    ): List<AgentPartPayload> =
        mentions
            .sortedBy { it.start }
            .mapNotNull { mention ->
                if (mention.start < 0 || mention.end > text.length || mention.start >= mention.end) {
                    return@mapNotNull null
                }
                val value = text.substring(mention.start, mention.end)
                if (value != mention.token) return@mapNotNull null
                AgentPartPayload(
                    name = mention.name,
                    sourceValue = value,
                    start = mention.start,
                    end = mention.end,
                )
            }

    fun fileParts(
        text: String,
        mentions: List<FileMention>,
    ): List<FilePartPayload> =
        mentions
            .sortedBy { it.start }
            .mapNotNull { mention ->
                if (mention.start < 0 || mention.end > text.length || mention.start >= mention.end) {
                    return@mapNotNull null
                }
                val value = text.substring(mention.start, mention.end)
                if (value != mention.token) return@mapNotNull null
                FilePartPayload(
                    path = mention.path,
                    filename = ProjectFileReference.filename(mention.path),
                    sourceValue = value,
                    start = mention.start,
                    end = mention.end,
                )
            }

    fun trimForSend(
        text: String,
        mentions: List<AgentMention>,
        fileMentions: List<FileMention> = emptyList(),
    ): TrimmedPrompt {
        val leading = text.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return TrimmedPrompt("", emptyList(), emptyList())
        val trailing = text.indexOfLast { !it.isWhitespace() }
        val trimmed = text.substring(leading, trailing + 1)
        fun adjust(
            start: Int,
            end: Int,
        ): Pair<Int, Int>? {
            if (end <= leading || start > trailing) return null
            return (start - leading) to (end - leading)
        }
        return TrimmedPrompt(
            text = trimmed,
            agentMentions =
                mentions.mapNotNull { mention ->
                    val range = adjust(mention.start, mention.end) ?: return@mapNotNull null
                    mention.copy(start = range.first, end = range.second)
                },
            fileMentions =
                fileMentions.mapNotNull { mention ->
                    val range = adjust(mention.start, mention.end) ?: return@mapNotNull null
                    mention.copy(start = range.first, end = range.second)
                },
        )
    }

    private data class TokenInsertion(
        val text: String,
        val cursor: Int,
        val atIndex: Int,
        val token: String,
        val delta: Int,
        val replacedUntil: Int,
    )

    private fun insertToken(
        text: String,
        cursor: Int,
        token: String,
    ): TokenInsertion {
        val clamped = cursor.coerceIn(0, text.length)
        val before = text.substring(0, clamped)
        val atIndex = before.lastIndexOf('@')
        require(atIndex >= 0) { "No @ token before cursor" }
        val insertion = "$token "
        return TokenInsertion(
            text = text.substring(0, atIndex) + insertion + text.substring(clamped),
            cursor = atIndex + insertion.length,
            atIndex = atIndex,
            token = token,
            delta = insertion.length - (clamped - atIndex),
            replacedUntil = clamped,
        )
    }

    private fun shiftedAgents(
        mentions: List<AgentMention>,
        inserted: TokenInsertion,
    ): List<AgentMention> =
        mentions.mapNotNull { mention ->
            when {
                mention.end <= inserted.atIndex -> mention
                mention.start >= inserted.replacedUntil ->
                    mention.copy(
                        start = mention.start + inserted.delta,
                        end = mention.end + inserted.delta,
                    )
                else -> null
            }
        }.sortedBy { it.start }

    private fun shiftedFiles(
        mentions: List<FileMention>,
        inserted: TokenInsertion,
    ): List<FileMention> =
        mentions.mapNotNull { mention ->
            when {
                mention.end <= inserted.atIndex -> mention
                mention.start >= inserted.replacedUntil ->
                    mention.copy(
                        start = mention.start + inserted.delta,
                        end = mention.end + inserted.delta,
                    )
                else -> null
            }
        }.sortedBy { it.start }

    private data class MentionSpan(
        val id: String,
        val kind: String,
        val label: String,
        val start: Int,
        val end: Int,
    )

    private data class SpanReconcile(
        val text: String,
        val cursor: Int,
        val spans: List<MentionSpan>,
    )

    private fun spans(
        agents: List<AgentMention>,
        files: List<FileMention>,
    ): List<MentionSpan> =
        agents.map { MentionSpan(it.id, AGENT, it.name, it.start, it.end) } +
            files.map { MentionSpan(it.id, FILE, it.path, it.start, it.end) }

    private fun List<MentionSpan>.agents(): List<AgentMention> =
        filter { it.kind == AGENT }
            .sortedBy { it.start }
            .map { AgentMention(id = it.id, name = it.label, start = it.start, end = it.end) }

    private fun List<MentionSpan>.files(): List<FileMention> =
        filter { it.kind == FILE }
            .sortedBy { it.start }
            .map { FileMention(id = it.id, path = it.label, start = it.start, end = it.end) }

    private fun reconcileSpans(
        previousText: String,
        previousSpans: List<MentionSpan>,
        proposedText: String,
        proposedCursor: Int,
    ): SpanReconcile {
        if (previousText == proposedText) {
            return SpanReconcile(
                text = proposedText,
                cursor = proposedCursor.coerceIn(0, proposedText.length),
                spans = previousSpans,
            )
        }

        val prefix = previousText.commonPrefixWith(proposedText).length
        val maxSuffix = minOf(previousText.length - prefix, proposedText.length - prefix)
        var suffix = 0
        while (suffix < maxSuffix &&
            previousText[previousText.length - 1 - suffix] == proposedText[proposedText.length - 1 - suffix]
        ) {
            suffix += 1
        }

        val oldEditStart = prefix
        val oldEditEnd = previousText.length - suffix
        val overlapping =
            previousSpans.filter { span ->
                span.start < oldEditEnd && span.end > oldEditStart
            }

        if (overlapping.isEmpty()) {
            val delta = proposedText.length - previousText.length
            return SpanReconcile(
                text = proposedText,
                cursor = proposedCursor.coerceIn(0, proposedText.length),
                spans = previousSpans.map { span -> span.shifted(oldEditEnd, delta) },
            )
        }

        if (overlapping.size == 1) {
            val span = overlapping.single()
            val isInternalEdit = oldEditStart >= span.start && oldEditEnd <= span.end
            val isShrink = proposedText.length < previousText.length
            if (isInternalEdit && isShrink) {
                val text = previousText.removeRange(span.start, span.end)
                val removed = span.end - span.start
                val spans =
                    previousSpans
                        .filterNot { it.id == span.id && it.kind == span.kind }
                        .map { current ->
                            if (current.start >= span.end) {
                                current.copy(start = current.start - removed, end = current.end - removed)
                            } else {
                                current
                            }
                        }
                return SpanReconcile(
                    text = text,
                    cursor = span.start.coerceIn(0, text.length),
                    spans = spans,
                )
            }
        }

        val delta = proposedText.length - previousText.length
        val spans =
            previousSpans
                .filterNot { span -> overlapping.any { it.id == span.id && it.kind == span.kind } }
                .map { span -> span.shifted(oldEditEnd, delta) }
        return SpanReconcile(
            text = proposedText,
            cursor = proposedCursor.coerceIn(0, proposedText.length),
            spans = spans,
        )
    }

    private fun MentionSpan.shifted(
        oldEditEnd: Int,
        delta: Int,
    ): MentionSpan =
        if (start >= oldEditEnd) copy(start = start + delta, end = end + delta) else this

    private const val AGENT = "agent"
    private const val FILE = "file"
}
