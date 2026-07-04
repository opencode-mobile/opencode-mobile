# Native Rendered Markdown Parity Gaps

## TextMate Highlighter

- Status: temporary
- Area: behavior
- Shared intent: code highlighting should use bundled TextMate grammars with an
  Oniguruma-compatible native backend and Shiki-level breadth.
- Current iOS state: SwiftUI renderer calls the KMP JSON bridge, which loads
  bundled TextMate grammar resources from the app bundle and tokenizes through
  the shared TextMate tokenizer with `OpenCodeLibOnigRegexBackend` (libonig via
  Kotlin/Native cinterop). Production iOS no longer emits
  `HighlighterBackendFallback` for the regex backend. The hybrid
  `OpenCodeKotlinRegexHybridTextMateBackend` (Kotlin `Regex` shim; deprecated
  typealias `OpenCodeOnigurumaRegexBackend`) remains in `commonMain` for
  regression comparisons only.
- Current Android state: session chat, component gallery, and the KMP platform
  provider all resolve the same bundled Joni TextMate highlighter through
  `OpenCodeBundledJoniMarkdownHighlighter`. JavaScript HttpClient and withCache
  regression snippets pass Joni and iOS-hybrid backend regression coverage.
  A popular-languages TextMate pack (Java, C#, C/C++, Ruby, PHP, SQL, TOML,
  XML, Scala, Lua, Docker, Vue, Svelte, GraphQL, PowerShell, Perl, Haskell,
  Elixir, Erlang, R, Zig, and Make/Makefile) is bundled with Shiki golden fixtures and smoke
  coverage; fenced blocks in those languages no longer hit semantic fallback.
  Java method-signature lines (`void main(String[] args)`) can still miss inner
  tokens until broader Java grammar parity lands. EventEmitter-style duplicate
  span emission (`constconst`, doubled comment slashes) is deduplicated in
  `OpenCodeSyntaxHighlighter`, and partial-overlap spans (for example scattered
  `p`/`l` letters inside `HttpClient` or `patch`) are partitioned to
  non-overlapping intervals and consolidated within identifier tokens (including
  same-role coverage gaps such as `wit` + `h` + `Cache` inside `withCache`)
  before native render. JavaScript private field declarations stay plain while
  `this.#…` member access uses `Property`. Path A PA1 now maps `Variable`,
  `Operator`, and `Punctuation` to dedicated syntax tokens on both platforms
  (`syntax-variable`, `syntax-operator`, `syntax-punctuation`) while `Plain`
  remains body text. `Property` and `Constant` share `syntax-constant`; `Number`
  and `Attribute` share `syntax-property`. Shiki assigns JVM parameter types such
  as `String` to `Keyword` and leaves qualified names such as `System` as
  `Variable`; the shared `storage.*` scope mapper is unchanged.
- Reason: TextMate/Oniguruma is the feasibility-gated subsystem. Top-10
  `*-basic` Shiki semantic golden gates pass on both Android/Joni and iOS/libonig
  (2026-07-04). Full byte-for-byte Shiki parity for all 36+ bundled languages
  remains open.
- Owner or next action: keep Path A closed unless real Session Chat samples show
  repeated visual gaps that justify a new role or the heavier Path B theme-key
  model. `syntax-object` remains available as a resolver token but unused by the
  render-tree role mapping.

## Markdown Parse Performance

- Status: narrowed
- Area: performance
- Shared intent: cold Session Chat open should prepare styled markdown documents
  in roughly interactive time, including large syntax-highlighted code blocks.
- Current state (2026-06-27): manual **Big Session** cold open is **~2.5 s** on
  iOS and Android with libonig on iOS, full-history enrichment on both platforms,
  and no `HighlighterBackendFallback` on the production regex path.
- iOS still round-trips through `OpenCodeMarkdownJson.parseMarkdownJson` before
  Swift decodes the render tree; Android calls `OpenCodeMarkdownParser.parse()`
  directly. The JSON bridge is a remaining optimization target, not a blocker
  for Big Session on current hardware.
- Historical: hybrid regex backend + staged window reached **~27 s** styled
  timeline on the same fixture (2026-06-24 probes).
- Owner or next action: optional JSON-bridge slimming; code-block virtualization
  if larger sessions regress. See `docs/features/session-chat/progress.md`.

## Code Block Layout

- Status: resolved
- Area: layout
- Shared intent: fenced code blocks inside assistant message bubbles should
  scroll horizontally when lines exceed bubble width without truncating glyphs.
- Current iOS state: horizontal `ScrollView` for code and diff blocks is
  constrained with `.frame(maxWidth: .infinity, alignment: .leading)` so the
  viewport matches bubble width while content keeps intrinsic width.
- Current Android state: code and diff line `Text` composables inside
  `horizontalScroll` use `wrapContentWidth(unbounded = true)` so long lines
  measure to full intrinsic width.
- Reason: prior layout measured code text to the viewport width, clipping
  trailing tokens such as `callback` and `return`.
- Owner or next action: none; verify visually in Session Chat when touching
  rendered markdown again.
