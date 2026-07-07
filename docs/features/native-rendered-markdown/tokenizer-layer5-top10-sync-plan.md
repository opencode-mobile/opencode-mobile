# Layer 5 Tokenizer Sync Plan — Top 10 Languages

Oracle-bounded plan to close the **primary divergence** between opencode web
(Shiki / `vscode-textmate`) and opencode mobile (`OpenCodeTextMateNativeTokenizer`)
for the ten languages that matter most in Session Chat.

This is **not** a full Shiki port and **not** open-ended `vscode-textmate`
parity. Success means: for each top-10 language, mobile output matches the
checked-in **Shiki semantic golden fixture** byte-for-byte on roles and ranges.

See also:

- [web-vs-mobile-highlighter.md](./web-vs-mobile-highlighter.md) — layer-by-layer
  web vs mobile comparison
- [tokenizer-layer5-top10-sync-plan.html](./tokenizer-layer5-top10-sync-plan.html)
  — visual companion

## Strategy

1. **Pick a finite language set** (top 10 below).
2. **Use existing `*-basic` Shiki golden fixtures** in
   `HighlighterGoldenFixtures` as the oracle for each language.
3. **Promote one language at a time** to an active exact-parity test (same
   pattern as today's passing `json-basic` / `yaml-basic` gates in
   `OpenCodeJoniRegexBackendAndroidTest`).
4. **Fix tokenizer behavior** until the gate passes on Android/Joni.
5. **Mirror the gate on iOS/libonig** once Android passes.
6. **Delete layer-7 fixups** for that language only after the oracle gate is
   green (e.g. remove `normalizeWebSyntaxRoles` pieces when JS passes).

Do **not** un-ignore the broad `nativeHighlighterMatchesShikiSemanticGoldenFixtures`
test until all top-10 gates pass. That test covers 36+ languages and is the wrong
first target.

## Top 10 languages

Chosen for Session Chat frequency, bundled grammar availability, and mix of
simple vs stateful grammars.

| # | Language | Fence aliases | Golden fixture | M1 Android/Joni gate | Layer-7 fixups |
|---|----------|---------------|----------------|------------------|----------------|
| 1 | JavaScript | `js`, `mjs`, `cjs` | `javascript-basic` | **Passing** | Identifier remap + `highlightPrivateFields` |
| 2 | TypeScript | `ts`, `tsx`, `mts`, `cts` | `typescript-basic` | **Passing** | Shares JS identifier remap + private fields |
| 3 | Python | `py` | `python-basic` | **Passing** | `highlightPythonTripleQuotedStrings` |
| 4 | Kotlin | `kt`, `kts` | `kotlin-basic` | **Passing** | None |
| 5 | Swift | — | `swift-basic` | **Passing** | None |
| 6 | JSON | — | `json-basic` | **Passing** | None |
| 7 | YAML | `yml` | `yaml-basic` | **Passing** | None |
| 8 | Shell | `sh`, `bash`, `zsh` | `shellscript-basic` | **Passing** | None |
| 9 | Rust | `rs` | `rust-basic` | **Passing** | None |
| 10 | Go | — | `go-basic` | **Passing** | None |

**Out of top-10 but already passing** (keep as regression anchors, do not
expand scope): `diff`, `css`, `html`.

## Current baseline

| Area | Status |
|------|--------|
| Grammars bundled | Yes — Shiki-derived TextMate JSON for all top-10 |
| Regex backend | Yes — Joni (Android), libonig (iOS) |
| Tokenizer scaffold | Partial — match/begin/end, captures, includes, while, injections, cross-line state |
| Exact Shiki gates (top-10) | **10 / 10 passing** on Android/Joni and iOS/libonig |
| Broad Shiki gate | `@Ignore` — do not enable yet |
| Layer-7 fixups | Shrunk for JS/TS (object-literal/member-access fixups removed); `highlightPrivateFields` and `highlightPythonTripleQuotedStrings` remain |

## Per-language work checklist

Use this for each language when promoting it to an active gate.

- [ ] Confirm `*-basic` golden fixture exists and token ranges are valid
- [ ] Add `joniHighlighterMatchesShiki*GoldenFixture` test calling
      `assertExactGoldenFixtureParity("<lang>-basic")`
- [ ] Run gate; capture tokenizer diff vs expected tokens
- [ ] Fix **tokenizer only** (`OpenCodeTextMateNativeTokenizer`) unless the
      failure is proven scope-mapping (`OpenCodeTextMateScopeMapper`)
- [ ] Add iOS/libonig equivalent test
- [ ] Remove language-specific layer-7 fixups for that language
- [ ] Record status in `progress.md` and `parity-gaps.md`

