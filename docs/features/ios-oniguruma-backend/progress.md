# iOS Oniguruma Backend Progress

## Status

- Status: **complete** for Phases 0–4.
- Blocks: none for production wiring.
- Android: unchanged (Joni remains production backend).

## Shared Planning

- [x] Plan written (`plan.md`).
- [ ] ADR if build/link approach becomes hard to reverse (optional).

## KMP (`shared/markdown-core`)

- [x] Vendor libonig source via `native/build-oniguruma-ios.sh` (downloads oniguruma 6.9.9).
- [x] cinterop `oniguruma.def` for iOS targets.
- [x] `OpenCodeLibOnigRegexBackend` in `iosMain`.
- [x] Wire `OpenCodePlatformMarkdownHighlighter` to libonig.
- [x] Fix `ArrayIndexOutOfBoundsException` from invalid `addressOf(bytes.size)` end pointers.
- [x] Shared `Utf8IndexMap` extracted to `commonMain` (Joni backend reuses it).
- [x] Multiplatform test resource reader (`expect`/`actual`) for bundled grammars.
- [x] `iosSimulatorArm64Test` suite: backend contract, regression, `diff-basic` golden,
      open-rule invariants (Joni-aligned fixture subset), benchmark.
- [x] Link `libonig` into iOS **test** binaries (`binaries.all { linkerOpts … }`).
- [x] Stage test grammars beside `test.kexe` (`syncIosTestResources` Gradle task).

## iOS App

- [x] `embedAndSignAppleFrameworkForXcode` succeeds through Xcode simulator build.
- [x] `linkDebugFrameworkIosSimulatorArm64` and `compileKotlinIosArm64` succeed.
- [x] Big Session cold open on iOS device matches Android (~2.5 s; see below).

## Android

- [x] Joni backend unit tests still pass after `Utf8IndexMap` extraction.
- [x] Hybrid/Joni benchmark tests moved to `androidUnitTest` (JVM-only APIs).

## Phase 4 Cleanup

- [x] Hybrid shim renamed to `OpenCodeKotlinRegexHybridTextMateBackend` with deprecated
      `OpenCodeOnigurumaRegexBackend` typealias.
- [x] `iosSimulatorArm64Test` added to `ios-checks.yml` CI workflow.
- [ ] Revisit full Shiki golden gate on iOS (`@Ignore` on
      `nativeHighlighterMatchesShikiSemanticGoldenFixtures`) as a follow-up decision.

## Benchmarks

### Big Session cold open (manual, device)

- Date: 2026-06-27.
- Fixture: local **Big Session** (large syntax-highlighted code response).
- Result: **~2.5 s** from open to styled timeline on iOS, matching Android in
  manual testing after libonig shipped and iOS returned to full-history enrichment.
- Prior baseline (2026-06-24, hybrid regex backend): markdown preparation alone
  took **~24 s** for the initial visible window; see
  `docs/features/session-chat/progress.md`.

### KMP micro-benchmark (20 messages × 150-line TypeScript fences)

Measured on developer machine (Jun 2026). Android numbers are JVM unit tests;
iOS number is `iosSimulatorArm64Test` on simulator (not comparable to app
cold open).

| Backend | Platform | Parser ms | Notes |
| --- | --- | ---: | --- |
| Kotlin Regex hybrid | Android JVM test | 4581 | regression shim only |
| Joni | Android JVM test | 1356 | production Android backend |
| libonig | iOS simulator test | 21809 | KMP native test harness overhead |

Production iOS no longer emits `HighlighterBackendFallback`. App-level Big
Session timing is the authoritative performance signal for this feature.

## Remaining Follow-Ups

- Investigate `javascript-http-error-repro` open-rule leak at EOF on libonig (not
  in Joni's strict open-rule fixture subset).
- Optional: slim or bypass the iOS JSON bridge if profiling shows it is still
  material on other fixtures.
