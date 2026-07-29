# Native Rendered Markdown Decisions

## Phase 1 Closes On Simulated Backends; Shipping Calibration Moves To Phase 6

- Decision: Phase 1 uses one named simulated backend per platform: the Pixel 9a
  AVD on Android 17 / API 37 for Joni and the iPhone 17 Pro iOS 26.5 simulator
  for libonig. Five warm-up cycles precede a 30-minute mixed-language soak over
  TypeScript, Kotlin, Swift, Python, JSON, and shell. The opt-in soak harness is
  excluded from ordinary CI unless a duration is supplied.
- Decision: Android records process PSS after forced GC/finalization and permits
  8 MiB of simulator noise after warm-up. iOS requires the live compiled-regex
  count to remain stable after warm-up and requires allocation/free equality
  after explicit cache clear. Peak PSS, peak live regexes, longest native call,
  and simulator timings are diagnostic evidence only.
- Decision: Phase 1 simulator evidence gates correctness, concurrency,
  cancellation, allocation ownership, retained-state stability, and readiness
  to introduce the single-owner engine. It does not establish real-device
  latency, thermal, memory-pressure, or end-to-end bridge guarantees.
- Decision: representative physical low- and mid-tier devices, release builds,
  end-to-end bridge inclusion, thermal state, latency distributions, peak and
  retained memory, cancellation, and a repeated soak remain mandatory in Phase
  6 before TextMate is enabled by default.
- Reason: the user accepted emulator/simulator evidence for the architectural
  migration, while retaining a production gate where hardware-dependent
  evidence can affect user-visible default enablement.
- Evidence: Android completed 457 mixed-language cycles over 1,801.760 seconds
  with +358 KiB retained PSS after warm-up, 143,815 KiB peak PSS, and a 62.573
  ms longest native search. iOS completed 1,128 cycles over 1,800.941 seconds
  with 464 retained/peak live expressions, 465 allocations matched by 465
  frees after clear, 97,697,792 bytes maximum RSS, and a 5.794 ms longest
  native search.
- Evidence: the 20-message/3,000-line fixture measured 117.713 seconds cold and
  91.938–140.374 seconds across five warm samples on the Android AVD. It
  measured 52.735 seconds cold and 52.891–53.074 seconds warm on the iOS
  simulator. The inconsistent/absent warm improvement is a Phase 2–5
  optimization baseline, not a shipping gate.
- Date: 2026-07-29.

## TextMate Logbook Is Request-Scoped, Bounded, And Content-Free

- Decision: an observed parser envelope may return one
  `TextMateParseObservation` for each actual finalized fence attempt while the
  existing document-only parser remains available. The observation is built
  from a request-local metrics accumulator passed explicitly through grammar
  lookup and tokenization; there is no global or thread-local "current parse."
- Decision: observations may contain timestamp, requested/canonical language,
  UTF-16 character and line counts, fence position, outcome, backend, span
  count, total duration, aggregate stage timings, and diagnostic kinds. They
  must not contain Markdown/code source, session/message/block/fence identity,
  file paths, diagnostic messages, regex patterns, or grammar payloads.
- Decision: each native app owns a serial JSONL journal outside the shared
  parser. Submission is non-blocking and bounded to 64 pending batches; storage
  retains the newest 500 entries and compacts after 550 entries or 2 MiB.
  Corrupt records are ignored. Queue overflow and I/O failure never block or
  fail rendering and are surfaced as logbook health metadata.
- Decision: the logbook is a Developer Tools surface, persists across launches,
  has an explicit confirmed clear action, and participates in Clear All Data.
  Streaming, disabled TextMate, and prepared-document cache hits create no
  entries because no TextMate fence attempt occurred.
- Reason: the pipeline needs durable field evidence without putting disk I/O,
  mutable telemetry context, high-cardinality identity, or user content on the
  rendering critical path.
- Date: 2026-07-29.

## TextMate Pipeline Uses One Asynchronous, Single-Owner Engine

- Decision: structural Markdown first produces a complete plain or
  semantic-fallback document. A deep asynchronous
  `TextMateHighlightingEngine` in `shared/markdown-core` may then upgrade one
  fence at a time through an opaque fence key, caller-owned monotonic revision
  token, exact source, language, intent, and policy epoch. The outer display
  pipeline remains the only visible-publication authority and revalidates
  generation, fence identity, revision, exact source, streaming state, and
  policy before attaching spans or mutating visible decoration caches.
