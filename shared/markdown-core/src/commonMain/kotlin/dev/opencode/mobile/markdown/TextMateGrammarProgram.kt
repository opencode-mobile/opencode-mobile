package dev.opencode.mobile.markdown

data class GrammarId(
    val value: Int,
)

data class RuleId(
    val value: Int,
)

/**
 * Immutable compiled view of a typed [TextMateGrammar]. IDs are process-local and
 * assigned deterministically by DFS at compile time.
 */
data class TextMateGrammarProgram(
    val grammarId: GrammarId,
    val scopeName: String,
    val rootRuleIds: List<RuleId>,
    val rules: List<TextMateRulePlan>,
    /**
     * Parallel to [rules]: the live [TextMatePattern] instance assigned each
     * [RuleId] at compile time. Used for snapshot↔runtime open-rule restore.
     */
    val patterns: List<TextMatePattern>,
    val repositoryRuleIds: Map<String, RuleId>,
    val estimatedBytes: Long,
) {
    fun rule(id: RuleId): TextMateRulePlan = rules[id.value]

    fun pattern(id: RuleId): TextMatePattern = patterns[id.value]

    fun ruleIdOf(pattern: TextMatePattern): RuleId? {
        val index = patterns.indexOfFirst { candidate -> candidate === pattern }
        return if (index >= 0) RuleId(index) else null
    }

    companion object {
        fun compile(
            grammar: TextMateGrammar,
            grammarId: GrammarId,
        ): TextMateGrammarProgram {
            val rules = mutableListOf<TextMateRulePlan>()
            val patterns = mutableListOf<TextMatePattern>()
            val repositoryIds = linkedMapOf<String, RuleId>()

            fun visit(pattern: TextMatePattern): RuleId {
                val ruleId = RuleId(rules.size)
                // Reserve slot before children so nested IDs stay DFS-ordered.
                patterns += pattern
                rules +=
                    TextMateRulePlan(
                        ruleId = ruleId,
                        patternIdentity = patternIdentity(pattern),
                        include = pattern.include,
                        name = pattern.name,
                        contentName = pattern.contentName,
                        match = pattern.match,
                        begin = pattern.begin,
                        end = pattern.end,
                        whilePattern = pattern.whilePattern,
                        childRuleIds = emptyList(),
                        captureNames = pattern.captures.mapValues { it.value.name },
                        beginCaptureNames = pattern.beginCaptures.mapValues { it.value.name },
                        endCaptureNames = pattern.endCaptures.mapValues { it.value.name },
                        whileCaptureNames = pattern.whileCaptures.mapValues { it.value.name },
                        shouldApplyEndPatternLast = pattern.shouldApplyEndPatternLast,
                        estimatedBytes = estimatePatternBytes(pattern),
                    )

                val childIds = pattern.patterns.map(::visit)
                pattern.repository.forEach { (key, nested) ->
                    if (key !in repositoryIds) {
                        repositoryIds[key] = visit(nested)
                    }
                }
                pattern.captures.values.flatMap { it.patterns }.forEach(::visit)
                pattern.beginCaptures.values.flatMap { it.patterns }.forEach(::visit)
                pattern.endCaptures.values.flatMap { it.patterns }.forEach(::visit)
                pattern.whileCaptures.values.flatMap { it.patterns }.forEach(::visit)

                rules[ruleId.value] = rules[ruleId.value].copy(childRuleIds = childIds)
                return ruleId
            }

            val rootIds = grammar.patterns.map(::visit)
            grammar.repository.forEach { (key, pattern) ->
                repositoryIds[key] = visit(pattern)
            }
            grammar.injections.values.forEach(::visit)

            val programRules = rules.toList()
            val programPatterns = patterns.toList()
            val estimated =
                256L +
                    programRules.sumOf { it.estimatedBytes } +
                    grammar.scopeName.length * 2L +
                    repositoryIds.keys.sumOf { it.length * 2L }

            return TextMateGrammarProgram(
                grammarId = grammarId,
                scopeName = grammar.scopeName,
                rootRuleIds = rootIds,
                rules = programRules,
                patterns = programPatterns,
                repositoryRuleIds = repositoryIds,
                estimatedBytes = estimated,
            )
        }

        internal fun patternIdentity(pattern: TextMatePattern): Long {
            var hash = 17L
            fun mix(value: String?) {
                hash = hash * 31L + (value?.hashCode()?.toLong() ?: 0L)
            }
            mix(pattern.include)
            mix(pattern.name)
            mix(pattern.contentName)
            mix(pattern.match)
            mix(pattern.begin)
            mix(pattern.end)
            mix(pattern.whilePattern)
            hash = hash * 31L + pattern.patterns.size
            hash = hash * 31L + pattern.repository.size
            hash = hash * 31L + pattern.captures.size
            hash = hash * 31L + pattern.beginCaptures.size
            hash = hash * 31L + pattern.endCaptures.size
            hash = hash * 31L + pattern.whileCaptures.size
            hash = hash * 31L + if (pattern.shouldApplyEndPatternLast) 1 else 0
            // Disambiguate structurally identical siblings by nested payload size.
            hash = hash * 31L + nestedWeight(pattern)
            return hash
        }

        private fun nestedWeight(pattern: TextMatePattern): Long {
            var weight = 1L
            pattern.patterns.forEach { weight += nestedWeight(it) }
            pattern.repository.values.forEach { weight += nestedWeight(it) }
            pattern.captures.values.flatMap { it.patterns }.forEach { weight += nestedWeight(it) }
            pattern.beginCaptures.values.flatMap { it.patterns }.forEach { weight += nestedWeight(it) }
            pattern.endCaptures.values.flatMap { it.patterns }.forEach { weight += nestedWeight(it) }
            pattern.whileCaptures.values.flatMap { it.patterns }.forEach { weight += nestedWeight(it) }
            return weight
        }

        private fun estimatePatternBytes(pattern: TextMatePattern): Long {
            var bytes = 128L
            fun add(value: String?) {
                if (value != null) bytes += 32L + value.length * 2L
            }
            add(pattern.include)
            add(pattern.name)
            add(pattern.contentName)
            add(pattern.match)
            add(pattern.begin)
            add(pattern.end)
            add(pattern.whilePattern)
            return bytes
        }
    }
}

data class TextMateRulePlan(
    val ruleId: RuleId,
    val patternIdentity: Long,
    val include: String?,
    val name: String?,
    val contentName: String?,
    val match: String?,
    val begin: String?,
    val end: String?,
    val whilePattern: String?,
    val childRuleIds: List<RuleId>,
    val captureNames: Map<String, String?>,
    val beginCaptureNames: Map<String, String?>,
    val endCaptureNames: Map<String, String?>,
    val whileCaptureNames: Map<String, String?>,
    val shouldApplyEndPatternLast: Boolean,
    val estimatedBytes: Long,
)
