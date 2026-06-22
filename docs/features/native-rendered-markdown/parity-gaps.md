# Native Rendered Markdown Parity Gaps

## TextMate Highlighter

- Status: temporary
- Area: behavior
- Shared intent: code highlighting should use bundled TextMate grammars with an
  Oniguruma-compatible native backend and Shiki-level breadth.
- Current iOS state: SwiftUI renderer calls the KMP JSON bridge, which loads
  bundled TextMate grammar resources from the app bundle and tokenizes through
  the shared TextMate tokenizer with a Kotlin Regex-based bridge. It has limited
  `\\G` anchoring support, but it is not Oniguruma-compatible and emits
  `HighlighterBackendFallback`.
- Current Android state: Compose renderer loads bundled grammar assets and uses
  the shared TextMate tokenizer with the Joni Oniguruma-compatible backend.
  JavaScript HttpClient regression snippets now pass Joni regression coverage and
  open-rule invariant tests after the shared tokenizer open-rule resume fix.
  EventEmitter-style duplicate span emission (`constconst`, doubled comment
  slashes) is deduplicated in `OpenCodeSyntaxHighlighter`, and partial-overlap
  spans (for example scattered `p`/`l` letters inside `HttpClient` or `patch`)
  are partitioned to non-overlapping intervals and consolidated within identifier
  tokens (including same-role coverage gaps such as `wit` + `h` + `Cache` inside
  `withCache`) before native render. JavaScript private fields (`#baseUrl`,
  `#timeout`, `this.#headers`) are normalized to one `Variable` span per
  `#identifier` when TextMate emits only `meta.class` coverage or mixed
  `entity.name.type` / `property` roles. Both native renderers apply range styles
  with semantic priority so overlapping input cannot cherry-pick letters.
- Reason: TextMate/Oniguruma is the feasibility-gated subsystem. Full
  byte-for-byte Shiki semantic golden parity and some line-boundary ASI cases
  (for example multi-line `const` without semicolon) remain open on both
  platforms.
- Owner or next action: expand active Shiki golden comparisons beyond the
  Android/Joni `diff`, `json`, `yaml`, `css`, and `html` subsets, remove
  `@Ignore` from the KMP native-vs-Shiki highlighter comparison test, and close
  remaining JavaScript line-state parity gaps.

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
