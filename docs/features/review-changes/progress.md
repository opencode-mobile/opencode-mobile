# Review Changes Progress

## Shared Planning

- Status: accepted and documented.
- Linear parent: `OPE-25` Session Diff Viewer.

## Design System

- Status: Unified Diff is implemented and registered in both Component
  Galleries for OPE-35; Review Changes Pill remains planned under OPE-38.
- New dependencies: Review Changes Pill and Unified Diff.
- Reused: top bar, overflow menu, bottom sheet, list item, button, semantic diff
  and syntax tokens, and existing typography roles.

## iOS

- Status: OPE-35 complete.
- Session diffs decode into structured file models, Last turn loads are scoped
  by captured user message and worktree, and Session Chat navigates to a native
  Changes screen that presents the first path-sorted file.
- Loading, no completed turn, no changes, unavailable patch, retryable error,
  and retry behavior are implemented.

## Android

- Status: OPE-35 complete.
- Session diffs decode into structured file models, Last turn loads are scoped
  by captured user message and worktree, and Session Chat navigates to a native
  Changes screen that presents the first path-sorted file.
- Loading, no completed turn, no changes, unavailable patch, retryable error,
  and retry behavior are implemented.

## Blockers

- Implementation tickets must follow the dependency order below.
- No external product decision is blocking the accepted scope.

## Remaining Work

- [`OPE-36`](https://linear.app/opencode-mobile/issue/OPE-36) — Navigate
  multi-file Change Sets.
- [`OPE-37`](https://linear.app/opencode-mobile/issue/OPE-37) — Review Git
  Change Sets from Working tree and Main branch.
- [`OPE-38`](https://linear.app/opencode-mobile/issue/OPE-38) — Surface the
  completed-turn Review changes pill.
- [`OPE-39`](https://linear.app/opencode-mobile/issue/OPE-39) — Add practical
  diff review tools.

## Completed Work

- [`OPE-35`](https://linear.app/opencode-mobile/issue/OPE-35) — Review the
  latest completed turn on a dedicated Changes screen on iOS and Android.
