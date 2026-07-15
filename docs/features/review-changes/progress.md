# Review Changes Progress

## Shared Planning

- Status: the OPE-43 two-screen simplification is implemented from
  [`ope-43-two-screen-diff-plan.md`](ope-43-two-screen-diff-plan.md).
- Linear parent: `OPE-25` Session Diff Viewer.
- Implementation bucket: `OPE-43` Session Diff Viewer fixes and improvements.

## Design System

- Status: implementation complete on iOS and Android; native UI verification is
  blocked on Android by an unrelated instrumentation compile failure.
- Added reusable List Group components for one bordered, clipped collection of
  List Item rows with internal separators.
- Added reusable Hunk Navigation Pill components with equal Previous hunk,
  Expand/Collapse context, and Next hunk targets plus independent disabled
  states.
- Updated Unified Diff to retain typed rows, context projection, unwrapped
  source lines with viewport-clamped horizontal scrolling, and selected-hunk
  focus without reviewed presentation.
- Removed File Navigation Pill implementations, tokens, documentation,
  galleries, previews, and tests.
- Registered List Group and Hunk Navigation Pill in both Component Galleries
  with native previews and interactive state controls.

## iOS

- Status: implementation complete; production build and focused unit/UI checks
  pass.
- Changes now opens a Changed Files list with Git changes and Last turn changes
  in fixed source-menu order, Git capability fallback, path filtering, filtered
  semantic totals, grouped rows, source-specific empty/error states, and native
  pull-to-refresh.
- File rows push a native File Viewer that inherits the stable snapshot, starts
  at the top with the first hunk selected and context collapsed, and exposes the
  floating Hunk Navigation Pill only for renderable patches.
- The File Viewer clamps the diff surface to the measured iOS viewport while
  long source rows keep their intrinsic width and scroll inside that surface.
- Back navigation preserves list source, filter, snapshot, and scroll state.
  Source switching preserves the filter and resets list scroll.
- Reviewed state, file-picker presentation, previous/next file stepping, Main
  branch source selection, and viewer refresh/source controls are removed.

## Android

- Status: implementation complete; production build and focused unit checks
  pass, while native UI verification remains blocked.
- Changes uses a nested Navigation 3 back stack for the Changed Files and File
  Viewer destinations while one ViewModel retains the list snapshot and filter.
  Its forward, Back, and predictive-Back motion reuses the app-level horizontal
  push/pop transition contract.
- The Changed Files behavior, source order/fallback, filtered totals, grouped
  rows, pull-to-refresh, errors, and empty states match iOS.
- The File Viewer creates fresh local hunk/context state on every entry, keeps
  the snapshot stable, reserves bottom content space, and omits the pill for an
  unavailable preview.
- Reviewed state, file-picker presentation, previous/next file stepping, Main
  branch source selection, and viewer refresh/source controls are removed.

## Blockers

- No blocker affects the feature implementation.
- The affected Android native screen/component tests cannot compile because the
  instrumentation source set has a pre-existing unrelated `MainScreenTest`
  call-signature failure. Focused touched-file formatting, production
  compilation, and Changes unit coverage pass.

## Remaining Work

- Repair the unrelated Android instrumentation source-set failure, then run the
  changed Review Changes UI/component tests.

## Completed Work

- OPE-43 two-screen redesign: Changed Files list plus pushed File Viewer on both
  platforms, two-source model, reusable List Group and Hunk Navigation Pill,
  stable viewer snapshots, filtered totals, native refresh, and retirement of
  reviewed and file-navigation behavior.
- OPE-35 through OPE-44 established the original Changes screen, multi-file
  support, Git Change Sets, Review Changes Pill, typed unified diffs, and the
  refinements superseded or retained by this redesign.
