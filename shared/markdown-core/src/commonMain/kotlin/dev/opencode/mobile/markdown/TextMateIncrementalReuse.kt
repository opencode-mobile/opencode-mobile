package dev.opencode.mobile.markdown

const val ROLE_MAPPER_VERSION: Int = 1
const val REPAIR_PIPELINE_VERSION: Int = 1
const val INCREMENTAL_SCHEMA_VERSION: Int = 1

enum class TextMateIncrementalMode {
    None,
    ExactSource,
    Prefix,
    PrefixSuffix,
}

data class TextMateOpenRuleSnapshot(
    val grammarId: GrammarId,
    val ruleId: RuleId,
    val endPattern: String?,
    val whilePattern: String?,
    val ruleScopes: List<String>,
    val contentScopes: List<String>,
    val activeIncludes: List<String>,
)

data class TextMateLineStateSnapshot(
    val openRules: List<TextMateOpenRuleSnapshot> = emptyList(),
) {
    fun fingerprint(): Long = fnv1a64OpenRules(openRules)

    companion object {
        val Initial: TextMateLineStateSnapshot = TextMateLineStateSnapshot()
    }
}

data class TextMateLineCheckpoint(
    val lineIndex: Int,
    val lineText: String,
    val lineTextHash32: Int,
    val incoming: TextMateLineStateSnapshot,
    val incomingFingerprint: Long,
    val outgoing: TextMateLineStateSnapshot,
    val outgoingFingerprint: Long,
    val relativeTokens: List<TextMateScopeToken>,
    val estimatedBytes: Long,
)

data class IncrementalBlockIdentity(
    val key: CodeBlockKey,
    val canonicalLanguage: String,
    val grammarResourcePath: String,
    val grammarId: GrammarId,
    val roleMapperVersion: Int = ROLE_MAPPER_VERSION,
    val repairPipelineVersion: Int = REPAIR_PIPELINE_VERSION,
    val policyEpoch: Long,
    val incrementalSchemaVersion: Int = INCREMENTAL_SCHEMA_VERSION,
)

data class IncrementalBlockState(
    val identity: IncrementalBlockIdentity,
    val sourceHash32: Int,
    val sourceLength: Int,
    val source: String,
    val lines: List<TextMateLineCheckpoint>,
    val intent: HighlightIntent,
    val revision: HighlightRevision,
    val estimatedBytes: Long,
)

data class TextMateIncrementalReusePlan(
    val mode: TextMateIncrementalMode,
    val resumeLine: Int,
    val reusedPrefix: List<TextMateLineCheckpoint>,
    val incomingAtResume: TextMateLineStateSnapshot,
    val previous: IncrementalBlockState?,
)

data class TextMateIncrementalHighlightSession(
    var identity: IncrementalBlockIdentity,
    val intent: HighlightIntent,
    val revision: HighlightRevision,
    val previous: IncrementalBlockState?,
    val suffixConvergenceEnabled: Boolean,
    var produced: IncrementalBlockState? = null,
    var mode: TextMateIncrementalMode = TextMateIncrementalMode.None,
    var reusedLineCount: Int = 0,
    var retokenizedLineCount: Int = 0,
    var suffixReusedLineCount: Int = 0,
)

object TextMatePhysicalLines {
    fun split(source: String): List<String> {
        if (source.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start <= source.length) {
            val end = source.indexOf('\n', start).let { index -> if (index == -1) source.length else index }
            lines += source.substring(start, end)
            if (end == source.length) break
            start = end + 1
        }
        return lines
    }

    fun lineStartOffsets(lines: List<String>): IntArray {
        val offsets = IntArray(lines.size)
        var offset = 0
        for (index in lines.indices) {
            offsets[index] = offset
            offset += lines[index].length
            if (index < lines.lastIndex) {
                offset += 1 // newline separator
            }
        }
        return offsets
    }
}

