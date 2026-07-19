package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.domain.command.ComposerSubmitRouting

object ComposerMentionRouting {
    fun atSuggestionQuery(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
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
        // A completed structured mention at the caret is not an open @ query.
        if (mentions.any { it.start == atIndex && it.end == clamped }) return null
        return token
    }

    fun suggestionMode(
        text: String,
        cursor: Int,
        mentions: List<AgentMention> = emptyList(),
    ): ComposerSuggestionMode {
        val atQuery = atSuggestionQuery(text, cursor, mentions)
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
    ): MentionInsertResult {
        val clamped = cursor.coerceIn(0, text.length)
        val before = text.substring(0, clamped)
        val atIndex = before.lastIndexOf('@')
        require(atIndex >= 0) { "No @ token before cursor" }
        val token = "@$agentName"
        val insertion = "$token "
        val newText = text.substring(0, atIndex) + insertion + text.substring(clamped)
        val delta = insertion.length - (clamped - atIndex)
        val shifted =
            existingMentions.mapNotNull { mention ->
                when {
                    mention.end <= atIndex -> mention
                    mention.start >= clamped ->
                        mention.copy(start = mention.start + delta, end = mention.end + delta)
                    else -> null
                }
            }
        val inserted = AgentMention(id = mentionId, name = agentName, start = atIndex, end = atIndex + token.length)
        val mentions = (shifted + inserted).sortedBy { it.start }
        return MentionInsertResult(
            text = newText,
            cursor = atIndex + insertion.length,
            mentions = mentions,
        )
    }

    fun reconcileAfterEdit(
        previousText: String,
        previousMentions: List<AgentMention>,
        proposedText: String,
        proposedCursor: Int,
    ): MentionReconcileResult {
        if (previousText == proposedText) {
            return MentionReconcileResult(
                text = proposedText,
                cursor = proposedCursor.coerceIn(0, proposedText.length),
                mentions = previousMentions,
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
        val newEditEnd = proposedText.length - suffix
        val overlapping =
            previousMentions.filter { mention ->
                mention.start < oldEditEnd && mention.end > oldEditStart
            }

        if (overlapping.isEmpty()) {
            val delta = proposedText.length - previousText.length
            val mentions =
                previousMentions.map { mention ->
                    if (mention.start >= oldEditEnd) {
                        mention.copy(start = mention.start + delta, end = mention.end + delta)
                    } else {
                        mention
                    }
                }
            return MentionReconcileResult(
                text = proposedText,
                cursor = proposedCursor.coerceIn(0, proposedText.length),
                mentions = mentions,
            )
        }

        // Atomic delete: a shrink entirely inside one mention removes that whole token.
        if (overlapping.size == 1) {
            val mention = overlapping.single()
            val isInternalEdit = oldEditStart >= mention.start && oldEditEnd <= mention.end
            val isShrink = proposedText.length < previousText.length
            if (isInternalEdit && isShrink) {
                val text = previousText.removeRange(mention.start, mention.end)
                val removed = mention.end - mention.start
                val mentions =
                    previousMentions
                        .filterNot { it.id == mention.id }
                        .map { current ->
                            if (current.start >= mention.end) {
                                current.copy(start = current.start - removed, end = current.end - removed)
                            } else {
                                current
                            }
                        }
                return MentionReconcileResult(
                    text = text,
                    cursor = mention.start.coerceIn(0, text.length),
                    mentions = mentions,
                )
            }
        }

        // Broader edits (paste, replace-all, typing over a mention): keep proposed text
        // and drop only the overlapping mentions.
        val delta = proposedText.length - previousText.length
        val mentions =
            previousMentions
                .filterNot { mention -> overlapping.any { it.id == mention.id } }
                .map { mention ->
                    if (mention.start >= oldEditEnd) {
                        mention.copy(start = mention.start + delta, end = mention.end + delta)
                    } else {
                        mention
                    }
                }
        return MentionReconcileResult(
            text = proposedText,
            cursor = proposedCursor.coerceIn(0, proposedText.length),
            mentions = mentions,
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

    fun trimForSend(
        text: String,
        mentions: List<AgentMention>,
    ): Pair<String, List<AgentMention>> {
        val leading = text.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return "" to emptyList()
        val trailing = text.indexOfLast { !it.isWhitespace() }
        val trimmed = text.substring(leading, trailing + 1)
        val adjusted =
            mentions.mapNotNull { mention ->
                if (mention.end <= leading || mention.start > trailing) return@mapNotNull null
                mention.copy(start = mention.start - leading, end = mention.end - leading)
            }
        return trimmed to adjusted
    }
}
