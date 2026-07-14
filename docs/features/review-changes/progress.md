# Review Changes Progress

## Shared Planning

- Status: accepted and documented.
- Linear parent: `OPE-25` Session Diff Viewer.

## Design System

- Status: Unified Diff and Review Changes Pill are implemented and registered
  in both Component Galleries with interactive and native preview states. The
  pill accepts a positive file count and nonnegative line totals, displays
  compact singular/plural file copy with independently omitted addition and
  deletion totals, and exposes the same action summary to accessibility on both
  platforms.
- New dependencies: Review Changes Pill and Unified Diff.
- Reused: top bar, overflow menu, bottom sheet, list item, button, semantic diff
  and syntax tokens, and existing typography roles.

## iOS

- Status: OPE-35 through OPE-40 complete.
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
- The Session Chat overflow opens Working tree by default. Changes discovers
  worktree-scoped VCS metadata, exposes Last turn, Working tree, and conditional
  Main branch sources, keeps each loaded snapshot stable, and reloads only for
  source selection, retry, or explicit refresh. Non-Git projects and servers
  without VCS endpoints fall back to Last turn; transient failures stay
  retryable.
- Session Chat requests a worktree-scoped diff count only after the latest user
  turn completes. A positive result shows the Review Changes Pill only while
  the timeline is pinned to its end; pending, empty, and failed results keep it
  hidden. The pill is mutually exclusive with Scroll to end and opens Last
  turn with the captured completed-user-message identifier.
- OPE-40 refines the reusable pill API and presentation for file, addition, and
  deletion totals. Session Chat supplies zero line totals until OPE-41 wires
  the latest-turn aggregate values.

## Android

- Status: OPE-35 through OPE-40 complete.
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
- The Session Chat overflow opens Working tree by default. Changes discovers
  worktree-scoped VCS metadata, exposes Last turn, Working tree, and conditional
  Main branch sources, keeps each loaded snapshot stable, and reloads only for
  source selection, retry, or explicit refresh. Non-Git projects and servers
  without VCS endpoints fall back to Last turn; transient failures stay
  retryable.
- Session Chat requests a worktree-scoped diff count only after the latest user
  turn completes. A positive result shows the Review Changes Pill only while
  the timeline is pinned to its end; pending, empty, and failed results keep it
  hidden. The pill is mutually exclusive with Scroll to end and opens Last
  turn with the captured completed-user-message identifier.
- OPE-40 refines the reusable pill API and presentation for file, addition, and
  deletion totals. Session Chat supplies zero line totals until OPE-41 wires
  the latest-turn aggregate values.

## Blockers

- None.

## Remaining Work

- [`OPE-41`](https://linear.app/opencode-mobile/issue/OPE-41) — Supply the
  reusable pill with latest-turn addition and deletion aggregates from Session
  Chat on both platforms.

## Completed Work

- [`OPE-40`](https://linear.app/opencode-mobile/issue/OPE-40) — Refine the
  Review Changes Pill contract, native implementations, galleries, previews,
  formatting checks, and accessibility to present compact file and line totals
  on iOS and Android.

- [`OPE-38`](https://linear.app/opencode-mobile/issue/OPE-38) — Add the
  completed-turn Review Changes Pill component, count loading, visibility and
  mutual-exclusion gates, Last turn navigation, galleries, previews,
  accessibility, and automated coverage on iOS and Android.

- [`OPE-37`](https://linear.app/opencode-mobile/issue/OPE-37) — Add
  worktree-scoped VCS metadata and diff clients, Working tree and conditional
  Main branch sources, source switching, source-specific empty states, stable
  snapshots, explicit refresh, and non-Git or unsupported-server fallback on
  iOS and Android.

- [`OPE-39`](https://linear.app/opencode-mobile/issue/OPE-39) — Add typed
  unified hunks and rows, collapsed/expanded context, hunk navigation,
  horizontal long-line review, viewer-local reviewed markers, refresh, and
  equivalent native gallery/test fixtures on iOS and Android.

- [`OPE-36`](https://linear.app/opencode-mobile/issue/OPE-36) — Navigate
  multi-file Change Sets with aggregate context, a filterable file picker, and
  previous/next file controls on iOS and Android.
- [`OPE-35`](https://linear.app/opencode-mobile/issue/OPE-35) — Review the
  latest completed turn on a dedicated Changes screen on iOS and Android.
