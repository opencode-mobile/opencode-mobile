# Native Rendered Markdown Parity Gaps

## TextMate Highlighter

- Status: temporary
- Area: behavior
- Shared intent: code highlighting should use bundled TextMate grammars with an
  Oniguruma-compatible native backend and Shiki-level breadth.
- Current iOS state: SwiftUI renderer calls the KMP JSON bridge, which loads
  bundled TextMate grammar resources from the app bundle and reaches the shared
  tokenizer through the temporary Kotlin regex backend. It still emits
  `HighlighterBackendFallback` because the native Oniguruma backend is missing.
- Current Android state: Compose renderer loads bundled grammar assets and uses
  the shared TextMate tokenizer with the Joni Oniguruma-compatible backend.
- Reason: TextMate/Oniguruma is the feasibility-gated subsystem.
- Owner or next action: add the iOS native Oniguruma backend,
  then remove `@Ignore` from the KMP native-vs-Shiki golden comparison test and
  make the checked-in Shiki fixture set pass on Android and iOS.
