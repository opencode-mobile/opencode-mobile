# iOS Oniguruma Backend Parity Gaps

## TextMate Regex Engine (parent gap)

- Status: **resolved on iOS production path** (2026-06-26).
- Parent: `docs/features/native-rendered-markdown/parity-gaps.md` → TextMate
  Highlighter.
- Shared intent: iOS and Android use Oniguruma-compatible regex execution for
  bundled TextMate grammars.
- Current iOS state: `OpenCodeLibOnigRegexBackend` (libonig via cinterop) wired
  through `OpenCodePlatformMarkdownHighlighter`.
- Current Android state: `OpenCodeJoniRegexBackend` (Oniguruma-compatible).
- Follow-up: iOS-native regression tests and device benchmarks.

## iOS JSON Parse Bridge

- Status: **unchanged** by this slice.
- Area: performance.
- Shared intent: minimize parse overhead on iOS.
- Current iOS state: `parseMarkdownJson` JSON round-trip per parse.
- Current Android state: direct `OpenCodeMarkdownParser.parse()`.
- Owner: separate follow-up after libonig profiling.

## Cold-Open Full-Block Tokenization

- Status: **unchanged** by this slice.
- Area: performance.
- Shared intent: first frame should not require full syntax highlight of multi-
  thousand-line fences.
- Owner: session-chat virtualization experiment (documented in
  `docs/features/session-chat/progress.md`).
