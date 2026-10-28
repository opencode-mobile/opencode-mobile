package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.domain.filesystem.ProjectFileReference

object ComposerMentionRouting {
    fun atSuggestionQuery(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
        fileMentions: List<FileMention> = emptyList(),
        skillMentions: List<SkillMention> = emptyList(),
    ): String? = openTriggerQuery(text, cursor, '@', mentions, fileMentions, skillMentions)?.query

    /**
     * The open `/` query at the caret, with the same trigger rules as [atSuggestionQuery]:
     * the `/` starts the draft or follows whitespace, and no whitespace sits between it and
     * the caret. A caret inside a file mention (paths contain `/`) or right after a completed
     * skill span is not a query.
     */
    fun slashSuggestionQuery(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
        fileMentions: List<FileMention> = emptyList(),
        skillMentions: List<SkillMention> = emptyList(),
    ): String? = openTriggerQuery(text, cursor, '/', mentions, fileMentions, skillMentions)?.query

    /** True when the `/` of the open slash query sits at offset 0 of the draft. */
    fun slashTokenIsLeading(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
        fileMentions: List<FileMention> = emptyList(),
        skillMentions: List<SkillMention> = emptyList(),
    ): Boolean = openTriggerQuery(text, cursor, '/', mentions, fileMentions, skillMentions)?.triggerIndex == 0

    fun suggestionMode(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
        fileMentions: List<FileMention> = emptyList(),
        skillMentions: List<SkillMention> = emptyList(),
        allowsMentions: Boolean = true,
        allowsSlashCommands: Boolean = true,
    ): ComposerSuggestionMode {
        if (allowsMentions) {
            val atQuery = atSuggestionQuery(text, cursor, mentions, fileMentions, skillMentions)
            if (atQuery != null) return ComposerSuggestionMode.At(query = atQuery)
        }
        if (allowsSlashCommands) {
            val slash = openTriggerQuery(text, cursor, '/', mentions, fileMentions, skillMentions)
            if (slash != null) {
                return ComposerSuggestionMode.Slash(query = slash.query, isLeading = slash.triggerIndex == 0)
            }
        }
        return ComposerSuggestionMode.None
    }

    /**
     * Drops structured mentions and unresolved `@tokens` (start, or after
     * whitespace, then a non-whitespace run). An address such as `a@b.com`
     * stays. A bare `@` stays unless it is the end of the text.
     */
    fun withoutMentionTokens(
        text: String,
        mentions: List<AgentMention> = emptyList(),
    ): String {
        var result = text
        for (mention in mentions.sortedByDescending { it.start }) {
            if (mention.start < 0 || mention.end > result.length || mention.start > mention.end) continue
            val dropFollowingSpace =
                mention.start > 0 &&
                    result[mention.start - 1].isWhitespace() &&
                    mention.end < result.length &&
                    result[mention.end].isWhitespace()
            val end = if (dropFollowingSpace) mention.end + 1 else mention.end
            result = result.removeRange(mention.start, end)
        }
        return stripUnresolvedAtTokens(result)
    }

