# Hex Code Agent Guide

Hex Code is an experimental voice-first app for opencode, for iPhone and
Android. It is the second app in this repository, next to OpenCode Mobile.
"Hex Code", "hexcode", and "Hex" all mean this app. What it does and how to
install it: [README.md](README.md) in this folder.

The root [AGENTS.md](../AGENTS.md) applies in full: public-repository and
sensitive-data rules, code practices, API and security assumptions, issues
and branches, and pull request evidence. This file adds what is specific to
Hex Code, and wins where the two differ. Claude Code loads this file only
when it works inside `hexcode/`, so the root guide points here too.

## Code Map

| Path | What is there |
|---|---|
| `android/hexcode/` | Android app, Gradle module `:hexcode`, package `dev.opencode.hexcode` |
| `ios/OpenCode Mobile/Hex Code/` | iOS app (SwiftUI) |
| `ios/OpenCode Mobile/Hex CodeTests/` | iOS unit tests |
| `ios/OpenCode Mobile/Hex Code.xcodeproj` | Xcode project, scheme `Hex Code`, separate from `OpenCode Mobile.xcodeproj` |
| `android/opencode-kit/`, `ios/OpenCodeKit/` | Network and data layer shared with OpenCode Mobile |
| `shared/voice-engine/` | On-device dictation: Gradle module `:voice-engine`, Swift package `VoiceEngineKit` |
| `hexcode/store-listing/` | Store screenshots and graphics |

`hexcode/` also holds symlinks to the code: `android`, `ios`,
`opencode-kit-android`, `opencode-kit-ios`, and `voice-engine`. Editing
through them changes the real files; git, CI, and GitHub use the real paths.

## Shared Code

- `android/opencode-kit/` and `ios/OpenCodeKit/` are used by both apps. A
  change there affects OpenCode Mobile too: build and test both apps
  (`:app` and `:hexcode` on Android, the `OpenCode Mobile` and `Hex Code`
  schemes on iOS). CI runs both apps' checks for kit changes.
- The kits hold no Hex Code screens or design; those stay in the Hex Code
  paths above.

## Design System

Hex Code has its own design system and does not use OpenCode Mobile's
(`docs/design-system/` describes OpenCode Mobile's). It lives in:

- Android: `android/hexcode/src/main/java/dev/opencode/hexcode/designsystem/`
  (`HexTheme` with colors, type, shape, and motion; `HexIcons`; components in
  `components/`).
- iOS: `ios/OpenCode Mobile/Hex Code/DesignSystem/` (`HexTheme` with colors,
  type, and shape; `HexIcons`; components in `Components/`).

The root guide's component-first order applies with this system: use an
existing `Hex…` component, otherwise add or extend one on both platforms
before building the screen. Keep a component's name and behaviour the same
on both platforms (`HexTalkButton.kt` and `HexTalkButton.swift`). The
component gallery (`HexGallery`) shows every token and component in its
states; open it from Settings › Developer, or on iOS debug builds with the
launch argument `-HexCodeGallery YES`.

## Branches And Pull Requests

- Hex Code lives on `main`. Fixes and features go as branches and pull
  requests off `main`, named as the root guide says. The old
  `feature/hex-experiment` branch is history; do not base work on it.
- Pull request titles for Hex-only work usually start with `Hex Code:`.

## Checks

- `.github/workflows/hexcode-android-checks.yml`: ktlint and Android Lint
  (soft-fail) and unit tests for `:hexcode` and `:voice-engine`.
- `.github/workflows/hexcode-ios-checks.yml`: builds the `Hex Code` scheme
  and runs its unit tests.
- Each one runs only when a pull request touches its app's paths; its
  `changes` job lists them. Those paths include the shared kits and
  `shared/voice-engine/`. OpenCode Mobile's `android-checks.yml` and
  `ios-checks.yml` leave out the Hex Code paths but still run for kit
  changes.

## Test Builds

Hex Code is not in the App Store or on Google Play. Test builds go only to
the `hexcode-android` and `hexcode-ios` tester groups in Firebase App
Distribution, through `.github/workflows/hexcode-app-distribution.yml`:

- Nightly at 02:00 UTC, when `main` has Hex Code changes from the last 26
  hours. The workflow's `changes` job lists the paths that count.
- On demand, with `platform` set to `all`, `android`, or `ios`:

  ```bash
  gh workflow run hexcode-app-distribution.yml --ref main -f platform=all
  ```

  Adding `-f source_ref=<sha>` builds the head commit of an open pull request
  from this repository instead.
- On a pull request, the maintainer can comment `/distribute hex`,
  `/distribute hex android`, or `/distribute hex ios`
  (`distribute-comment.yml`).

`play-listing/`, `release-notes/`, and the store release workflows belong to
OpenCode Mobile, not Hex Code.

## Versions

Hex Code has its own version, not the repository `VERSION` file. The base
version is `versionName` in `android/hexcode/build.gradle.kts` and
`MARKETING_VERSION` in `Hex Code.xcodeproj` (both `0.1.0` now). The iOS
distribution job fails unless every Xcode configuration has the same
`MARKETING_VERSION`. Distribution builds stamp the version per run:

- Android: version name `<versionName>-hex.<run number>`, for example
  `0.1.0-hex.42`, and version code `1000000000 + <run number>`.
- iOS: marketing version `<MARKETING_VERSION>`, for example `0.1.0`, and
  build number `1000000000 + <run number>`.
