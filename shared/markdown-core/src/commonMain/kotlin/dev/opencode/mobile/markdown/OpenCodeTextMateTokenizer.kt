package dev.opencode.mobile.markdown

import kotlin.time.TimeSource

interface OpenCodeTextMateRegexBackend {
    val name: String
        get() = "unknown"

    val isOnigurumaCompatible: Boolean
        get() = false

    fun find(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int = input.length,
        contiguousAnchor: Int? = null,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? =
        find(
            pattern = pattern,
            buffer = PreparedTextMateLineBuffer.prepare(input, metricsSink),
            startIndex = startIndex,
            endIndex = endIndex,
            contiguousAnchor = contiguousAnchor,
            metricsSink = metricsSink,
        )

    fun find(
        pattern: String,
        buffer: PreparedTextMateLineBuffer,
        startIndex: Int,
        endIndex: Int = buffer.length,
        contiguousAnchor: Int? = null,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch?
}

data class TextMateRegexMatch(
    val start: Int,
    val end: Int,
    val captures: List<TextMateRegexCapture> = emptyList(),
)

data class TextMateRegexCapture(
    val index: Int,
    val start: Int,
    val end: Int,
)

object KotlinRegexTextMateRegexBackend : OpenCodeTextMateRegexBackend {
    override val name: String = "kotlin-regex"

    // The iOS hybrid backend delegates here for all non-\\G expressions. TextMate
    // grammars retry the same patterns at every cursor position, so compiling them
    // per match turns long fenced blocks into a regex-compilation workload.
    private val regexCache = mutableMapOf<String, Regex?>()

    private fun compiledRegex(
        pattern: String,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): Regex? {
        regexCache[pattern]?.let { return it }
        if (regexCache.containsKey(pattern)) return null

        val startedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
        val compiled =
            try {
                Regex(pattern, setOf(RegexOption.MULTILINE))
            } catch (_: IllegalArgumentException) {
                null
            }
        regexCache[pattern] = compiled
        startedAt?.let {
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.RegexCompile,
                    outcome = if (compiled == null) TextMateMetricOutcome.Failure else TextMateMetricOutcome.Success,
                    durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                    backend = name,
                ),
            )
        }
        return compiled
    }

    override fun find(
        pattern: String,
        buffer: PreparedTextMateLineBuffer,
        startIndex: Int,
        endIndex: Int,
        contiguousAnchor: Int?,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? {
        val input = buffer.source
        val normalizedPattern = OpenCodeTextMateRegexLookbehindSupport.normalize(pattern)
        val effectivePattern =
            OpenCodeTextMateRegexContiguousAnchorSupport.effectivePattern(
                pattern = normalizedPattern,
                startIndex = startIndex,
                contiguousAnchor = contiguousAnchor,
            )
        val regex = compiledRegex(effectivePattern, metricsSink) ?: return null
        val match = regex.find(input, startIndex) ?: return null
        if (match.range.first > endIndex || match.range.last + 1 > endIndex) return null

        return OpenCodeTextMateRegexContiguousAnchorSupport.enforceContiguousAnchor(
            pattern = effectivePattern,
            match =
                TextMateRegexMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    captures =
                        match.groups
                            .mapIndexedNotNull { index, group ->
                                val range = group?.range ?: return@mapIndexedNotNull null
                                if (range.first < 0 || range.last < range.first) return@mapIndexedNotNull null
                                TextMateRegexCapture(
                                    index = index,
                                    start = range.first,
                                    end = range.last + 1,
                                )
                            },
                ),
            contiguousAnchor = contiguousAnchor,
        )
    }
}

data class TextMateLineState(
    val openRules: List<TextMateOpenRule> = emptyList(),
) {
    val isEmpty: Boolean = openRules.isEmpty()

    companion object {
        val Initial: TextMateLineState = TextMateLineState()
    }
}

data class TextMateOpenRule(
    val grammar: TextMateGrammar,
    val pattern: TextMatePattern,
    val endPattern: String?,
    val whilePattern: String?,
    val ruleScopes: List<String>,
    val contentScopes: List<String>,
    val activeIncludes: Set<String>,
)

data class TextMateLineTokenizationResult(
    val tokens: List<TextMateScopeToken>,
    val state: TextMateLineState,
)

data class TextMateLinesTokenizationResult(
    val tokens: List<TextMateScopeToken>,
    val state: TextMateLineState,
    val diagnostics: List<MarkdownDiagnostic>,
    val cancelled: Boolean = false,
)

