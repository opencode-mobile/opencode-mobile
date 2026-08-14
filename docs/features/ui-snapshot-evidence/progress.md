# UI Snapshot Evidence — Progress

Linear: [OPE-161](https://linear.app/opencode-mobile/issue/OPE-161/on-demand-ui-snapshot-evidence)
Branch: `feature/ope-161-ui-snapshot-evidence`

## Shared planning

- [x] Research: on-demand evidence vs UITests vs golden snapshot tests
  ([`../ci-pr-gating/research-on-demand-screenshot-evidence.md`](../ci-pr-gating/research-on-demand-screenshot-evidence.md))
- [x] Feature plan, decisions, parity, skill task plan
- [x] Gitignore + output directory convention
- [x] Committed Session Chat fixture host + assistant-message mocks
- [x] iOS `ImageRenderer` helper
- [x] `publish-ui-snapshots` skill
- [ ] Android host-side Layoutlib generate path
- [ ] Prove the loop on assistant message (isolated + in-session, both
  platforms, published HTML)

## Design-system dependencies

None. Fixture host is feature-local tooling. It reuses existing timeline
child views (`OpenCodeOutgoingMessage`, assistant bubble chrome, top bar,
composer).

## iOS implementation

Fixture host, mocks, and `SnapshotEvidenceRenderer` are in tree. Throwaway
tests are written by the skill into `GeneratedSnapshotEvidence/`.

## Android implementation

Fixture host and mocks are in tree. Layoutlib generate is not wired: Compose
Preview Screenshot Testing fights ktlint; Paparazzi 2.x needs JDK 21;
Paparazzi 1.3.5 breaks Hilt transforms on AGP 9. The skill skips Android
images until that path lands.

## Blockers

Android generate needs a Layoutlib plugin that works with AGP 9, JDK 17,
ktlint, and Hilt on `:app`.

## Remaining work

- Wire Android host-side generate (or an equivalent no-emulator renderer)
  without breaking Checks.
- Prove the generate-and-publish loop on assistant message after that
  (run the skill; confirm git stays clean of PNGs/tests).
- Additional subjects (tool card, child-session row) when needed.
