# Research: On-Demand Screenshot Evidence (iOS and Android)

**Date:** 2026-08-13  
**Question:** How can this repo produce screenshots on demand (agent-triggered
or `workflow_dispatch`, not every PR) as evidence that a feature UI is working
on iOS and Android?  
**Primary sources:** Apple XCTest / Simulator `simctl` / `xcresulttool`, Google
Compose Preview Screenshot Testing and `adb`, Cash App Paparazzi, Roborazzi,
GitHub Actions artifacts, Linear file-upload MCP, this repo’s Checks workflows
and UI harnesses.  
**Canonical note:** this file (committed under `docs/features/ci-pr-gating/`).
Research only — no implementation.

**Product plan:** throwaway generate-and-publish snapshots live under
[`docs/features/ui-snapshot-evidence/`](../ui-snapshot-evidence/plan.md).

## Verdict

Treat this as **evidence capture**, not golden-file visual regression, and
**not UITests**.

UITests are the wrong tool for on-demand screenshots. They pay for a second
process, SpringBoard/launcher, accessibility queries, and animation waits in
order to *drive* the app. Evidence only needs: put a known screen on screen,
then grab pixels. Keep existing UITests for behavior (tap, filter, scroll);
do not add a UITest per screenshot.

**Do this:**

1. **Agent / local capture (available now).** Launch the Debug app with a
   harness flag, dump a PNG, publish it off-git.
   - iOS: XcodeBuildMCP `screenshot` or `xcrun simctl io booted screenshot`.
   - Android: `adb exec-out screencap -p` on a connected device or emulator.
2. **Optional later: a `workflow_dispatch`-only evidence job.** Not a required
   check. iOS on the Mac mini **launches the harness and calls `simctl`** —
   not `xcodebuild test` on `OpenCode MobileUITests`. Android on fedora-box
   dumps **host-side** preview/snapshot PNGs (no emulator); full-app Android
   shots stay on a local device or Firebase Test Lab.

If the subject is isolated chrome (a pill, a row) and full-app fidelity is
not required, skip the running app and render a **preview** (Compose Preview
Screenshot Testing on the JVM; SwiftUI `ImageRenderer` or an existing
`#Preview`). That is the fast path. Full-window evidence still needs a
harness + screenshot.

