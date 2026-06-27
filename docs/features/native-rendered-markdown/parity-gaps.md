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
  `this.#…` member access uses `Property`. Both native renderers map
  `CodeTokenRole` values to web-aligned syntax tokens (`Variable` →
  `text-primary`, `Function` → `syntax-primitive`, `Property` / `Constant` →
  `syntax-constant`, `String` → `syntax-string`, `Number` → `syntax-property`,
  punctuation → `text-primary`). Shiki assigns JVM parameter types such as
  `String` to `Keyword` and leaves qualified names such as `System` as
  `Variable`; the shared `storage.*` scope mapper is unchanged.
- Reason: TextMate/Oniguruma is the feasibility-gated subsystem. Full
  byte-for-byte Shiki semantic golden parity and some line-boundary ASI cases
  (for example multi-line `const` without semicolon) remain open on both
  platforms.
- Owner or next action: expand active Shiki golden comparisons beyond the
  Android/Joni `diff`, `json`, `yaml`, `css`, and `html` subsets, remove
  `@Ignore` from the KMP native-vs-Shiki highlighter comparison test, close
  remaining Java method-signature token gaps, and close remaining JavaScript
  line-state parity gaps.

## Markdown Parse Performance

- Status: temporary
- Area: performance
- Shared intent: cold Session Chat open should prepare styled markdown documents
  in roughly interactive time, including large syntax-highlighted code blocks.
- Current iOS state: `RenderedMarkdownMapper.enrichRows()` calls
  `OpenCodeRenderedMarkdownParser`, which round-trips through
  `OpenCodeMarkdownJson.parseMarkdownJson` before Swift decodes the render tree.
  Highlighting uses `OpenCodeLibOnigRegexBackend` (libonig). Historical
  benchmark on the hybrid backend: 20-message / 3,000-line fixture
  `parser-bridge-ms=958` vs Android `parser-ms=77`. Re-profile after libonig.
  Session Chat enriches only the initial visible window
  (`initialHistoryWindowSize = 3`) before first publish.
- Current Android state: `RenderedMarkdownMapper` calls
  `OpenCodeMarkdownParser.parse()` directly with
  `OpenCodeBundledJoniMarkdownHighlighter` (Joni/Oniguruma). Session Chat
  enriches the full merged timeline on load.
- Reason: the shared hot path is KMP TextMate tokenization inside
  `parseFence()`. iOS still pays extra for the JSON bridge; the regex backend
  gap is closed. Kotlin/Native vs JVM runtime differences add a smaller third
  factor. Big Session profiling showed markdown preparation (~24 s) dominated
  history fetch (~0.12 s) before libonig; re-profile on device after this change.
- Owner or next action: benchmark bypassing or slimming the JSON bridge;
  virtualize or collapse large code blocks on first frame. See
  `docs/features/session-chat/progress.md`.

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