- Decision: each platform initially uses one active, instance-owned serial KMP
  runtime for all mutable grammar, rule, regex, queue, incremental, and eviction
  state. Android adapts it through an injected single-lane dispatcher; iOS uses
  a serial KMP executor or worker, optionally behind a Swift actor. Production
  mutable caches may not remain process-global or thread-local. A second
  isolated shard requires device evidence and independent mutable/native state.
- Decision: 24 MiB is the provisional pooled retained-cache eviction threshold
  and 32 MiB is the admission ceiling. Accounted state includes decoded
  grammars, rule plans, compiled regexes, incremental checkpoints, and cached
  highlight results. Packaged resources are separate; active-job, bridge, and
  leased allocations are peak-working-set metrics. Phase 1 may revise the
  figures only through a new evidence-backed decision.
- Decision: the documented latency values remain owned engineering hypotheses
  until measured on frozen low- and mid-tier device matrices. Plain publication
  belongs to `DisplayMessagePipeline`; queueing and service belong to the
  engine; native-call duration belongs to the regex adapter. Material engine,
  backend, grammar, repair, scheduler, or compiler changes require an explicit
  rebaseline.
- Decision: keep one global user-facing TextMate preference, backed by a
  versioned promoted-language allowlist. A language is promoted only after its
  Joni and libonig correctness, representative Session Chat, latency, fallback,
  native-lifetime, memory, and soak gates pass. Other languages retain complete
  semantic fallback.
- Reason: independent architecture triages agreed that the current synchronous
  parser-owned path, shared mutable caches, raw iOS native-pointer lifetime, and
  unbounded per-search allocation cannot provide reusable, measurable, or
  reliable highlighting. The locked design keeps optional decoration away from
  source publication while concentrating scheduling, memory, lifetime, and
  measurement inside one deep module.
- Implementation gate: Phase 1 instrumentation and lifetime-safety code is
  complete and simulator-verified. The fixed debug reference fixture is 20
  finalized messages and 3,000 TypeScript code lines. On 2026-07-29 it measured
  2,542 ms in the Android/Joni JVM test on an arm64 macOS 26.5.2 host, with a
  3.934 ms maximum native search, and 55,388 ms for iOS/libonig on an iPhone 17
  Pro iOS 26.5 simulator, with a 50.324 ms maximum native search. These are
  diagnostic reference values, not release guardrails.
- Implementation gate: Phase 1 is closed on the accepted Pixel 9a Android 17 AVD
  and iPhone 17 Pro iOS 26.5 simulator evidence recorded above. Phase 2 has not
  started and additionally requires a deterministic fence locator, pure
  span-attachment operation, exact-source correctness check, monotonic revision
  token, and asynchronous platform-to-KMP adapter. Physical release calibration
  is a Phase 6 default-enablement gate.
- Date: 2026-07-29.

## Markdown Fences Close Like CommonMark / Web

- Decision: Fenced code blocks close at the first bare closing fence of the same
  marker. There is no special nest-depth for `markdown`/`md` fences.
