# CI PR Gating — Decisions

## Gate on Checks only, not Build

**Decision:** Required PR checks are Android Checks and iOS Checks. Build
workflows stay `workflow_dispatch`.

**Why:** Checks already compile (unit tests / `xcodebuild test`). Requiring
Build doubles self-hosted runner time without stronger merge signal.

## Draft skip via job `if`, not trigger-only

**Decision:** Trigger on `opened`, `synchronize`, `reopened`, and
`ready_for_review`, and skip jobs when `pull_request.draft` is true.

**Why:** Default PR types do not fire when a draft becomes ready. Job-level
skip reports skipped (success for required checks); drafts still cannot merge.

## No path filters on required workflows

**Decision:** Always run both platform Checks on ready PRs.

**Why:** Path-filtered required workflows stay Pending and block merge. Dual
platform Feature Slices make cross-platform CI the default.

## Soft-fail Android lint/ktlint for the required job

**Decision:** `ktlintCheck` and `lintDebug` run with `continue-on-error: true`.
`testDebugUnitTest` remains hard-fail.

**Why:** AGENTS.md records pre-existing lint failures that were never gated.
Blocking every PR on that debt would stall shipping PR CI; follow-up ticket
should make lint hard-fail.
