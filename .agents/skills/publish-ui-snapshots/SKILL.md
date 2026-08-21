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
3. Write throwaway tests (names/paths below). Do not edit `project.pbxproj`.
4. Generate images — do not verify goldens, do not run UITests, do not run `verifyPaparazziDebug`.
5. Copy PNGs into `.snapshot-evidence/out/` using the filename convention, then run `scripts/write-report.sh`.
6. Load `~/.agents/skills/publish-artifact/SKILL.md` and publish `.snapshot-evidence/out/` as a **sketch** (`--type plan`). Paste the URLs.
7. Do not `git add` throwaway tests, PNGs, HTML, or Paparazzi goldens under `src/test/snapshots/`. Durable mock/host/skill changes may be committed when the user asks.

This step is complete when isolated and in-session PNGs exist for every requested platform that could run, the HTML report labels them, artifact URLs are in the reply, and `git status` does not present throwaway files as intended commits.

## Paths

| Kind | Path |
| --- | --- |
| iOS throwaway tests | `ios/OpenCode Mobile/OpenCode MobileTests/GeneratedSnapshotEvidence/*SnapshotEvidence.swift` |
| Android throwaway tests | `android/screenshot/src/test/java/dev/opencode/mobile/ui/snapshot/GeneratedSnapshotEvidence/*SnapshotEvidence.kt` |
| Android Paparazzi goldens | `android/screenshot/src/test/snapshots/images/` |
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

Android (from `android/`, after writing the throwaway test):

```bash
OUT="$REPO/.snapshot-evidence/out"
mkdir -p "$OUT"
./gradlew :screenshot:recordPaparazziDebug \
  --tests 'dev.opencode.mobile.ui.snapshot.GeneratedSnapshotEvidence.*'
SNAP_DIR="$REPO/android/screenshot/src/test/snapshots/images"
for png in "$SNAP_DIR"/*.png; do
  name="$(basename "$png" | sed -n 's/.*_\(android-[^.]*\)\.png$/\1/p')"
  [ -n "$name" ] || continue
  cp "$png" "$OUT/${name}.png"
done
```

Paparazzi lives on `:screenshot`, not `:app`. Applying it to `:app` breaks AGP 9 + Hilt artifact transforms. `:screenshot` uses a Java 21 toolchain (Paparazzi 2 requires class file 65). Never add `verifyPaparazzi*` or `:screenshot:testDebugUnitTest` to Android Checks.

Then:

```bash
bash "$REPO/.agents/skills/publish-ui-snapshots/scripts/write-report.sh" \
  "$REPO/.snapshot-evidence/out" \
  "UI snapshots"
```

## Throwaway test shape

See [reference.md](reference.md) for copy-paste iOS and Android templates (assistant message). Isolated renders `OpenCodeTextInput` / `OpenCodeAssistantMessage` as available on `:screenshot`. In-session renders the subject in screen chrome (`OpenCodeTopBar` + fields, or `SessionChatSnapshotHost` when that host compiles on `:screenshot`).

## Report

Follow publish-artifact **sketch** rules: centered column, `max-width: 72rem`, image grid, viewport meta. `write-report.sh` emits that HTML.
