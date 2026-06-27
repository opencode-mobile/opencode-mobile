# iOS Oniguruma Backend Progress

## Status

- Status: **complete** for Phases 0–4 (simulator measurement recorded; device Big
  Session probe still optional follow-up).
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
- [ ] Device install and Big Session cold-path probe comparison (optional follow-up).

## Android

- [x] Joni backend unit tests still pass after `Utf8IndexMap` extraction.
- [x] Hybrid/Joni benchmark tests moved to `androidUnitTest` (JVM-only APIs).

## Phase 4 Cleanup

- [x] Hybrid shim renamed to `OpenCodeKotlinRegexHybridTextMateBackend` with deprecated
      `OpenCodeOnigurumaRegexBackend` typealias.
- [x] `iosSimulatorArm64Test` added to `ios-checks.yml` CI workflow.
- [ ] Revisit full Shiki golden gate on iOS (`@Ignore` on
      `nativeHighlighterMatchesShikiSemanticGoldenFixtures`) as a follow-up decision.

## Benchmarks (20 messages × 150-line TypeScript fences)

Measured on developer machine (Jun 2026). Android numbers are JVM unit tests;
iOS number is `iosSimulatorArm64Test` on simulator (not device).

| Backend | Platform | Parser ms | Notes |
| --- | --- | ---: | --- |
| Kotlin Regex hybrid | Android JVM test | 4581 | regression shim only |
| Joni | Android JVM test | 1356 | production Android backend |
| libonig | iOS simulator test | 21809 | production iOS backend |

Simulator libonig is slower than Android Joni in this micro-benchmark; treat as
a coarse signal only until a device Session Chat cold-path probe is recorded.
Production iOS no longer emits `HighlighterBackendFallback`.

## Remaining Follow-Ups

- Re-profile Session Chat cold open on iPad with libonig backend.
- Investigate `javascript-http-error-repro` open-rule leak at EOF on libonig (not
  in Joni's strict open-rule fixture subset).
