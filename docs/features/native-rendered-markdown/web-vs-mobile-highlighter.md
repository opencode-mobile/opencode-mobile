# Highlighter: opencode Web vs opencode Mobile (Layer by Layer)

This document compares how syntax highlighting for markdown code blocks works in
**opencode web** versus **opencode mobile**, layer by layer, and marks where the
two diverge.

- opencode web treats **Shiki** as the authoritative highlighting engine.
- opencode mobile does not run Shiki. It bundles Shiki-derived TextMate grammars
  and runs a **custom native TextMate tokenizer** per platform, then maps tokens
  to a small set of semantic roles.

The goal on mobile is to approximate web output. This doc shows which layers are
faithful reproductions, which are intentional simplifications, and which are the
real source of divergence.

## Architecture at a glance

| | opencode web | opencode mobile |
|---|---|---|
| Highlighting engine | Shiki (`markedShiki` + `getSharedHighlighter`) | Custom KMP `OpenCodeTextMateNativeTokenizer` |
| Runtime | JS / WASM in browser | Kotlin Multiplatform (JVM/ART on Android, Kotlin/Native on iOS) |
| Source of truth | Shiki itself | Shiki-derived grammars + oracle fixtures |
| Output | Themed HTML `<span>`s | Semantic `CodeTokenRole` spans rendered natively |
| Parity target | n/a (is the target) | Approximate web/Shiki output |

## Layer-by-layer comparison

