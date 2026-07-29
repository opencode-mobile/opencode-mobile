# Native Rendered Markdown Parity Gaps

## TextMate Developer Logbook

- Status: aligned
- Area: developer observability
- Shared intent: one persisted entry per actual finalized TextMate fence
  attempt, with the same content-free schema, outcomes, UTF-16 counts, stage
  summaries, retention, clear behavior, and store-health reporting.
- Current iOS state: each engine result returns the same content-free
  observation, which is submitted without blocking to a serial Application
  Support JSONL store; Developer Tools exposes the bounded logbook.
- Current Android state: each shared engine result returns the same observation,
  which is submitted without blocking to a serial files-directory JSONL store;
  Developer Tools exposes the same bounded logbook.
- Intentional platform difference: only the native storage location and
  concurrency primitive differ. iOS uses a utility `DispatchQueue` plus lock;
  Android uses a coroutine writer plus bounded channel.
- Owner or next action: retain schema and behavior parity when Phase 6 adds
  device-soak observation fields if needed.

## TextMate Execution And Memory Ownership

- Status: Phases 1–5 closed and aligned; Phase 6 default enablement pending
- Area: architecture and performance
- Shared intent: both apps should expose the same reusable highlighting-engine
  contract and share priority, cancellation, cache budgets, quality outcomes,
  metric names, work limits, and release gates while retaining native regex and
  executor adapters.
- Current iOS state: an injected Swift actor lazily constructs and drives one
  serial KMP/libonig runtime; prepared line buffers, OwnedOnigRegex leases, and
  shared incremental reuse are complete through Phase 5.
- Current Android state: one injected single-lane KMP/Joni runtime; prepared
  line buffers, in-use regex pins, and shared incremental reuse are complete
  through Phase 5.
- Intentional platform difference: regex backend only (Joni vs libonig) and
  lease disposal (`onig_free` vs JVM GC).
- Owner or next action: satisfy the Phase 6 device soak / default-enablement
  gate.

## TextMate Highlighter

- Status: temporary
- Area: behavior
- Shared intent: code highlighting should use bundled TextMate grammars with an
  Oniguruma-compatible native backend and Shiki-level breadth.
- Current iOS state: SwiftUI renderer calls the KMP JSON bridge, which loads
  bundled TextMate grammar resources from the app bundle and tokenizes through
  the shared TextMate tokenizer with `OpenCodeLibOnigRegexRuntime` (libonig via
  Kotlin/Native cinterop). Production iOS no longer emits
  `HighlighterBackendFallback` for the regex backend. The hybrid
  `OpenCodeKotlinRegexHybridTextMateBackend` (Kotlin `Regex` shim; deprecated
  typealias `OpenCodeOnigurumaRegexBackend`) remains in `commonMain` for
  regression comparisons only.
- Current Android state: Session Chat resolves the bundled Joni TextMate
  highlighter only through the injected engine. Component Gallery uses the
  stateless semantic fallback. JavaScript HttpClient and withCache
  regression snippets pass Joni and iOS-hybrid backend regression coverage.
  A top-language TextMate pack is bundled with Shiki golden fixtures and smoke
  coverage for the requested Shiki-supported languages, including Java, C#,
  C/C++, Ruby, PHP, SQL, TOML, XML, Scala, Lua, Docker, Vue, Svelte, GraphQL,
  PowerShell, Perl, Haskell, Elixir, Erlang, R, Zig, Make/Makefile, Dart,
  MATLAB, Groovy, Objective-C, Clojure, CoffeeScript, Fortran free/fixed form,
  Pascal, Visual Basic, Assembly, COBOL, Julia, SAS, ABAP, Apex, Crystal, F#,
  AWK, Nim, Ada, Prolog, Smalltalk, OCaml, and Common Lisp. Fenced blocks in
  those languages no longer hit semantic fallback solely because a grammar is
  missing. Scratch is recognized as a plain-code fallback because Shiki 4.2.0 has
  no bundled Scratch grammar.
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
  (2026-07-04). Full byte-for-byte Shiki parity for all 62 manifest languages
  remains open; Scratch needs a licensed grammar choice before semantic
  highlighting can be claimed.
- Owner or next action: keep Path A closed unless real Session Chat samples show
  repeated visual gaps that justify a new role or the heavier Path B theme-key
  model. `syntax-object` remains deferred and is not part of the active token
  contract.

## Markdown Parse Performance

- Status: narrowed
- Area: performance
- Shared intent: cold Session Chat open should prepare styled markdown documents
  in roughly interactive time, including large syntax-highlighted code blocks.
- Last measured baseline (2026-06-27): manual **Big Session** cold open was
  **~2.5 s** on iOS and Android with libonig on iOS, all-at-once full-history
  enrichment on both platforms, and no `HighlighterBackendFallback` on the
  production regex path.
- Current implementation (2026-07-26 / OPE-82): both platforms expose every
  history row immediately, but histories with at least 4,000
  markdown-candidate characters render plain first and enrich newest-first in
  cancellable increments. A newer live snapshot cancels stale history
  enrichment. No replacement Big Session measurement has been recorded, so the
  earlier ~2.5 s result is historical rather than a claim for the staged
  pipeline.
- iOS still round-trips through `OpenCodeMarkdownJson.parseMarkdownJson` before
  Swift decodes the render tree; Android calls `OpenCodeMarkdownParser.parse()`
  directly. The JSON bridge is a remaining optimization target, not a blocker
  for Big Session on current hardware.
- Historical: hybrid regex backend + staged window reached **~27 s** styled
  timeline on the same fixture (2026-06-24 probes).
- Owner or next action: measure the staged pipeline on a deterministic large
  fixture; optional JSON-bridge slimming or code-block virtualization if larger
  sessions still regress. See `docs/features/session-chat/progress.md`.

## Final Native Annotation Construction

- Status: temporary
- Area: performance
- Shared intent: applying syntax and inline style spans for a large finalized
  document must not block scrolling or input after parsing has completed.
- Current Android state: code, diff, and inline annotated strings at or above
  2,000 characters are built on `Dispatchers.Default`; Compose shows a plain
  attributed-string placeholder until the styled value is ready.
- Current iOS state: code and diff attributed strings at or above 2,000
  characters are built off the main actor via UIKit colors after a plain
  placeholder frame (Android parity). Small fences stay synchronous. Timeline
  applies are deferred while the user is actively scrolling, and scroll-to-end
  jumps without animation when the remaining distance is large.
- Reason: OPE-82 moved the Android construction hot path off composition; iOS
  now mirrors that path for large fences after profiling MainActor hitches on
  Greeting-scale histories.
- Owner or next action: none for large code/diff; revisit large inline-only
  documents if they still hitch without fences.

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