object TextMateIncrementalPlanner {
    fun plan(
        previous: IncrementalBlockState?,
        identity: IncrementalBlockIdentity,
        source: String,
        suffixConvergenceEnabled: Boolean,
    ): TextMateIncrementalReusePlan {
        if (previous == null || previous.identity != identity) {
            return fullRetokenizePlan()
        }
        if (previous.sourceLength == source.length &&
            previous.sourceHash32 == source.hashCode() &&
            previous.source == source
        ) {
            return TextMateIncrementalReusePlan(
                mode = TextMateIncrementalMode.ExactSource,
                resumeLine = previous.lines.size,
                reusedPrefix = previous.lines,
                incomingAtResume = TextMateLineStateSnapshot.Initial,
                previous = previous,
            )
        }
        val newLines = TextMatePhysicalLines.split(source)
        val oldLines = previous.lines
        var k = 0
        val limit = minOf(newLines.size, oldLines.size)
        while (k < limit) {
            val old = oldLines[k]
            val candidate = newLines[k]
            if (old.lineTextHash32 != candidate.hashCode() || old.lineText != candidate) break
            k += 1
        }
        if (k == 0) return fullRetokenizePlan(previous)
        val resumeLine = (k - 1).coerceAtLeast(0)
        val reusedPrefix = oldLines.take(resumeLine)
        val incoming =
            when {
                resumeLine == 0 -> TextMateLineStateSnapshot.Initial
                else -> oldLines[resumeLine - 1].outgoing
            }
        return TextMateIncrementalReusePlan(
            mode =
                if (suffixConvergenceEnabled) {
                    TextMateIncrementalMode.Prefix
                } else {
                    TextMateIncrementalMode.Prefix
                },
            resumeLine = resumeLine,
            reusedPrefix = reusedPrefix,
            incomingAtResume = incoming,
            previous = previous,
        )
    }

    private fun fullRetokenizePlan(previous: IncrementalBlockState? = null): TextMateIncrementalReusePlan =
        TextMateIncrementalReusePlan(
            mode = TextMateIncrementalMode.None,
            resumeLine = 0,
            reusedPrefix = emptyList(),
            incomingAtResume = TextMateLineStateSnapshot.Initial,
            previous = previous,
        )
}

class TextMateIncrementalStateStore(
    private val cacheCoordinator: TextMateWeightedCacheCoordinator,
    private val maxRetainedBlocks: Int = DEFAULT_MAX_RETAINED_BLOCKS,
    private val categoryBudgetBytes: Long = DEFAULT_CATEGORY_BUDGET_BYTES,
    private val perBlockAdmissionBytes: Long = DEFAULT_PER_BLOCK_ADMISSION_BYTES,
    private val maxLinesPerBlock: Int = DEFAULT_MAX_LINES_PER_BLOCK,
) {
    private val blocks = LinkedHashMap<String, IncrementalBlockState>()

    fun get(key: CodeBlockKey): IncrementalBlockState? = blocks[key.value]

    fun promote(state: IncrementalBlockState): Boolean {
        if (state.lines.size > maxLinesPerBlock) return false
        if (state.estimatedBytes > perBlockAdmissionBytes) return false
        evictUntilBlockCount(maxRetainedBlocks - 1)
        evictUntilCategoryBytes(categoryBudgetBytes - state.estimatedBytes)
        if (categoryRetainedBytes() + state.estimatedBytes > categoryBudgetBytes) {
            return false
        }
        val cacheKey = cacheKey(state.identity.key)
        blocks.remove(cacheKey)
        val admitted =
            cacheCoordinator.promote(
                key = cacheKey,
                category = TextMateWeightedCacheCoordinator.Category.IncrementalBlock,
                estimatedBytes = state.estimatedBytes,
                dispose = { blocks.remove(cacheKey) },
            )
        if (!admitted) return false
        blocks[cacheKey] = state
        return true
    }

    fun trim(
        level: MemoryPressure,
        backgroundOnly: Boolean = false,
    ) {
        when (level) {
            MemoryPressure.Normal -> Unit
            MemoryPressure.Moderate -> {
                val victims =
                    blocks.values
                        .filter { it.intent == HighlightIntent.BackgroundHistory }
                        .sortedBy { it.revision.value }
                        .toList()
                victims.forEach { remove(it.identity.key) }
                if (!backgroundOnly) {
                    evictUntilCategoryBytes(categoryBudgetBytes / 2)
                }
            }
            MemoryPressure.Critical -> {
                blocks.keys.toList().forEach { key ->
                    blocks.remove(key)
                    cacheCoordinator.remove(key)
                }
            }
        }
    }

    fun remove(key: CodeBlockKey) {
        val cacheKey = cacheKey(key)
        blocks.remove(cacheKey)
        cacheCoordinator.remove(cacheKey)
    }

    fun retainedBlockCount(): Int = blocks.size

    fun categoryRetainedBytes(): Long = blocks.values.sumOf { it.estimatedBytes }

    private fun evictUntilBlockCount(target: Int) {
        while (blocks.size > target.coerceAtLeast(0)) {
            val victim = blocks.values.minByOrNull { it.revision.value } ?: break
            remove(victim.identity.key)
        }
    }

    private fun evictUntilCategoryBytes(targetBytes: Long) {
        while (categoryRetainedBytes() > targetBytes) {
            val victim =
                blocks.values
                    .sortedWith(
                        compareBy<IncrementalBlockState> {
                            when (it.intent) {
                                HighlightIntent.BackgroundHistory -> 0
                                HighlightIntent.VisibleHistory -> 1
                                HighlightIntent.InteractiveFinal -> 2
                            }
                        }.thenBy { it.revision.value },
                    ).firstOrNull()
                    ?: break
            remove(victim.identity.key)
        }
    }

    private fun cacheKey(key: CodeBlockKey): String = "incremental:${key.value}"

    companion object {
        const val DEFAULT_MAX_RETAINED_BLOCKS: Int = 64
        const val DEFAULT_CATEGORY_BUDGET_BYTES: Long = 8L * 1024L * 1024L
        const val DEFAULT_PER_BLOCK_ADMISSION_BYTES: Long = 256L * 1024L
        const val DEFAULT_MAX_LINES_PER_BLOCK: Int = 2_048
    }
}

