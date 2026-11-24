---
name: component-captures
description: "Component captures: use after changing a design-system component or its native gallery screen, to recapture its iOS and Android screenshots for the HTML gallery, or to add captures for a new component or state."
---

# Component captures

Native PNGs of every gallery state, shown in the HTML design system gallery's
**Native** tab next to an HTML replica. Output lives in
`scripts/component-gallery/captures/` and is git-ignored: never commit it, and
never delete the folder wholesale (partial runs merge into the manifests).

| File | Role |
| --- | --- |
| `scripts/component-gallery/components/*.js` | HTML components and their named `states` |
| `scripts/component-gallery/export-capture-contract.mjs` | writes `captures/contract.json` |
| `scripts/component-gallery/capture-ios.sh`, `capture-android.sh` | build, run the capture test, merge `captures/<platform>/manifest.json` |
| `ios/OpenCode Mobile/OpenCode Mobile/ComponentGallery/Capture/ComponentCaptureRegistry+<Category>.swift` | iOS states |
| `android/app/src/debug/java/dev/opencode/mobile/ui/componentgallery/capture/<Category>Captures.kt` | Android states |
| `ios/OpenCode Mobile/OpenCode MobileUITests/ComponentCaptureUITests.swift`, `android/app/src/androidTest/java/dev/opencode/mobile/ui/componentgallery/capture/ComponentCaptureTest.kt` | the capture tests the scripts drive |

**Ids.** Component id = the native gallery entry's title slugged
(`Filter Tabs` → `filter-tabs`, from `ComponentGalleryEntry.kt` /
`ComponentGalleryEntry.swift`). State id = the HTML state name slugged
(`Expand, open` → `expand-open`). Both registries key on these exact strings.

## Prerequisites

- iOS: Xcode with an iOS simulator runtime. The script uses the newest runtime
  and its own simulator, `Component Capture iPhone 17`, created if missing and
  **erased every run**. It never touches other simulators.
- Android: SDK with `platform-tools`, `emulator`, `cmdline-tools` and a system
  image. The script uses its own AVD, `component_capture_phone` (Pixel 9
  profile, newest installed image), created if missing. It reuses that AVD if
  already running, resets its animation, font, and display settings, and shuts
  it down only if it booted it.
- HTML renders: Google Chrome (or `CHROME_PATH`).
- Builds take most of the time. A full run takes several minutes per platform;
  a few ids are faster. iOS prints `capture took Ns`; its log is in
  `$TMPDIR/opencode-component-capture-ios.log`.

## Decide: changed what → run what

| Changed | Run |
| --- | --- |
| One component's Swift/Kotlin, or its gallery screen | Map to ids (below), then **Recapture** those ids on that platform; both if shared kit or both changed |
| A shared building block (Button, List Item, Tag, Floating Pill, …) | Recapture every id that composes it, or a full run |
| Theme, tokens, typography, icons | Full run on both platforms |
| HTML component markup or CSS only | `npm run gallery:html-captures` |
| HTML state added, renamed, or removed | **Add a component or state** |
| New gallery entry | **Add a component or state** |

## Map a changed file to component ids

1. Take the component's type name (`OpenCodeFloatingPill`).
2. Grep both registries for it:
   ```bash
   rg -l 'OpenCodeFloatingPill' "ios/OpenCode Mobile/OpenCode Mobile/ComponentGallery/Capture" \
     android/app/src/debug/java/dev/opencode/mobile/ui/componentgallery/capture
   rg -n 'componentID: "|ComponentCaptureComponent\(|^\s+id = "' <matched files>
   ```
   Every entry whose states build that type is affected, including composite
   ones (a session screen that draws the pill).
3. For a sample-data or gallery-screen change, grep the registries for the
   sample helper name the same way; captures reuse the gallery screens' data.

Done when every entry that draws the changed type is on your id list.

## Recapture

```bash
node scripts/component-gallery/export-capture-contract.mjs   # always the full contract
scripts/component-gallery/capture-ios.sh floating-pill action-pill
scripts/component-gallery/capture-android.sh floating-pill action-pill
npm run gallery:html-captures                                  # swipe comparison
```

No ids = every component. Ids replace only those components in the manifest.
Android: `CAPTURE_SKIP_BUILD=1` reuses APKs you already built.

## Add a component or state

1. **HTML.** Add or edit the state in the component's `states` in
   `components/<group>.js`. A new component also needs a native gallery entry
   on at least one platform; `npm run gallery:check` fails until both sides
   exist.
2. **Contract.** `node scripts/component-gallery/export-capture-contract.mjs`
   and read its printed `id: states` line for the exact ids.
3. **iOS.** In the category's `ComponentCaptureRegistry+<Category>.swift`, add
   `ComponentCaptureState("<state-id>") { … }` to the entry, or a new
   `ComponentCaptureEntry(componentID:alignment:states:skipped:)` listed in that
   file's `<category>Entries`.
4. **Android.** In `<Category>Captures.kt`, add
   `ComponentCaptureState("<state-id>") { … }`, or a new
   `ComponentCaptureComponent(id = …)` listed in that file's
   `<category>Captures()`.
5. Build the content from the gallery screen's sample data and public component
   API, the same as the gallery screen. Use `alignment` / `height` / overlay
   options to match the HTML cell.
6. **State a platform cannot draw:** record it, so coverage stays complete.
   - Skip one state: iOS `skipped: [ComponentCaptureSkip(id:reason:)]`,
     Android `skipped = listOf(ComponentCaptureSkip(id, reason))`.
   - Whole component missing: iOS adds it to `ComponentCaptureRegistry.unavailable`;
     Android skips iOS-only components automatically from the contract's
     `platforms`.
   - Drawable but different from the HTML props: keep the state and give it
     `note:` / `note =` explaining the difference.
7. Recapture the new ids on both platforms.

## Verify

- The iOS test fails with `No tile or skip for: …` when a contract state has
  neither; the Android script prints `Missing N tiles, first: …`. Both must be
  clean for the ids you ran.
- Open a few PNGs under `captures/<platform>/<id>/<state>--<light|dark>.png`
  and look at them: correct state, nothing clipped, no keyboard or spinner.
- `npm run gallery`, open http://127.0.0.1:4600/, pick the component, check the
  Native tab for both platforms and swipe against HTML.
- `npm run gallery:check` passes after adding a component.

## Pitfalls

- `export-capture-contract.mjs <ids>` rewrites `contract.json` with only those
  ids, which shrinks later full runs, coverage reports, and HTML renders. Export
  the full contract; pass ids to the capture scripts instead.
- Renaming an HTML state renames its id. Update both registries in the same
  change, or the old tiles show as extra states and the new one as missing.
- A capture that only changed on one platform still needs the other checked
  when the change touched shared kit code or the HTML state.
- Animated states must render at rest: iOS runs with Reduce Motion, Android
  with animations off. A state that needs time to appear uses iOS `delay:`.
- Keep capture-only code in the registries (debug builds). Missing sample data
  belongs in the gallery screen, so the gallery and the capture stay the same.
