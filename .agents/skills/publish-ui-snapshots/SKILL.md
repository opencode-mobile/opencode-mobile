---
name: publish-ui-snapshots
description: >-
  Create throwaway UI snapshot tests, generate PNGs (isolated component plus
  in-session Session Chat), and publish an HTML report. Use when the user
  asks to create ui snapshots to illustrate the changes and publish artifacts,
  or mentions UI snapshots, snapshot evidence, or illustrate the changes with
  published artifacts.
---

# Publish UI Snapshots

Generate **throwaway** snapshot tests, run them to produce images, then publish
an HTML sketch with the `publish-artifact` skill. Do not commit tests or PNGs.

Trigger phrase (verbatim):

> create ui snapshots to illustrate the changes and publish artifacts

## Steps

1. Identify the **subject** (default: assistant message), platforms (both unless named), frames (`isolated` + `in-session`), appearance (light; add dark when cheap).
2. Reuse committed mocks/hosts under `SnapshotEvidence/` (iOS) and `ui/snapshot/` (Android). If the subject has no mock, add a small committed mock or stop and name the gap. Never screenshot a live server.
3. Write throwaway tests (names/paths below). Do not edit `project.pbxproj`. Skip writing Android tests when no Layoutlib generate task exists.
4. Generate images — do not verify goldens, do not run UITests, do not run `verifyPaparazziDebug`.
5. Copy PNGs into `.snapshot-evidence/out/` using the filename convention, then run `scripts/write-report.sh`.
6. Load `~/.agents/skills/publish-artifact/SKILL.md` and publish `.snapshot-evidence/out/` as a **sketch** (`--type plan`). Paste the URLs.
7. Do not `git add` throwaway tests, PNGs, HTML, or Paparazzi goldens under `src/test/snapshots/`. Durable mock/host/skill changes may be committed when the user asks.

This step is complete when isolated and in-session PNGs exist for every requested platform that could run, the HTML report labels them, artifact URLs are in the reply, and `git status` does not present throwaway files as intended commits.

## Paths

| Kind | Path |
| --- | --- |
| iOS throwaway tests | `ios/OpenCode Mobile/OpenCode MobileTests/GeneratedSnapshotEvidence/*SnapshotEvidence.swift` |
| Android throwaway tests | `android/app/src/test/java/dev/opencode/mobile/ui/snapshot/GeneratedSnapshotEvidence/*SnapshotEvidence.kt` |
| Output | `.snapshot-evidence/out/` |
| iOS helper | `OpenCode MobileTests/SnapshotEvidenceRenderer.swift` |
| Hosts/mocks | iOS `OpenCode Mobile/SnapshotEvidence/`, Android `ui/snapshot/` |

Filename convention: `{platform}-{frame}-{appearance}.png`  
Examples: `ios-isolated-light.png`, `android-in-session-dark.png`.

## Generate commands

iOS (from `ios/OpenCode Mobile`, after writing the throwaway test):

```bash
OUT="$REPO/.snapshot-evidence/out"
mkdir -p "$OUT"
xcodebuild test \
  -project "OpenCode Mobile.xcodeproj" \
  -scheme "OpenCode Mobile" \
  -destination "platform=iOS Simulator,name=iPhone 16" \
  -only-testing:"OpenCode MobileTests/AssistantMessageSnapshotEvidence" \
  SNAPSHOT_EVIDENCE_OUT="$OUT" \
  CODE_SIGNING_ALLOWED=NO
```

If the simulator name is wrong, use `.github/scripts/select-ios-simulator.sh` / `xcrun simctl list`. Skip iOS on Linux.

Android: skip generate unless `./gradlew :app:tasks` lists `recordPaparazziDebug` or `updateDebugScreenshotTest`. Those plugins are **not** applied on `:app` today (see `docs/features/ui-snapshot-evidence/decisions.md`). Name the gap in the reply and still publish iOS shots. If a generate task exists later, copy PNGs into `.snapshot-evidence/out/` and rename to the filename convention. Never add verify/validate screenshot tasks to Checks.

Then:

```bash
bash "$REPO/.agents/skills/publish-ui-snapshots/scripts/write-report.sh" \
  "$REPO/.snapshot-evidence/out" \
  "UI snapshots"
```

## Throwaway test shape

See [reference.md](reference.md) for copy-paste iOS and Android templates (assistant message). Isolated renders `OpenCodeAssistantMessage` only. In-session renders `SessionChatSnapshotHost` with `SnapshotEvidenceMocks.conversation`.

## Report

Follow publish-artifact **sketch** rules: centered column, `max-width: 72rem`, image grid, viewport meta. `write-report.sh` emits that HTML.
