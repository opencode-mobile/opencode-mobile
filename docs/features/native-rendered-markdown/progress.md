# Native Rendered Markdown Progress

## Status

- Status: in progress.
- Shared docs and ADR: added.
- Android runtime: native renderer implemented and WebView asset removed.
- iOS runtime: native SwiftUI renderer implemented and WebView asset removed.
- Shared KMP: render-tree parser module added and wired into Android and iOS.
- Highlighter: Android runtime now has a Joni-backed TextMate path; iOS now uses
  the shared tokenizer with a hybrid Apple regex backend that layers explicit
  Oniguruma anchor handling on Kotlin `Regex`. Android/Joni JavaScript regression
  fixtures and open-rule invariants are active. Full Shiki golden parity remains
  open.
- Parse threading: Android and iOS now parse markdown off the UI thread,
  keep the last rendered document visible while newer content parses, debounce
  streaming updates, and defer TextMate highlighting until streaming completes.
- Parse ownership: Session Chat Screen Models now produce
  `renderedMarkdown` documents; `OpenCodeRenderedMarkdown` is render-only and
  Component Gallery uses pre-parsed mock documents.
- Transform-before-publish: Session Chat keeps the timeline in `Loading` until
  the full message list is markdown-enriched, and live streaming updates use a
  debounced enrich step before state is published (no post-publish parse jobs).

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
  stale-while-revalidate rendering, 40ms streaming debounce, semantic fallback
  highlighting while `isStreaming`, and Android Joni regex compile caching.
- Moved markdown parse ownership into Session Chat Screen Models with
  `RenderedMarkdownMapper`, a render-only `OpenCodeRenderedMarkdown`, and gallery
  mock documents.
- Moved Session Chat markdown enrichment into a transform-before-publish step
  with `RenderedMarkdownMapper.enrichRows`, removing post-publish parse jobs and
  the interim raw-markdown chat bubble fallback.
- Unified Android highlight entry points through
  `OpenCodeBundledJoniMarkdownHighlighter` for session chat, component gallery,
  and the KMP platform provider; `Application.onCreate` initializes grammar
  asset access.
- Moved `OpenCodeOnigurumaRegexBackend` into `commonMain` and added shared
  withCache/HttpClient regression coverage for the iOS hybrid backend.

## Remaining

- Complete TextMate/Oniguruma feasibility gate and replace fallback code spans.
- Complete the tokenizer beyond the scaffold: full TextMate rule state,
  injections, embedded grammar behavior, line-boundary ASI closure, and
  incremental reuse needed to pass the full Shiki golden fixtures.
- Keep iOS grammar packaging/resource-reader behavior covered as release
  packaging changes.
- Review grammar bundle size before release; the current Milestone 0 grammar
  bundle is about 1.2 MB across 14 JSON files.
- Expand active Shiki golden comparisons beyond the Android/Joni `diff`, `json`,
  `yaml`, `css`, and `html` subsets and remove the ignored full native-vs-Shiki
  highlighter comparison once the native TextMate engine is in place.
- Add screenshot tests for both native renderers.
- Tune outgoing markdown collapse thresholds after device review.
- Run full manual scroll checks on long Session Chat timelines.

## Verification

- 2026-06-20: `./gradlew :markdown-core:allTests` passed.
- 2026-06-20: `npm run markdown:highlighter:golden` regenerated Shiki semantic
  fixtures, the pinned TextMate grammar manifest, and bundled grammar JSON
  resources.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding
  `TextMateGrammarBundle` loader coverage and generated grammar-resource
  metadata fixtures.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding the typed
  TextMate grammar model and Android classpath resource loading coverage for
  all 14 bundled grammar JSON payloads.
