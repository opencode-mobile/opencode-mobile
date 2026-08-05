# Native Rendered Markdown Progress

## Status

- Status: in progress.
- Ticket: [OPE-109](https://linear.app/opencode-mobile/issue/OPE-109/rendered-markdown-desktop-visual-polish)
  (parent [OPE-16](https://linear.app/opencode-mobile/issue/OPE-16/native-rendered-markdown)).
  Branch: `feature/ope-109-rendered-markdown-desktop-visual-polish`.
- 2026-08-05: **OPE-109** desktop visual polish — inline code bg/weight, bare
  URL autolink, link/quote token chrome, list marker column sizing, shared
  table column widths + expand-to-container, Android streaming layout
  fingerprint + user-only apply hold, table equal-height via SubcomposeLayout.
- 2026-07-31: Doc sync — superseded “TextMate default off” decision; current
  Session Chat / message-store / parity-gap wording now matches format-always
  scaffolds + Phase 6 default-on allowlist (device smoke / Tier B still open).
- 2026-07-30: **OPE-92** format-always / color-later — streaming and history
  never leave detectable markdown as plain source; TextMate on → structure-only
  then Final/enrichment colors; TextMate off → full semantic. Plan:
  `ope-92-structure-first-code-blocks-plan.md`.
- 2026-07-30: Wave 2 Pixel 9 JS remeasure **misses** Tier A (≤2k warm p95
  ~200 ms vs 50 ms; mixed vs Wave 1). Scorecard in `phase-6-calibration.md`.
- 2026-07-30: Wave 2 software OnigScanner (`findEarliest` two-pass at-cursor +
  capped search) wired through tokenizer cursor loops. Pixel remeasure pending.
- 2026-07-30: Wave 1 Pixel 9 JS remeasure **misses** Tier A (≤2k warm p95
  ~268 ms vs 50 ms; ~2× median speedup vs baseline). Proceed to Wave 2
  multi-pattern search. Scorecard in `phase-6-calibration.md`.
- 2026-07-30: Wave 1 TextMate physical latency work landed (see
  `textmate-physical-latency-plan.md`): production engine skips per-search
  RegexSearch/NativeRegexSearch metrics by default; tokenizer earliest-match
  short-circuit; Joni Matcher reuse + touch instead of per-search pin/lease.
  Pixel 9 remeasure pending before Wave 2.
- 2026-07-30: Expanded `phase-6-calibration.md` with logbook pull steps,
  field/gap notes, and a per-language assess/optimize loop for post-close
  speed work and Tier B evidence.
- 2026-07-30: Logbook combo metrics: keep Fast/OK/Slow fence budgets and add
  `chars/ms` · `spans/ms` throughput on measured rows (subtitle + details).
- 2026-07-30: Added Developer Tools TextMate language toggles (A+B: opt out of
  promoted, force-enable packaged) and logbook Fast/OK/Slow speed bands on the
  shared OPE-88 branch. Engineering allowlist v1 remains the default when
  overrides are unset.
- 2026-07-29: Closed TextMate pipeline Phase 6. Global TextMate preference defaults
  on when unset; shared `TextMateLanguageAllowlist` v1 promotes the top-ten
  languages and forces complete semantic fallback otherwise. Android memory /
  power-save hooks and iOS memory / thermal / low-power hooks drive engine trim
  and `setLowPowerOrThermal`. Provisional JVM Tier A latency + short soak are
  recorded in `phase-6-calibration.md`; one engine owner retained. Physical
  Tier B / full 30-minute soak remain a post-close validation note.
- 2026-07-29: Closed TextMate pipeline Phase 5. Shared engine-owned
  `TextMateIncrementalStateStore` keeps immutable per-line checkpoints keyed by
  fence identity, grammar, mapper/repair versions, and policy epoch. Compatible
  revisions resume from a one-line-backed unchanged prefix; suffix convergence
  is enabled after multiline differential fixtures. Observations use
  schemaVersion 2 with reused/retokenized/suffix line counts and
  `IncrementalReuse` metrics. Differential oracle and incremental reuse unit
  tests pass; iOS simulator KMP compiles. Phase 6 work is recorded above.
- 2026-07-29: Closed TextMate pipeline Phase 4. Shared immutable
  `TextMateGrammarProgram` compiles on first grammar load with process-local
  IDs and drops raw JSON from hot caches. Job-local
  `PreparedTextMateLineBuffer` reuses one UTF-8 index map per line across Joni
  and libonig searches. `TextMateWeightedCacheCoordinator` owns grammar / rule
  / regex weights with lease-safe Critical trim; iOS uses `OwnedOnigRegex`
  leases. Phase 4 reuse and lease unit tests plus Joni golden/oracle suites
  pass; iOS simulator KMP compiles. Phase 5 work is recorded above.
- 2026-07-29: Closed TextMate pipeline Phase 3. Shared pure
  `TextMateRequestScheduler` owns priority, latest-wins coalescing, work
  limits, interactive deadlines, trim/low-power admission, and queue metrics.
  Android and iOS adapters remain serial owners and drive one active job.
  Observations record queue wait, cancel-to-stop, and scheduling reason without
  content identity. FinalPlaceholder and streaming plain/fallback publication
  stay independent of a blocked highlighter. Shared scheduler tests and Android
  owner/coalesce/gate tests pass. Phase 4 compiled-program work is recorded
  above.
- 2026-07-29: Closed TextMate pipeline Phase 2. Shared
  `TextMateHighlightingEngine` now upgrades individual finalized fences after
  structural parsing has produced a complete semantic-fallback document. The
  contract carries deterministic opaque fence identity, caller-owned monotonic
  revision, exact source, intent, and policy epoch; pure attachment rejects
  changed documents, fences, identity, revision, policy, source, and failed or
  cancelled results. Android injects one lazily initialized KMP/Joni runtime on
  a single-lane dispatcher behind a mutex. iOS injects one lazily initialized
  KMP/libonig runtime behind a Swift actor. Backend regex caches are
  instance-owned; production no longer depends on process-global Joni or
  thread-local libonig caches. Streaming fallback, final placeholder/upgrade,
  logbook observations, and outer generation publication gates are preserved.
  Shared Android tests, the complete iOS-simulator KMP suite, focused Android
  preparer plus 24-way first-use ownership tests, 17 focused iOS engine/bridge/
  logbook/rendering tests, and an iPhone 17 Pro simulator build/run pass.
  Phase 3 scheduling work is recorded above.
- 2026-07-29: Closed TextMate pipeline Phase 1 on the accepted simulated
  backend matrix. The Pixel 9a Android 17 / API 37 AVD completed 457
  mixed-language cycles over 1,801.760 seconds; retained PSS changed from
  131,491 KiB to 131,849 KiB after five warm-up cycles (+358 KiB, inside the
  8 MiB simulator tolerance), peak PSS was 143,815 KiB, and the longest native
  search was 62.573 ms. The iPhone 17 Pro iOS 26.5 simulator completed 1,128
  cycles over 1,800.941 seconds; live libonig expressions remained 464,
  peak-live remained 464, all 465 allocations were freed after cache clear,
  maximum resident set size was 97,697,792 bytes, and the longest native search
  was 5.794 ms. The frozen 20-message/3,000-line fixture measured 117.713
  seconds cold and 91.938–140.374 seconds warm on the AVD, versus 52.735
  seconds cold and 52.891–53.074 seconds warm on iOS. These are diagnostic
  baselines and show that warm reuse is absent or inconsistent; they are not
  shipping guardrails. The rebuilt Android short soak, focused iOS libonig
  class, and harness builds pass. Phase 2 was subsequently closed above. Physical release
  performance, memory-pressure, bridge, thermal, cancellation, and soak
  calibration remains mandatory in Phase 6 before default enablement.
- 2026-07-29: Added a persisted developer-only TextMate parsing logbook on
  Android and iOS. Each actual finalized code-fence attempt records a
  content-free, request-local observation with timestamp, requested/canonical
  language, UTF-16 message and fence character counts, line and fence position,
  outcome, backend, final span count, total duration, per-stage
  count/total/maximum timings, and diagnostic kinds. Streaming, disabled
  TextMate, and prepared-document cache hits do not create entries. Native
  JSONL stores write off the UI thread, bound submission to 64 pending batches,
  retain the newest 500 entries, compact at 550 entries or 2 MiB, recover valid
  records around corrupt lines, expose dropped/write-error health, and clear
  independently or through Clear All Data. Both Developer Tools screens expose
  refresh, expandable details, and confirmed clear. Shared Android/iOS suites,
  focused Android preparer/store tests, and 3 focused iOS bridge/store tests
  pass.
- 2026-07-29: Resolved the iOS semantic-fallback crash found during Phase 1
  simulator replay. A long unterminated quoted string drove Kotlin/Native's
  combined fallback `Regex.findAll` into 1,216 recursive frames and a stack-
  guard `EXC_BAD_ACCESS`. The fallback tokenizer is now a bounded single-pass
  scanner for quoted strings, line/block comments, numbers, identifiers,
  punctuation, and operators. A 3,968-character unterminated JavaScript string
  regression changed from deterministic signal 11 to passing. Complete shared
  Android/iOS suites pass, and the rebuilt app opened and enriched the original
  12-row `Display JavaScript code block` session without a new crash. The
  remaining Phase 1 gate at that point was physical-device release calibration
  and soak evidence; the later accepted decision above replaced it with the
  simulated closure matrix and moved physical release calibration to Phase 6.
- 2026-07-29: Implemented the Phase 1 TextMate measurement and native-lifetime
  slice. Shared metrics now separate grammar load/lookup, rule resolution,
  regex compile/search, tokenize, repair, cache outcomes, source-size buckets,
  maximum exact native-search duration without recording content identity. iOS
  now accounts every successful libonig allocation/free, releases successful
  decomposition probes, supports explicit cache cleanup, and keys attributed
  code by palette revision. Parallel first-use, cancellation, exact-source,
  native-lifetime, and palette regressions pass. Complete shared Android/iOS
  simulator suites, focused Android pipeline tests, and 12 focused Swift tests
  pass. The debug 20-message/3,000-line reference was 2,542 ms with a 3.934 ms
  maximum native search on the arm64 Android/JVM host and 55,388 ms with a
  50.324 ms maximum native search on an iPhone 17 Pro simulator; it is
  diagnostic only. Phase 1 was still open at that point; the later accepted
  decision above closes it on simulated backends and moves physical release
  calibration to Phase 6. Phase 2 was subsequently closed above.
- 2026-07-29: Locked the TextMate pipeline architecture after independent
  module/concurrency and performance/memory/reliability triages. The five
  architecture choices are recorded in
  [decisions](./decisions.md): a deep asynchronous post-parse engine, one
  instance-owned serial KMP runtime per platform, pooled 24 MiB eviction /
  32 MiB admission guardrails, evidence-owned provisional latency targets, and
  a versioned language promotion allowlist. Phases 1–6 are now closed;
  Phase 4 is next.
- 2026-07-29: Completed a software-architect review and separated the selected
  [TextMate Pipeline Architecture Proposal](./textmate-pipeline-architecture-proposal.md)
  from its six-phase
  [implementation plan](./textmate-pipeline-improvement-plan.md). The proposal
  report and its
  [HTML companion](./textmate-pipeline-architecture-proposal.html) now focus on
  one asynchronous, single-owner engine, including its boundary, request
  lifecycle, incremental fast path, native lifetime, bounded memory, failure
  behavior, and measurement contract. Phase 1 implementation status is recorded
  above.
- 2026-07-28: Reverted `` ```markdown `` nest-depth special case. Fences close on
  the first bare closing marker (CommonMark / opencode web). Parser test
  `markdownFenceClosesOnFirstBareFenceLikeCommonMark` replaces the old nested-
  fence keep-open assertion.
- Shared docs and ADR: added.
- Android runtime: native renderer implemented and WebView asset removed.
- iOS runtime: native SwiftUI renderer implemented and WebView asset removed.
- Shared KMP: render-tree parser module added and wired into Android and iOS.
- Highlighter: Android runtime now has a Joni-backed TextMate path; iOS now uses
  the shared tokenizer with the instance-owned libonig-backed
  `OpenCodeLibOnigRegexRuntime`. The hybrid Kotlin `Regex` path is retained for
  regression comparison only. Android/Joni JavaScript regression fixtures and
  open-rule invariants are active. Full Shiki golden parity remains open.
- Parse threading: Android and iOS now parse markdown off the UI thread,
  reuse the last rendered document where safe, batch streaming updates on a
  16 ms quiet window with a flat 48 ms maximum age, and keep very large
  streaming messages plain until finalization.
- Parse ownership: the domain `DisplayMessagePipeline` and
  `MessageContentPreparer` produce `renderedMarkdown` documents before Session
  Chat applies display snapshots. Session Chat Screen Models consume those
  documents, `OpenCodeRenderedMarkdown` is render-only, and Component Gallery
  uses pre-parsed mock documents.
- Staged publication: small histories are transformed before their first
  publish. Histories with at least 4,000 markdown-candidate characters publish
  a format-first scaffold (structure/semantic), then upgrade TextMate colors
  newest-first and publish at most twice (`enrich-viewport` for the open
  bottom, then `enrich-batch` for the rest) — never per-bubble UI frames that
  look like the chat reprinting. Live streaming updates retain
  transform-before-publish semantics and OPE-92 format-always scaffolds.
- Outgoing overflow: structural markdown content retains collapse/expand;
  long wrapped inline markdown stays fully visible when no expand control can
  be offered.
- Performance pass: preserves the JSON bridge and final TextMate result while
  staging expensive cold-history and large-stream work, removing avoidable
  native render allocations, and building large finalized Android/iOS
  attributed strings outside the UI critical path. iOS also defers timeline
  applies while scrolling, jumps scroll-to-end for long remaining distances,
  reuses per-session prepared markdown on warm reopen as a single `cache-hit`
  (peek attaches cached docs when complete), skips identical REST republishes
  after `lastValue` replay, pins tall timelines via `scrollToItem`, paints
  markdown cells fully in the data model (visible huge cells may paint plain
  for one frame before attaching markdown so scroll stays interactive), and
  reuses unchanged timeline rows so enrich no longer rebuilds the full history
  on every publish. Scroll-to-end / pin paths never call `layoutIfNeeded` on
  tall timelines.
- Performance measurement: Session Chat ViewModel benchmarks load 20 rendered
  markdown messages with 3,000 total TypeScript lines through the production
  highlighter path on each platform.

## Performance Profiling Synthesis

- Date: 2026-06-26.
- Current orchestration call chain: `DisplayMessagePipeline` →
  `MessageContentPreparer` →
  KMP `OpenCodeMarkdownParser.parse()` → fenced-code `parseFence()` →
  `OpenCodeSyntaxHighlighter.highlight()` →
  `OpenCodeTextMateNativeTokenizer.tokenize()`.
- The 2026-06-23 fixture timings below used the earlier Screen Model /
  `RenderedMarkdownMapper.enrichRows()` orchestration. The KMP parser and
  tokenizer hot path measured by that fixture is unchanged.
- Almost all cold-path CPU time lives in KMP. Native app code orchestrates
  when to parse and caches results; SwiftUI and Compose rendering are separate
  from the measured markdown-preparation gap.
- Cheap stages: `OpenCodeMarkdownDetector.shouldRender()`, block walking, and
  inline parsing. Expensive stage: TextMate tokenization of fenced code blocks
  (regex matching at every cursor position on every line).
- Platform benchmark on the 20-message / 3,000-line TypeScript fixture
  (2026-06-23, pre-libonig):
  - Android: `parser-ms=77`, `mapper-ms=52`, `viewmodel-load-ms=104`.
  - iOS: `parser-bridge-ms=958`, `mapper-ms=404`, `viewmodel-load-ms=407`.
- Big Session cold open (2026-06-27, post-libonig, full history): **~2.5 s** on
  iOS and Android in manual device testing.
- Why iOS was slower before libonig despite shared KMP tokenizer code:
  1. **Regex backend (primary, resolved):** Android uses Joni/Oniguruma; iOS now
     uses libonig (`OpenCodeLibOnigRegexBackend`). The Kotlin `Regex` hybrid
     shim is regression-only.
  2. **JSON bridge (secondary, optional):** iOS encodes a parse request and decodes
     the full render-tree JSON; Android calls `OpenCodeMarkdownParser.parse()`
     directly.
  3. **Runtime (tertiary):** KMP on JVM/ART vs Kotlin/Native framework.
- Enrichment scope: iOS and Android expose the full timeline immediately and
  eventually parse every markdown-bearing message. Large histories enrich
  incrementally rather than blocking the initial usable frame.
  See `docs/features/native-rendered-markdown/parity-gaps.md`.

## Completed

- Superseded ADR 0003 with ADR 0004.
- Added `docs/features/native-rendered-markdown/plan.md` and `plan.html`.
- Added KMP `shared/markdown-core` module with render-tree models, detector,
  parser, JSON boundary, diagnostics, and common tests.
- Added a JSON-boundary schema test covering the versioned render tree shape
  used by the iOS Swift bridge.
- Added Shiki semantic golden fixture generation for the Milestone 0 highlighter
  language set and an active KMP fixture-coverage test.
- Added an ignored KMP native-vs-Shiki highlighter comparison test that becomes
  the gate when the TextMate/Oniguruma engine replaces the fallback.
- Added a generated TextMate grammar manifest under
  `shared/markdown-core/src/commonMain/resources/textmate-grammars/manifest.json`
  plus a KMP manifest coverage test.
- Added bundled TextMate grammar JSON resources for the Milestone 0 language set
  under `shared/markdown-core/src/commonMain/resources/textmate-grammars/grammars/`
  so the future native tokenizer has local grammar payloads to load.
- Added shared TextMate manifest decoding, alias lookup, and TextMate
  scope-to-`CodeTokenRole` mapping for the future native tokenizer.
- Added a shared `TextMateGrammarBundle` loader that resolves local grammar
  payloads by language alias, scope name, and resource path, with diagnostics
  for missing, invalid, and scope-mismatched bundled grammar JSON.
- Added a typed TextMate grammar model and decoder for top-level metadata,
  repository entries, include rules, match rules, begin/end rules, captures,
  injections, embedded languages, and tokenizer-relevant flags.
- Added generated grammar-resource metadata fixtures so KMP tests verify the
  pinned manifest and bundled grammar JSON payloads agree on resource path,
  display name, scope name, and non-empty byte size.
- Added Android source-set wiring that packages the grammar payloads as library
  assets and exposes them to Android unit tests as classpath resources.
- Added Android resource loading coverage that reads the actual bundled
  Milestone 0 grammar JSON payloads, decodes them into the shared typed grammar
  model, and verifies every payload exposes tokenizer rules.
- Added a shared `OpenCodeSyntaxHighlighter` pipeline with a TextMate tokenizer
  interface that receives loaded `TextMateGrammarPayload` JSON, preserving
  fallback diagnostics while removing direct parser coupling to the fallback
  highlighter.
- Added an injectable shared TextMate tokenizer scaffold with include
  resolution, `match` rule tokenization, `begin`/`end` rule tokenization,
  captures, nested patterns, and a regex backend abstraction.
- Extended the TextMate tokenizer pipeline so the highlighter passes the loaded
  grammar bundle into tokenization, enabling repository pattern containers and
  external scope includes such as embedded JavaScript/CSS/Markdown grammar
  references to resolve through bundled local grammars.
- Added nested TextMate capture-pattern tokenization so captures can apply their
  own child rules while un-tokenized capture ranges still retain the capture
  scope.
- Added match-rule capture-hole preservation so captured punctuation no longer
  erases parent rule scopes for uncovered text, with bundled diff grammar
  coverage for inserted and deleted content.
- Added the first active Shiki semantic golden subset check: Android/Joni now
  compares the bundled `diff` grammar output against the checked-in Shiki
  `diff` fixture exactly.
- Added active Android/Joni Shiki semantic golden parity for `json`, including
  shared handling for space-delimited TextMate scope names, property-name scope
  precedence over broad type scopes, and semantic string-span merging while
  keeping string delimiter punctuation separate.
- Added active Android/Joni Shiki semantic golden parity for `yaml`, including
  parent-scope preservation around uncaptured `begin`/`end` delimiter ranges so
  plain scalar values keep their first character.
- Added active Android/Joni Shiki semantic golden parity for `css`, including
  begin/end content scanning where earlier nested rules can consume delimiters
  before an outer zero-width end lookahead, plus selective adjacent punctuation
  merging for Shiki-compatible spans such as `);`.
- Added active Android/Joni Shiki semantic golden parity for `html`, including
  primary grammar selection for manifest entries that list embedded dependency
  grammars before the language's own grammar.
- Added TextMate `applyEndPatternLast` ordering for begin/end rules so child
  patterns can win when they start at the same position as an end pattern.
- Added basic TextMate injection selector support with `L:`/`R:` ordering,
  positive scope matching, and comma-separated exclusion scopes, covering the
  bundled HTML stray-angle-bracket injection.
- Added tokenizer line-state scaffolding that can carry an open `begin`/`end`
  rule across physical lines and preserve absolute token offsets.
- Added TextMate `end` pattern back-reference substitution from `begin` captures
  for same-line and cross-line `begin`/`end` rules, covering common grammar
  shapes such as HTML tag pairs and Markdown fences.
- Added TextMate `while` rule state handling so carried block rules can continue
  across matching physical lines and close cleanly when the next line no longer
  matches.
- Added a `HighlighterBackendFallback` diagnostic so tokenization through the
  temporary Kotlin `Regex` backend remains visibly distinct from the future
  Oniguruma-compatible backend.
- Added Android/Joni JavaScript regression fixtures from the HttpClient repro
  (`javascript-request-repro`, `javascript-http-error-repro`,
  `javascript-private-field-template`) with active regression coverage and
  open-rule invariant tests.
- Fixed shared TextMate tokenizer open-rule resume to use `tokenizeUntilEndPattern`
  instead of blind `endPattern` scans, closing template-literal state leaks in
  object literals such as `` url: `${this.#baseUrl}${path}` ``.
- Added zero-width end-pattern cursor handling for rules such as
  `class-declaration` `end: "(?<=})"`.
- Added an iOS hybrid regex backend (`OpenCodeOnigurumaRegexBackend`) wired through
  `OpenCodePlatformMarkdownHighlighter`, with limited `\\G` handling on top of Kotlin
  `Regex`. It remains a temporary non-Oniguruma backend and continues to emit
  `HighlighterBackendFallback` on iOS.
- Added parser highlighter injection so platform renderers can provide a
  grammar-backed highlighter instead of always using the shared fallback.
- Wired Android `OpenCodeRenderedMarkdown` to load bundled TextMate grammars
  from app assets and parse code blocks with the Joni-backed tokenizer path.
- Added a platform highlighter provider for the KMP JSON bridge so iOS parsing
  can use bundled grammar resources instead of always using
  `OpenCodeSyntaxHighlighter.default`.
- Added an iOS bundle resource reader and Xcode build-phase copy for the shared
  `textmate-grammars` resources, with Swift bridge coverage proving the KMP JSON
  parser reaches the TextMate tokenizer path on iOS.
- Updated TextMate scope role mapping to prefer the innermost matching scope so
  capture scopes such as string punctuation override parent scopes correctly.
- Replaced Android `OpenCodeRenderedMarkdown` WebView wrapper with native
  Compose block rendering.
- Replaced iOS `OpenCodeRenderedMarkdown` WKWebView wrapper with native SwiftUI
  block rendering.
- Removed static rendered-markdown HTML assets and the standalone renderer
  package.
- Updated detector tests so inline markdown now enters Rendered Markdown.
- Wired iOS SwiftUI renderer to `OpenCodeMarkdownCore.parseMarkdownJson` and
  removed the temporary Swift parser/highlighter mirror.
- Moved markdown parsing off the UI thread on Android and iOS with
  stale-while-revalidate rendering, streaming update batching, deferred
  highlighting for large live content, and Android Joni regex compile caching.
- Historical: moved markdown parse ownership out of the renderer and into
  Session Chat Screen Models through `RenderedMarkdownMapper`, leaving
  `OpenCodeRenderedMarkdown` render-only and gallery previews backed by mock
  documents.
- Historical: moved Session Chat markdown enrichment into a
  transform-before-publish Screen Model step, removing renderer-owned
  post-publish parse jobs and the interim raw-markdown chat bubble fallback.
- 2026-07-26 / OPE-82: Replaced all-at-once preparation for large cold histories
  with a complete plain-text first frame and cancellable newest-first
  enrichment in the domain display pipeline, which now owns preparation instead
  of Session Chat Screen Models. Added flat 16/48 ms streaming pacing, plain
  rendering for very large live messages and code fences, final-document
  handoff without a blank frame, and background construction of large Android
  attributed strings.
- Unified Android highlight entry points through
  `OpenCodeBundledJoniMarkdownHighlighter` for session chat, component gallery,
  and the KMP platform provider; `Application.onCreate` initializes grammar
  asset access.
- Moved `OpenCodeOnigurumaRegexBackend` into `commonMain` and added shared
  withCache/HttpClient regression coverage for the iOS hybrid backend.
- Added a popular-languages TextMate grammar pack (22 languages beyond Milestone
  0) with Shiki golden fixtures, manifest/resource regeneration, XML grammar
  capture shorthand decoding, capture-depth recursion guards for C/C++-style
  `$self` loops, `java-basic` Joni/iOS-hybrid regression coverage, per-language
  smoke tests, and gallery language-sweep blocks for Java, C#, SQL, and TOML.
- 2026-07-04: Expanded OPE-29 language recognition to the requested top-language
  list through Shiki-backed smoke fixtures and bundled TextMate resources. The
  manifest now has 62 language entries and 74 grammar JSON resources (~4.7 MB),
  including Dart, MATLAB, Groovy, Objective-C, Clojure, CoffeeScript, Fortran
  free/fixed form, Pascal, Visual Basic, Assembly, COBOL, Julia, SAS, ABAP, Apex,
  Crystal, F#, AWK, Nim, Ada, Prolog, Smalltalk, OCaml, and Common Lisp. Scratch
  remains a recognized plain-code fallback because Shiki 4.2.0 has no bundled
  Scratch grammar. Added capture-array normalization for TextMate grammars such
  as Smalltalk.
- 2026-07-04: Fixed the Android `cpp` popularity-snippet regression where the
  Joni TextMate path produced only comment/punctuation roles, leaving most C++
  code grey. Production Android DI now builds the highlighter from app assets
  with the injected application context, and the shared highlighter adds a
  C/C++ lexical role enhancement for obvious comments, strings, keywords, types,
  functions, constants, and numbers until full tokenizer parity covers those
  scopes natively.
- 2026-07-04: Fixed the PHP popularity-snippet regression where Android styled
  `<?php` but left ordinary comments and code grey. The shared highlighter now
  adds PHP lexical role enhancement for comments, strings, variables, keywords,
  types, functions, constants, operators, and numbers while broader TextMate
  tokenizer parity remains incremental.
- 2026-07-04: Closed top-10 `*-basic` Shiki golden gates on both Android/Joni
  (10/10) and iOS/libonig (10/10). iOS Kotlin/Swift/Go gaps required
  `ONIG_OPTION_CAPTURE_GROUP` at libonig compile time. Shrunk layer-7
  `normalizeWebSyntaxRoles` for JS/TS; retained `highlightPrivateFields` and
  `highlightPythonTripleQuotedStrings` for regression fixtures. Added
  capture-depth guards fixing `cpp-basic` stack overflow in smoke tests.
- 2026-07-04: Documented Path A (enriched semantic roles + token mapping) and
  Path B (scope-carrying render tree + bundled Shiki theme) in
  `shiki-visual-parity-paths-plan.md` for layer-6+ visual parity work.
- 2026-07-04: Added Path A implementation plan (`shiki-visual-parity-path-a-plan.md`):
  PA0 audit, PA1 wire existing preset syntax tokens, PA2 mapper alignment.
- 2026-07-04: Started Path A implementation with a multi-agent split. Added the
  PA0 audit artifact and completed PA1 token wiring on Android and iOS:
  `Variable` → `syntax-variable`, `Operator` → `syntax-operator`, and
  `Punctuation` → `syntax-punctuation`, with `Plain` left on body text and
  private field declaration behavior preserved. Added syntax token swatches,
  resolver fallback coverage, and role-to-token tests on both platforms.
- 2026-07-04: Completed the Path A PA2 audit without adding new roles. Raw Shiki
  scope checks kept method-shorthand `async`, TypeScript type fields, JS
  namespace/object receivers, Java `System.out.println`, and C# smoke samples in
  existing semantic buckets. Added scope-mapper tests to lock those decisions and
  marked PA3 role expansion skipped for this pass.
- 2026-07-23: OPE-74 replaced the Android Joni backend's API-33-only
  `OutputStream.nullOutputStream()` call with an API-24-safe no-op stream after
  the incompatibility caused a startup crash in Play-internal
  `0.1.0-internal.5`. Module Android lint is the deterministic API-compatibility
  regression gate. Replacement Play-internal `0.1.0-internal.6` launched
  repeatedly on the affected Pixel 9, and Firebase showed the startup-crash
  issue only for version 5.

## Remaining

- Complete TextMate/Oniguruma feasibility gate and replace fallback code spans.
- Complete the tokenizer beyond the scaffold: full TextMate rule state,
  injections, embedded grammar behavior, line-boundary ASI closure, and
  incremental reuse.
- Review grammar bundle size before release; the bundled grammar set is about
  4.7 MB across 74 JSON files for 62 manifest language entries plus the Scratch
  plain fallback.
- Continue broader Path A follow-up only if real Session Chat samples reveal
  repeated visual gaps that justify a new role or the heavier Path B theme-key
  model.
- Tune outgoing markdown collapse thresholds after device review.
