# Model Selection — Progress

- [x] Shared product behavior documented.
- [x] iOS model/provider repository and parser added.
- [x] Android model/provider repository and parser added.
- [x] Session Chat model picker uses `/config/providers` options.
- [x] Variant picker replaces the prior Thinking Effort mock.
- [x] Prompt send includes selected `model` and only sends `variant` when
  non-default.
- [x] Unit coverage added for provider parsing and prompt request mapping.

## Verification

- Android unit tests were attempted with `./gradlew testDebugUnitTest` on
  2026-05-30, but this environment has no Java runtime (`JAVA_HOME` unset and
  `java` missing).
- iOS tests were attempted with `xcodebuild test` on 2026-05-30, but this
  environment does not include `xcodebuild`.