object TextMateLineStateCodec {
    fun snapshot(
        state: TextMateLineState,
        resolveGrammarId: (TextMateGrammar) -> GrammarId?,
        resolveRuleId: (TextMateGrammar, TextMatePattern) -> RuleId?,
    ): TextMateLineStateSnapshot? {
        val openRules =
            state.openRules.map { rule ->
                val grammarId = resolveGrammarId(rule.grammar) ?: return null
                val ruleId = resolveRuleId(rule.grammar, rule.pattern) ?: return null
                TextMateOpenRuleSnapshot(
                    grammarId = grammarId,
                    ruleId = ruleId,
                    endPattern = rule.endPattern,
                    whilePattern = rule.whilePattern,
                    ruleScopes = rule.ruleScopes,
                    contentScopes = rule.contentScopes,
                    activeIncludes = rule.activeIncludes.sorted(),
                )
            }
        return TextMateLineStateSnapshot(openRules)
    }

    fun restore(
        snapshot: TextMateLineStateSnapshot,
        resolveGrammar: (GrammarId) -> TextMateGrammar?,
        resolvePattern: (GrammarId, RuleId) -> TextMatePattern?,
    ): TextMateLineState? {
        val openRules =
            snapshot.openRules.map { rule ->
                val grammar = resolveGrammar(rule.grammarId) ?: return null
                val pattern = resolvePattern(rule.grammarId, rule.ruleId) ?: return null
                TextMateOpenRule(
                    grammar = grammar,
                    pattern = pattern,
                    endPattern = rule.endPattern,
                    whilePattern = rule.whilePattern,
                    ruleScopes = rule.ruleScopes,
                    contentScopes = rule.contentScopes,
                    activeIncludes = rule.activeIncludes.toSet(),
                )
            }
        return TextMateLineState(openRules)
    }
}

internal fun estimateCheckpointBytes(
    lineText: String,
    incoming: TextMateLineStateSnapshot,
    outgoing: TextMateLineStateSnapshot,
    relativeTokens: List<TextMateScopeToken>,
): Long {
    fun openRuleBytes(rules: List<TextMateOpenRuleSnapshot>): Long =
        rules.sumOf { rule ->
            64L +
                (rule.endPattern?.length?.times(2L) ?: 0L) +
                (rule.whilePattern?.length?.times(2L) ?: 0L) +
                rule.ruleScopes.sumOf { it.length * 2L } +
                rule.contentScopes.sumOf { it.length * 2L } +
                rule.activeIncludes.sumOf { it.length * 2L }
        }

    return 32L +
        lineText.length * 2L +
        openRuleBytes(incoming.openRules) +
        openRuleBytes(outgoing.openRules) +
        relativeTokens.sumOf { token -> 24L + token.scopes.sumOf { scope -> scope.length * 2L } }
}

internal fun estimateBlockBytes(lines: List<TextMateLineCheckpoint>): Long =
    128L + lines.sumOf { it.estimatedBytes }

private fun fnv1a64OpenRules(rules: List<TextMateOpenRuleSnapshot>): Long {
    var hash = -0x340d631b7bdddcdbL // FNV offset basis
    fun mix(value: Long) {
        hash = hash xor value
        hash *= 0x100000001b3L
    }

    fun mix(value: String?) {
        if (value == null) {
            mix(0L)
            return
        }
        mix(value.length.toLong())
        value.forEach { character -> mix(character.code.toLong()) }
    }
    mix(rules.size.toLong())
    rules.forEach { rule ->
        mix(rule.grammarId.value.toLong())
        mix(rule.ruleId.value.toLong())
        mix(rule.endPattern)
        mix(rule.whilePattern)
        mix(rule.ruleScopes.size.toLong())
        rule.ruleScopes.forEach(::mix)
        mix(rule.contentScopes.size.toLong())
        rule.contentScopes.forEach(::mix)
        mix(rule.activeIncludes.size.toLong())
        rule.activeIncludes.forEach(::mix)
    }
    return hash
}
