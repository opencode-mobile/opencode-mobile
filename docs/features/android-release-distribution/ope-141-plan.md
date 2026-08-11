# OPE-141 — Ship Play Crash Symbolication For Release Builds

## Goal

Make Play Console and Crashlytics crash/ANR stacks readable for release AABs by
embedding native debug symbols and enabling R8 with mapping upload.

Linear: [OPE-141](https://linear.app/opencode-mobile/issue/OPE-141/ship-play-crash-symbolication-for-release-builds).
Parent: [OPE-69](https://linear.app/opencode-mobile/issue/OPE-69/android-release-distribution).

## In Scope

- Pin an NDK version and set `ndk.debugSymbolLevel = SYMBOL_TABLE` on release.
- Enable R8 minify for release with an app `proguard-rules.pro`.
- Keep Crashlytics mapping upload enabled (plugin default) for release.
- Verify release AABs embed ProGuard mapping and native debug-symbol metadata
  in CI before Play upload.
- Retain the R8 mapping file as a longer-lived workflow artifact.
- Document operator acceptance and the shrink-resources deferral.

## Out Of Scope

- Crashlytics NDK / Firebase native symbol upload (still deferred from OPE-72).
- Resource shrinking (`isShrinkResources`) until minify is proven on a
  Play-internal build.
- iOS symbolication / dSYM upload.
- Production / open testing Play tracks.

## Implementation Notes

- Mapping rides inside the AAB under
  `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`.
- Native symbols ride under
  `BUNDLE-METADATA/com.android.tools.build.debugsymbols/`.
- Libraries (Hilt, Room, Serialization, Compose, Ktor, Firebase) supply consumer
  keep rules; app rules keep source/line attributes for Crashlytics.

## Acceptance

1. Android Release CI installs the pinned NDK, builds signed AAB/APK, and passes
   signature plus R8 mapping metadata checks.
2. Play Console no longer warns about missing deobfuscation/mapping for the
   published version.
3. A Crashlytics verification non-fatal for that version is symbolicated in
   Firebase.
4. Operator runbook records the mapping artifact and acceptance steps.
5. Native debug-symbol packaging stays configured; if Play still warns because
   dependency `.so` files are pre-stripped, that limitation is documented rather
   than blocked.
