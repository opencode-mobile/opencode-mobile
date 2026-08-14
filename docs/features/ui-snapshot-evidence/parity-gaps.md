# UI Snapshot Evidence — Parity Gaps

## Renderer: ImageRenderer vs Layoutlib

- **Expected shared intent:** Isolated and in-session frames of the same
  subject, same mocks, both platforms, shown together in one report.
- **Current iOS state:** SwiftUI `ImageRenderer` (Core Animation /
  simulator fonts).
- **Current Android state:** Fixture host exists; Layoutlib generate is not
  wired (see decisions).
- **Affected area:** visual semantics (text, shadows, font metrics). Pixels
  will not match across platforms once both generate.
- **Reason:** Each platform’s supported generate-only renderer. Pixel-equal
  iOS/Android goldens are out of scope.
- **Owner or next action:** Show both in the HTML report; do not fail on
  cross-platform diff. Wire Android generate as remaining work.
- **Status:** intentional for pixel mismatch; Android generate itself is
  temporary.

## Android generate unavailable

- **Expected shared intent:** Both platforms in one skill run.
- **Current iOS state:** `ImageRenderer` helper can write PNGs from a
  throwaway unit test.
- **Current Android state:** Skill skips generate and names this gap.
- **Affected area:** missing implementation.
- **Reason:** Compose Preview Screenshot Testing double-registers ktlint
  tasks; Paparazzi 2.x requires JDK 21; Paparazzi 1.3.5 breaks Hilt
  `jar-for-dagger` transforms on AGP 9.
- **Owner or next action:** Revisit when a Layoutlib plugin works with AGP
  9 + JDK 17 + ktlint + Hilt, or when Checks move off JDK 17.
- **Status:** temporary.

## Capture availability

- **Expected shared intent:** Both platforms in one skill run.
- **Current iOS state:** Needs a Mac + Simulator (local or Mac mini).
- **Current Android state:** Intended JVM Layoutlib on fedora-box / this
  Mac without an emulator, once generate is wired. Cannot render iOS there.
- **Affected area:** missing implementation on a given machine, not product
  chrome.
- **Reason:** iOS snapshots cannot run on Linux.
- **Owner or next action:** Skip the unavailable platform and publish the
  rest; name the gap in the skill report.
- **Status:** intentional.