- 2026-06-20: `./gradlew :markdown-core:bundleDebugAar` passed, and the
  generated AAR contains `assets/textmate-grammars/manifest.json` plus 14
  bundled grammar JSON assets.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding the
  injectable TextMate tokenizer scaffold and tests for include resolution,
  `match` rules, `begin`/`end` rules, captures, nested patterns, and innermost
  scope role mapping.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding
  tokenizer line-state coverage, absolute cross-line token offsets, and
  `HighlighterBackendFallback` diagnostics for the temporary Kotlin regex
  backend.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding
  `begin` capture substitution in TextMate `end` patterns, including same-line
  and cross-line back-reference coverage.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding TextMate
  `while` rule line-state coverage.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding
  repository pattern container expansion and external scope include coverage.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding nested
  TextMate capture-pattern tokenization coverage.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding TextMate
  `applyEndPatternLast` ordering coverage.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding basic
  TextMate injection selector coverage.
- 2026-06-20: `./gradlew :markdown-core:allTests` passed after adding the
  Android Joni regex backend and parser highlighter injection coverage.
- 2026-06-20: `./gradlew :app:compileDebugKotlin` passed after wiring Android
  `OpenCodeRenderedMarkdown` to the asset-backed TextMate/Joni highlighter.
- 2026-06-20:
  `./gradlew :app:testDebugUnitTest --tests dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownDetectorTest`
  passed.
- 2026-06-20: `./gradlew :app:compileDebugKotlin` passed.
- 2026-06-20:
  `xcodebuild test -project 'ios/OpenCode Mobile/OpenCode Mobile.xcodeproj' -scheme 'OpenCode Mobile' -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:'OpenCode MobileTests/OpenCodeRenderedMarkdownDetectorTests'`
  passed, including the iOS KMP JSON render-tree decode test.
- 2026-06-21: `./gradlew :markdown-core:compileKotlinIosSimulatorArm64`
  passed after adding the iOS bundled grammar resource reader.
- 2026-06-21:
  `xcodebuild test -project 'ios/OpenCode Mobile/OpenCode Mobile.xcodeproj' -scheme 'OpenCode Mobile' -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:'OpenCode MobileTests/OpenCodeRenderedMarkdownDetectorTests'`
  passed, including coverage that the iOS KMP JSON bridge reaches the bundled
  TextMate grammar path and emits `HighlighterBackendFallback` rather than
  `HighlighterFallback`.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after adding the
  platform highlighter provider and iOS resource reader.
- 2026-06-21: `./gradlew :app:compileDebugKotlin` passed after the shared
  platform highlighter provider change.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after preserving
  match-rule parent scopes around captures and adding bundled diff grammar
  coverage through the Android/Joni backend.
- 2026-06-21: `./gradlew :app:compileDebugKotlin` passed after the match-rule
  capture-hole tokenizer change.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after adding the
  active Android/Joni `diff` Shiki golden parity subset test.
- 2026-06-21: `./gradlew :app:compileDebugKotlin` passed after adding the
  active Android/Joni `diff` Shiki golden parity subset test.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after adding the
  active Android/Joni `json` Shiki golden parity subset test and the scope/merge
  fixes it required.
- 2026-06-21: `./gradlew :app:compileDebugKotlin` passed after adding the
  active Android/Joni `json` Shiki golden parity subset test.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after adding the
  active Android/Joni `yaml` Shiki golden parity subset test and preserving
  parent scopes around uncaptured begin/end delimiter ranges.
- 2026-06-21: `./gradlew :app:compileDebugKotlin` passed after adding the
  active Android/Joni `yaml` Shiki golden parity subset test.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after adding the
  active Android/Joni `css` Shiki golden parity subset test, begin/end content
  scanning for nested delimiter consumers, and selective punctuation span
  merging.
- 2026-06-21: `./gradlew :markdown-core:allTests` passed after adding the
  active Android/Joni `html` Shiki golden parity subset test and preferring the
  language's own grammar over embedded dependency grammars for primary
  tokenization.
- 2026-06-21: `./gradlew :app:compileDebugKotlin` passed after adding the
  active Android/Joni `html` Shiki golden parity subset test.
- 2026-06-21: Moved markdown parsing off the UI thread on Android and iOS,
  added streaming debounce and semantic fallback highlighting while streaming,
  and Android Joni regex compilation caching.
- 2026-06-21: Moved markdown parse ownership into Session Chat Screen Models,
  made `OpenCodeRenderedMarkdown` render-only, and added gallery mock documents.
