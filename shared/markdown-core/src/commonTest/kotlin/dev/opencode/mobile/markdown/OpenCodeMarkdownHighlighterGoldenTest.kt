package dev.opencode.mobile.markdown

import kotlinx.serialization.json.jsonObject
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeMarkdownHighlighterGoldenTest {
    @Test
    fun shikiGoldenFixturesCoverMilestoneZeroLanguages() {
        assertEquals(1, HighlighterGoldenFixtures.schemaVersion)
        assertTrue(HighlighterGoldenFixtures.shikiVersion.isNotBlank())
        assertEquals("opencode-semantic-golden", HighlighterGoldenFixtures.theme)

        val coveredLanguages =
            HighlighterGoldenFixtures.all
                .flatMap { fixture -> listOf(fixture.language) + fixture.aliases }
                .toSet()

        val requiredLanguages =
            setOf(
                "kotlin",
                "swift",
                "typescript",
                "javascript",
                "json",
                "markdown",
                "shell",
                "yaml",
                "css",
                "html",
                "python",
                "rust",
                "go",
                "diff",
            )

        assertTrue(coveredLanguages.containsAll(requiredLanguages))
        HighlighterGoldenFixtures.all.forEach { fixture ->
            assertFalse(fixture.tokens.isEmpty(), "${fixture.language} should have Shiki semantic tokens")
            fixture.tokens.forEach { token ->
                assertTrue(token.start < token.end, "${fixture.language} token has invalid range: $token")
                assertEquals(
                    token.text,
                    fixture.code.substring(token.start, token.end),
                    "${fixture.language} token text must match its source range",
                )
            }
        }
    }

    @Test
    fun textMateGrammarManifestCoversGoldenFixtureLanguages() {
        assertEquals(1, TextMateGrammarManifestFixtures.schemaVersion)
        assertEquals(HighlighterGoldenFixtures.shikiVersion, TextMateGrammarManifestFixtures.shikiVersion)

        val fixtureLanguages = HighlighterGoldenFixtures.all.map { it.language }.toSet()
        val manifestLanguages = TextMateGrammarManifestFixtures.languages.map { it.language }.toSet()
        assertTrue(manifestLanguages.containsAll(fixtureLanguages))

        val requiredAliases =
            HighlighterGoldenFixtures.all
                .flatMap { fixture -> fixture.aliases }
                .toSet()
        val manifestAliases =
            TextMateGrammarManifestFixtures.languages
                .flatMap { entry -> entry.aliases + entry.grammars.flatMap { it.aliases } }
                .toSet()
        assertTrue(manifestAliases.containsAll(requiredAliases))

        TextMateGrammarManifestFixtures.languages.forEach { entry ->
            assertTrue(entry.displayName.isNotBlank(), "${entry.language} should have a display name")
            assertTrue(entry.grammarModule.endsWith(".mjs"), "${entry.language} should reference a Shiki grammar module")
            assertFalse(entry.grammars.isEmpty(), "${entry.language} should have at least one grammar descriptor")
            entry.grammars.forEach { grammar ->
                assertTrue(grammar.name.isNotBlank(), "${entry.language} grammar should have a name")
                assertTrue(grammar.scopeName.isNotBlank(), "${entry.language} grammar should have a TextMate scope name")
                assertTrue(
                    grammar.resourcePath.startsWith("textmate-grammars/grammars/"),
                    "${entry.language} grammar should reference a bundled resource path",
                )
                assertTrue(grammar.resourcePath.endsWith(".json"), "${entry.language} grammar should reference JSON")
            }
        }
    }

    @Test
    fun shikiGoldenFixturesCoverTopLanguageListExceptScratchPlainFallback() {
        val coveredLabels =
            HighlighterGoldenFixtures.all
                .flatMap { fixture -> listOf(fixture.language) + fixture.aliases }
                .toSet()

        val requiredLabels =
            setOf(
                "python",
                "javascript",
                "java",
                "csharp",
                "c#",
                "cpp",
                "c++",
                "typescript",
                "php",
                "go",
                "kotlin",
                "swift",
                "ruby",
                "rust",
                "c",
                "dart",
                "r",
                "perl",
                "scala",
                "matlab",
                "powershell",
                "lua",
                "groovy",
                "objective-c",
                "objectivec",
                "objc",
                "shellscript",
                "bash",
                "sql",
                "html",
                "css",
                "haskell",
                "elixir",
                "clojure",
                "coffee",
                "coffeescript",
                "fortran",
                "fortran-free-form",
                "fortran-fixed-form",
                "pascal",
                "delphi",
                "object-pascal",
                "vb",
                "visual-basic",
                "asm",
                "assembly",
                "cobol",
                "julia",
                "sas",
                "abap",
                "apex",
                "crystal",
                "fsharp",
                "f#",
                "fs",
                "awk",
                "nim",
                "ada",
                "prolog",
                "smalltalk",
                "zig",
                "ocaml",
                "erlang",
                "common-lisp",
                "lisp",
            )

        assertTrue(
            coveredLabels.containsAll(requiredLabels),
            "Missing top-language labels: ${requiredLabels - coveredLabels}",
        )
        assertFalse(coveredLabels.contains("scratch"), "Scratch must remain a documented plain fallback until a grammar is chosen")
    }

    @Test
    fun scratchFenceIsRecognizedAsPlainFallbackWithoutSemanticSpans() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeKotlinRegexHybridTextMateBackend),
            )
        val result = highlighter.highlight("scratch", """when green flag clicked""")

        assertTrue(highlighter.supportsLanguage("scratch"))
        assertTrue(result.spans.isEmpty())
        assertTrue(result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterFallback })

        val document =
            OpenCodeMarkdownParser.parse(
                """
                ```scratch
                when green flag clicked
                ```
                """.trimIndent(),
                codeHighlighter = highlighter,
            )

        assertFalse(document.diagnostics.any { it.kind == MarkdownDiagnosticKind.UnsupportedLanguage })
        val block = document.blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("scratch", block.language)
        assertTrue(block.spans.isEmpty())
    }

    @Test
    fun generatedGrammarResourceFixturesMatchManifestDescriptors() {
        val descriptors =
            TextMateGrammarManifestFixtures.languages
                .flatMap { entry -> entry.grammars }
                .distinctBy { descriptor -> descriptor.resourcePath }
        val resourcesByPath = TextMateGrammarResourceFixtures.all.associateBy { resource -> resource.resourcePath }

        assertEquals(descriptors.map { it.resourcePath }.toSet(), resourcesByPath.keys)

        descriptors.forEach { descriptor ->
            val resource = assertNotNull(resourcesByPath[descriptor.resourcePath])
            assertEquals(descriptor.name, resource.name)
            assertEquals(descriptor.displayName, resource.displayName)
            assertEquals(descriptor.scopeName, resource.scopeName)
            assertTrue(resource.byteSize > 0, "${descriptor.resourcePath} should not be empty")
        }
    }

    @Test
    fun textMateGrammarManifestDecodesAndResolvesAliases() {
        val manifest =
            OpenCodeTextMateGrammarManifest.decode(
                """
                {
                  "schemaVersion": 1,
                  "generator": "test",
                  "shikiVersion": "4.2.0",
                  "languages": [
                    {
                      "language": "typescript",
                      "displayName": "TypeScript",
                      "aliases": ["ts", "mts"],
                      "grammarModule": "shiki/dist/langs/typescript.mjs",
                      "grammars": [
                        {
                          "name": "typescript",
                          "displayName": "TypeScript",
                          "scopeName": "source.ts",
                          "resourcePath": "textmate-grammars/grammars/typescript.json",
                          "embeddedLanguages": [],
                          "aliases": ["ts"]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertTrue(manifest.contains("typescript"))
        assertTrue(manifest.contains("ts"))
        assertTrue(manifest.contains("MTS"))
        assertFalse(manifest.contains("swift"))
        assertEquals("source.ts", manifest.entryFor("ts")?.grammars?.single()?.scopeName)
        assertEquals(
            "textmate-grammars/grammars/typescript.json",
            manifest.entryFor("ts")?.grammars?.single()?.resourcePath,
        )
    }

    @Test
    fun textMateGrammarBundledLoadIsLazyUntilLanguageRequested() {
        val manifest = singleLanguageManifest()
        val bundle =
            OpenCodeTextMateGrammarBundleLoader.loadFromManifestJson(
                manifestJson = OpenCodeMarkdownJson.json.encodeToString(manifest),
                reader = singleLanguageResourceReader(),
            ).bundle ?: error("missing manifest bundle")

        assertTrue(bundle.grammars.isEmpty())

        val kotlinGrammar = bundle.primaryGrammarFor("kotlin")
        assertNotNull(kotlinGrammar)
        assertEquals(1, bundle.grammars.size)
        assertTrue(bundle.grammarsFor("swift").isEmpty())
    }

    @Test
    fun textMateGrammarBundleLoadsManifestAndGrammarPayloadsFromReader() {
        val manifest = singleLanguageManifest()
        val bundle =
            OpenCodeTextMateGrammarBundleLoader.load(
                manifest = manifest,
                reader = singleLanguageResourceReader(),
            )

        assertTrue(bundle.diagnostics.isEmpty())
        assertEquals("source.kotlin", bundle.primaryGrammarFor("kt")?.scopeName)
        assertEquals("source.kotlin", bundle.primaryGrammarFor("kotlin")?.scopeName)
        assertEquals("source.kotlin", bundle.grammarForScope("source.kotlin")?.scopeName)
        assertEquals("kotlin", bundle.primaryGrammarFor("kt")?.grammar?.name)
        assertEquals(1, bundle.primaryGrammarFor("kt")?.grammar?.patterns?.size)
        assertEquals(1, bundle.primaryGrammarFor("kt")?.grammar?.repository?.size)
        assertEquals(1, bundle.grammarsFor("kts").size)
        assertTrue(bundle.grammarsFor("swift").isEmpty())
    }

    @Test
    fun textMateGrammarBundlePrefersLanguageGrammarOverEmbeddedDependencies() {
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "4.2.0",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = "html",
                            displayName = "HTML",
                            aliases = listOf("htm"),
                            grammarModule = "test",
                            grammars =
                                listOf(
                                    TextMateGrammarDescriptor(
                                        name = "javascript",
                                        displayName = "JavaScript",
                                        scopeName = "source.js",
                                        resourcePath = "textmate-grammars/grammars/javascript.json",
                                        embeddedLanguages = emptyList(),
                                        aliases = listOf("js"),
                                    ),
                                    TextMateGrammarDescriptor(
                                        name = "html",
                                        displayName = "HTML",
                                        scopeName = "text.html.basic",
                                        resourcePath = "textmate-grammars/grammars/html.json",
                                        embeddedLanguages = listOf("javascript"),
                                        aliases = listOf("htm"),
                                    ),
                                ),
                        ),
                    ),
            )
        val bundle =
            OpenCodeTextMateGrammarBundleLoader.load(
                manifest = manifest,
                reader =
                    OpenCodeTextMateResourceReader { resourcePath ->
                        when (resourcePath) {
                            "textmate-grammars/grammars/javascript.json" ->
                                """{"name":"javascript","scopeName":"source.js","patterns":[]}"""
                            "textmate-grammars/grammars/html.json" ->
                                """{"name":"html","scopeName":"text.html.basic","patterns":[]}"""
                            else -> null
                        }
                    },
            )

        assertEquals("source.js", bundle.grammarsFor("html").firstOrNull()?.scopeName)
        assertEquals("text.html.basic", bundle.primaryGrammarFor("html")?.scopeName)
        assertEquals("text.html.basic", bundle.primaryGrammarFor("htm")?.scopeName)
    }

    @Test
    fun textMateGrammarBundleReportsMissingAndMismatchedResources() {
        val missingBundle =
            OpenCodeTextMateGrammarBundleLoader.load(
                manifest = singleLanguageManifest(),
                reader = OpenCodeTextMateResourceReader { null },
            )

        assertTrue(missingBundle.grammars.isEmpty())
        assertEquals(TextMateGrammarLoadDiagnosticKind.GrammarMissing, missingBundle.diagnostics.single().kind)

        val mismatchedBundle =
            OpenCodeTextMateGrammarBundleLoader.load(
                manifest = singleLanguageManifest(),
                reader =
                    OpenCodeTextMateResourceReader { resourcePath ->
                        when (resourcePath) {
                            "textmate-grammars/grammars/kotlin.json" ->
                                """{"name":"kotlin","scopeName":"source.not-kotlin","patterns":[]}"""
                            else -> null
                        }
                    },
            )

        assertEquals("source.not-kotlin", mismatchedBundle.primaryGrammarFor("kt")?.scopeName)
        assertEquals(TextMateGrammarLoadDiagnosticKind.ScopeMismatch, mismatchedBundle.diagnostics.single().kind)
    }

    @Test
    fun textMateGrammarBundleReportsManifestLoadFailures() {
        val missing =
            OpenCodeTextMateGrammarBundleLoader.loadBundled(
                reader = OpenCodeTextMateResourceReader { null },
            )

        assertEquals(null, missing.bundle)
        assertEquals(TextMateGrammarLoadDiagnosticKind.ManifestMissing, missing.diagnostics.single().kind)

        val invalid =
            OpenCodeTextMateGrammarBundleLoader.loadFromManifestJson(
                manifestJson = "{",
                reader = OpenCodeTextMateResourceReader { null },
            )

        assertEquals(null, invalid.bundle)
        assertEquals(TextMateGrammarLoadDiagnosticKind.ManifestInvalid, invalid.diagnostics.single().kind)
    }

    @Test
    fun textMateScopesMapToSemanticTokenRoles() {
        val cases =
            listOf(
                listOf("source.kotlin", "comment.line.double-slash.kotlin") to CodeTokenRole.Comment,
                listOf("source.swift", "keyword.operator.optional.swift") to CodeTokenRole.Operator,
                listOf("source.ts", "keyword.control.export.ts") to CodeTokenRole.Keyword,
                listOf("source.js", "string.quoted.double.js") to CodeTokenRole.String,
                listOf("source.json", "constant.numeric.json") to CodeTokenRole.Number,
                listOf("source.ts", "constant.language.boolean.true.ts") to CodeTokenRole.Constant,
                listOf("source.swift", "entity.name.type.struct.swift") to CodeTokenRole.Type,
                listOf("source.go", "entity.name.function.go") to CodeTokenRole.Function,
                listOf("source.ts", "variable.other.property.ts") to CodeTokenRole.Property,
                listOf("source.js", "meta.object-literal.key.js") to CodeTokenRole.Property,
                listOf("source.ts", "variable.object.property.ts") to CodeTokenRole.Variable,
                listOf("source.js", "variable.language.this.js") to CodeTokenRole.Variable,
                listOf("source.js", "variable.other.object.js") to CodeTokenRole.Variable,
                listOf("source.js", "variable.other.readwrite.js") to CodeTokenRole.Variable,
                listOf("source.js", "storage.modifier.async.js") to CodeTokenRole.Keyword,
                listOf("text.html.basic", "entity.name.tag.section.html") to CodeTokenRole.Tag,
                listOf("text.html.basic", "entity.other.attribute-name.class.html") to CodeTokenRole.Attribute,
                listOf("source.diff", "markup.inserted.diff") to CodeTokenRole.Inserted,
                listOf("source.diff", "markup.deleted.diff") to CodeTokenRole.Deleted,
                listOf("source.diff", "meta.diff.header") to CodeTokenRole.Warning,
                listOf("source.rust", "invalid.illegal.character.rust") to CodeTokenRole.Critical,
                listOf("source.css", "punctuation.definition.block.css") to CodeTokenRole.Punctuation,
                listOf("source.plain") to CodeTokenRole.Plain,
            )

        cases.forEach { (scopes, expectedRole) ->
            assertEquals(expectedRole, OpenCodeTextMateScopeMapper.roleFor(scopes), scopes.joinToString(" "))
        }
    }

    @Test
    fun textMateScopeTokenConvertsToCodeSpan() {
        val span =
            OpenCodeTextMateScopeMapper.codeSpanFor(
                TextMateScopeToken(
                    start = 4,
                    end = 10,
                    scopes = listOf("source.ts", "entity.name.function.ts"),
                ),
            )

        assertEquals(CodeSpan(4, 10, CodeTokenRole.Function), span)
    }

    @Test
    fun textMateGrammarDecoderSummarizesTokenizerRelevantRules() {
        val grammar =
            OpenCodeTextMateGrammarDecoder.decode(
                OpenCodeMarkdownJson.json
                    .parseToJsonElement(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            { "include": "#comments" },
                            {
                              "begin": "\"",
                              "beginCaptures": { "0": { "name": "punctuation.definition.string.begin.sample" } },
                              "end": "\"",
                              "endCaptures": { "0": { "name": "punctuation.definition.string.end.sample" } },
                              "name": "string.quoted.double.sample",
                              "patterns": [
                                { "match": "\\\\.", "name": "constant.character.escape.sample" }
                              ]
                            }
                          ],
                          "repository": {
                            "comments": {
                              "match": "//.*$",
                              "name": "comment.line.double-slash.sample",
                              "captures": {
                                "0": { "name": "punctuation.definition.comment.sample" }
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    ).jsonObject,
            )
        val summary = OpenCodeTextMateGrammarDecoder.summarize(grammar)

        assertEquals("source.sample", grammar.scopeName)
        assertEquals(2, grammar.patterns.size)
        assertEquals(1, grammar.repository.size)
        assertEquals(4, summary.patternCount)
        assertEquals(1, summary.repositoryCount)
        assertEquals(1, summary.includeCount)
        assertEquals(2, summary.matchRuleCount)
        assertEquals(1, summary.beginEndRuleCount)
    }

    @Test
    fun syntaxHighlighterUsesTextMateTokenizerWhenAvailable() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = singleLanguageBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult {
                            assertEquals("kotlin", grammar.descriptor.name)
                            assertEquals("source.kotlin", grammar.scopeName)
                            assertEquals("val answer = 42", code)
                            return TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(
                                            start = 0,
                                            end = 3,
                                            scopes = listOf("source.kotlin", "keyword.other.kotlin"),
                                        ),
                                        TextMateScopeToken(
                                            start = 13,
                                            end = 15,
                                            scopes = listOf("source.kotlin", "constant.numeric.kotlin"),
                                        ),
                                    ),
                            )
                        }
                    },
            )

        assertTrue(highlighter.supportsLanguage("kt"))
        assertEquals(
            SyntaxHighlightResult(
                spans =
                    listOf(
                        CodeSpan(0, 3, CodeTokenRole.Keyword),
                        CodeSpan(13, 15, CodeTokenRole.Number),
                    ),
                diagnostics = emptyList(),
                source = SyntaxHighlightSource.TextMate,
                canonicalLanguage = "kotlin",
                textMateOutcome = TextMateParseOutcome.Highlighted,
            ),
            highlighter.highlight("kt", "val answer = 42"),
        )
    }

    @Test
    fun syntaxHighlighterFallsBackWhenTextMateTokenizerProducesNoSemanticSpans() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = singleLanguageBundle(),
                textMateTokenizer =
                    object : OpenCodeTextMateTokenizer {
                        override fun tokenize(
                            grammar: TextMateGrammarPayload,
                            code: String,
                            grammarBundle: TextMateGrammarBundle?,
                        ): TextMateTokenizationResult =
                            TextMateTokenizationResult(
                                tokens =
                                    listOf(
                                        TextMateScopeToken(
                                            start = 0,
                                            end = code.length,
                                            scopes = listOf("source.kotlin"),
                                        ),
                                    ),
                            )
                    },
            )

        val result = highlighter.highlight("kotlin", "val answer = 42")

        assertTrue(result.spans.any { it.role == CodeTokenRole.Keyword })
        assertTrue(result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterFallback })
    }

    @Test
    fun nativeTextMateTokenizerResolvesIncludesAndMatchRules() {
        val code = "val answer = 42 // ok"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            { "include": "#keywords" },
                            { "include": "#comments" }
                          ],
                          "repository": {
                            "keywords": {
                              "match": "\\b(val|return)\\b",
                              "name": "keyword.control.sample"
                            },
                            "comments": {
                              "match": "//.*$",
                              "name": "comment.line.double-slash.sample"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "val", CodeTokenRole.Keyword)
        assertToken(code, result.spans, "// ok", CodeTokenRole.Comment)
    }

    @Test
    fun nativeTextMateTokenizerExpandsRepositoryPatternContainers() {
        val code = "val answer = 42"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            { "include": "#expressions" }
                          ],
                          "repository": {
                            "expressions": {
                              "patterns": [
                                { "include": "#keywords" },
                                {
                                  "match": "\\b\\d+\\b",
                                  "name": "constant.numeric.sample"
                                }
                              ]
                            },
                            "keywords": {
                              "match": "\\b(val|return)\\b",
                              "name": "keyword.control.sample"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "val", CodeTokenRole.Keyword)
        assertToken(code, result.spans, "42", CodeTokenRole.Number)
    }

    @Test
    fun nativeTextMateTokenizerAppliesNestedCapturePatterns() {
        val code = "[fix TODO](https://example.com)"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "match": "\\[([^\\]]+)\\]\\(([^)]+)\\)",
                              "name": "meta.link.inline.sample",
                              "captures": {
                                "1": {
                                  "name": "string.other.link.title.sample",
                                  "patterns": [
                                    {
                                      "match": "\\bTODO\\b",
                                      "name": "keyword.control.sample"
                                    }
                                  ]
                                },
                                "2": {
                                  "name": "markup.underline.link.sample",
                                  "patterns": [
                                    {
                                      "match": "https?://[^)]+",
                                      "name": "constant.other.reference.link.sample"
                                    }
                                  ]
                                }
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "fix ", CodeTokenRole.String)
        assertToken(code, result.spans, "TODO", CodeTokenRole.Keyword)
        assertToken(code, result.spans, "https://example.com", CodeTokenRole.Constant)
    }

    @Test
    fun nativeTextMateTokenizerPreservesMatchRuleScopeAroundCaptures() {
        val code = "+new line"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "match": "^(\\+)(.*)$",
                              "name": "markup.inserted.sample",
                              "captures": {
                                "1": { "name": "punctuation.definition.inserted.sample" }
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "+", CodeTokenRole.Punctuation)
        assertToken(code, result.spans, "new line", CodeTokenRole.Inserted)
    }

    @Test
    fun nativeTextMateTokenizerAppliesEndPatternLastWhenRequested() {
        val code = "TODO END val"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "begin": "^",
                              "end": "\\bTODO\\b|END",
                              "applyEndPatternLast": true,
                              "name": "meta.region.sample",
                              "contentName": "string.quoted.double.sample",
                              "patterns": [
                                {
                                  "match": "\\bTODO\\b",
                                  "name": "keyword.control.sample"
                                }
                              ]
                            },
                            {
                              "match": "\\bval\\b",
                              "name": "keyword.control.sample"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "TODO", CodeTokenRole.Keyword)
        assertToken(code, result.spans, "val", CodeTokenRole.Keyword)
    }

    @Test
    fun nativeTextMateTokenizerLetsEarlierNestedBeginEndRuleConsumeParentEndLookahead() {
        val code = ": var(--text);"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "begin": ":\\s*",
                              "beginCaptures": {
                                "0": { "name": "punctuation.separator.key-value.sample" }
                              },
                              "contentName": "meta.property-value.sample",
                              "end": "\\s*(;)|\\s*(?=[)])",
                              "endCaptures": {
                                "1": { "name": "punctuation.terminator.rule.sample" }
                              },
                              "patterns": [
                                {
                                  "begin": "(var)(\\()",
                                  "beginCaptures": {
                                    "1": { "name": "support.function.misc.sample" },
                                    "2": { "name": "punctuation.section.function.begin.bracket.round.sample" }
                                  },
                                  "end": "\\)",
                                  "endCaptures": {
                                    "0": { "name": "punctuation.section.function.end.bracket.round.sample" }
                                  },
                                  "name": "meta.function.variable.sample",
                                  "patterns": [
                                    {
                                      "match": "--[-A-Za-z]+",
                                      "name": "variable.argument.sample"
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "var", CodeTokenRole.Function)
        assertToken(code, result.spans, "--text", CodeTokenRole.Variable)
        assertTrue(
            result.spans.any { span ->
                span.role == CodeTokenRole.Punctuation && code.substring(span.start, span.end).contains(")")
            },
            "Expected closing function punctuation to be emitted in ${result.spans}",
        )
    }

    @Test
    fun nativeTextMateTokenizerAppliesInjectionSelectors() {
        val code = "x < y /* < */ <script><</script>"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "begin": "/\\\\*",
                              "end": "\\\\*/",
                              "name": "comment.block.sample",
                              "contentName": "comment.block.sample"
                            },
                            {
                              "begin": "<script>",
                              "end": "</script>",
                              "name": "meta.embedded.sample",
                              "contentName": "meta.embedded.sample"
                            }
                          ],
                          "injections": {
                            "R:source.sample - (comment.block.sample, meta.embedded.sample)": {
                              "match": "<",
                              "name": "invalid.illegal.sample"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)
        val criticalTokens =
            result.spans
                .filter { span -> span.role == CodeTokenRole.Critical }
                .map { span -> span.start to code.substring(span.start, span.end) }

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertEquals(listOf(2 to "<"), criticalTokens)
        assertTrue(
            result.spans.any { span ->
                span.role == CodeTokenRole.Comment && code.substring(span.start, span.end).contains("<")
            },
            "Expected the inner comment '<' to stay under comment scope in ${result.spans}",
        )
    }

    @Test
    fun nativeTextMateTokenizerResolvesExternalScopeIncludes() {
        val code = "<script>const answer = 42</script>"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    twoLanguageBundle(
                        primaryGrammarJson =
                            """
                            {
                              "name": "sample",
                              "scopeName": "source.sample",
                              "patterns": [
                                {
                                  "begin": "<script>",
                                  "end": "</script>",
                                  "name": "meta.embedded.block.sample",
                                  "contentName": "source.embedded.sample",
                                  "patterns": [
                                    { "include": "source.embedded" }
                                  ]
                                }
                              ]
                            }
                            """.trimIndent(),
                        embeddedGrammarJson =
                            """
                            {
                              "name": "embedded",
                              "scopeName": "source.embedded",
                              "patterns": [
                                {
                                  "match": "\\bconst\\b",
                                  "name": "keyword.control.embedded"
                                },
                                {
                                  "match": "\\b\\d+\\b",
                                  "name": "constant.numeric.embedded"
                                }
                              ]
                            }
                            """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "const", CodeTokenRole.Keyword)
        assertToken(code, result.spans, "42", CodeTokenRole.Number)
    }

    @Test
    fun nativeTextMateTokenizerHandlesBeginEndRulesAndNestedPatterns() {
        val code = "\"hi\\n\""
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "begin": "\"",
                              "beginCaptures": {
                                "0": { "name": "punctuation.definition.string.begin.sample" }
                              },
                              "end": "\"",
                              "endCaptures": {
                                "0": { "name": "punctuation.definition.string.end.sample" }
                              },
                              "name": "string.quoted.double.sample",
                              "patterns": [
                                {
                                  "match": "\\\\.",
                                  "name": "constant.character.escape.sample"
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "\"hi", CodeTokenRole.String)
        assertToken(code, result.spans, "\\n", CodeTokenRole.Constant)
        assertToken(code, result.spans, "\"", CodeTokenRole.String)
    }

    @Test
    fun nativeTextMateTokenizerCarriesBeginEndStateAcrossLines() {
        val grammar =
            syntheticLanguageBundle(
                """
                {
                  "name": "sample",
                  "scopeName": "source.sample",
                  "patterns": [
                    {
                      "begin": "/\\*",
                      "end": "\\*/",
                      "name": "comment.block.sample",
                      "contentName": "comment.block.sample"
                    },
                    {
                      "match": "\\bval\\b",
                      "name": "keyword.control.sample"
                    }
                  ]
                }
                """.trimIndent(),
            ).primaryGrammarFor("sample") ?: error("missing sample grammar")
        val tokenizer = OpenCodeTextMateNativeTokenizer()
        val first = tokenizer.tokenizeLine(grammar, "/* hello", lineOffset = 0)
        val second = tokenizer.tokenizeLine(grammar, "world */ val", lineOffset = 9, initialState = first.state)

        assertFalse(first.state.isEmpty)
        assertTrue(second.state.isEmpty)
        assertTrue(first.tokens.any { token -> token.scopes.any { it.startsWith("comment.block") } })
        assertTrue(second.tokens.any { token -> token.scopes.any { it.startsWith("comment.block") } })
        assertTrue(
            second.tokens.any { token ->
                token.scopes.any { it.startsWith("keyword") } && token.start == 18 && token.end == 21
            },
            "Expected keyword token with carried line offset in ${second.tokens}",
        )

        val full = tokenizer.tokenize(grammar, "/* hello\nworld */ val")
        assertBackendFallbackDiagnostic(full.diagnostics)
        assertTrue(full.tokens.any { token -> token.start == 18 && token.end == 21 })
    }

    @Test
    fun nativeTextMateTokenizerResolvesBeginCaptureBackReferencesInEndPatterns() {
        val code = "<note>body</note> <other>skip</nope> val"
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    syntheticLanguageBundle(
                        """
                        {
                          "name": "sample",
                          "scopeName": "source.sample",
                          "patterns": [
                            {
                              "begin": "<([A-Za-z]+)>",
                              "beginCaptures": {
                                "0": { "name": "punctuation.definition.tag.begin.sample" },
                                "1": { "name": "entity.name.tag.sample" }
                              },
                              "end": "</\\1>",
                              "endCaptures": {
                                "0": { "name": "punctuation.definition.tag.end.sample" }
                              },
                              "name": "meta.tag.sample",
                              "contentName": "string.quoted.double.sample"
                            },
                            {
                              "match": "\\bval\\b",
                              "name": "keyword.control.sample"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(),
            )

        val result = highlighter.highlight("sample", code)

        assertBackendFallbackDiagnostic(result.diagnostics)
        assertToken(code, result.spans, "note", CodeTokenRole.Tag)
        assertToken(code, result.spans, "body", CodeTokenRole.String)
        assertTrue(
            result.spans.any { span -> code.substring(span.start, span.end).contains("skip</nope> val") && span.role == CodeTokenRole.String },
            "Mismatched closing tag should keep the second region open in ${result.spans}",
        )
        assertFalse(
            result.spans.any { span -> code.substring(span.start, span.end) == "val" && span.role == CodeTokenRole.Keyword },
            "Mismatched closing tag should not expose val to the outer keyword rule in ${result.spans}",
        )
    }

    @Test
    fun nativeTextMateTokenizerCarriesResolvedBackReferencedEndPatternAcrossLines() {
        val grammar =
            syntheticLanguageBundle(
                """
                {
                  "name": "sample",
                  "scopeName": "source.sample",
                  "patterns": [
                    {
                      "begin": "<([A-Za-z]+)>",
                      "end": "</\\1>",
                      "name": "meta.tag.sample",
                      "contentName": "string.quoted.double.sample"
                    },
                    {
                      "match": "\\bval\\b",
                      "name": "keyword.control.sample"
                    }
                  ]
                }
                """.trimIndent(),
            ).primaryGrammarFor("sample") ?: error("missing sample grammar")
        val tokenizer = OpenCodeTextMateNativeTokenizer()
        val first = tokenizer.tokenizeLine(grammar, "<note>hello", lineOffset = 0)
        val second = tokenizer.tokenizeLine(grammar, "world</note> val", lineOffset = 12, initialState = first.state)

        assertFalse(first.state.isEmpty)
        assertTrue(second.state.isEmpty)
        assertTrue(first.state.openRules.single().endPattern?.contains("note") == true)
        assertTrue(second.tokens.any { token -> token.start == 25 && token.end == 28 && token.scopes.any { it.startsWith("keyword") } })

        val full = tokenizer.tokenize(grammar, "<note>hello\nworld</note> val")
        assertBackendFallbackDiagnostic(full.diagnostics)
        assertTrue(full.tokens.any { token -> token.start == 25 && token.end == 28 && token.scopes.any { it.startsWith("keyword") } })
    }

    @Test
    fun nativeTextMateTokenizerCarriesWhileRuleStateAcrossMatchingLines() {
        val grammar =
            syntheticLanguageBundle(
                """
                {
                  "name": "sample",
                  "scopeName": "source.sample",
                  "patterns": [
                    {
                      "begin": "^> ?",
                      "beginCaptures": {
                        "0": { "name": "punctuation.definition.quote.begin.sample" }
                      },
                      "while": "^> ?",
                      "whileCaptures": {
                        "0": { "name": "punctuation.definition.quote.continue.sample" }
                      },
                      "name": "markup.quote.sample",
                      "contentName": "string.quoted.double.sample",
                      "patterns": [
                        {
                          "match": "\\bTODO\\b",
                          "name": "keyword.control.sample"
                        }
                      ]
                    },
                    {
                      "match": "\\bval\\b",
                      "name": "keyword.control.sample"
                    }
                  ]
                }
                """.trimIndent(),
            ).primaryGrammarFor("sample") ?: error("missing sample grammar")
        val tokenizer = OpenCodeTextMateNativeTokenizer()
        val first = tokenizer.tokenizeLine(grammar, "> one", lineOffset = 0)
        val second = tokenizer.tokenizeLine(grammar, "> TODO", lineOffset = 6, initialState = first.state)
        val third = tokenizer.tokenizeLine(grammar, "val", lineOffset = 13, initialState = second.state)

        assertFalse(first.state.isEmpty)
        assertFalse(second.state.isEmpty)
        assertTrue(third.state.isEmpty)
        assertTrue(first.tokens.any { token -> token.scopes.any { it.startsWith("markup.quote") } })
        assertTrue(second.tokens.any { token -> token.start == 8 && token.end == 12 && token.scopes.any { it.startsWith("keyword") } })
        assertTrue(third.tokens.any { token -> token.start == 13 && token.end == 16 && token.scopes.any { it.startsWith("keyword") } })

        val full = tokenizer.tokenize(grammar, "> one\n> TODO\nval")
        assertBackendFallbackDiagnostic(full.diagnostics)
        assertTrue(full.tokens.any { token -> token.start == 8 && token.end == 12 && token.scopes.any { it.startsWith("keyword") } })
        assertTrue(full.tokens.any { token -> token.start == 13 && token.end == 16 && token.scopes.any { it.startsWith("keyword") } })
    }

    private fun singleLanguageManifest(): TextMateGrammarManifest =
        TextMateGrammarManifest(
            schemaVersion = 1,
            generator = "test",
            shikiVersion = "4.2.0",
            languages =
                listOf(
                    TextMateGrammarManifestEntry(
                        language = "kotlin",
                        displayName = "Kotlin",
                        aliases = listOf("kt", "kts"),
                        grammarModule = "shiki/dist/langs/kotlin.mjs",
                        grammars =
                            listOf(
                                TextMateGrammarDescriptor(
                                    name = "kotlin",
                                    displayName = "Kotlin",
                                    scopeName = "source.kotlin",
                                    resourcePath = "textmate-grammars/grammars/kotlin.json",
                                    embeddedLanguages = emptyList(),
                                    aliases = listOf("kt", "kts"),
                                ),
                            ),
                    ),
                ),
        )

    private fun singleLanguageBundle(): TextMateGrammarBundle =
        OpenCodeTextMateGrammarBundleLoader.load(
            manifest = singleLanguageManifest(),
            reader = singleLanguageResourceReader(),
        )

    private fun singleLanguageResourceReader(): OpenCodeTextMateResourceReader =
        OpenCodeTextMateResourceReader { resourcePath ->
            when (resourcePath) {
                "textmate-grammars/grammars/kotlin.json" ->
                    """
                    {
                      "name": "kotlin",
                      "scopeName": "source.kotlin",
                      "patterns": [
                        { "include": "#keywords" }
                      ],
                      "repository": {
                        "keywords": {
                          "match": "\\b(val|var)\\b",
                          "name": "keyword.other.kotlin"
                        }
                      }
                    }
                    """.trimIndent()
                else -> null
            }
        }

    private fun syntheticLanguageBundle(grammarJson: String): TextMateGrammarBundle =
        OpenCodeTextMateGrammarBundleLoader.load(
            manifest =
                TextMateGrammarManifest(
                    schemaVersion = 1,
                    generator = "test",
                    shikiVersion = "4.2.0",
                    languages =
                        listOf(
                            TextMateGrammarManifestEntry(
                                language = "sample",
                                displayName = "Sample",
                                aliases = emptyList(),
                                grammarModule = "test",
                                grammars =
                                    listOf(
                                        TextMateGrammarDescriptor(
                                            name = "sample",
                                            displayName = "Sample",
                                            scopeName = "source.sample",
                                            resourcePath = "textmate-grammars/grammars/sample.json",
                                            embeddedLanguages = emptyList(),
                                            aliases = emptyList(),
                                        ),
                                    ),
                            ),
                        ),
                ),
            reader =
                OpenCodeTextMateResourceReader { resourcePath ->
                    when (resourcePath) {
                        "textmate-grammars/grammars/sample.json" -> grammarJson
                        else -> null
                    }
                },
        )

    private fun twoLanguageBundle(
        primaryGrammarJson: String,
        embeddedGrammarJson: String,
    ): TextMateGrammarBundle =
        OpenCodeTextMateGrammarBundleLoader.load(
            manifest =
                TextMateGrammarManifest(
                    schemaVersion = 1,
                    generator = "test",
                    shikiVersion = "4.2.0",
                    languages =
                        listOf(
                            TextMateGrammarManifestEntry(
                                language = "sample",
                                displayName = "Sample",
                                aliases = emptyList(),
                                grammarModule = "test",
                                grammars =
                                    listOf(
                                        TextMateGrammarDescriptor(
                                            name = "sample",
                                            displayName = "Sample",
                                            scopeName = "source.sample",
                                            resourcePath = "textmate-grammars/grammars/sample.json",
                                            embeddedLanguages = listOf("embedded"),
                                            aliases = emptyList(),
                                        ),
                                    ),
                            ),
                            TextMateGrammarManifestEntry(
                                language = "embedded",
                                displayName = "Embedded",
                                aliases = emptyList(),
                                grammarModule = "test",
                                grammars =
                                    listOf(
                                        TextMateGrammarDescriptor(
                                            name = "embedded",
                                            displayName = "Embedded",
                                            scopeName = "source.embedded",
                                            resourcePath = "textmate-grammars/grammars/embedded.json",
                                            embeddedLanguages = emptyList(),
                                            aliases = emptyList(),
                                        ),
                                    ),
                            ),
                        ),
                ),
            reader =
                OpenCodeTextMateResourceReader { resourcePath ->
                    when (resourcePath) {
                        "textmate-grammars/grammars/sample.json" -> primaryGrammarJson
                        "textmate-grammars/grammars/embedded.json" -> embeddedGrammarJson
                        else -> null
                    }
                },
        )

    private fun assertToken(
        code: String,
        spans: List<CodeSpan>,
        text: String,
        role: CodeTokenRole,
    ) {
        assertTrue(
            spans.any { span ->
                span.role == role && code.substring(span.start, span.end) == text
            },
            "Expected $role token for '$text' in $spans",
        )
    }

    private fun assertBackendFallbackDiagnostic(diagnostics: List<MarkdownDiagnostic>) {
        assertTrue(
            diagnostics.any { diagnostic ->
                diagnostic.kind == MarkdownDiagnosticKind.HighlighterBackendFallback
            },
            "Expected backend fallback diagnostic in $diagnostics",
        )
    }

    // TextMate/Oniguruma native highlighter gate is pending; remove Ignore when replacing the fallback highlighter.
    @Ignore
    @Test
    fun nativeHighlighterMatchesShikiSemanticGoldenFixtures() {
        HighlighterGoldenFixtures.all.forEach { fixture ->
            val actual = nativeTokensFor(fixture)
            val expected =
                fixture.tokens.map { token ->
                    HighlighterGoldenToken(
                        start = token.start,
                        end = token.end,
                        role = token.role,
                        text = token.text,
                    )
                }
            assertEquals(expected, actual, "${fixture.language} tokens differ from Shiki semantic golden")
        }
    }

    private fun nativeTokensFor(fixture: HighlighterGoldenFixture): List<HighlighterGoldenToken> {
        val markdown =
            """
            ```${fixture.language}
            ${fixture.code}```
            """.trimIndent()
        val document = OpenCodeMarkdownParser.parse(markdown)
        val block = document.blocks.single()

        return when (block) {
            is MarkdownBlock.CodeBlock ->
                block.spans.map { span ->
                    HighlighterGoldenToken(
                        start = span.start,
                        end = span.end,
                        role = span.role,
                        text = block.code.substring(span.start, span.end),
                    )
                }
            is MarkdownBlock.DiffBlock -> {
                var lineOffset = 0
                block.lines.flatMap { line ->
                    val tokens =
                        line.spans.map { span ->
                            HighlighterGoldenToken(
                                start = lineOffset + span.start,
                                end = lineOffset + span.end,
                                role = span.role,
                                text = line.text.substring(span.start, span.end),
                            )
                        }
                    lineOffset += line.text.length + 1
                    tokens
                }
            }
            else -> error("Expected code or diff block for ${fixture.language}, got $block")
        }
    }
}
