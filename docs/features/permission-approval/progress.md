# Permission Approval — Progress

## Shared planning

- [x] Web behavior and server contract researched
- [x] Feature plan and v1 boundary documented
- [x] Linear issue OPE-19 linked

## Design system

- [x] Session Chat composer permission state documented
- [x] Android composer permission state and preview
- [x] iOS composer permission state and preview
- [x] Standalone permission component and gallery entry removed
- [x] Prompt entry and permission approval share the same composer shell on both
  platforms
- [x] Warning/error outline restored for the composer permission state
- [x] Permission copy/actions aligned to composer input/action padding
- [x] Composer resize animation added on Android and iOS
- [x] Android resize boundary corrected so the bottom gutter stays fixed and the
  composer shell itself expands
- [x] Android prompt/permission content switching coordinated with shell resize
  using `AnimatedContent` and `SizeTransform`
- [x] Composer promoted to the Component Collection as
  `OpenCodeSessionComposer` on Android and iOS
- [x] Feature-independent prompt/permission state and action API defined on both
  platforms
- [x] Interactive, edge-to-edge Component Gallery page added on both platforms
  for empty, prompt, active, permission, queued, submitting, and error states
- [x] Component Gallery composer anchored to the bottom of the available page
  on both platforms for realistic Session Chat placement
- [x] Session Chat updated to consume the design-system component on both
  platforms

## Android

- [x] Correct response wire values
- [x] Pending-list API and event-triggered reconciliation
- [x] Permission live store and reconnect reconciliation
- [x] Session-tree queue and response state
- [x] Session Chat integration
- [x] Decision contract unit tests
- [x] Parent-first queue refreshes session topology for every permission snapshot
- [x] Stale 404 responses immediately reconcile against the pending list

## iOS

- [x] Correct response wire values
- [x] Pending-list API and event-triggered reconciliation
- [x] Permission live store and reconnect reconciliation
- [x] Session-tree queue and response state
- [x] Session Chat integration
- [x] Decision contract tests added
- [x] Permission responses include the active worktree directory
- [x] Parent-first queue refreshes session topology for every permission snapshot
- [x] Stale 404 responses immediately reconcile against the pending list

## Remaining

Implementation is complete on both platforms, including permission approval as
a native composer state, dynamic descendant discovery, stale-request
reconciliation, and successful manual approval against a live server.
No implementation work remains in this slice.
The canonical behavior is in [plan.md](plan.md) and the upstream evidence is in
[web-spec.md](web-spec.md).