- Reason: The prior nest-depth extension (from highlighting regression work)
  kept LLM doc samples wrapped in `` ```markdown `` as one literal CodeBlock,
  while opencode web renders post-first-close content as real markdown. Mobile
  approximates web chat rendering; first-fence-wins restores that parity.
- Date: 2026-07-28.

## TextMate Highlighting Is Opt-In Via Settings

- Decision: TextMate grammar highlighting runs only when Settings → Developer
  Tools → **TextMate Highlighting** is enabled. Default is **off**; code fences
  use the semantic-fallback highlighter instead.
- Reason: TextMate is the expensive path on open and settle; semantic fallback
  keeps Session Chat responsive while still coloring common tokens. Power users
  can opt into full grammar fidelity.
- Date: 2026-07-28.

## Semantic Fallback Tokenization Is Iterative And Bounded

- Decision: the shared semantic fallback highlighter tokenizes quoted strings,
  line and block comments, numbers, ASCII identifiers, punctuation, and
  operators with a single-pass scanner. It does not use a combined
  `Regex.findAll` over an entire fence. Unterminated strings and block comments
  extend to the end of the fence and remain complete semantic spans.
- Reason: Kotlin/Native's recursive regex implementation overflowed the iOS
  worker stack on a 3,968-character unterminated JavaScript string. The failure
  terminated the app with `EXC_BAD_ACCESS` before Swift could recover. The
  scanner makes fallback work linear and bounded while preserving the complete
  fenced-code source.
- Date: 2026-07-29.

## Native Renderer Replaces WebView

- Decision: Rendered Markdown uses native SwiftUI and Compose rendering rather
  than local WebView islands.
- Reason: WebView startup, measurement, and bridge lifecycle costs caused chat
  scroll instability.
- Date: 2026-06-20.

## Shared Parser Contract

- Decision: parsing and render-tree contracts live in `shared/markdown-core`.
- Reason: the markdown subset and diagnostics should stay aligned across iOS and
  Android while UI remains platform native.
- Date: 2026-06-20.

## iOS Uses JSON Render Tree

- Decision: iOS calls `OpenCodeMarkdownCore.parseMarkdownJson` and decodes the
  versioned render tree into Swift structs before SwiftUI rendering.
- Reason: the JSON boundary avoids exposing Kotlin parser internals to Swift
  while still letting `shared/markdown-core` own parsing and diagnostics.
- Date: 2026-06-20.

## Performance Work Preserves the Current Display Pipeline

- Status: superseded on 2026-07-26 by **Stage Expensive Display Preparation**
  below.
- Decision: optimize native renderer allocations and shared span processing
  without changing all-at-once Session Chat loading, full TextMate
  highlighting, or the iOS JSON boundary.
- Reason: these are the directly observable large inefficiencies; changing
  parsing ownership, loading order, or the bridge would be a broader display
  architecture rewrite without a measured need.
- Date: 2026-06-22.
- Revisit when: Big Session and fixture benchmarks show the dominant cost is KMP
  TextMate tokenization in `parseFence()`, with iOS further penalized by the
  hybrid regex backend and JSON bridge. Profiling synthesis (2026-06-26)
  identifies those as the primary iOS/Android gap, not block parsing or native
  UI layout. Reconsider preserving the JSON boundary and deferring an iOS
  Oniguruma backend once code-block virtualization has been benchmarked.

## Stage Expensive Display Preparation

- Decision: keep the shared parser and final native render tree, but stage work
  that would block chat interaction. Streaming snapshots use a 16 ms quiet
  window with a flat 48 ms maximum batch age; messages at or above 8,000
  streaming characters stay plain until finalization. Initial histories with at
  least 4,000 markdown-candidate characters publish all rows as plain text,
  then enrich markdown-bearing messages newest-first with cancellation and
  yields between publications.
- Reason: parsing and publishing every token-sized snapshot wastes TextMate and
  native annotation work, while one all-at-once cold-history parse delays
  scrolling and live updates. Flat pacing prevents longer replies from becoming
  progressively less responsive.
- Consequence: source text and history scope are unchanged. Finalized messages
  still converge on the complete TextMate-rendered document. Android constructs
  large finalized annotated strings outside composition; the remaining iOS
  synchronous attributed-string construction is tracked in
  [parity gaps](parity-gaps.md#final-native-annotation-construction).
- Date: 2026-07-26.
- Revisit when: deterministic device benchmarks justify different thresholds or
  incremental parser support eliminates the need for plain placeholders.

## Markdown Parse Hot Path Lives In KMP

- Finding: cold-path markdown preparation time is dominated by
  `shared/markdown-core`, specifically `parseFence()` →
  `OpenCodeSyntaxHighlighter.highlight()` →
  `OpenCodeTextMateNativeTokenizer.tokenize()`. Detector, block structure, and
  inline parsing are comparatively cheap.
- Reason: Session Chat and fixture benchmarks isolate history fetch,
  mapper orchestration, and parser/tokenizer work. Big Session shows ~24 s
  markdown preparation vs ~0.12 s history fetch.
- Date: 2026-06-26.

## iOS Hybrid TextMate Regexes Are Compiled Once Per Pattern

- Decision: cache the Kotlin `Regex` instance for each TextMate pattern in the
  hybrid iOS backend path.
- Reason: unlike Android's Joni backend, iOS delegated every non-`\\G` match to
  a newly compiled Kotlin regex. Grammar tokenization retries the same patterns
  at many cursor positions, making compilation dominate long fenced blocks.
- Date: 2026-06-23.

## Inline Markdown Uses Rendered Markdown

- Decision: inline code, emphasis, and links now mount Rendered Markdown when
  markdown markers exist.
- Reason: after removing WebView cost, smooth chat scrolling no longer requires
  limiting rendering to block markdown only.
- Date: 2026-06-20.

## TextMate Gate Stays Explicit

- Decision: semantic fallback highlighting is allowed only as scaffold and must
  remain documented until TextMate/Oniguruma passes the gate.
- Reason: pretending the fallback is Shiki-level would hide the hardest part of
  the feature.
- Date: 2026-06-20.

## Shiki Golden Fixtures Are Test Artifacts

- Decision: Shiki is used only by the dev fixture generator
  `markdown:highlighter:golden`, which writes checked-in semantic token
  fixtures for the native highlighter gate.
- Reason: Shiki remains the reference oracle for TextMate-style token ranges,
  while app runtime code stays fully native and does not ship JavaScript.
- Date: 2026-06-20.

## Golden Parity Can Close Per Language

- Decision: the native-vs-Shiki gate may gain active per-language subset tests
  before the full fixture suite is unignored.
- Reason: small exact comparisons against real bundled grammars make progress
  visible without lowering the final bar. The Android/Joni `diff` fixture is the
  first active subset because it exercises opencode-relevant inserted/deleted
  highlighting with an Oniguruma-compatible backend.
- Date: 2026-06-21.

## TextMate Scopes Map Centrally

- Decision: raw TextMate scopes are converted to `CodeTokenRole` in
  `shared/markdown-core` before either native UI sees code spans.
- Reason: SwiftUI and Compose should resolve the same semantic roles through
  design-system tokens instead of duplicating scope parsing per platform.
- Date: 2026-06-20.

## Grammar Payloads Are Bundled Locally

- Decision: the Shiki fixture generator writes TextMate grammar JSON resources
  into `shared/markdown-core` for the Milestone 0 language set.
- Reason: the native tokenizer must load deterministic local grammar payloads
  and must not depend on Shiki, JavaScript modules, or network access at
  runtime.
- Date: 2026-06-20.

## Grammar Loading Is A Shared Boundary

- Decision: `shared/markdown-core` owns a `TextMateGrammarBundle` loader that
  resolves local grammar payloads from a resource reader before tokenization.
- Reason: the tokenizer should consume validated local JSON payloads, while
  Android and iOS remain free to provide the final app-packaged resource source.
- Date: 2026-06-20.

## Primary Grammar Selection Prefers The Requested Language

- Decision: when a manifest entry lists embedded dependency grammars before the
  requested language grammar, `primaryGrammarFor` chooses the descriptor whose
  name or aliases match the requested language before falling back to the first
  loaded grammar.
- Reason: Shiki language modules such as HTML can bundle JavaScript and CSS
  grammars before the HTML grammar itself. Treating the first descriptor as
  primary tokenized HTML as JavaScript and blocked HTML golden parity.
- Date: 2026-06-21.

## Grammar JSON Decodes Before Tokenization

- Decision: loaded grammar payloads include both the original JSON object and a
  typed `TextMateGrammar` tree for tokenizer-relevant fields.
- Reason: the native tokenizer should operate on validated grammar structure
  rather than ad hoc JSON lookups, while still leaving raw JSON available for
  edge cases during the TextMate port.
- Date: 2026-06-20.

## Android Packages Grammar Payloads As Assets

- Decision: the Android target packages `shared/markdown-core` grammar payloads
  as library assets and mirrors them into Android unit-test resources.
- Reason: Android runtime code can provide an asset-backed resource reader, and
  unit tests can verify the exact bundled grammar JSON payloads decode into the
  shared grammar model.
- Date: 2026-06-20.

## Tokenizer Scaffold Is Injectable Only

- Decision: `OpenCodeTextMateNativeTokenizer` exists as an injectable shared
  tokenizer scaffold using a replaceable regex backend; it is not the default
  production highlighter.
- Reason: include resolution, `match` rules, `begin`/`end` rules, captures, and
  nested pattern behavior can be developed and tested before the
  Oniguruma-compatible backend is ready, while the final Shiki parity gate stays
  explicit.
- Date: 2026-06-20.

## Tokenizer State Is Line-Oriented

- Decision: the tokenizer scaffold exposes `TextMateLineState` and
  `tokenizeLine` so open `begin`/`end` rules can carry across physical lines.
- Reason: real TextMate engines tokenize line by line with persistent rule
  stacks; the native implementation needs this shape before it can support
  streaming, incremental reuse, and Shiki-comparable token ranges.
- Date: 2026-06-20.

## End Patterns Resolve Begin Captures

- Decision: `begin`/`end` tokenization resolves numeric back-references in
  `end` patterns from the corresponding `begin` match captures and stores the
  resolved end pattern in carried line state.
- Reason: many bundled TextMate grammars use this behavior for paired
  delimiters, including HTML closing tags and Markdown fences; without it,
  line-state tokenization cannot converge on Shiki-compatible ranges.
- Date: 2026-06-20.

## While Rules Carry Block State

- Decision: the tokenizer scaffold stores resolved `while` patterns in carried
  line state and keeps a rule open only while each following line matches that
  continuation pattern.
- Reason: bundled TextMate Markdown grammars use `while` rules heavily for
  blockquotes, lists, fenced code, paragraphs, and tables; modeling this now
  reduces the gap between the scaffold and a Shiki-comparable tokenizer.
- Date: 2026-06-20.

## Includes Resolve Through The Grammar Bundle

- Decision: `OpenCodeSyntaxHighlighter` passes the loaded
  `TextMateGrammarBundle` into tokenizer calls, and the tokenizer expands
  repository pattern containers plus external scope includes from that bundle.
- Reason: the Milestone 0 grammars rely on grouped repository rules and embedded
  scopes such as HTML including JavaScript/CSS or Markdown including fenced-code
  language grammars. Resolving these locally is required before Shiki fixture
  comparisons can represent real grammar behavior.
- Date: 2026-06-20.

## Capture Patterns Tokenize Nested Content

- Decision: TextMate captures may run their own nested patterns inside the
  captured range, and any uncovered parts of the range keep the capture scope.
- Reason: bundled grammars use capture patterns for structured inline regions
  such as Markdown links/headings and HTML attributes; preserving nested scopes
  is required for the innermost scope mapping to approach Shiki-compatible
  semantic roles.
- Date: 2026-06-20.

## Match Captures Preserve Parent Rule Scope

- Decision: when a `match` rule emits capture tokens, uncovered parts of the
  matched range keep the parent rule scope.
- Reason: TextMate grammars often capture punctuation separately from the rest
  of the semantic region. The bundled diff grammar uses this shape for
  inserted/deleted lines; dropping parent-scope holes kept `+` and `-`
  punctuation but lost the inserted/deleted content roles needed for Shiki
  parity.
- Date: 2026-06-21.

## Begin And End Captures Preserve Parent Rule Scope

- Decision: `begin` and `end` matches preserve the parent rule scope for
  uncaptured ranges, using generic `captures` as a fallback when
  `beginCaptures` or `endCaptures` are absent.
- Reason: real grammars can use the delimiter match as semantic content, not
  just punctuation. The bundled YAML grammar starts plain scalar values with the
  `begin` match itself; dropping that uncaptured range lost the first character
  and blocked YAML golden parity.
- Date: 2026-06-21.

## TextMate Scope Names Can Contain Multiple Scopes

- Decision: TextMate `name` and `contentName` strings are split on whitespace
  into ordered scope stacks before semantic role mapping.
- Reason: bundled grammars use names such as
  `string.json support.type.property-name.json`. Treating the whole string as
  one scope loses the innermost property-name role and blocks JSON parity.
- Date: 2026-06-21.

## Semantic Span Merging Is Role-Aware

- Decision: adjacent semantic spans with the same non-punctuation role are
  merged after TextMate scope mapping. Adjacent punctuation spans are merged
  only when neither side is a string delimiter punctuation token.
- Reason: Shiki groups JSON string delimiters with their string contents but
  keeps punctuation such as closing property quotes and colons as separate
  tokens, while also grouping punctuation clusters such as CSS `);`, Markdown
  `](`, and TypeScript `})`. Role-aware merging lets native output match those
  semantics without hiding string delimiter boundaries.
- Date: 2026-06-21.

## Begin/End Content Scans Compare Child Rules With End Rules

- Decision: begin/end tokenization scans child rules and the current rule's end
  pattern together instead of precomputing the parent end before tokenizing
  nested content.
- Reason: parent end patterns can be zero-width lookaheads that occur inside a
  child begin/end rule. The bundled CSS grammar's property-value rule can end
  before `)`, while a nested `var(...)` function must consume that same `)`
  first to match Shiki's semantic token ranges.
- Date: 2026-06-21.

## End Pattern Ordering Is Rule-Owned

- Decision: begin/end rules that set `applyEndPatternLast` let child patterns
  match before an end pattern when both start at the same offset.
- Reason: bundled Markdown and Shell grammars use this TextMate flag to parse
  embedded inline structures and function definitions. Treating every end
  pattern as first-priority would prevent the native tokenizer from converging
  on Shiki-compatible token ranges.
- Date: 2026-06-20.

## Injection Selectors Are Scope-Aware

- Decision: the tokenizer scaffold supports basic TextMate injection selectors,
  including `L:`/`R:` ordering, positive scope matching, and comma-separated
  exclusions.
- Reason: the bundled HTML grammar uses an injection to mark stray angle
  brackets only in normal HTML text while excluding comments, embedded content,
  and tag scopes. Applying injections without selectors would create noisy
  false tokens and make Shiki parity harder to reason about.
- Date: 2026-06-20.

## Temporary Regex Backend Emits Diagnostics

- Decision: tokenization through the temporary Kotlin `Regex` backend emits
  `HighlighterBackendFallback`.
- Reason: the scaffold is useful for developing rule/state behavior, but it is
  not Oniguruma-compatible and must not be confused with the final highlighter
  gate.
- Date: 2026-06-20.

## Android Uses Joni For The First Oniguruma Backend

- Decision: Android uses Joni as the first Oniguruma-compatible regex backend
  for `OpenCodeTextMateNativeTokenizer`, while iOS still requires a native
  backend before the highlighter gate can close.
- Reason: Joni is a JVM/Android-compatible Java port of Oniguruma, which lets
  Android exercise real Oniguruma regex features against the shared tokenizer
  while the iOS cinterop backend remains separate work.
- Date: 2026-06-20.

## Parser Accepts A Platform Highlighter

- Decision: `OpenCodeMarkdownParser.parse` accepts an `OpenCodeCodeHighlighter`
  parameter, Android `OpenCodeRenderedMarkdown` provides an asset-backed
  TextMate/Joni highlighter, and the KMP JSON bridge resolves a platform
  highlighter for Swift callers.
- Reason: the shared parser should remain platform-neutral, while each native
  app owns how bundled grammar resources and regex backends are loaded at
  runtime.
- Date: 2026-06-20.

## iOS Loads Shared Grammar Resources Through The KMP Bridge

- Decision: the iOS build phase copies the shared `textmate-grammars` resources
  into the app bundle, and `iosMain` provides a bundle-scanning resource reader
  for the KMP JSON parser bridge.
- Reason: SwiftUI should keep calling `OpenCodeMarkdownCore.parseMarkdownJson`
  without owning grammar loading, while KMP still consumes the same pinned
  grammar payloads as Android. The temporary Kotlin regex backend remains
  visible through `HighlighterBackendFallback` until the iOS Oniguruma binding
  exists.
- Date: 2026-06-21.

## Scope Mapping Prefers Inner Scopes

- Decision: TextMate scope-to-role mapping checks the scope stack from innermost
  to outermost before applying role priority.
- Reason: capture scopes such as string delimiters or regex groups must be able
  to override parent scopes in the semantic role conversion used by both
  platforms.
- Date: 2026-06-20.

## Parser Calls A Highlighter Pipeline

- Decision: markdown parsing calls `OpenCodeSyntaxHighlighter`, which can use a
  TextMate tokenizer with loaded grammar payload JSON when available and
  otherwise emits semantic fallback spans with a `HighlighterFallback`
  diagnostic.
- Reason: the parser should not know whether highlighting came from the fallback
  scaffold or the future TextMate/Oniguruma engine.
- Date: 2026-06-20.

## Android Uses One Bundled Joni Highlighter Factory

- Decision: Android session chat, component gallery, and
  `OpenCodePlatformMarkdownHighlighter` all resolve highlighting through
  `OpenCodeBundledJoniMarkdownHighlighter`. `OpenCodeAndroidMarkdownAssets.init`
  runs from `Application.onCreate` so asset-backed grammar loading is available to
  gallery mocks and the KMP platform provider.
- Reason: gallery and chat must exercise the same TextMate/Joni path during
  manual QA and CI; a separate semantic-fallback gallery path hid iOS-only
  tokenizer regressions.
- Date: 2026-06-21.

## iOS Hybrid Regex Backend Lives In commonMain

- Decision: `OpenCodeOnigurumaRegexBackend` lives in `commonMain` so shared
  regression tests can exercise the iOS production backend without an iOS-only
  test target.
- Reason: the hybrid backend is pure Kotlin and should be covered by the same
  withCache and HttpClient regression assertions used for Joni.
- Date: 2026-06-21.
