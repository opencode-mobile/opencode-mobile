---
name: publish-ui-snapshots
description: "UI snapshots: use when the user asks to generate and publish isolated or in-session snapshot evidence."
---

# Publish UI snapshots

Trigger phrase, verbatim:

> create ui snapshots to illustrate the changes and publish artifacts

## 1. Define the snapshot matrix

Default subject: assistant message. Default platforms: iOS and Android. Default
frames: `isolated` and `in-session`. Default appearance: light. Add dark when it
is cheap.

**Completion criterion:** Every requested subject has an explicit platform,
frame, and appearance matrix, including every default the user did not
override.

## 2. Establish deterministic render hosts

Reuse committed mocks and hosts under `SnapshotEvidence/` on iOS and
`ui/snapshot/` on Android. If the subject has no mock, add a small committed
mock or stop and name the gap. Use deterministic mock data. Never screenshot a
live server.

For the default subject, isolated frames render `OpenCodeTextInput` or
`OpenCodeAssistantMessage` as available on `:screenshot`. In-session frames
render the subject in screen chrome with `OpenCodeTopBar` plus fields, or
`SessionChatSnapshotHost` when that host compiles on `:screenshot`.

**Completion criterion:** Every frame in the matrix has a deterministic,
platform-native render host and mock data, or a named missing-host blocker.

## 3. Write throwaway tests

Read only the matching platform section in
[snapshot recipes and templates](reference.md). Write tests in the gitignored
generated paths named there. Keep Xcode project membership unchanged. Do not
edit `project.pbxproj`.

**Completion criterion:** Every runnable platform has one throwaway test that
generates every requested frame and appearance with the required filename.

## 4. Generate PNGs

Run the matching generation recipe from
[reference.md](reference.md#ios-generation) or
[reference.md](reference.md#android-generation). Record snapshots only. Do not
verify goldens, run UITests, or run `verifyPaparazziDebug`.

**Completion criterion:** A PNG exists for every runnable matrix entry, and
each unavailable platform has a concrete environment or compilation blocker.

## 5. Build the report

Copy every PNG to `.snapshot-evidence/out/` with this filename convention:

```text
{platform}-{frame}-{appearance}.png
```

Examples: `ios-isolated-light.png`, `android-in-session-dark.png`.

Then run:

```bash
bash "$REPO/.agents/skills/publish-ui-snapshots/scripts/write-report.sh" \
  "$REPO/.snapshot-evidence/out" \
  "UI snapshots"
```

The script emits the required sketch layout: centered column,
`max-width: 72rem`, image grid, and viewport metadata.

**Completion criterion:** `.snapshot-evidence/out/` contains every generated
PNG under the exact filename convention, and `index.html` labels every image.

## 6. Publish the sketch

Load `~/.agents/skills/publish-artifact/SKILL.md`. Publish
`.snapshot-evidence/out/` as a sketch with `--type plan`, then paste the
returned URLs.

**Completion criterion:** The artifact is published, and the response contains
every returned access URL.

## 7. Keep evidence throwaway

Keep throwaway tests, PNGs, HTML, and Paparazzi goldens under
`src/test/snapshots/` out of commits. Do not `git add` them. Durable mock, host,
or skill changes may be committed only when the user asks.

**Completion criterion:** `git status` distinguishes every throwaway artifact
from durable requested changes, and no throwaway file is staged or presented as
an intended commit.
