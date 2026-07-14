# Review Changes Progress

## Shared Planning

- Status: accepted and documented.
- Linear parent: `OPE-25` Session Diff Viewer.

## Design System

- Status: Unified Diff is implemented as a practical review component and
  registered in both Component Galleries; Review Changes Pill remains planned
  under OPE-38.
- New dependencies: Review Changes Pill and Unified Diff.
- Reused: top bar, overflow menu, bottom sheet, list item, button, semantic diff
  and syntax tokens, and existing typography roles.

## iOS

- Status: OPE-35, OPE-36, and OPE-39 complete.
- Session diffs decode into structured file models, Last turn loads are scoped
  by captured user message and worktree, and Session Chat navigates to a native
  Changes screen that presents one path-sorted file at a time.
- Changes shows aggregate counts and selected-file status and position. Its
  native file-picker sheet supports path filtering, clearing, explicit no-match
  feedback, selection, and previous/next movement through the filtered set.
- Loading, no completed turn, no changes, unavailable patch, retryable error,
  retry, accessibility labels, and navigation enablement are implemented.
- Unified patches decode into typed hunk and row models with stable old/new
  line numbers. Changes defaults to collapsed context, expands all loaded
  context on request, navigates and focuses previous/next hunks, preserves long
  unwrapped lines through horizontal scrolling, and supports viewer-local
  reviewed markers that clear on reload or exit.

## Android

- Status: OPE-35, OPE-36, and OPE-39 complete.
- Session diffs decode into structured file models, Last turn loads are scoped
  by captured user message and worktree, and Session Chat navigates to a native
  Changes screen that presents one path-sorted file at a time.
- Changes shows aggregate counts and selected-file status and position. Its
  native file-picker sheet supports path filtering, clearing, explicit no-match
  feedback, selection, and previous/next movement through the filtered set.
- Loading, no completed turn, no changes, unavailable patch, retryable error,
  retry, accessibility labels, and navigation enablement are implemented.
- Unified patches decode into typed hunk and row models with stable old/new
  line numbers. Changes defaults to collapsed context, expands all loaded
  context on request, navigates and focuses previous/next hunks, preserves long
  unwrapped lines through horizontal scrolling, and supports viewer-local
  reviewed markers that clear on reload or exit.

## Blockers

- Implementation tickets must follow the dependency order below.
- No external product decision is blocking the accepted scope.

## Remaining Work

- [`OPE-37`](https://linear.app/opencode-mobile/issue/OPE-37) — Review Git
  Change Sets from Working tree and Main branch.
- [`OPE-38`](https://linear.app/opencode-mobile/issue/OPE-38) — Surface the
  completed-turn Review changes pill.

## Completed Work

- [`OPE-39`](https://linear.app/opencode-mobile/issue/OPE-39) — Add typed
  unified hunks and rows, collapsed/expanded context, hunk navigation,
  horizontal long-line review, viewer-local reviewed markers, refresh, and
  equivalent native gallery/test fixtures on iOS and Android.

- [`OPE-36`](https://linear.app/opencode-mobile/issue/OPE-36) — Navigate
  multi-file Change Sets with aggregate context, a filterable file picker, and
  previous/next file controls on iOS and Android.
- [`OPE-35`](https://linear.app/opencode-mobile/issue/OPE-35) — Review the
  latest completed turn on a dedicated Changes screen on iOS and Android.