**Snapshot tests** (golden compare) are the fast visual-*lock* tool, not the
on-demand evidence tool. They beat UITests on speed. They still commit or
diff PNGs, render isolated views not the running app, and turn “looks
different” into a CI failure. Use them only if the goal changes to
regression of gallery/preview chrome. See [What if we use snapshot
tests?](#what-if-we-use-snapshot-tests).

**Do not do this:** golden-file visual regression in Android Checks or iOS
Checks. Google’s own screenshot-testing definition is compare-against-golden,
which implies committed PNGs, Linux-vs-macOS pixel drift, and a different
product from “show me this screen works.” That conflicts with
`docs/documentation.md` (never commit raw screenshots into canonical docs) and
with `docs/features/ci-pr-gating/plan.md` (Android emulator / instrumented
tests are out of scope for PR Checks).

iOS UITests **exist** and already attach screenshots. That proves Apple’s
attachment API; it is **not** the recommended evidence pump. iOS Checks
**does not run them** (`-only-testing:"OpenCode MobileTests"`). Android
Checks runs JVM unit tests only.

---

## Constraints this repo already owns

| Constraint | Source |
| --- | --- |
| Do not commit raw screenshots into canonical docs | `docs/documentation.md` §5.1 / routing cheat sheet; `docs/features/README.md` (`progress.md` durability) |
| Store-listing JPGs under `test-screenshots/` are an intentional exception | `docs/features/android-release-distribution/operator-runbook.md`; `play-listing/README.md` |
| Android emulator / instrumented tests out of PR Checks | `docs/features/ci-pr-gating/plan.md` Out Of Scope |
| iOS Checks: unit tests only | `.github/workflows/ios-checks.yml` (`-only-testing:"OpenCode MobileTests"`) |
| Android Checks: `testDebugUnitTest` only | `.github/workflows/android-checks.yml` |
| Cursor Cloud Linux VM: no KVM, no Android emulator, no iOS | `AGENTS.md` Cursor Cloud notes |
| iOS Checks / Build run on Mac mini with a real Simulator | `docs/mac-mini-runner.md`; `.github/scripts/select-ios-simulator.sh` |
| Android Test Lab is a separate main-only Robo workflow | `.github/workflows/android-test-lab.yml`; operator-runbook |
| Both apps have a Component Gallery | iOS `ComponentGalleryHomeView`; Android `ComponentGalleryEntry`; Gallery And Preview Contract in `docs/design-system/components/README.md` |

---

## 1. Apple XCTest screenshots and attachments

**Yes: UI tests can dump PNGs without asserting pixels.** This repo already
does that.

### Capture APIs

[`XCUIScreenshot`](https://developer.apple.com/documentation/xcuiautomation/xcuiscreenshot)
is “a captured image of a screen, app, or UI element state.” Types that
conform to `XCUIScreenshotProviding` (`XCUIScreen`, `XCUIElement`,
`XCUIApplication`) expose `.screenshot()`. Apple’s own sample takes a
screenshot and stops there — no pixel compare:

```swift
let mainScreenScreenshot = XCUIScreen.main.screenshot()
let windowScreenshot = app.windows.firstMatch.screenshot()
```

The class also exposes `pngRepresentation` (PNG `Data`) and `image`
(platform-native). Writing `pngRepresentation` to a file is a dump, not a
golden test.

### Attachments (keep on pass)

[`XCTAttachment`](https://developer.apple.com/documentation/xctest/xctattachment)
wraps files, images, or screenshots for later analysis.
[`init(screenshot:)`](https://developer.apple.com/documentation/xctest/xctattachment/init(screenshot:))
stores PNG (`public.png`).
[`init(screenshot:quality:)`](https://developer.apple.com/documentation/xctest/xctattachment/init(screenshot:quality:))
stores PNG at `.original` and JPEG at `.medium` / `.low`.

Apple’s
[Adding Attachments to Tests, Activities, and Issues](https://developer.apple.com/documentation/xctest/adding-attachments-to-tests-activities-and-issues)
is explicit:

- Default lifetime is `.deleteOnSuccess` so passing tests do not keep
  attachments.
- Set `.keepAlways` to persist even when the test passes.
- The documented “take a screenshot of the main window” sample does **not**
  compare pixels:

```swift
let screenshot = app.windows.firstMatch.screenshot()
let attachment = XCTAttachment(screenshot: screenshot)
attachment.lifetime = .keepAlways
add(attachment)
```

This repo’s LaunchTests already follow that sample:

```21:32:ios/OpenCode Mobile/OpenCode MobileUITests/OpenCode_MobileUITestsLaunchTests.swift
    func testLaunch() throws {
        let app = XCUIApplication()
        app.launch()
        // ...
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Launch Screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
```

Feature UITests (`OpenCode_MobileUITests.swift`) currently assert
accessibility / existence, not pixels. They already launch fixture screens
via env flags (below). Reuse those **flags**. Do not reuse the UITest
runner just to take a picture — `simctl io screenshot` after a harness
launch is the same PNG without XCTest overhead.

### Result bundles

`xcodebuild -help` (local Xcode on this Mac) documents:

```text
-resultBundlePath PATH    specifies the directory where a result bundle
                          describing what occurred will be placed
```

Apple’s
[Running tests and interpreting results](https://developer.apple.com/documentation/xcode/running-tests-and-interpreting-results)
says `xcodebuild test` produces an `.xcresult` bundle that can be opened in
Xcode. [Xcode 16.3 Release Notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-16_3-release-notes)
added `xcresulttool export attachments`. Local help:

```text
xcrun xcresulttool export attachments --path <bundle.xcresult> --output-path <dir>
```

Exports all attachments (or `--test-id` / `--only-failures`) plus a
`manifest.json`. That is the CI-friendly way to turn
`XCTAttachment(screenshot:)` into loose PNGs for `upload-artifact`.

[`xcresulttool`](https://developer.apple.com/documentation/xcode/xcode-command-line-tool-reference)
is an Xcode-owned CLI (`man xcresulttool`).

---

## 2. `simctl io screenshot` and XcodeBuildMCP

### First-party CLI

`xcrun simctl io help` (this machine):

```text
screenshot [--type=<type>] [--display=<display>] [--mask=<policy>] <file or url>
    Saves a screenshot as a PNG to the specified file or url (use "-" for stdout).
    --type       png, tiff, bmp, gif, jpeg. Default is png.

Example:
    simctl io booted screenshot screenshot.png
```

Apple’s archived
[iOS Simulator Guide](https://developer.apple.com/library/archive/documentation/IDEs/Conceptual/iOS_Simulator_Guide/InteractingwiththeiOSSimulator/InteractingwiththeiOSSimulator.html)
documents the same `xcrun simctl io booted screenshot` command. WWDC19
“Getting the Most Out of Simulator” calls `simctl io screenshot` the way to
automate Simulator screenshots
([session 418](https://developer.apple.com/videos/play/wwdc2019/418/)).

No XCTest, no pixel assert, no result bundle. Needs a booted simulator and
the app already on the target screen.

### XcodeBuildMCP (this environment)

The configured `user-XcodeBuildMCP` server exposes:

- `screenshot` — “Capture screenshot.” `returnFormat`: `path` | `base64`.
- `snapshot_ui` — semantic runtime UI snapshot with `elementRef` targets
  (structure, not pixels).

Official package: [getsentry/XcodeBuildMCP](https://github.com/getsentry/xcodebuildmcp)
([tools list](https://github.com/getsentry/XcodeBuildMCP/blob/main/docs/TOOLS.md)
lists `screenshot` and `snapshot_ui` under simulator / UI automation).
Vendor docs: [xcodebuildmcp.com](https://xcodebuildmcp.com).

An agent on this Mac can `build_run_sim`, navigate (or launch with a harness
flag), then `screenshot`. That is the fastest evidence loop. It does not run
on fedora-box, Cursor Cloud Linux, or GitHub-hosted Linux.

---

## 3. Google Compose Preview Screenshot Testing

Official:
[Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)
(experimental / alpha). Companion:
[Screenshot testing](https://developer.android.com/training/testing/ui-tests/screenshot).

| Question | Answer (from those pages) |
| --- | --- |
| Need an emulator? | **No.** Host-side via Layoutlib (“runs the tests on the host”). |
| Record on demand? | **Yes.** Gradle `updateDebugScreenshotTest` / `update{Variant}ScreenshotTest`, or IDE gutter “Add/Update reference images.” |
| Commit goldens? | **The intended workflow does.** Reference PNGs land in `{module}/src/screenshotTest{Variant}/reference`. Google: “Reference (or golden) images are usually PNG files that can be checked into your source control.” |
| What is a “screenshot test”? | Compare a new render to an approved golden. Failures produce a report; the response is either fix the UI or replace the golden. |

Current plugin docs (Panda / AGP 9 path): AGP 9.0+, Kotlin 2.2.10+, JDK 17,
plugin `com.android.compose.screenshot` (example `0.0.1-alpha15`). This repo
already has AGP `9.0.1` and Kotlin `2.2.21`
(`android/gradle/libs.versions.toml`) and JDK 17 in Checks.

[Release notes](https://developer.android.com/studio/preview/compose-screenshot-testing-release-notes)
moved reference images out of production source sets specifically so goldens
are not packaged into the app — they are still source-controlled test
assets.

**Evidence-only use without committing:** run `updateDebugScreenshotTest` (or
render into `build/`) and upload the PNGs / HTML report as a workflow
artifact, then discard the working tree. That fights the tool’s default
(goldens in `src/`). It also renders **`@Preview` composables**, not the
installed app with navigation chrome.

Google warns that host-side pixels differ across Linux / macOS / Windows. If
this were ever used as a gate, record on the same host that verifies (fedora-box).
For on-demand evidence that is less important: one host, one dump.

**Not in this repo today** (no `screenshotTest` source set, no
`com.android.compose.screenshot` plugin).

---

## 4. Paparazzi and Roborazzi (JVM, no emulator)

Neither is in the tree today.

### Paparazzi (Cash App)

[github.com/cashapp/paparazzi](https://github.com/cashapp/paparazzi) /
[cashapp.github.io/paparazzi](https://cashapp.github.io/paparazzi/):

> Render your Android screens without a physical device or emulator.

- `paparazzi.snapshot { MyComposable() }` in JVM unit tests.
- `./gradlew :module:testDebug` generates an HTML report at
  `module/build/reports/paparazzi/` “showing all test runs and snapshots”
  **without** requiring committed goldens.
- `recordPaparazziDebug` saves goldens to `src/test/snapshots` (Git LFS
  recommended).
- `verifyPaparazziDebug` diffs against those goldens.

For **evidence**, `testDebug` + upload of `build/reports/paparazzi/` matches
“dump, don’t commit.” `record` / `verify` is visual regression.

### Roborazzi

[github.com/takahirom/roborazzi](https://github.com/takahirom/roborazzi)
README:

- JVM tests via [Robolectric Native Graphics](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.10)
  (`@GraphicsMode(GraphicsMode.Mode.NATIVE)`).
- `recordRoborazziDebug` / `-Proborazzi.test.record=true` writes to
  `build/outputs/roborazzi` and an HTML report at
  `build/reports/roborazzi/index.html`.
- `verifyRoborazziDebug` fails on pixel diff against saved images.

Google’s screenshot-testing page classifies host-side tools as Layoutlib
(Compose Preview / Paparazzi-class) vs Robolectric Native Graphics
(Roborazzi-class). RNG can exercise more Android framework; Layoutlib is
preview-oriented.

**Prefer Paparazzi `testDebug` report or Compose Preview record-to-artifact
over Roborazzi** if the goal is evidence of Compose screens without
Robolectric. Prefer Roborazzi if a screen needs Hilt / more of the Android
runtime on the JVM. None of these replace a full-app `adb screencap`.

---

## 5. Android `adb exec-out screencap`

Official
[Android Debug Bridge](https://developer.android.com/tools/adb#screencap):

```text
adb shell screencap /sdcard/screen.png
adb pull /sdcard/screen.png

# omit filename → stdout; -p → PNG; exec-out → raw bytes (no PTY mangling)
adb exec-out screencap -p > screen.png
```

This is a framebuffer dump of a **running** device or emulator. No goldens.
No Gradle plugin. Needs `adb` + a connected target.

- Local Mac with emulator or USB device: yes.
- fedora-box Checks: no emulator job today; plan out-of-scopes it.
- Cursor Cloud: no `/dev/kvm`, cannot run a hardware-accelerated emulator
  (`AGENTS.md`).
- Android instrumented tests exist under `android/app/src/androidTest/`
  (behavior, not screenshots) and are **not** run in Checks.

---

## 6. GitHub Actions artifacts + `workflow_dispatch`

### Upload

[`actions/upload-artifact`](https://github.com/actions/upload-artifact)
uploads a file or directory from a job. GitHub’s
[workflow artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts)
concept page lists **“Test results, failures, and screenshots”** as a
common artifact type.

[Storing workflow data as artifacts](https://docs.github.com/en/actions/using-workflows/storing-workflow-data-as-artifacts)
shows `upload-artifact` + optional `retention-days`. Default retention is
**90 days**
([Downloading workflow artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts));
this repo already uses `retention-days: 7` or `90` on release APKs/IPAs
(`.github/workflows/android-release.yml`, `ios-release.yml`,
`android-app-distribution.yml`) — **not** on Checks. Pinning 7 days for
evidence PNGs would match release artifacts and keep storage small.

This repo currently uses `actions/upload-artifact@v4`. Upstream README also
documents `@v7` (`archive: false` can upload a single unzipped PNG so the
browser can preview it). Either works; stay consistent with existing
workflows unless there is a reason to bump.

### On-demand trigger

[`workflow_dispatch`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)
is the supported manual event. Humans: Actions → Run workflow.
Agents / scripts: [`gh workflow run`](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
(this repo’s `run-github-actions` skill). Inputs are allowed (max 25).

**Constraint:** GitHub only dispatches a workflow if **the workflow file
exists on the default branch**. After it is on `main`, `gh workflow run
… --ref <feature-branch>` runs that file’s jobs against the chosen ref.

iOS Checks and Android Checks already declare `workflow_dispatch` (iOS
Checks even has an optional extra test input). A dedicated evidence
workflow would follow the same pattern as iOS Build / Android Test Lab:
dispatch only, not `pull_request`.

### Download

[`gh run download <run-id>`](https://cli.github.com/manual/gh_run_download)
extracts artifacts. Agents can dispatch, watch, download, then attach to
Linear or publish to the artifact server — still never committing PNGs.

---

## 7. Do iOS UITests run in this repo’s CI?

**No.**

`.github/workflows/ios-checks.yml` “Run unit tests”:

```yaml
xcodebuild test \
  -project "OpenCode Mobile.xcodeproj" \
  -scheme "OpenCode Mobile" \
  ...
  -only-testing:"OpenCode MobileTests" \
  CODE_SIGNING_ALLOWED=NO
```

The shared scheme **does** include `OpenCode MobileUITests` as a testable
(`OpenCode Mobile.xcscheme` TestAction, `skipped = NO`). Checks filter them
out. iOS Build (`ios-build.yml`) is `xcodebuild build` only — no tests at
all. No other workflow runs UITests.

Android Checks:

```yaml
./gradlew testDebugUnitTest --stacktrace
```

No `connectedDebugAndroidTest`, no emulator.

---

## Delivery surfaces that already exist (off-git)

| Surface | How | Fit |
| --- | --- | --- |
| Chat | Agent returns the PNG (XcodeBuildMCP `path`/`base64`) | Fastest review |
| Artifact server | `publish-artifact` skill: screenshot galleries are a **sketch** (`max-width: 72rem`, image grid) | Shareable LAN/Tailscale URL |
| Linear issue | MCP `prepare_attachment_upload` → PUT bytes → `create_attachment_from_upload`. Linear GraphQL hosts uploaded assets behind auth ([Accessing Images](https://linear.app/developers/graphql#accessing-images)) | Ties evidence to the ticket |
| GitHub Actions artifact | `upload-artifact` on `workflow_dispatch`; `gh run download` | Repeatable CI dump; expires |

---

## Options compared

### A. Agent/local framebuffer dump (evidence)

iOS: boot simulator → open harness or Gallery → `screenshot` / `simctl io`.  
Android: connected device/emulator → `adb exec-out screencap -p`.

| Criterion | Fit |
| --- | --- |
| On demand, not every PR | Yes |
| Both platforms conceptually | Yes (same “dump the running UI” idea) |
| No committed PNGs | Yes |
| Android without emulator in Checks | Local only; Checks/Cloud cannot |
| Agent + human | Agent on this Mac: yes. Human: Simulator/Device screenshot buttons also work |

**Gap:** getting the app onto the right screen. iOS already has DEBUG launch
flags (`OPENCODE_UI_TEST_CHANGES`, `OPENCODE_UI_TEST_REVIEW_CHANGES_PILL`,
`OPENCODE_UI_TEST_MESSAGE_LIST` in `OpenCode_MobileApp.swift`). Android has
no equivalent. Component Gallery is reachable from Settings on both apps
but that is still a navigation script, not a one-shot harness.

### B. XCTest attachments + result-bundle export (available, not preferred)

UITests *can* dump PNGs: `-resultBundlePath`, then
`xcresulttool export attachments`, then `upload-artifact`. LaunchTests
already prove the attachment path. That is slower than launching the Debug
binary with a harness env and calling `simctl io screenshot` on the same
Mac mini. Prefer the latter for CI evidence. Keep UITests for behavior.

| Criterion | Fit |
| --- | --- |
| On demand | Yes, if gated by `workflow_dispatch` |
| Both platforms | iOS only |
| No committed PNGs | Yes (artifact retention) |
| Speed vs harness + `simctl` | Worse (XCTest process, waits, xcresult parse) |
| Agent + human | `gh workflow run` / Actions UI |

### C. Host-side Android preview/snapshot dump (CI evidence, no emulator)

Paparazzi `testDebug` HTML report, Roborazzi `record` into `build/`, or
Compose Preview `updateScreenshotTest` with goldens **not** committed —
upload `build/` PNGs.

| Criterion | Fit |
| --- | --- |
| On demand | Yes |
| Both platforms | Android only; renders previews/composables, not full app chrome |
| No committed PNGs | Yes **if** we never run `record` into `src/` / never merge goldens |
| Android without emulator in Checks | Yes — this is the Checks-compatible Android path |
| Agent + human | `./gradlew` locally or dispatch a job on fedora-box / Cursor Cloud |

Google’s official Compose Preview plugin is the first-party choice if we
add a library; Paparazzi’s `testDebug` report is the least golden-flavored
JVM dump. Do not enable `validateScreenshotTest` / `verifyPaparazziDebug`
on required Checks.

### D. Golden-file visual regression (not recommended here)

Compose Preview validate, Paparazzi verify, Roborazzi verify, or
iOS third-party snapshot testing. Conflicts with “don’t commit screenshots”
and with “evidence, not goldens.” Google documents platform pixel drift and
thousands of binaries as first-class disadvantages.

### E. Firebase Test Lab Robo (exists, wrong job)

`.github/workflows/android-test-lab.yml` is `workflow_dispatch` from
`main` only, consumes a signed release APK, runs Robo on two virtual
devices. Firebase documents that Robo
[saves annotated screenshots and video](https://firebase.google.com/docs/test-lab/android/robo-ux-test).
Useful as release smoke, not as “this feature screen on this branch.”

---

## What if we use snapshot tests?

This repo has none today (no Paparazzi, no Compose `screenshotTest` source
set, no `swift-snapshot-testing`). “Snapshot test” here means: render a
view or `@Preview`, compare the PNG to a committed golden, fail on pixel
diff. That is Google’s definition of [screenshot
testing](https://developer.android.com/training/testing/ui-tests/screenshot)
and Paparazzi’s `verifyPaparazziDebug` / Compose
`validateDebugScreenshotTest`.

They are **better than UITests for speed**. They are **not a substitute**
for on-demand “this feature works” evidence.

| | UITest | Snapshot / golden | Harness + dump |
| --- | --- | --- | --- |
| Speed | Slow (second process, waits) | Fast (JVM Layoutlib / in-process `ImageRenderer`) | One app launch, then `simctl`/`adb` |
| What you see | Real running app | Isolated preview/view | Real running app |
| Typical use | Behavior | Visual lock / regression | Evidence when asked |
| Git | No PNG required | Goldens in `src/` (or Git LFS) | PNG stays off-git |
| This repo’s Checks | Not run today | Android could run on fedora-box with **no emulator**. iOS would ride the existing simulator unit-test job | Optional `workflow_dispatch` only |

### When snapshot tests *are* the right tool

Design-system gallery chrome: a pill, a list row, light/dark, fixed copy.
The fixture is already the Component Gallery / `#Preview` / `@Preview`. A
pixel lock there catches token and layout regressions without a live
server.

Android is the easy platform: AGP `9.0.1` and Kotlin `2.2.21` already match
Compose Preview Screenshot Testing’s current docs; it is host-side. iOS
has no Apple-owned equivalent — `swift-snapshot-testing` or SwiftUI
`ImageRenderer` inside `OpenCode MobileTests` (already on the simulator in
Checks).

Record on demand (`updateDebugScreenshotTest` / Paparazzi `record`) is
still a dump. **Verify on every PR** is the product change: Checks start
failing when someone tweaks padding.

### What they do not buy

- Session Chat in context, navigation chrome, composer, real data — that
  is the running app, not a preview.
- Proof for a reviewer who asked “show me it working” — a green
  `validateScreenshotTest` is a diff against last week’s PNG, not a
  picture in chat.
- An escape from binaries. Goldens *are* committed screenshots. That is
  allowed as test fixtures (the docs rule is about `progress.md` evidence,
  not `src/screenshotTest/`). It is still a policy choice: Git LFS, Linux
  vs macOS pixel drift, and “failed test means update the golden.”

### Fit for the original question

Keep harness + dump for **feature evidence**. Consider snapshot tests later
only as a **component-collection lock**, recorded when the gallery changes,
verified in Checks only after goldens are stable on one CI host (fedora-box
for Android). Do not use snapshot tests as a UITest replacement for
full-screen feature proof.

---

## Recommended fit for this repo

**Recommend two complementary approaches — not a PR gate.**

### 1. Primary: agent/local capture (Approach A)

Use it whenever someone (human or Cursor agent) is on a Mac with Simulator
and/or an Android device.

1. Put the UI in a known state (existing iOS harness flags, Component
   Gallery, or a live session if that is the thing being evidenced).
2. iOS: XcodeBuildMCP `screenshot` (optionally `snapshot_ui` for structure).
   Fallback: `xcrun simctl io booted screenshot out.png`.
3. Android: `adb exec-out screencap -p > out.png`.
4. Publish off-git: chat, Linear attachment, or `publish-artifact` sketch
   gallery. Distill any durable fact into `progress.md` / `decisions.md`.

No new libraries. Works today for iOS on this Mac. Android requires a
connected target.

If launch friction becomes the blocker, the **first product change** (not
done in this research) is a DEBUG harness flag on **both** platforms that
opens a gallery entry or feature fixture — mirroring
`OPENCODE_UI_TEST_*` — so capture is not a Settings-tap script.

### 2. Secondary: `workflow_dispatch` evidence workflow (harness + dump)

Add later, only as a **non-required** workflow (same family as iOS Build /
Android Test Lab), after harness IDs are stable enough to name in inputs.

- **iOS job** on `[self-hosted, macOS, mac-mini]`: install/launch the Debug
  app with the harness env, `simctl io … screenshot`,
  `actions/upload-artifact` with short `retention-days`. Do **not** run
  `xcodebuild test -only-testing:"OpenCode MobileUITests"` just to attach
  a PNG. (`xcresulttool export attachments` remains documented above if a
  behavioral UITest already ran and happened to attach screenshots.)
- **Android job** on `[self-hosted, Linux, X64, android]`: host-side dump
  (Paparazzi report or Compose Preview record into `build/`), upload those
  files. **Do not** start an emulator in Checks.
- Trigger: Actions UI or `gh workflow run` (`run-github-actions` skill).
- Ship the workflow file to `main` first so GitHub will dispatch it; then
  `--ref` a feature branch.

Until that workflow exists, iOS Checks’ existing `workflow_dispatch` is
**not** a screenshot job — it still runs unit tests only.

### Explicitly reject for this question

- Pixel-diff goldens on required PR Checks.
- Committing reference PNGs / Git LFS for feature evidence.
- Running `connectedDebugAndroidTest` on fedora-box or Cursor Cloud to
  satisfy this research.
- New UITests whose only job is to take a screenshot.
- Replacing accessibility UITests with screenshots. UITests prove
  behavior; screenshots prove look.

---

## Source index

| Claim | Source |
| --- | --- |
| `XCUIScreenshot` / attach without pixel assert | https://developer.apple.com/documentation/xcuiautomation/xcuiscreenshot |
| `XCTAttachment.init(screenshot:)` PNG | https://developer.apple.com/documentation/xctest/xctattachment/init(screenshot:) |
| `.keepAlways` vs `.deleteOnSuccess` | https://developer.apple.com/documentation/xctest/adding-attachments-to-tests-activities-and-issues |
| `pngRepresentation` | https://developer.apple.com/documentation/xcuiautomation/xcuiscreenshot (Screenshot representations) |
| `-resultBundlePath` | `xcodebuild -help` (this Mac); https://developer.apple.com/documentation/xcode/running-tests-and-interpreting-results |
| `xcresulttool export attachments` | https://developer.apple.com/documentation/xcode-release-notes/xcode-16_3-release-notes ; `xcrun xcresulttool help export attachments` |
| `simctl io screenshot` | `xcrun simctl io help`; https://developer.apple.com/library/archive/documentation/IDEs/Conceptual/iOS_Simulator_Guide/InteractingwiththeiOSSimulator/InteractingwiththeiOSSimulator.html |
| XcodeBuildMCP `screenshot` / `snapshot_ui` | MCP tool descriptors in this environment; https://github.com/getsentry/xcodebuildmcp |
| Compose Preview Screenshot Testing (host-side, goldens, Gradle tasks) | https://developer.android.com/studio/preview/compose-screenshot-testing |
| Screenshot testing = compare to golden; Layoutlib vs RNG | https://developer.android.com/training/testing/ui-tests/screenshot |
| `adb exec-out screencap -p` | https://developer.android.com/tools/adb#screencap |
| Paparazzi no-device + `testDebug` report vs `recordPaparazzi` | https://github.com/cashapp/paparazzi ; https://cashapp.github.io/paparazzi/ |
| Roborazzi JVM / RNG + `build/outputs/roborazzi` | https://github.com/takahirom/roborazzi |
| `upload-artifact` | https://github.com/actions/upload-artifact ; https://docs.github.com/en/actions/using-workflows/storing-workflow-data-as-artifacts |
| Artifacts include screenshots; 90-day default | https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts ; https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts |
| `workflow_dispatch` + must exist on default branch | https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch |
| `gh workflow run` / `gh run download` | https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow ; https://cli.github.com/manual/gh_run_download |
| Linear hosted images require auth | https://linear.app/developers/graphql#accessing-images |
| Linear MCP upload | `prepare_attachment_upload` / `create_attachment_from_upload` |
| iOS Checks unit-only | `.github/workflows/ios-checks.yml` |
| Android Checks JVM-only | `.github/workflows/android-checks.yml` |
| UITests in scheme, not in Checks | `OpenCode Mobile.xcscheme`; LaunchTests attachment |
| Harness flags | `ios/OpenCode Mobile/OpenCode Mobile/OpenCode_MobileApp.swift` |
| PR gating out-of-scope emulator | `docs/features/ci-pr-gating/plan.md` |
| No screenshots in canonical docs | `docs/documentation.md` |
| Test Lab Robo screenshots | https://firebase.google.com/docs/test-lab/android/robo-ux-test ; `.github/workflows/android-test-lab.yml` |
| AGP 9.0.1 / Kotlin 2.2.21 | `android/gradle/libs.versions.toml` |
