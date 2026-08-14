# Task plan — `publish-ui-snapshots` skill

Implements [`plan.md`](./plan.md). First prove the loop on **assistant
message**, then reuse the same pattern for other subjects.

## User invocation (verbatim)

> create ui snapshots to illustrate the changes and publish artifacts

The skill description must include that phrase. Also match: “UI snapshots”,
“illustrate the changes”, “publish artifacts”, “snapshot evidence”.

## Skill contract

Path: `.agents/skills/publish-ui-snapshots/SKILL.md`  
List it from `AGENTS.md` Skill Usage. Load `publish-artifact` for the upload
step (same composition as `publish-prototype`).

Do **not** set `disable-model-invocation`. The agent should pick this up from
the trigger phrase.

### Steps the skill must run

1. **Identify the subject** from the user’s change or an explicit name
   (default example: assistant message). Platforms: both, unless named.
   Frames: isolated + in-session. Appearance: light; add dark when the
   helper already supports it.
2. **Reuse committed mocks / fixture host.** If the subject has no mock,
   add a small committed mock in the same change as the throwaway test, or
   stop and say what is missing. Do not screenshot a live server.
3. **Write throwaway tests** into gitignored paths (below). One iOS test
   file, one Android preview/test file. Names include `SnapshotEvidence` so
   they are easy to exclude from `git add`.
4. **Generate images** (not verify):
   - iOS: `xcodebuild test` `-only-testing:` the throwaway test class, or
     equivalent Swift Testing filter. Helper writes PNGs under
     `.snapshot-evidence/out/`.
   - Android: if `recordPaparazziDebug` (or an equivalent Layoutlib
     generate task) exists, run it and copy PNGs into
     `.snapshot-evidence/out/`. If it does not, skip Android and name the
     gap. Do not add verify/validate screenshot tasks to Checks.
5. **Build the HTML sketch** in `.snapshot-evidence/out/index.html`:
   centered page, `max-width: 72rem`, grid of labeled shots (platform,
   frame, appearance). Follow `publish-artifact` sketch rules.
6. **Publish** with the `publish-artifact` skill (`--type plan`, screenshot
   gallery). Paste the printed URLs in the user-facing reply.
7. **Do not commit** throwaway tests, PNGs, or HTML. Do not `git add -f`
   gitignored files. Durable mock/host/skill changes may be committed when
   the user asks.

### Completion check

- Isolated and in-session PNGs exist for every requested platform that
  could run.
- HTML report shows them with labels.
- Artifact URLs are in the reply.
- `git status` does not show the throwaway tests as intended commits.

## Gitignore (committed)

```text
.snapshot-evidence/
ios/OpenCode Mobile/OpenCode MobileTests/GeneratedSnapshotEvidence/
android/app/src/test/java/dev/opencode/mobile/ui/snapshot/GeneratedSnapshotEvidence/
android/app/src/test/snapshots/
**/*SnapshotEvidence.swift
**/*SnapshotEvidence.kt
```

Keep these tight enough that CI never uploads them, loose enough that the
skill can drop files without pbxproj/Gradle source-set edits.

iOS tests go in `ios/OpenCode Mobile/OpenCode MobileTests/` (synchronized
root group). Android throwaway tests go in gitignored
`GeneratedSnapshotEvidence/` under the existing unit-test source set once a
Layoutlib plugin is wired.

## Committed scaffolding (first slice)

1. **`SessionChatSnapshotHost`** (iOS + Android): phone-sized column;
   production `OpenCodeTopBar` (title only), timeline using the same row
   views as Session Chat, idle composer. Input: mock timeline items +
   session title. No ViewModel, no network.
2. **Assistant-message mocks:** one assistant text block (markdown-capable
   if that is the change), plus a short user outgoing message so the
   in-session frame has conversation context. Prefer mapping through
   existing `SessionChatTimelineBlock` types.
3. **iOS helper:** `ImageRenderer` at 390×844 (or intrinsic height capped
   to phone width), scale 2 or 3, writes PNG. Light/dark via
   `.openCodeTheme()` / color scheme override.
4. **Android:** committed fixture host and mocks only. Do not apply Compose
   Preview Screenshot Testing (ktlint duplicate task) or Paparazzi on `:app`
   until a version works with AGP 9, JDK 17, ktlint, and Hilt. Never add
   `validateDebugScreenshotTest` or `verifyPaparazziDebug` to
   `android-checks.yml`.
5. **HTML template** in the skill reference (not a committed report).

## Throwaway test shape (assistant message)

Isolated: `SessionChatSnapshotHost` is **not** used; render the assistant
row view (rendered markdown / timeline text block) on `backgroundBase`.

In-session: `SessionChatSnapshotHost` with `[user outgoing, assistant
subject, …]` so the subject sits in real chat chrome.

Same mock copy on both platforms.

## Prove it

After scaffolding + skill exist, run the trigger phrase against the current
assistant-message UI, publish the report, and confirm git status is clean of
PNGs/tests. Only then treat other subjects (tool card, child-session row)
as a catalog the skill can pick from.
