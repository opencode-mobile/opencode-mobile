# CI PR Gating

## User Goal

Ready-for-review pull requests run Android Checks on the Fedora box and iOS
Checks on the Mac mini, and cannot merge into `main` while those checks are
pending or failing.

## In Scope

- Trigger Android Checks and iOS Checks on non-draft pull requests.
- Retrigger when a draft is marked ready for review.
- Cancel superseded runs on new pushes to the same PR.
- Keep Android Build and iOS Build as manual `workflow_dispatch` only.
- Soft-fail known Android lint/ktlint debt so unit tests can gate merges.
- Document the `main` branch ruleset that requires both Checks.

## Out Of Scope

- Path-filtered required checks (pending-check trap).
- Requiring separate Build workflows on PRs.
- Android emulator / instrumented tests.
- Merge queue (`merge_group`) unless enabled later.
- Fixing all historical ktlint / Android Lint violations in this ticket.

## Server APIs Involved

Not applicable — repository CI only.

## Domain Objects

Not applicable.

## UI States / User Actions

Not applicable.

## Error States

Not applicable.

## Permission Behavior

Not applicable.

## Design-System Components

Not applicable — no app UI.
