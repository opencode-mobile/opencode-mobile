# Review Changes Progress

## Shared Planning

- Status: accepted and documented.
- Linear parent: `OPE-25` Session Diff Viewer.

## Design System

- Status: component contracts documented; native implementation not started.
- New dependencies: Review Changes Pill and Unified Diff.
- Reused: top bar, overflow menu, bottom sheet, list item, button, semantic diff
  and syntax tokens, and existing typography roles.

## iOS

- Status: not started.
- Existing session-diff DTO and domain model are placeholders, and the
  repository does not currently pass the worktree directory for diff requests.

## Android

- Status: not started.
- Existing session-diff DTO and domain model are placeholders.

## Blockers

- Implementation tickets must follow the dependency order below.
- No external product decision is blocking the accepted scope.

## Remaining Work

- [`OPE-35`](https://linear.app/opencode-mobile/issue/OPE-35) — Review the
  latest turn on a dedicated Changes screen.
- [`OPE-36`](https://linear.app/opencode-mobile/issue/OPE-36) — Navigate
  multi-file Change Sets.
- [`OPE-37`](https://linear.app/opencode-mobile/issue/OPE-37) — Review Git
  Change Sets from Working tree and Main branch.
- [`OPE-38`](https://linear.app/opencode-mobile/issue/OPE-38) — Surface the
  completed-turn Review changes pill.
- [`OPE-39`](https://linear.app/opencode-mobile/issue/OPE-39) — Add practical
  diff review tools.
