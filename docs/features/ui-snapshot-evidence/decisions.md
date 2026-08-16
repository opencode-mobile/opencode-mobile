# UI Snapshot Evidence — Decisions

## Throwaway generate-only tests, never pushed

**Decision:** Snapshot tests and their PNGs are gitignored. The skill writes
them, runs them to generate images, publishes the report, and does not stage
them. Checks never see them.

**Why:** The user wants proof of work, not a visual-regression gate. Committing
goldens would fight `docs/documentation.md` (raw evidence stays out of
canonical docs) and would make every padding tweak a CI failure.

## Two frames per subject: isolated + in-session

**Decision:** Every subject produces at least one isolated snapshot and one
Session Chat snapshot with that chrome in a mocked timeline.

**Why:** Isolated shows the component; in-session shows it in product chrome.
That is the example given (assistant message).

## In-process renderers, not UITests

**Decision:**

- iOS: SwiftUI `ImageRenderer` inside a Swift Testing test in
  `OpenCode MobileTests` (already a `PBXFileSystemSynchronizedRootGroup`, so
  a gitignored file is compiled without pbxproj edits).
- Android: host-side Layoutlib generate is **not wired** in this slice.
  Compose Preview Screenshot Testing cannot coexist with ktlint (duplicate
  `runKtlintCheckOverAndroidTestSourceSet`). Paparazzi 2.x requires JDK 21
  (Checks pin 17). Paparazzi 1.3.5 configures on this AGP 9 tree but breaks
  Hilt's `jar-for-dagger` transforms against `:markdown-core`.
  Throwaway JVM tests still go in gitignored `GeneratedSnapshotEvidence/`
  once a renderer applies.

**Why:** UITests are too slow for this loop. Android Checks/Cloud have no
emulator, so the intended Android pump remains Layoutlib. Until a plugin
works with AGP 9 + JDK 17 + ktlint + Hilt, the skill skips Android generate
and still publishes iOS shots. Never add `verifyPaparazziDebug` or
`validateDebugScreenshotTest` to Checks.

## Fixture host, not production Session Chat

**Decision:** Snapshots render a committed `SessionChatSnapshotHost` that
takes mock timeline blocks. They do not construct `SessionChatViewModel` or
call `loadMessages`.

**Why:** Production Session Chat binds LiveStore and the server. Evidence
must be deterministic and offline. The host composes the same child views
the timeline already uses.

## Publish as an HTML sketch via publish-artifact

**Decision:** After generate, wrap PNGs in a sketch HTML gallery (centered,
`max-width: 72rem`, grid) and call the `publish-artifact` skill. Do not
commit the HTML.

**Why:** That is the existing remote-review path (`publish-prototype` does
the same two-step). Reviewers open a URL; git stays clean.

## Committed mocks are scaffolding; per-run tests are throwaway

**Decision:** Reusable mock factories and the fixture host are committed.
The skill-generated test/preview file for *this* subject is throwaway.

**Why:** Assistant-message (and later tool-card) fixtures should not be
rewritten every run. The test file is only “render these mocks to PNG.”
