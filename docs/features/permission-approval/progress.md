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

## Android

- [x] Correct response wire values
- [x] Pending-list API and event-triggered reconciliation
- [x] Permission live store and reconnect reconciliation
- [x] Session-tree queue and response state
- [x] Session Chat integration
- [x] Decision contract unit tests

## iOS

- [x] Correct response wire values
- [x] Pending-list API and event-triggered reconciliation
- [x] Permission live store and reconnect reconciliation
- [x] Session-tree queue and response state
- [x] Session Chat integration
- [x] Decision contract tests added

## Validation

- [x] Android debug build and focused permission tests
- [x] iOS app build
- [ ] Full iOS test run (Xcode test action stalled twice on 2026-07-12)
- [ ] Light/dark composer permission-state preview review on both platforms
- [x] Manual approval against `opencode serve` confirmed in the mobile app

## Remaining

Implementation is complete on both platforms, including permission approval as
a native composer state and successful manual approval against a live server.
Remaining work is visual composer-state review and a successful iOS runtime test action.
The canonical behavior is in [plan.md](plan.md) and the upstream evidence is in
[web-spec.md](web-spec.md).