class OpenCodeTextMateNativeTokenizer private constructor(
    private val regexBackend: OpenCodeTextMateRegexBackend = KotlinRegexTextMateRegexBackend,
    private val metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
    private val sharedState: SharedState,
    private val cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
) : OpenCodeTextMateTokenizer,
    TextMateCacheTrimmable {
    constructor(
        regexBackend: OpenCodeTextMateRegexBackend = KotlinRegexTextMateRegexBackend,
        metricsSink: OpenCodeTextMateMetricsSink = NoOpOpenCodeTextMateMetricsSink,
        cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
    ) : this(regexBackend, metricsSink, SharedState(cacheCoordinator), cacheCoordinator)

    // Job-local prepared buffers for the active physical line.
    private var activeLineBuffer: PreparedTextMateLineBuffer? = null
    private var activeBoundaryBuffer: PreparedTextMateLineBuffer? = null

    override fun trimCaches(level: MemoryPressure) {
        sharedState.trimCaches(level)
        (regexBackend as? TextMateCacheTrimmable)?.trimCaches(level)
    }

    override fun tokenize(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle?,
    ): TextMateTokenizationResult = tokenize(grammar, code, grammarBundle, metricsSink, shouldCancel = { false })

    override fun tokenize(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle?,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateTokenizationResult =
        OpenCodeTextMateNativeTokenizer(
            regexBackend = regexBackend,
            metricsSink = metricsSink,
            sharedState = sharedState,
            cacheCoordinator = cacheCoordinator,
        ).tokenize(grammar, code, grammarBundle)

    override fun tokenize(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle?,
        metricsSink: OpenCodeTextMateMetricsSink,
        shouldCancel: () -> Boolean,
    ): TextMateTokenizationResult {
        if (shouldCancel()) {
            return TextMateTokenizationResult(tokens = emptyList(), cancelled = true, diagnostics = backendDiagnostics())
        }
        if (code.isEmpty()) {
            return TextMateTokenizationResult(
                tokens = emptyList(),
                diagnostics = backendDiagnostics(),
            )
        }

        val startedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null
        var outcome = TextMateMetricOutcome.Success
        return try {
            val tokenization = tokenizeLines(grammar, code, grammarBundle, shouldCancel = shouldCancel)
            TextMateTokenizationResult(
                tokens = tokenization.tokens,
                diagnostics = tokenization.diagnostics,
                cancelled = tokenization.cancelled,
            )
        } catch (failure: Throwable) {
            outcome = TextMateMetricOutcome.Failure
            throw failure
        } finally {
            startedAt?.let {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.Tokenize,
                        outcome = outcome,
                        durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                        backend = regexBackend.name,
                        sourceCharacters = code.length,
                        sourceLines = code.count { character -> character == '\n' } + 1,
                    ),
                )
            }
        }
    }

    fun tokenizeLines(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle? = null,
        initialState: TextMateLineState = TextMateLineState.Initial,
        shouldCancel: () -> Boolean = { false },
    ): TextMateLinesTokenizationResult {
        val tokens = mutableListOf<TextMateScopeToken>()
        var state = initialState
        var lineStart = 0

        while (lineStart <= code.length) {
            if (shouldCancel()) {
                return TextMateLinesTokenizationResult(
                    tokens = emptyList(),
                    state = state,
                    diagnostics = backendDiagnostics(),
                    cancelled = true,
                )
            }
            val lineEnd = code.indexOf('\n', lineStart).let { index -> if (index == -1) code.length else index }
            val line = code.substring(lineStart, lineEnd)
            val nextLineStart = lineEnd + 1
            val nextLine =
                if (nextLineStart < code.length) {
                    val nextLineEnd =
                        code.indexOf('\n', nextLineStart).let { index ->
                            if (index == -1) code.length else index
                        }
                    code.substring(nextLineStart, nextLineEnd)
                } else {
                    ""
                }
            val boundarySuffix = "\n$nextLine"
            val lineResult =
                tokenizeLine(
                    grammar = grammar,
                    grammarBundle = grammarBundle,
                    line = line,
                    lineOffset = lineStart,
                    initialState = state,
                    boundarySuffix = boundarySuffix,
                )
            tokens += lineResult.tokens
            state = lineResult.state

            if (lineEnd == code.length) break
            lineStart = lineEnd + 1
        }

        return TextMateLinesTokenizationResult(
            tokens = normalize(tokens, code.length),
            state = state,
            diagnostics = backendDiagnostics(),
        )
    }

    fun tokenizeLinesIncremental(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle? = null,
        plan: TextMateIncrementalReusePlan,
        session: TextMateIncrementalHighlightSession,
        shouldCancel: () -> Boolean = { false },
        metricsSink: OpenCodeTextMateMetricsSink = this.metricsSink,
    ): TextMateLinesTokenizationResult {
        if (metricsSink !== this.metricsSink) {
            return OpenCodeTextMateNativeTokenizer(
                regexBackend = regexBackend,
                metricsSink = metricsSink,
                sharedState = sharedState,
                cacheCoordinator = cacheCoordinator,
            ).tokenizeLinesIncremental(
                grammar = grammar,
                code = code,
                grammarBundle = grammarBundle,
                plan = plan,
                session = session,
                shouldCancel = shouldCancel,
                metricsSink = metricsSink,
            )
        }
        if (code.isEmpty()) {
            session.mode = TextMateIncrementalMode.ExactSource
            session.reusedLineCount = 0
            session.retokenizedLineCount = 0
            session.suffixReusedLineCount = 0
            session.produced =
                IncrementalBlockState(
                    identity = session.identity,
                    sourceHash32 = 0,
                    sourceLength = 0,
                    source = code,
                    lines = emptyList(),
                    intent = session.intent,
                    revision = session.revision,
                    estimatedBytes = 128L,
                )
            return TextMateLinesTokenizationResult(
                tokens = emptyList(),
                state = TextMateLineState.Initial,
                diagnostics = backendDiagnostics(),
            )
        }

        val lines = TextMatePhysicalLines.split(code)
        val offsets = TextMatePhysicalLines.lineStartOffsets(lines)
        val checkpoints = mutableListOf<TextMateLineCheckpoint>()
        val tokens = mutableListOf<TextMateScopeToken>()

        if (plan.mode == TextMateIncrementalMode.ExactSource && plan.previous != null) {
            plan.previous.lines.forEachIndexed { index, checkpoint ->
                val lineOffset = offsets.getOrElse(index) { 0 }
                tokens +=
                    checkpoint.relativeTokens.map { token ->
                        token.copy(start = token.start + lineOffset, end = token.end + lineOffset)
                    }
            }
            session.mode = TextMateIncrementalMode.ExactSource
            session.reusedLineCount = plan.previous.lines.size
            session.retokenizedLineCount = 0
            session.suffixReusedLineCount = 0
            session.produced = plan.previous.copy(revision = session.revision, intent = session.intent)
            if (metricsSink.isEnabled) {
                metricsSink.record(
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.IncrementalReuse,
                        outcome = TextMateMetricOutcome.CacheHit,
                        reusedLineCount = session.reusedLineCount,
                        retokenizedLineCount = 0,
                        suffixReusedLineCount = 0,
                        sourceLines = lines.size,
                    ),
                )
            }
            return TextMateLinesTokenizationResult(
                tokens = normalize(tokens, code.length),
                state =
                    restoreLineState(plan.previous.lines.lastOrNull()?.outgoing ?: TextMateLineStateSnapshot.Initial, grammar, grammarBundle)
                        ?: TextMateLineState.Initial,
                diagnostics = backendDiagnostics(),
            )
        }

        checkpoints += plan.reusedPrefix
        plan.reusedPrefix.forEachIndexed { index, checkpoint ->
            val lineOffset = offsets.getOrElse(checkpoint.lineIndex) { offsets.getOrElse(index) { 0 } }
            tokens +=
                checkpoint.relativeTokens.map { token ->
                    token.copy(start = token.start + lineOffset, end = token.end + lineOffset)
                }
        }

        var state =
            restoreLineState(plan.incomingAtResume, grammar, grammarBundle)
                ?: TextMateLineState.Initial
        var lineIndex = plan.resumeLine
        var reusedSuffix = 0
        var retokenized = 0
        val tokenizeStartedAt = if (metricsSink.isEnabled) TimeSource.Monotonic.markNow() else null

        while (lineIndex < lines.size) {
            if (shouldCancel()) {
                return TextMateLinesTokenizationResult(
                    tokens = emptyList(),
                    state = state,
                    diagnostics = backendDiagnostics(),
                    cancelled = true,
                )
            }

            if (session.suffixConvergenceEnabled && plan.previous != null) {
                val incomingSnapshot =
                    snapshotLineState(state, grammar, grammarBundle)
                val previousLine = plan.previous.lines.getOrNull(lineIndex)
                if (
                    incomingSnapshot != null &&
                    previousLine != null &&
                    previousLine.lineText == lines[lineIndex] &&
                    previousLine.incomingFingerprint == incomingSnapshot.fingerprint()
                ) {
                    val suffix = plan.previous.lines.drop(lineIndex)
                    var converged = true
                    for (candidate in suffix) {
                        if (candidate.lineIndex >= lines.size || candidate.lineText != lines[candidate.lineIndex]) {
                            converged = false
                            break
                        }
                    }
                    if (converged && suffix.size == lines.size - lineIndex) {
                        suffix.forEach { checkpoint ->
                            val lineOffset = offsets[checkpoint.lineIndex]
                            tokens +=
                                checkpoint.relativeTokens.map { token ->
                                    token.copy(start = token.start + lineOffset, end = token.end + lineOffset)
                                }
                            checkpoints += checkpoint
                        }
                        reusedSuffix = suffix.size
                        state =
                            restoreLineState(suffix.last().outgoing, grammar, grammarBundle)
                                ?: state
                        break
                    }
                }
            }

            val line = lines[lineIndex]
            val lineOffset = offsets[lineIndex]
            val nextLine = lines.getOrElse(lineIndex + 1) { "" }
            val boundarySuffix = "\n$nextLine"
            val incomingSnapshot =
                snapshotLineState(state, grammar, grammarBundle)
                    ?: return fallbackFullTokenize(grammar, code, grammarBundle, session, shouldCancel)
            val lineResult =
                tokenizeLine(
                    grammar = grammar,
                    grammarBundle = grammarBundle,
                    line = line,
                    lineOffset = lineOffset,
                    initialState = state,
                    boundarySuffix = boundarySuffix,
                )
            val relativeTokens =
                lineResult.tokens.map { token ->
                    token.copy(start = token.start - lineOffset, end = token.end - lineOffset)
                }
            val outgoingSnapshot =
                snapshotLineState(lineResult.state, grammar, grammarBundle)
                    ?: return fallbackFullTokenize(grammar, code, grammarBundle, session, shouldCancel)
            val checkpoint =
                TextMateLineCheckpoint(
                    lineIndex = lineIndex,
                    lineText = line,
                    lineTextHash32 = line.hashCode(),
                    incoming = incomingSnapshot,
                    incomingFingerprint = incomingSnapshot.fingerprint(),
                    outgoing = outgoingSnapshot,
                    outgoingFingerprint = outgoingSnapshot.fingerprint(),
                    relativeTokens = relativeTokens,
                    estimatedBytes =
                        estimateCheckpointBytes(
                            lineText = line,
                            incoming = incomingSnapshot,
                            outgoing = outgoingSnapshot,
                            relativeTokens = relativeTokens,
                        ),
                )
            checkpoints += checkpoint
            tokens += lineResult.tokens
            state = lineResult.state
            retokenized += 1
            lineIndex += 1
        }
        tokenizeStartedAt?.let {
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.Tokenize,
                    outcome = TextMateMetricOutcome.Success,
                    durationNanoseconds = it.elapsedNow().inWholeNanoseconds,
                    backend = regexBackend.name,
                    sourceCharacters = code.length,
                    sourceLines = lines.size,
                    reusedLineCount = plan.reusedPrefix.size + reusedSuffix,
                    retokenizedLineCount = retokenized,
                    suffixReusedLineCount = reusedSuffix,
                ),
            )
        }

        val reusedPrefixCount = plan.reusedPrefix.size
        session.mode =
            when {
                reusedSuffix > 0 && reusedPrefixCount > 0 -> TextMateIncrementalMode.PrefixSuffix
                reusedPrefixCount > 0 -> TextMateIncrementalMode.Prefix
                else -> TextMateIncrementalMode.None
            }
        session.reusedLineCount = reusedPrefixCount + reusedSuffix
        session.retokenizedLineCount = retokenized
        session.suffixReusedLineCount = reusedSuffix
        session.produced =
            IncrementalBlockState(
                identity = session.identity,
                sourceHash32 = code.hashCode(),
                sourceLength = code.length,
                source = code,
                lines = checkpoints,
                intent = session.intent,
                revision = session.revision,
                estimatedBytes = estimateBlockBytes(checkpoints),
            )
        if (metricsSink.isEnabled) {
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.IncrementalReuse,
                    outcome =
                        if (session.reusedLineCount > 0) {
                            TextMateMetricOutcome.CacheHit
                        } else {
                            TextMateMetricOutcome.CacheMiss
                        },
                    reusedLineCount = session.reusedLineCount,
                    retokenizedLineCount = session.retokenizedLineCount,
                    suffixReusedLineCount = session.suffixReusedLineCount,
                    sourceLines = lines.size,
                ),
            )
        }
        return TextMateLinesTokenizationResult(
            tokens = normalize(tokens, code.length),
            state = state,
            diagnostics = backendDiagnostics(),
        )
    }

    private fun fallbackFullTokenize(
        grammar: TextMateGrammarPayload,
        code: String,
        grammarBundle: TextMateGrammarBundle?,
        session: TextMateIncrementalHighlightSession,
        shouldCancel: () -> Boolean,
    ): TextMateLinesTokenizationResult {
        val full =
            tokenizeLines(
                grammar = grammar,
                code = code,
                grammarBundle = grammarBundle,
                shouldCancel = shouldCancel,
            )
        session.mode = TextMateIncrementalMode.None
        session.reusedLineCount = 0
        session.retokenizedLineCount = TextMatePhysicalLines.split(code).size
        session.suffixReusedLineCount = 0
        session.produced = null
        return full
    }

    private fun snapshotLineState(
        state: TextMateLineState,
        primary: TextMateGrammarPayload,
        grammarBundle: TextMateGrammarBundle?,
    ): TextMateLineStateSnapshot? =
        TextMateLineStateCodec.snapshot(
            state = state,
            resolveGrammarId = { grammar ->
                when {
                    grammar === primary.grammar -> primary.grammarId
                    else -> grammarBundle?.grammarIdFor(grammar)
                }
            },
            resolveRuleId = { grammar, pattern ->
                val program =
                    when {
                        grammar === primary.grammar -> primary.program
                        else -> grammarBundle?.grammarIdFor(grammar)?.let { id -> grammarBundle.grammarForId(id)?.program }
                    }
                program?.ruleIdOf(pattern)
            },
        )

    private fun restoreLineState(
        snapshot: TextMateLineStateSnapshot,
        primary: TextMateGrammarPayload,
        grammarBundle: TextMateGrammarBundle?,
    ): TextMateLineState? =
        TextMateLineStateCodec.restore(
            snapshot = snapshot,
            resolveGrammar = { grammarId ->
                when {
                    grammarId == primary.grammarId -> primary.grammar
                    else -> grammarBundle?.grammarForId(grammarId)?.grammar
                }
            },
            resolvePattern = { grammarId, ruleId ->
                val program =
                    when {
                        grammarId == primary.grammarId -> primary.program
                        else -> grammarBundle?.grammarForId(grammarId)?.program
                    }
                program?.pattern(ruleId)
            },
        )

    fun tokenizeLine(
        grammar: TextMateGrammarPayload,
        line: String,
        grammarBundle: TextMateGrammarBundle? = null,
        lineOffset: Int = 0,
        initialState: TextMateLineState = TextMateLineState.Initial,
        boundarySuffix: String = "\n",
    ): TextMateLineTokenizationResult {
        val previousLine = activeLineBuffer
        val previousBoundary = activeBoundaryBuffer
        activeLineBuffer = PreparedTextMateLineBuffer.prepare(line, metricsSink)
        activeBoundaryBuffer =
            if (boundarySuffix.isEmpty()) {
                null
            } else {
                PreparedTextMateLineBuffer.prepare(line + boundarySuffix, metricsSink)
            }
        try {
            return tokenizeLineWithActiveBuffers(
                grammar = grammar,
                line = line,
                grammarBundle = grammarBundle,
                lineOffset = lineOffset,
                initialState = initialState,
                boundarySuffix = boundarySuffix,
            )
        } finally {
            activeLineBuffer = previousLine
            activeBoundaryBuffer = previousBoundary
        }
    }

    private fun tokenizeLineWithActiveBuffers(
        grammar: TextMateGrammarPayload,
        line: String,
        grammarBundle: TextMateGrammarBundle?,
        lineOffset: Int,
        initialState: TextMateLineState,
        boundarySuffix: String,
    ): TextMateLineTokenizationResult {
        val tokens = mutableListOf<TextMateScopeToken>()
        val lineEnd = lineOffset + line.length
        var cursor = lineOffset
        val openRules = initialState.openRules.toMutableList()

        while (cursor < lineEnd && openRules.isNotEmpty()) {
            val openRule = openRules.removeAt(openRules.lastIndex)
            if (openRule.whilePattern != null) {
                val whileMatch =
                    measuredRegexFind(openRule.whilePattern, line, 0, line.length)
                        ?.takeIf { match -> match.start == 0 }
                if (whileMatch == null) {
                    continue
                }

                tokens +=
                    tokensForCaptures(
                        captures = openRule.pattern.whileCaptures,
                        match = whileMatch,
                        code = line,
                        baseScopes = openRule.ruleScopes,
                        grammar = openRule.grammar,
                        grammarBundle = grammarBundle,
                        activeIncludes = openRule.activeIncludes,
                        baseGrammar = grammar.grammar,
                        offset = lineOffset,
                    )
                val contentStart = maxOf(cursor, whileMatch.end + lineOffset)
                val nestedTokens =
                    tokenizeRange(
                        grammar = openRule.grammar,
                        grammarBundle = grammarBundle,
                        patterns = openRule.pattern.patterns,
                        code = line,
                        startIndex = contentStart - lineOffset,
                        endIndex = line.length,
                        baseScopes = openRule.contentScopes,
                        activeIncludes = openRule.activeIncludes,
                        baseGrammar = grammar.grammar,
                        offset = lineOffset,
                    )
                tokens += nestedTokens
                tokens += scopedHoles(contentStart, lineEnd, nestedTokens, openRule.contentScopes)
                openRules += openRule
                cursor = lineEnd
                break
            }

            val resolvedEndPattern = openRule.endPattern ?: continue
            val delimitedContent =
                tokenizeUntilEndPattern(
                    grammar = openRule.grammar,
                    grammarBundle = grammarBundle,
                    patterns = openRule.pattern.patterns,
                    code = line,
                    startIndex = cursor - lineOffset,
                    endIndex = line.length,
                    baseScopes = openRule.contentScopes,
                    activeIncludes = openRule.activeIncludes,
                    baseGrammar = grammar.grammar,
                    offset = lineOffset,
                    endPattern = resolvedEndPattern,
                    applyEndPatternLast = openRule.pattern.shouldApplyEndPatternLast,
                )
            val contentStart = cursor
            val contentEnd = delimitedContent.endMatch?.start?.plus(lineOffset) ?: lineEnd
            tokens += delimitedContent.tokens
            tokens += scopedHoles(contentStart, contentEnd, delimitedContent.tokens, openRule.contentScopes)
            val endMatch = delimitedContent.endMatch

            if (endMatch == null) {
                openRules += openRule
                openRules += delimitedContent.openRules
                return TextMateLineTokenizationResult(
                    tokens = normalize(tokens, lineEnd),
                    state = TextMateLineState(openRules),
                )
            }

            tokens +=
                tokensForCaptures(
                    captures = openRule.pattern.endCaptures,
                    match = endMatch,
                    code = line,
                    baseScopes = openRule.ruleScopes,
                    grammar = openRule.grammar,
                    grammarBundle = grammarBundle,
                    activeIncludes = openRule.activeIncludes,
                    baseGrammar = grammar.grammar,
                    offset = lineOffset,
                )
            cursor = advancePastEndMatch(endMatch, lineOffset, cursor)
        }

        if (cursor < lineEnd) {
            val result =
                tokenizeRangeWithState(
                    grammar = grammar.grammar,
                    grammarBundle = grammarBundle,
                    patterns = grammar.grammar.patterns,
                    code = line,
                    startIndex = cursor - lineOffset,
                    endIndex = line.length,
                    baseScopes = listOf(grammar.scopeName),
                    activeIncludes = emptySet(),
                    baseGrammar = grammar.grammar,
                    offset = lineOffset,
                )
            tokens += result.tokens
            openRules += result.openRules
        }

        if (openRules.isNotEmpty()) {
            val stillOpen = mutableListOf<TextMateOpenRule>()
            while (openRules.isNotEmpty()) {
                val openRule = openRules.removeAt(openRules.lastIndex)
                val endPattern = openRule.endPattern
                val boundaryMatch =
                    endPattern?.let { pattern ->
                        findEndPattern(
                            endPattern = pattern,
                            code = line,
                            startIndex = (cursor - lineOffset).coerceIn(0, line.length),
                            endIndex = line.length,
                            boundarySuffix = boundarySuffix,
                        )
                    }
                if (boundaryMatch != null) {
                    tokens +=
                        tokensForCaptures(
                            captures = openRule.pattern.endCaptures,
                            match = boundaryMatch,
                            code = line,
                            baseScopes = openRule.ruleScopes,
                            grammar = openRule.grammar,
                            grammarBundle = grammarBundle,
                            activeIncludes = openRule.activeIncludes,
                            baseGrammar = grammar.grammar,
                            offset = lineOffset,
                        )
                    cursor = advancePastEndMatch(boundaryMatch, lineOffset, cursor).coerceAtMost(lineEnd)
                } else {
                    stillOpen += openRule
                }
            }
            openRules.clear()
            openRules += stillOpen.asReversed()
        }

        return TextMateLineTokenizationResult(
            tokens = normalize(tokens, lineEnd),
            state = TextMateLineState(openRules),
        )
    }

    private fun tokenizeRange(
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        patterns: List<TextMatePattern>,
        code: String,
        startIndex: Int,
        endIndex: Int,
        baseScopes: List<String>,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int = 0,
        captureDepth: Int = 0,
    ): List<TextMateScopeToken> =
        tokenizeRangeWithState(
            grammar = grammar,
            grammarBundle = grammarBundle,
            patterns = patterns,
            code = code,
            startIndex = startIndex,
            endIndex = endIndex,
            baseScopes = baseScopes,
            activeIncludes = activeIncludes,
            baseGrammar = baseGrammar,
            offset = offset,
            captureDepth = captureDepth,
        ).tokens

    private fun tokenizeRangeWithState(
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        patterns: List<TextMatePattern>,
        code: String,
        startIndex: Int,
        endIndex: Int,
        baseScopes: List<String>,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int = 0,
        captureDepth: Int = 0,
    ): RangeTokenizationResult {
        if (captureDepth > MAX_CAPTURE_TOKENIZE_DEPTH) {
            return RangeTokenizationResult(tokens = emptyList())
        }

        val rules =
            resolveRules(
                grammar = grammar,
                grammarBundle = grammarBundle,
                patterns = patterns,
                activeIncludes = activeIncludes,
                baseGrammar = baseGrammar,
                baseScopes = baseScopes,
            )
        val tokens = mutableListOf<TextMateScopeToken>()
        var cursor = startIndex
        var contiguousAnchor = startIndex
        var emptyContiguousBeginAt: Int? = null

        while (cursor < endIndex) {
            val next =
                rules
                    .mapNotNull { rule ->
                        if (rule.pattern.begin == "\\G" && emptyContiguousBeginAt == cursor) {
                            return@mapNotNull null
                        }
                        rule.findNext(
                            code,
                            cursor,
                            endIndex,
                            regexBackend,
                            metricsSink,
                            contiguousAnchor,
                            bufferFor(code),
                        )
                    }
                    .minWithOrNull(
                        compareBy<TextMateRuleMatch> { it.match.start }
                            .thenBy { it.rule.order },
                    ) ?: break

            if (next.match.start > cursor) {
                cursor = next.match.start
            }

            val rule = next.rule.pattern
            val ruleGrammar = next.rule.grammar
            val ruleBaseScopes = baseScopes + next.rule.scopePrefix
            if (rule.match != null) {
                tokens +=
                    tokensForMatch(
                        pattern = rule,
                        match = next.match,
                        code = code,
                        baseScopes = ruleBaseScopes,
                        grammar = ruleGrammar,
                        grammarBundle = grammarBundle,
                        activeIncludes = activeIncludes,
                        baseGrammar = baseGrammar,
                        offset = offset,
                        captureDepth = captureDepth,
                    )
                cursor = next.match.end.coerceAtLeast(cursor + 1)
                contiguousAnchor = next.match.end
                emptyContiguousBeginAt = null
            } else if (rule.begin != null) {
                val result =
                    tokensForBeginEndRule(
                        grammar = ruleGrammar,
                        grammarBundle = grammarBundle,
                        pattern = rule,
                        beginMatch = next.match,
                        code = code,
                        endIndex = endIndex,
                        baseScopes = ruleBaseScopes,
                        activeIncludes = activeIncludes,
                        baseGrammar = baseGrammar,
                        offset = offset,
                        captureDepth = captureDepth,
                        initialContiguousAnchor = contiguousAnchor,
                    )
                tokens += result.tokens
                if (result.openRule != null) {
                    return RangeTokenizationResult(tokens = tokens, openRules = listOf(result.openRule))
                }
                if (rule.begin == "\\G" && result.tokens.isEmpty() && result.nextIndex <= cursor) {
                    emptyContiguousBeginAt = cursor
                    continue
                }
                cursor = result.nextIndex.coerceAtLeast(cursor + 1)
                contiguousAnchor = result.nextIndex
                emptyContiguousBeginAt = null
            } else {
                cursor += 1
            }
        }

        return RangeTokenizationResult(tokens = tokens)
    }

    private fun tokensForBeginEndRule(
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        pattern: TextMatePattern,
        beginMatch: TextMateRegexMatch,
        code: String,
        endIndex: Int,
        baseScopes: List<String>,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int,
        captureDepth: Int = 0,
        initialContiguousAnchor: Int? = null,
    ): BeginEndTokenizationResult {
        if (captureDepth > MAX_CAPTURE_TOKENIZE_DEPTH) {
            return BeginEndTokenizationResult(
                tokens = emptyList(),
                nextIndex = beginMatch.end,
            )
        }

        val ruleScopes = baseScopes.withScope(pattern.name)
        val tokens = mutableListOf<TextMateScopeToken>()
        val beginTokens =
            tokensForDelimitedCaptures(
                captures = pattern.beginCaptures.ifEmpty { pattern.captures },
                match = beginMatch,
                code = code,
                scopes = ruleScopes,
                grammar = grammar,
                grammarBundle = grammarBundle,
                activeIncludes = activeIncludes,
                baseGrammar = baseGrammar,
                offset = offset,
                captureDepth = captureDepth,
            )
        tokens += beginTokens
        val resolvedEndPattern = pattern.end?.let { endPattern -> resolveBackReferences(endPattern, beginMatch, code) }
        val resolvedWhilePattern =
            pattern.whilePattern?.let { whilePattern -> resolveBackReferences(whilePattern, beginMatch, code) }

        val delimitedContent =
            if (resolvedWhilePattern == null && resolvedEndPattern != null) {
                tokenizeUntilEndPattern(
                    grammar = grammar,
                    grammarBundle = grammarBundle,
                    patterns = pattern.patterns,
                    code = code,
                    startIndex = beginMatch.end,
                    endIndex = endIndex,
                    baseScopes = ruleScopes.withScope(pattern.contentName),
                    activeIncludes = activeIncludes,
                    baseGrammar = baseGrammar,
                    offset = offset,
                    endPattern = resolvedEndPattern,
                    applyEndPatternLast = pattern.shouldApplyEndPatternLast,
                    captureDepth = captureDepth + 1,
                    initialContiguousAnchor = beginMatch.end,
                )
            } else {
                null
            }
        val endMatch =
            delimitedContent?.endMatch
                ?: if (resolvedWhilePattern == null) {
                    resolvedEndPattern?.let { endPattern ->
                        measuredRegexFind(endPattern, code, beginMatch.end, endIndex)
                    }
                } else {
                    null
                }
        val contentStart = beginMatch.end
        val contentEnd = endMatch?.start ?: endIndex
        val contentScopes = ruleScopes.withScope(pattern.contentName)
        val nestedTokens =
            if (delimitedContent != null) {
                delimitedContent.tokens
            } else if (contentStart < contentEnd) {
                tokenizeRange(
                    grammar = grammar,
                    grammarBundle = grammarBundle,
                    patterns = pattern.patterns,
                    code = code,
                    startIndex = contentStart,
                    endIndex = contentEnd,
                    baseScopes = contentScopes,
                    activeIncludes = activeIncludes,
                    baseGrammar = baseGrammar,
                    offset = offset,
                    captureDepth = captureDepth,
                )
            } else {
                emptyList()
            }

        tokens += nestedTokens
        tokens += scopedHoles(contentStart + offset, contentEnd + offset, nestedTokens, contentScopes)

        if (endMatch != null) {
            tokens +=
                tokensForDelimitedCaptures(
                    captures = pattern.endCaptures.ifEmpty { pattern.captures },
                    match = endMatch,
                    code = code,
                    scopes = ruleScopes,
                    grammar = grammar,
                    grammarBundle = grammarBundle,
                    activeIncludes = activeIncludes,
                    baseGrammar = baseGrammar,
                    offset = offset,
                    captureDepth = captureDepth,
                )
        }

        return BeginEndTokenizationResult(
            tokens = tokens,
            nextIndex = endMatch?.end ?: endIndex,
            openRule =
                delimitedContent?.openRules?.firstOrNull()
                    ?: if (resolvedWhilePattern != null || (resolvedEndPattern != null && endMatch == null)) {
                        TextMateOpenRule(
                            grammar = grammar,
                            pattern = pattern,
                            endPattern = resolvedEndPattern,
                            whilePattern = resolvedWhilePattern,
                            ruleScopes = ruleScopes,
                            contentScopes = contentScopes,
                            activeIncludes = activeIncludes,
                        )
                    } else {
                        null
                    },
        )
    }

    private fun resolveRules(
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        patterns: List<TextMatePattern>,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        baseScopes: List<String>,
    ): List<ResolvedTextMateRule> {
        val grammarId =
            grammarBundle?.grammars?.firstOrNull { it.scopeName == grammar.scopeName }?.grammarId?.value
                ?: grammar.scopeName.hashCode()
        val cacheKey =
            ResolvedRulesCacheKey(
                grammarId = grammarId,
                grammarScope = grammar.scopeName,
                patternsSignature = patternsSignature(patterns),
                activeIncludes = activeIncludes,
                baseGrammarScope = baseGrammar.scopeName,
                baseScopes = baseScopes,
            )
        sharedState.resolvedRulesCache[cacheKey]?.let { cached ->
            sharedState.touchRulePlan(cacheKey)
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.RuleResolve,
                    outcome = TextMateMetricOutcome.CacheHit,
                    backend = regexBackend.name,
                ),
            )
            return cached
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val rules = mutableListOf<ResolvedTextMateRule>()
        var order = 0

        fun addPatterns(
            patternGrammar: TextMateGrammar,
            candidates: List<TextMatePattern>,
            includeStack: Set<String>,
            scopePrefix: List<String>,
        ) {
            candidates.forEach { pattern ->
                val include = pattern.include
                if (include != null) {
                    val included = resolveInclude(patternGrammar, grammarBundle, baseGrammar, include) ?: return@forEach
                    val includeKey = "${patternGrammar.scopeName}:$include"
                    if (includeKey in includeStack) return@forEach
                    addPatterns(
                        patternGrammar = included.grammar,
                        candidates = listOf(included.pattern),
                        includeStack = includeStack + includeKey,
                        scopePrefix = scopePrefix + included.scopePrefix,
                    )
                } else if (pattern.match != null || pattern.begin != null) {
                    rules +=
                        ResolvedTextMateRule(
                            grammar = patternGrammar,
                            pattern = pattern,
                            order = order,
                            scopePrefix = scopePrefix,
                        )
                    order += 1
                } else if (pattern.patterns.isNotEmpty()) {
                    addPatterns(
                        patternGrammar = patternGrammar,
                        candidates = pattern.patterns,
                        includeStack = includeStack,
                        scopePrefix = scopePrefix.withScope(pattern.name),
                    )
                } else {
                    return@forEach
                }
            }
        }

        matchingInjections(grammar, baseScopes, InjectionPriority.Left).forEach { injection ->
            addPatterns(grammar, listOf(injection), activeIncludes, emptyList())
        }
        addPatterns(grammar, patterns, activeIncludes, emptyList())
        matchingInjections(grammar, baseScopes, InjectionPriority.Right).forEach { injection ->
            addPatterns(grammar, listOf(injection), activeIncludes, emptyList())
        }
        return rules.also {
            sharedState.resolvedRulesCache[cacheKey] = it
            sharedState.rememberRulePlan(
                key = cacheKey,
                estimatedBytes = 64L + it.size * 48L + cacheKey.patternsSignature.length * 2L,
            )
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.RuleResolve,
                    outcome = TextMateMetricOutcome.CacheMiss,
                    durationNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                    backend = regexBackend.name,
                ),
            )
        }
    }

    private fun patternsSignature(patterns: List<TextMatePattern>): String =
        buildString {
            append(patterns.size)
            patterns.forEach { pattern ->
                append('|')
                append(pattern.match ?: "")
                append('#')
                append(pattern.begin ?: "")
                append('#')
                append(pattern.include ?: "")
                append('#')
                append(pattern.name ?: "")
                append('#')
                append(pattern.contentName ?: "")
                append('#')
                append(pattern.patterns.size)
            }
        }

    private fun matchingInjections(
        grammar: TextMateGrammar,
        baseScopes: List<String>,
        priority: InjectionPriority,
    ): List<TextMatePattern> =
        grammar.injections
            .filter { (selector, _) -> injectionPriority(selector) == priority && selectorMatches(selector, baseScopes) }
            .map { (_, pattern) -> pattern }

    private fun injectionPriority(selector: String): InjectionPriority =
        when {
            selector.trimStart().startsWith("L:") -> InjectionPriority.Left
            else -> InjectionPriority.Right
        }

    private fun selectorMatches(
        rawSelector: String,
        scopes: List<String>,
    ): Boolean {
        val selector = rawSelector.trim().removePrefix("L:").removePrefix("R:").trim()
        val parts = selector.split(" - ", limit = 2)
        val positive = parts.firstOrNull().orEmpty().trim()
        val negative = parts.getOrNull(1).orEmpty().trim().removeSurrounding("(", ")")

        if (positive.isNotEmpty() && !selectorExpressionMatches(positive, scopes)) {
            return false
        }

        return negative
            .split(",")
            .map { excluded -> excluded.trim() }
            .filter { excluded -> excluded.isNotEmpty() }
            .none { excluded -> selectorExpressionMatches(excluded, scopes) }
    }

    private fun selectorExpressionMatches(
        expression: String,
        scopes: List<String>,
    ): Boolean =
        expression
            .split(Regex("\\s+"))
            .filter { term -> term.isNotBlank() }
            .all { term -> scopes.any { scope -> scopeMatchesSelector(scope, term) } }

    private fun scopeMatchesSelector(
        scope: String,
        selector: String,
    ): Boolean {
        val selectorParts = selector.split(".")
        val scopeParts = scope.split(".")
        if (selectorParts.size > scopeParts.size) return false

        return selectorParts.withIndex().all { (index, selectorPart) ->
            selectorPart == "*" || selectorPart == scopeParts[index]
        }
    }

    private fun tokenizeUntilEndPattern(
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        patterns: List<TextMatePattern>,
        code: String,
        startIndex: Int,
        endIndex: Int,
        baseScopes: List<String>,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int,
        endPattern: String,
        applyEndPatternLast: Boolean,
        captureDepth: Int = 0,
        initialContiguousAnchor: Int? = null,
    ): DelimitedContentTokenizationResult {
        if (captureDepth > MAX_CAPTURE_TOKENIZE_DEPTH) {
            return DelimitedContentTokenizationResult(tokens = emptyList(), endMatch = null)
        }

        val rules =
            resolveRules(
                grammar = grammar,
                grammarBundle = grammarBundle,
                patterns = patterns,
                activeIncludes = activeIncludes,
                baseGrammar = baseGrammar,
                baseScopes = baseScopes,
            )
        val tokens = mutableListOf<TextMateScopeToken>()
        var cursor = startIndex
        var contiguousAnchor = initialContiguousAnchor ?: startIndex
        var emptyContiguousBeginAt: Int? = null

        while (cursor < endIndex) {
            if (endPattern == NEGATIVE_CONTIGUOUS_ANCHOR_END_PATTERN && cursor > startIndex) {
                return DelimitedContentTokenizationResult(
                    tokens = tokens,
                    endMatch = TextMateRegexMatch(cursor, cursor),
                )
            }

            val endMatch = findDelimitedEndPattern(endPattern, code, cursor, endIndex)
            val next =
                rules
                    .mapNotNull { rule ->
                        if (rule.pattern.begin == "\\G" && emptyContiguousBeginAt == cursor) {
                            return@mapNotNull null
                        }
                        rule.findNext(
                            code,
                            cursor,
                            endIndex,
                            regexBackend,
                            metricsSink,
                            contiguousAnchor,
                            bufferFor(code),
                        )
                    }
                    .minWithOrNull(
                        compareBy<TextMateRuleMatch> { it.match.start }
                            .thenBy { it.rule.order },
                    )

            if (next == null) {
                if (endMatch != null && endMatch.start == cursor) {
                    return DelimitedContentTokenizationResult(tokens = tokens, endMatch = endMatch)
                }
                cursor += 1
                continue
            }

            if (endMatch != null && endMatch.startsBefore(next.match, applyEndPatternLast)) {
                return DelimitedContentTokenizationResult(tokens = tokens, endMatch = endMatch)
            }

            if (next.match.start > cursor) {
                cursor = next.match.start
            }

            val rule = next.rule.pattern
            val ruleGrammar = next.rule.grammar
            val ruleBaseScopes = baseScopes + next.rule.scopePrefix
            if (rule.match != null) {
                tokens +=
                    tokensForMatch(
                        pattern = rule,
                        match = next.match,
                        code = code,
                        baseScopes = ruleBaseScopes,
                        grammar = ruleGrammar,
                        grammarBundle = grammarBundle,
                        activeIncludes = activeIncludes,
                        baseGrammar = baseGrammar,
                        offset = offset,
                        captureDepth = captureDepth,
                    )
                cursor = next.match.end.coerceAtLeast(cursor + 1)
                contiguousAnchor = next.match.end
                emptyContiguousBeginAt = null
            } else if (rule.begin != null) {
                val result =
                    tokensForBeginEndRule(
                        grammar = ruleGrammar,
                        grammarBundle = grammarBundle,
                        pattern = rule,
                        beginMatch = next.match,
                        code = code,
                        endIndex = endIndex,
                        baseScopes = ruleBaseScopes,
                        activeIncludes = activeIncludes,
                        baseGrammar = baseGrammar,
                        offset = offset,
                        captureDepth = captureDepth + 1,
                        initialContiguousAnchor = contiguousAnchor,
                    )
                tokens += result.tokens
                if (result.openRule != null) {
                    return DelimitedContentTokenizationResult(
                        tokens = tokens,
                        endMatch = null,
                        openRules = listOf(result.openRule),
                    )
                }
                if (rule.begin == "\\G" && result.tokens.isEmpty() && result.nextIndex <= cursor) {
                    emptyContiguousBeginAt = cursor
                    continue
                }
                cursor = result.nextIndex.coerceAtLeast(cursor + 1)
                contiguousAnchor = result.nextIndex
                emptyContiguousBeginAt = null
            } else {
                cursor += 1
            }
        }

        return DelimitedContentTokenizationResult(
            tokens = tokens,
            endMatch =
                if (endPattern == NEGATIVE_CONTIGUOUS_ANCHOR_END_PATTERN && cursor > startIndex) {
                    TextMateRegexMatch(cursor, cursor)
                } else {
                    findDelimitedEndPattern(endPattern, code, cursor, endIndex)
                        ?: findDelimitedEndPattern(endPattern, code, endIndex, endIndex)
                },
            openRules = emptyList(),
        )
    }

    private fun findDelimitedEndPattern(
        endPattern: String,
        code: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? {
        if (startIndex > endIndex) return null
        measuredRegexFind(endPattern, code, startIndex, endIndex)?.let { return it }
        if (startIndex == endIndex && endIndex > 0) {
            if (isLineTerminatorEndPattern(endPattern) && !code.substring(0, endIndex).contains('\n')) {
                return TextMateRegexMatch(endIndex, endIndex)
            }
            return measuredRegexFind(endPattern, code, endIndex - 1, endIndex)
                ?.takeIf { match -> match.end == endIndex }
        }
        return null
    }

    private fun isLineTerminatorEndPattern(endPattern: String): Boolean =
        endPattern == "\\n" || endPattern == "\\r\\n"

    private fun findEndPattern(
        endPattern: String,
        code: String,
        startIndex: Int,
        endIndex: Int,
        boundarySuffix: String,
    ): TextMateRegexMatch? {
        if (boundarySuffix.isEmpty()) {
            return measuredRegexFind(endPattern, code, startIndex, endIndex)
        }

        val scopedInput = activeBoundaryBuffer?.source ?: (code + boundarySuffix)
        val searchEnd = endIndex + boundarySuffix.length
        val match =
            measuredRegexFind(
                pattern = endPattern,
                input = scopedInput,
                startIndex = startIndex,
                endIndex = searchEnd,
            ) ?: return null

        // A boundary suffix exists only to let zero-width, end-of-line rules see
        // the following line. A consuming end pattern belongs to that next line.
        if (match.start == match.end && match.start == endIndex + 1) {
            return TextMateRegexMatch(
                start = endIndex,
                end = endIndex,
                captures =
                    match.captures.map { capture ->
                        TextMateRegexCapture(
                            index = capture.index,
                            start = endIndex.coerceAtMost(capture.start),
                            end = endIndex.coerceAtMost(capture.end),
                        )
                    },
            )
        }

        return match.takeIf { it.end <= endIndex }
    }

    private fun advancePastEndMatch(
        endMatch: TextMateRegexMatch,
        offset: Int,
        cursor: Int,
    ): Int {
        val absoluteEnd = endMatch.end + offset
        return if (endMatch.start == endMatch.end) {
            (endMatch.start + offset).coerceAtLeast(cursor)
        } else {
            absoluteEnd.coerceAtLeast(cursor + 1)
        }
    }

    private fun TextMateRegexMatch.startsBefore(
        other: TextMateRegexMatch,
        applyEndPatternLast: Boolean,
    ): Boolean =
        start < other.start || (!applyEndPatternLast && start == other.start)

    private fun resolveInclude(
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        baseGrammar: TextMateGrammar,
        include: String,
    ): IncludedTextMatePattern? =
        when {
            include == "\$self" ->
                IncludedTextMatePattern(
                    grammar = grammar,
                    pattern = TextMatePattern(patterns = grammar.patterns),
                )
            include == "\$base" ->
                IncludedTextMatePattern(
                    grammar = baseGrammar,
                    pattern = TextMatePattern(patterns = baseGrammar.patterns),
                    scopePrefix = listOf(baseGrammar.scopeName).takeUnless { baseGrammar.scopeName == grammar.scopeName }.orEmpty(),
                )
            include.startsWith("#") ->
                grammar.repository[include.removePrefix("#")]?.let { pattern ->
                    IncludedTextMatePattern(
                        grammar = grammar,
                        pattern = pattern,
                    )
                }
            else ->
                grammarBundle?.grammarForScope(include, metricsSink)?.let { payload ->
                    IncludedTextMatePattern(
                        grammar = payload.grammar,
                        pattern = TextMatePattern(patterns = payload.grammar.patterns),
                        scopePrefix = listOf(payload.scopeName),
                    )
                }
        }

    private fun tokensForMatch(
        pattern: TextMatePattern,
        match: TextMateRegexMatch,
        code: String,
        baseScopes: List<String>,
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int,
        captureDepth: Int = 0,
    ): List<TextMateScopeToken> {
        val ruleScopes = baseScopes.withScope(pattern.name)
        val captureTokens =
            tokensForCaptures(
                captures = pattern.captures,
                match = match,
                code = code,
                baseScopes = ruleScopes,
                grammar = grammar,
                grammarBundle = grammarBundle,
                activeIncludes = activeIncludes,
                baseGrammar = baseGrammar,
                offset = offset,
                captureDepth = captureDepth,
            )
        if (captureTokens.isEmpty()) {
            return listOfNotNull(token(match.start + offset, match.end + offset, ruleScopes))
        }

        return captureTokens + scopedHoles(match.start + offset, match.end + offset, captureTokens, ruleScopes)
    }

    private fun tokensForDelimitedCaptures(
        captures: Map<String, TextMateCapture>,
        match: TextMateRegexMatch,
        code: String,
        scopes: List<String>,
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int,
        captureDepth: Int = 0,
    ): List<TextMateScopeToken> {
        val captureTokens =
            tokensForCaptures(
                captures = captures,
                match = match,
                code = code,
                baseScopes = scopes,
                grammar = grammar,
                grammarBundle = grammarBundle,
                activeIncludes = activeIncludes,
                baseGrammar = baseGrammar,
                offset = offset,
                captureDepth = captureDepth,
            )
        if (captureTokens.isEmpty()) {
            return listOfNotNull(token(match.start + offset, match.end + offset, scopes))
        }

        return captureTokens + scopedHoles(match.start + offset, match.end + offset, captureTokens, scopes)
    }

    private fun tokensForCaptures(
        captures: Map<String, TextMateCapture>,
        match: TextMateRegexMatch,
        code: String,
        baseScopes: List<String>,
        grammar: TextMateGrammar,
        grammarBundle: TextMateGrammarBundle?,
        activeIncludes: Set<String>,
        baseGrammar: TextMateGrammar,
        offset: Int,
        captureDepth: Int = 0,
    ): List<TextMateScopeToken> {
        val tokens = mutableListOf<TextMateScopeToken>()
        captures.forEach { (index, capture) ->
            val captureIndex = index.toIntOrNull() ?: return@forEach
            val group = match.captures.firstOrNull { it.index == captureIndex } ?: return@forEach
            if (group.start < 0 || group.end > code.length || group.start >= group.end) {
                return@forEach
            }

            val captureScopes = baseScopes.withScope(capture.name)
            if (capture.patterns.isNotEmpty()) {
                val nestedTokens =
                    tokenizeRange(
                        grammar = grammar,
                        grammarBundle = grammarBundle,
                        patterns = capture.patterns,
                        code = code,
                        startIndex = group.start,
                        endIndex = group.end,
                        baseScopes = captureScopes,
                        activeIncludes = activeIncludes,
                        baseGrammar = baseGrammar,
                        offset = offset,
                        captureDepth = captureDepth + 1,
                    )
                tokens += nestedTokens
                tokens += scopedHoles(group.start + offset, group.end + offset, nestedTokens, captureScopes)
            } else {
                token(group.start + offset, group.end + offset, captureScopes)?.let { token ->
                    tokens += token
                }
            }
        }
        return tokens
    }

    private fun scopedHoles(
        startIndex: Int,
        endIndex: Int,
        coveredTokens: List<TextMateScopeToken>,
        scopes: List<String>,
    ): List<TextMateScopeToken> {
        if (scopes.isEmpty() || startIndex >= endIndex) return emptyList()

        val holes = mutableListOf<TextMateScopeToken>()
        var cursor = startIndex
        coveredTokens
            .filter { it.end > startIndex && it.start < endIndex }
            .sortedWith(compareBy<TextMateScopeToken> { it.start }.thenBy { it.end })
            .forEach { covered ->
                val coveredStart = covered.start.coerceIn(startIndex, endIndex)
                if (cursor < coveredStart) {
                    holes += TextMateScopeToken(cursor, coveredStart, scopes)
                }
                cursor = maxOf(cursor, covered.end.coerceIn(startIndex, endIndex))
            }
        if (cursor < endIndex) {
            holes += TextMateScopeToken(cursor, endIndex, scopes)
        }
        return holes
    }

    private fun token(
        start: Int,
        end: Int,
        scopes: List<String>,
    ): TextMateScopeToken? =
        if (start < end && scopes.isNotEmpty()) {
            TextMateScopeToken(start = start, end = end, scopes = scopes)
        } else {
            null
        }

    private fun normalize(
        tokens: List<TextMateScopeToken>,
        codeLength: Int,
    ): List<TextMateScopeToken> =
        tokens
            .filter { token -> token.start < token.end && token.start >= 0 && token.end <= codeLength }
            .sortedWith(compareBy<TextMateScopeToken> { it.start }.thenBy { it.end })
            .fold(emptyList()) { normalized, token ->
                val previous = normalized.lastOrNull()
                if (previous != null && previous.end == token.start && previous.scopes == token.scopes) {
                    normalized.dropLast(1) + previous.copy(end = token.end)
                } else {
                    normalized + token
                }
            }

    private fun backendDiagnostics(): List<MarkdownDiagnostic> =
        if (regexBackend.isOnigurumaCompatible) {
            emptyList()
        } else {
            listOf(
                MarkdownDiagnostic(
                    MarkdownDiagnosticKind.HighlighterBackendFallback,
                    "TextMate tokenizer used ${regexBackend.name}; Oniguruma-compatible backend is still pending.",
                ),
            )
        }

    private fun resolveBackReferences(
        pattern: String,
        match: TextMateRegexMatch,
        code: String,
    ): String =
        backReferenceRegex.replace(pattern) { backReference ->
            val captureIndex = backReference.groupValues[1].toIntOrNull() ?: return@replace backReference.value
            val capture = match.captures.firstOrNull { it.index == captureIndex } ?: return@replace ""
            if (capture.start < 0 || capture.end > code.length || capture.start >= capture.end) {
                ""
            } else {
                escapeTextMateRegexLiteral(code.substring(capture.start, capture.end))
            }
        }

    private fun escapeTextMateRegexLiteral(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                if (char in textMateRegexMetacharacters) {
                    append('\\')
                }
                append(char)
            }
        }

    private fun TextMateRegexMatch.offsetBy(offset: Int): TextMateRegexMatch =
        if (offset == 0) {
            this
        } else {
            TextMateRegexMatch(
                start = start + offset,
                end = end + offset,
                captures =
                    captures.map { capture ->
                        TextMateRegexCapture(
                            index = capture.index,
                            start = capture.start + offset,
                            end = capture.end + offset,
                        )
                    },
            )
        }

    private fun List<String>.withScope(scope: String?): List<String> =
        if (scope.isNullOrBlank()) {
            this
        } else {
            this + scope.split(Regex("\\s+")).filter { scopePart -> scopePart.isNotBlank() }
        }

    private fun measuredRegexFind(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int = input.length,
        contiguousAnchor: Int? = null,
    ): TextMateRegexMatch? {
        val buffer = bufferFor(input)
        if (!metricsSink.isEnabled) {
            return regexBackend.find(
                pattern = pattern,
                buffer = buffer,
                startIndex = startIndex,
                endIndex = endIndex,
                contiguousAnchor = contiguousAnchor,
                metricsSink = metricsSink,
            )
        }
        val startedAt = TimeSource.Monotonic.markNow()
        var outcome = TextMateMetricOutcome.Success
        return try {
            regexBackend.find(
                pattern = pattern,
                buffer = buffer,
                startIndex = startIndex,
                endIndex = endIndex,
                contiguousAnchor = contiguousAnchor,
                metricsSink = metricsSink,
            )
        } catch (failure: Throwable) {
            outcome = TextMateMetricOutcome.Failure
            throw failure
        } finally {
            metricsSink.record(
                TextMateMetricEvent(
                    stage = TextMateMetricStage.RegexSearch,
                    outcome = outcome,
                    durationNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                    backend = regexBackend.name,
                ),
            )
        }
    }

    private fun bufferFor(input: String): PreparedTextMateLineBuffer {
        activeLineBuffer?.takeIf { it.source == input }?.let { return it }
        activeBoundaryBuffer?.takeIf { it.source == input }?.let { return it }
        return PreparedTextMateLineBuffer.prepare(input, metricsSink)
    }

    private data class ResolvedRulesCacheKey(
        val grammarId: Int,
        val grammarScope: String,
        val patternsSignature: String,
        val activeIncludes: Set<String>,
        val baseGrammarScope: String,
        val baseScopes: List<String>,
    )

    private class SharedState(
        private val cacheCoordinator: TextMateWeightedCacheCoordinator = TextMateWeightedCacheCoordinator(),
    ) {
        val resolvedRulesCache = mutableMapOf<ResolvedRulesCacheKey, List<ResolvedTextMateRule>>()

        fun trimCaches(level: MemoryPressure) {
            when (level) {
                MemoryPressure.Normal -> Unit
                MemoryPressure.Moderate,
                MemoryPressure.Critical,
                -> {
                    resolvedRulesCache.keys.toList().forEach { key ->
                        cacheCoordinator.removeIfUnleased(rulePlanCacheKey(key))
                    }
                    resolvedRulesCache.clear()
                    cacheCoordinator.trim(level)
                }
            }
        }

        fun rememberRulePlan(
            key: ResolvedRulesCacheKey,
            estimatedBytes: Long,
        ) {
            cacheCoordinator.promote(
                key = rulePlanCacheKey(key),
                category = TextMateWeightedCacheCoordinator.Category.RulePlan,
                estimatedBytes = estimatedBytes,
            )
        }

        fun touchRulePlan(key: ResolvedRulesCacheKey) {
            cacheCoordinator.touch(rulePlanCacheKey(key))
        }

        private fun rulePlanCacheKey(key: ResolvedRulesCacheKey): String =
            "rules:${key.grammarId}:${key.patternsSignature}:${key.activeIncludes.hashCode()}:${key.baseScopes.hashCode()}"
    }

    private data class ResolvedTextMateRule(
        val grammar: TextMateGrammar,
        val pattern: TextMatePattern,
        val order: Int,
        val scopePrefix: List<String> = emptyList(),
    ) {
        fun findNext(
            code: String,
            startIndex: Int,
            endIndex: Int,
            regexBackend: OpenCodeTextMateRegexBackend,
            metricsSink: OpenCodeTextMateMetricsSink,
            contiguousAnchor: Int,
            buffer: PreparedTextMateLineBuffer,
        ): TextMateRuleMatch? {
            val patternText = pattern.match ?: pattern.begin ?: return null
            if (!metricsSink.isEnabled) {
                val match =
                    regexBackend.find(
                        pattern = patternText,
                        buffer = buffer,
                        startIndex = startIndex,
                        endIndex = endIndex,
                        contiguousAnchor = contiguousAnchor,
                        metricsSink = metricsSink,
                    ) ?: return null
                return TextMateRuleMatch(rule = this, match = match)
            }
            val startedAt = TimeSource.Monotonic.markNow()
            var outcome = TextMateMetricOutcome.Success
            val match =
                try {
                    regexBackend.find(
                        pattern = patternText,
                        buffer = buffer,
                        startIndex = startIndex,
                        endIndex = endIndex,
                        contiguousAnchor = contiguousAnchor,
                        metricsSink = metricsSink,
                    )
                } catch (failure: Throwable) {
                    outcome = TextMateMetricOutcome.Failure
                    throw failure
                } finally {
                    metricsSink.record(
                        TextMateMetricEvent(
                            stage = TextMateMetricStage.RegexSearch,
                            outcome = outcome,
                            durationNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                            backend = regexBackend.name,
                        ),
                    )
                } ?: return null
            return TextMateRuleMatch(rule = this, match = match)
        }
    }

    private data class TextMateRuleMatch(
        val rule: ResolvedTextMateRule,
        val match: TextMateRegexMatch,
    )

    private data class DelimitedContentTokenizationResult(
        val tokens: List<TextMateScopeToken>,
        val endMatch: TextMateRegexMatch?,
        val openRules: List<TextMateOpenRule> = emptyList(),
    )

    private enum class InjectionPriority {
        Left,
        Right,
    }

    private data class IncludedTextMatePattern(
        val grammar: TextMateGrammar,
        val pattern: TextMatePattern,
        val scopePrefix: List<String> = emptyList(),
    )

    private data class RangeTokenizationResult(
        val tokens: List<TextMateScopeToken>,
        val openRules: List<TextMateOpenRule> = emptyList(),
    )

    private data class BeginEndTokenizationResult(
        val tokens: List<TextMateScopeToken>,
        val nextIndex: Int,
        val openRule: TextMateOpenRule? = null,
    )

    private companion object {
        const val NEGATIVE_CONTIGUOUS_ANCHOR_END_PATTERN = "(?!\\G)"
        const val MAX_CAPTURE_TOKENIZE_DEPTH = 48
        val backReferenceRegex = Regex("""\\([1-9][0-9]?)""")
        val textMateRegexMetacharacters =
            setOf(
                '\\',
                '^',
                '$',
                '.',
                '|',
                '?',
                '*',
                '+',
                '(',
                ')',
                '[',
                ']',
                '{',
                '}',
            )
    }
}