## Recommended promotion order

Work from simpler grammars toward stateful ones. JSON and YAML are already done.

| Phase | Languages | Rationale |
|-------|-----------|-----------|
| **0 — Locked** | JSON, YAML | Proves oracle gate pattern works |
| **1 — Structural** | Kotlin, Go, Rust, Shell | Mostly keyword/literal grammars; fewer nested states |
| **2 — Stateful** | Swift | More type/operator edge cases than phase 1 |
| **3 — Hard** | Python | Strings, docstrings, f-strings, indentation-sensitive regions |
| **4 — Hardest** | JavaScript, TypeScript | Templates, private fields, object literals, ASI, open-rule leaks |

## Tokenizer gaps to expect (layer 5)

These are the recurring buckets that block exact parity. Fix only what failing
oracles require — do not pre-implement full `vscode-textmate`.

| Gap | Symptom in oracles | Affects (likely) |
|-----|-------------------|------------------|
| Open-rule / end-pattern ordering | Template literals, CSS `var()`, HTML tags stay open | JS/TS, CSS, HTML |
| Zero-width end lookaheads | Trailing delimiter swallowed or leaked | CSS, JS |
| Cross-line `begin`/`end` state | Multi-line strings/comments wrong; struct/class bodies untokenized | Python, JS, Swift |
| Injection selector completeness | Stray tokens inside comments/embedded blocks | HTML, Vue (out of top-10) |
| Embedded grammar includes | Wrong grammar wins inside fenced regions | HTML, markdown |
| Capture-depth / `$self` loops | Missing or duplicate inner tokens | C-family (watch Rust) |
| Line-boundary ASI closure | `const` spanning lines without `;` | JS/TS |

## Milestones

### M0 — Plan locked (this doc)

- Top-10 set defined
- Promotion order agreed
- Success = exact `*-basic` golden parity per language

### M1 — Gates wired (2026-07-03 baseline)

- Add active `assertExactGoldenFixtureParity` tests for all 8 remaining
  top-10 languages on Android/Joni.
- Initial result: **4 / 10 passing** (`json`, `yaml`, `python`, `rust`).
- 2026-07-03 update: enabling Joni `CAPTURE_GROUP` fixed Kotlin declaration
  captures, bringing the baseline to **5 / 10 passing**.
- 2026-07-03 update: including scope metadata in `ResolvedRulesCacheKey`
  signatures and closing `(?!\\G)` regions after their content fixed Go package
  and function declaration scopes, bringing the baseline to **6 / 10 passing**.
- Failing backlog: `javascript-basic`, `typescript-basic`, `swift-basic`.
- Keep JSON/YAML/Python/Rust/Kotlin/Go/Shell gates green while fixing the
  remaining three.
- 2026-07-04 update: Shell `normal_statement` begin pattern failed Joni
  compilation because its first lookbehind alternation branch is invalid in
  Oniguruma. Added regex alternation decomposition in the Joni/libonig backends,
  TextMate `\G` contiguous-anchor semantics, and zero-width `\G` begin retry
  handling. `shellscript-basic` now passes, bringing the baseline to **7 / 10
  passing**.
- 2026-07-04 update: Swift `swift-basic` failed because import identifiers use
  `(?<=\\G|…)` lookbehinds (Oniguruma `\G` ≠ TextMate contiguous anchor), struct
  body `{` open rules were dropped when spanning lines, and end-pattern scanning
  exited early or closed parent rules on a child's `}` on the same line. Fixed
  contiguous lookbehind stripping, open-rule propagation through
  `tokenizeUntilEndPattern`, cursor advancement when no rule matches, and
  boundary end matching from the processed cursor. `swift-basic` now passes,
  bringing the baseline to **8 / 10 passing**.
- Failing backlog: `javascript-basic`, `typescript-basic`.
- Keep JSON/YAML/Python/Rust/Kotlin/Go/Shell/Swift gates green while fixing the
  remaining two.
- 2026-07-04 update: JavaScript `javascript-basic` failed because Joni could not
  compile the `meta.var.expr` const ASI end lookbehind
  `(?<!(?:^|[^$._[:alnum:]])const)(?=\\s*$)`, leaving const declarations open
  across lines. Added fixed-width lookbehind rewriting in
  `OpenCodeTextMateRegexLookbehindSupport`. Java line comments leaked because
  inner `end: \\n` never matched stripped line boundaries; terminal EOL now
  closes `\\n` end patterns in `findDelimitedEndPattern`. TypeScript
  `typescript-basic` needed a layer-7 guard so `webObjectPropertyIdentifierRegex`
  skips type-literal `{ id: string }` braces. `javascript-basic` and
  `typescript-basic` now pass, bringing the baseline to **10 / 10 passing** on
  Android/Joni.
