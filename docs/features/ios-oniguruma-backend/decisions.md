# iOS Oniguruma Backend Decisions

## Use libonig Through Kotlin/Native cinterop

- Decision: integrate [kkos/oniguruma](https://github.com/kkos/oniguruma) as a
  static library and bind it with Kotlin/Native cinterop in `shared/markdown-core`
  `iosMain`.
- Reason: the TextMate tokenizer already lives in KMP and calls
  `OpenCodeTextMateRegexBackend.find()` per pattern. A Swift-only wrapper would
  force a Kotlin↔Swift boundary on every regex attempt. Android already proves
  the shared tokenizer against Oniguruma semantics via Joni; iOS should bind the
  same engine family directly.
- Alternatives rejected: SwiftPM SwiftOnig (wrong layer), continued Kotlin
  `Regex` shim (temporary scaffold only).
- Date: 2026-06-26.

## Port Joni Backend Behavior, Not Joni Itself

- Decision: implement `OpenCodeLibOnigRegexBackend` by porting the contract and
  index-mapping logic from `OpenCodeJoniRegexBackend`, using libonig C APIs
  underneath.
- Reason: Joni is JVM-only. libonig is the reference implementation TextMate
  grammars target. Shared `Utf8IndexMap` and capture mapping should match Joni
  test expectations.
- Date: 2026-06-26.

## Keep JSON Bridge Out Of This Slice

- Decision: do not remove `OpenCodeMarkdownJson` or change Swift decode types as
  part of the Oniguruma backend work.
- Reason: regex backend and JSON bridge are independent performance costs. Mixing
  them obscures measurement and expands review scope.
- Revisit when: libonig is wired and parser-bridge vs tokenizer time is
  re-profiled on device.
- Date: 2026-06-26.

## Deprecate Hybrid Production Use After Gate Passes

- Decision: once libonig passes backend regression and at least one active Shiki
  golden subset on iOS, remove `OpenCodeOnigurumaRegexBackend` from the iOS
  production highlighter path. Keep the Kotlin Regex shim available for common
  tests if still useful.
- Reason: the hybrid backend exists only as an interim scaffold and emits
  `HighlighterBackendFallback`.
- Date: 2026-06-26.