    private fun stripUnresolvedAtTokens(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val atBoundary = text[index] == '@' && (index == 0 || text[index - 1].isWhitespace())
            if (!atBoundary) {
                out.append(text[index])
                index += 1
                continue
            }
            var end = index + 1
            while (end < text.length && !text[end].isWhitespace()) end += 1
            val hasName = end > index + 1
            val isTrailingBare = !hasName && end == text.length
            if (!hasName && !isTrailingBare) {
                out.append(text[index])
                index += 1
                continue
            }
            index = end
            if (index < text.length && text[index].isWhitespace() && (out.isEmpty() || out.last().isWhitespace())) {
                index += 1
            }
            if (out.isEmpty()) {
                while (index < text.length && text[index].isWhitespace()) index += 1
            }
        }
        return out.toString().trim()
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
        existingSkillMentions: List<SkillMention> = emptyList(),
    ): MentionInsertResult {
        val inserted =
            insertToken(
                text = text,
                cursor = cursor,
                trigger = '@',
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
            skillMentions = shiftedSkills(existingSkillMentions, inserted),
        )
    }

    fun insertFileMention(
        text: String,
        cursor: Int,
        path: String,
        mentionId: String,
        existingMentions: List<AgentMention> = emptyList(),
        existingFileMentions: List<FileMention> = emptyList(),
        existingSkillMentions: List<SkillMention> = emptyList(),
    ): MentionInsertResult {
        val inserted = insertToken(text = text, cursor = cursor, trigger = '@', token = "@$path")
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
            skillMentions = shiftedSkills(existingSkillMentions, inserted),
        )
    }

    /** Replaces the open `/query` at the caret with a tracked `/name` skill span plus a trailing space. */
    fun insertSkillMention(
        text: String,
        cursor: Int,
        skillId: String,
        name: String,
        mentionId: String,
        existingMentions: List<AgentMention> = emptyList(),
        existingFileMentions: List<FileMention> = emptyList(),
        existingSkillMentions: List<SkillMention> = emptyList(),
    ): MentionInsertResult {
        val inserted = insertToken(text = text, cursor = cursor, trigger = '/', token = "/$name")
        return MentionInsertResult(
            text = inserted.text,
            cursor = inserted.cursor,
            mentions = shiftedAgents(existingMentions, inserted),
            fileMentions = shiftedFiles(existingFileMentions, inserted),
            skillMentions =
                (
                    shiftedSkills(existingSkillMentions, inserted) +
                        SkillMention(
                            id = mentionId,
                            skillId = skillId,
                            name = name,
                            start = inserted.atIndex,
                            end = inserted.atIndex + inserted.token.length,
                        )
                ).sortedBy { it.start },
        )
    }

    /** Replaces the open `/query` at the caret with `/name ` and records no span. */
    fun insertCommandSelection(
        text: String,
        cursor: Int,
        commandName: String,
        existingMentions: List<AgentMention> = emptyList(),
        existingFileMentions: List<FileMention> = emptyList(),
        existingSkillMentions: List<SkillMention> = emptyList(),
    ): MentionInsertResult {
        val inserted = insertToken(text = text, cursor = cursor, trigger = '/', token = "/$commandName")
        return MentionInsertResult(
            text = inserted.text,
            cursor = inserted.cursor,
            mentions = shiftedAgents(existingMentions, inserted),
            fileMentions = shiftedFiles(existingFileMentions, inserted),
            skillMentions = shiftedSkills(existingSkillMentions, inserted),
        )
    }

    fun reconcileAfterEdit(
        previousText: String,
        previousMentions: List<AgentMention>,
        proposedText: String,
        proposedCursor: Int,
        previousFileMentions: List<FileMention> = emptyList(),
        previousSkillMentions: List<SkillMention> = emptyList(),
    ): MentionReconcileResult {
        val reconciled =
            reconcileSpans(
                previousText = previousText,
                previousSpans = spans(previousMentions, previousFileMentions, previousSkillMentions),
                proposedText = proposedText,
                proposedCursor = proposedCursor,
            )
        return MentionReconcileResult(
            text = reconciled.text,
            cursor = reconciled.cursor,
            mentions = reconciled.spans.agents(),
            fileMentions = reconciled.spans.files(),
            skillMentions = reconciled.spans.skills(),
        )
    }

    fun agentParts(
        text: String,
        mentions: List<AgentMention>,
    ): List<AgentPartPayload> =
        mentions
            .sortedBy { it.start }
            .mapNotNull { mention ->
                val value = tokenValue(text, mention.start, mention.end, mention.token) ?: return@mapNotNull null
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
                val value = tokenValue(text, mention.start, mention.end, mention.token) ?: return@mapNotNull null
                FilePartPayload(
                    path = mention.path,
                    filename = ProjectFileReference.filename(mention.path),
                    sourceValue = value,
                    start = mention.start,
                    end = mention.end,
                )
            }

    /** Skill spans whose text still equals their token, in draft order. */
    fun skillParts(
        text: String,
        skillMentions: List<SkillMention>,
    ): List<SkillPartPayload> =
        skillMentions
            .sortedBy { it.start }
            .mapNotNull { mention ->
                val value = tokenValue(text, mention.start, mention.end, mention.token) ?: return@mapNotNull null
                SkillPartPayload(
                    skillId = mention.skillId,
                    name = mention.name,
                    sourceValue = value,
                    start = mention.start,
                    end = mention.end,
                )
            }

    fun trimForSend(
        text: String,
        mentions: List<AgentMention>,
        fileMentions: List<FileMention> = emptyList(),
        skillMentions: List<SkillMention> = emptyList(),
    ): TrimmedPrompt {
        val leading = text.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return TrimmedPrompt("", emptyList(), emptyList(), emptyList())
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
            skillMentions =
                skillMentions.mapNotNull { mention ->
                    val range = adjust(mention.start, mention.end) ?: return@mapNotNull null
                    mention.copy(start = range.first, end = range.second)
                },
        )
    }

    private fun tokenValue(
        text: String,
        start: Int,
        end: Int,
        token: String,
    ): String? {
        if (start < 0 || end > text.length || start >= end) return null
        val value = text.substring(start, end)
        return value.takeIf { it == token }
    }

    private data class OpenQuery(
        val triggerIndex: Int,
        val query: String,
    )

    private fun openTriggerQuery(
        text: String,
        cursor: Int,
        trigger: Char,
        mentions: List<AgentMention>,
        fileMentions: List<FileMention>,
        skillMentions: List<SkillMention>,
    ): OpenQuery? {
        val clamped = cursor.coerceIn(0, text.length)
        val before = text.substring(0, clamped)
        val triggerIndex = before.lastIndexOf(trigger)
        if (triggerIndex < 0) return null
        if (triggerIndex > 0) {
            val previous = before[triggerIndex - 1]
            if (!previous.isWhitespace()) return null
        }
        val token = before.substring(triggerIndex + 1)
        if (token.any { it.isWhitespace() }) return null
        // A completed mention at the caret is not an open query. A caret inside a
        // file path (which may contain spaces or slashes) is still that mention.
        if (mentions.any { it.start == triggerIndex && it.end == clamped }) return null
        if (skillMentions.any { it.start == triggerIndex && it.end == clamped }) return null
        if (fileMentions.any { clamped > it.start && clamped <= it.end }) return null
        return OpenQuery(triggerIndex = triggerIndex, query = token)
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
        trigger: Char,
        token: String,
    ): TokenInsertion {
        val clamped = cursor.coerceIn(0, text.length)
        val before = text.substring(0, clamped)
        val atIndex = before.lastIndexOf(trigger)
        require(atIndex >= 0) { "No $trigger token before cursor" }
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
        }
            .sortedBy { it.start }

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
        }
            .sortedBy { it.start }

    private fun shiftedSkills(
        mentions: List<SkillMention>,
        inserted: TokenInsertion,
    ): List<SkillMention> =
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
        }
            .sortedBy { it.start }

    private data class MentionSpan(
        val id: String,
        val kind: String,
        val label: String,
        val start: Int,
        val end: Int,
        /** Skill spans: the wire skill id. */
        val detail: String? = null,
    )

    private data class SpanReconcile(
        val text: String,
        val cursor: Int,
        val spans: List<MentionSpan>,
    )

    private fun spans(
        agents: List<AgentMention>,
        files: List<FileMention>,
        skills: List<SkillMention>,
    ): List<MentionSpan> =
        agents.map { MentionSpan(it.id, AGENT, it.name, it.start, it.end) } +
            files.map { MentionSpan(it.id, FILE, it.path, it.start, it.end) } +
            skills.map { MentionSpan(it.id, SKILL, it.name, it.start, it.end, detail = it.skillId) }

    private fun List<MentionSpan>.agents(): List<AgentMention> =
        filter { it.kind == AGENT }
            .sortedBy { it.start }
            .map { AgentMention(id = it.id, name = it.label, start = it.start, end = it.end) }

    private fun List<MentionSpan>.files(): List<FileMention> =
        filter { it.kind == FILE }
            .sortedBy { it.start }
            .map { FileMention(id = it.id, path = it.label, start = it.start, end = it.end) }

    private fun List<MentionSpan>.skills(): List<SkillMention> =
        filter { it.kind == SKILL }
            .sortedBy { it.start }
            .map {
                SkillMention(
                    id = it.id,
                    skillId = it.detail ?: it.label,
                    name = it.label,
                    start = it.start,
                    end = it.end,
                )
            }

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
    ): MentionSpan = if (start >= oldEditEnd) copy(start = start + delta, end = end + delta) else this

    private const val AGENT = "agent"
    private const val FILE = "file"
    private const val SKILL = "skill"
}