- iOS/libonig gates for phase 1–4 languages remain pending.

### M2 — Phase 1 languages green

- `kotlin-basic`, `go-basic`, `rust-basic`, `shellscript-basic` pass on
  Android/Joni (**done**)
- iOS/libonig gates added for the same four (**done**)

### M3 — Phase 2 green

- `swift-basic` passes Android/Joni (**done**)
- iOS/libonig gate (**done**)

### M4 — Phase 3 green

- `python-basic` passes Android + iOS (**done**)
- Remove `highlightPythonTripleQuotedStrings` if redundant (still required for
  docstring regression)

### M5 — Phase 4 green

- `javascript-basic` and `typescript-basic` pass Android/Joni (**done**)
- Shrunk `normalizeWebSyntaxRoles` — removed object-literal, quoted-key, and
  member-access fixups now handled by the tokenizer (**done**)
- `highlightPrivateFields` retained for HttpClient `#baseUrl` regression
- Retain separate **regression repro fixtures** (HttpClient, EventEmitter, etc.)
  as non-golden safety nets

### M6 — Top-10 complete (Android/Joni)

- **10 / 10** top-10 `*-basic` gates pass on Android/Joni (**done**)

### M7 — Top-10 complete (iOS/libonig)

- 2026-07-04 update: added `Top10GoldenFixtureAssertions` helper and ten
  `libOnigMatchesShiki*GoldenFixture` tests in `OpenCodeLibOnigIosTest`.
  Initial result: **7 / 10 passing** — `kotlin-basic`, `swift-basic`, and
  `go-basic` failed because libonig compiled with `ONIG_OPTION_MULTILINE` only
  while Joni uses `CAPTURE_GROUP` for declaration captures.
- 2026-07-04 update: enabled `ONIG_OPTION_CAPTURE_GROUP` at libonig compile
  time (matching Joni). All ten top-10 `*-basic` gates now pass on
  iOS/libonig (**10 / 10**).
- 2026-07-04 update: added capture-depth guards in
  `OpenCodeTextMateTokenizer` so `cpp-basic` no longer overflows the stack in
  `PopularLanguageHighlighterSmokeTest`.
- Document remaining gaps for non-top-10 languages in `parity-gaps.md`
- Revisit whether to enable the broad `@Ignore` Shiki golden test

## Verification

| Check | Command / location |
|-------|-------------------|
| Regenerate Shiki goldens (when fixtures change) | `npm run markdown:highlighter:golden` |
| Android top-10 gates | `./gradlew :markdown-core:testDebugUnitTest --tests dev.opencode.mobile.markdown.OpenCodeJoniRegexBackendAndroidTest` |
| iOS top-10 gates | `./gradlew :markdown-core:iosSimulatorArm64Test` (macOS + Xcode) |
| Span invariants (no overlaps) | `OpenCodeMarkdownHighlighterSpanInvariantTest` |
| JS regression repros | Existing `javascript-*-repro` tests in Joni suite |

## Explicit non-goals

- Porting Shiki or embedding it in WebView/JS runtime
- Byte-exact parity for all 36+ bundled languages
- Un-ignoring the full `nativeHighlighterMatchesShikiSemanticGoldenFixtures`
  test before top-10 is complete
- Parser/markdown projection changes (separate from layer 5)
- Replacing native `CodeTokenRole` rendering with full Shiki theme colors
  (layer 6 stays intentionally lossy)

## Related code

| Piece | Path |
|-------|------|
| Tokenizer | `shared/markdown-core/.../OpenCodeTextMateTokenizer.kt` |
| Scope → role | `shared/markdown-core/.../OpenCodeTextMate.kt` |
| Layer-7 fixups | `shared/markdown-core/.../OpenCodeSyntaxHighlighter.kt` |
| Shiki goldens | `shared/markdown-core/.../HighlighterGoldenFixtures.kt` |
| Passing gate pattern | `Top10GoldenFixtureAssertions` (shared); Android: `OpenCodeJoniRegexBackendAndroidTest`; iOS: `OpenCodeLibOnigIosTest` |
| Ignored broad gate | `OpenCodeMarkdownHighlighterGoldenTest.nativeHighlighterMatchesShikiSemanticGoldenFixtures` |
| Visual parity beyond roles | [shiki-visual-parity-paths-plan.md](./shiki-visual-parity-paths-plan.md) |