| # | Layer | opencode web | opencode mobile | Divergence |
|---|-------|--------------|-----------------|------------|
| 0 | Philosophy | Shiki is the engine; output is authoritative | Reimplement TextMate natively, approximate Shiki | Fundamental |
| 1 | Scope of highlighting | Fenced code blocks via `markedShiki`; inline code themed by CSS | Fenced code blocks only; inline code stays plain | Minor |
| 2 | Language / alias detection | Shiki `bundledLanguages`; unknown → `text` | `OpenCodeMarkdownParser.parseFence` + manifest alias map | Aligned (mobile avoids web's historic `sh`/`yml`/`py` alias-to-white-text bug) |
| 3 | Grammar source | Shiki's bundled TextMate grammars | 48 pinned Shiki-derived grammar JSON files in `OpenCodeTextMateGrammarBundle` | Aligned (same grammar lineage) |
| 4 | Regex engine | Oniguruma (WASM) | Joni/Oniguruma (Android), libonig (iOS) | Aligned (both Oniguruma-compatible) |
| 5 | Tokenizer | `vscode-textmate` (battle-tested) | `OpenCodeTextMateNativeTokenizer` (hand-written scaffold) | **Primary divergence** |
| 6 | Token → color/role | Shiki theme maps scopes → colors, full fidelity | `OpenCodeTextMateScopeMapper` collapses scopes → ~18 `CodeTokenRole`s | **Secondary divergence** (lossy by design) |
| 7 | Post-processing | None | Per-language fixups (`normalizeWebSyntaxRoles`, `normalizeJavaSyntaxRoles`, `normalizeCSharpSyntaxRoles`, `highlightPrivateFields`, `highlightPythonTripleQuotedStrings`) | Mobile-only (symptom of layer 5/6) |
| 8 | Rendering | Themed HTML spans in DOM | Native `AttributedString` (SwiftUI) / Compose spans, role → theme token | Platform-native |
| 9 | Streaming | Highlights in normal render path | Forces cheap semantic fallback while `isStreaming`, real highlight after | Mobile-only |
| 10 | Fallback | Unknown lang → `text` (plain) | `OpenCodeSemanticFallbackHighlighter` regex approximation + diagnostic | Mobile-only |

## Layer detail

### Layer 0 — Philosophy
Web delegates entirely to Shiki, so its output *is* the reference. Mobile cannot
embed Shiki (JS/WASM) cheaply in native runtimes, so it reimplements the TextMate
tokenization pipeline and validates against Shiki-generated oracle fixtures.

### Layer 1 — Scope of highlighting
Both highlight fenced code blocks. Inline backtick code is not syntax-highlighted
in either; web styles it purely with CSS, mobile emits it as plain inline code.

### Layer 2 — Language / alias detection
Web resolves the fence language against Shiki's `bundledLanguages` and falls back
to `text` when missing. Mobile lowercases the fence language and resolves aliases
through the grammar manifest. Because aliases are baked into the manifest, mobile
avoids the web-side class of bugs where `sh`/`yml`/`py` fell through to unstyled
white text.

### Layer 3 — Grammar source
Both ultimately use the same TextMate grammar lineage. Web loads them through
Shiki; mobile bundles 48 pinned grammar JSON files (14 Milestone 0 languages plus
22 popular languages, ~3.6 MB) and loads them lazily.

### Layer 4 — Regex engine
TextMate grammars require Oniguruma-compatible regex. Web uses Oniguruma via WASM.
Mobile uses Joni (Oniguruma for the JVM) on Android and libonig via Kotlin/Native
cinterop on iOS. Both are Oniguruma-compatible, so this layer is aligned. The old
Kotlin-`Regex` hybrid shim remains only for regression comparison.

### Layer 5 — Tokenizer (primary divergence)
Web uses `vscode-textmate`, the same mature tokenizer VS Code ships. Mobile uses
`OpenCodeTextMateNativeTokenizer`, a hand-written implementation that is still a
scaffold. It handles include resolution, match rules, begin/end rules, captures,
nested patterns, `while` rules, injections, and cross-line state — but not yet at
full fidelity. This is why the broad native-vs-Shiki golden test is still ignored
and only a focused language subset has exact parity.

### Layer 6 — Token → color / role (secondary divergence)
Shiki maps full TextMate scope stacks to theme colors, preserving fine-grained
distinctions. Mobile's `OpenCodeTextMateScopeMapper` deliberately collapses scopes
into ~18 `CodeTokenRole` values (`Keyword`, `String`, `Function`, `Property`,
`Type`, …). This keeps the native theme simple but loses fidelity relative to web.

### Layer 7 — Post-processing (mobile only)
Because layers 5 and 6 are imperfect, mobile adds per-language correction passes
in `OpenCodeSyntaxHighlighter.highlight()`. Web has no equivalent. These fixups are
a symptom of the tokenizer/mapping gap, not a designed layer.

### Layer 8 — Rendering
Web renders themed HTML spans in the DOM. Mobile converts role spans into native
attributed text: SwiftUI `AttributedString` on iOS and Compose annotated strings
on Android, mapping each `CodeTokenRole` to a theme token. Path A PA1 now gives
`Variable`, `Operator`, and `Punctuation` dedicated syntax tokens instead of
body text on both platforms. This improves visual parity without changing the
render-tree schema or carrying full TextMate scope stacks.

### Layer 9 — Streaming
While a message is still streaming (`isStreaming`), mobile intentionally uses the
cheap semantic regex fallback and defers real TextMate highlighting until the
stream completes, to keep parsing responsive. Web highlights in its normal render
path.

### Layer 10 — Fallback
When no grammar applies, web renders `text` (plain). Mobile runs
`OpenCodeSemanticFallbackHighlighter`, a regex approximation that still colors
comments, strings, numbers, and common keywords, and emits a `HighlighterFallback`
diagnostic.

## Parity snapshot (native vs Shiki/web oracle)

| Language / area | Exact Shiki semantic parity on mobile? |
|-----------------|----------------------------------------|
| Top 10 (`javascript`, `typescript`, `python`, `kotlin`, `swift`, `json`, `yaml`, `shell`, `rust`, `go`) | **Yes** — `*-basic` gates pass on Android/Joni and iOS/libonig (2026-07-04) |
| `diff`, `json`, `yaml`, `css`, `html` (Milestone 0 subset) | Yes (focused oracle, includes overlap with top 10) |
| Java, C#, popular-language pack | Partial — smoke tests and some fixtures; not full golden gates |
| Nested markdown fences | Aligned — CommonMark first-fence-wins (no `markdown`/`md` nest depth) |
| Full broad language set (36+) | Open (broad `@Ignore` Shiki golden gate) |
| Live web theme colors | Open — semantic roles gated; visual parity is [Path A](./shiki-visual-parity-path-a-plan.md) |

Oracle tests use `opencode-semantic-golden`, which collapses Shiki scopes into the
same 18 `CodeTokenRole` values mobile uses. That is **semantic** parity, not
production palette parity.

## Where divergence concentrates

- **Aligned layers:** 2, 3, 4 (detection, grammars, regex engine).
- **Primary problem:** layer 5 (native tokenizer is an incomplete reimplementation
  of `vscode-textmate`).
- **Secondary problem:** layer 6 (intentional fidelity loss collapsing scopes to
  roles).
- **Symptom:** layer 7 (per-language fixups compensating for 5 and 6).
- **Intentional product differences:** layers 1, 9, 10 (inline code, streaming
  fallback, semantic fallback).

## Related docs

- [tokenizer-layer5-top10-sync-plan.md](./tokenizer-layer5-top10-sync-plan.md) —
  oracle-bounded layer-5 sync plan for the top 10 Session Chat languages
- [shiki-visual-parity-paths-plan.md](./shiki-visual-parity-paths-plan.md) —
  Path A (enriched semantic roles) and Path B (scope + Shiki theme) for visual
  parity beyond layer 5
- [parity-gaps.md](./parity-gaps.md) — cross-platform highlighter parity gaps
- [progress.md](./progress.md) — implementation progress and remaining tokenizer work
- [web-vs-mobile-highlighter.html](./web-vs-mobile-highlighter.html) — visual companion
