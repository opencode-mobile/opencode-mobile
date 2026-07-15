# OPE-43 Two-Screen Diff Plan

## Objective

Simplify Review Changes by separating Change Set browsing from file review:

1. a **Changed Files** screen owns source selection, loading, filtering,
   filtered totals, refresh, and the stable Change Set snapshot;
2. a pushed **File Viewer** screen renders one file from that snapshot and owns
   only hunk selection plus collapsed/expanded context.

The slice ships with equivalent behavior on iOS and Android.

## Agreed Product Contract

### Sources And Entry Points

- Supported sources are exactly **Git changes** and **Last turn changes**.
- Remove Main branch from the mobile source model and UI.
- Source-menu order is fixed: Git changes first, Last turn changes second.
- Hide Git changes when worktree diffs are unsupported; do not show a disabled
  or permanently failing source.
- The Session Chat overflow Changes action opens Git changes when available and
  otherwise falls back to Last turn changes.
- The Review Changes Pill always opens Last turn changes.
- Source switching preserves the filter, resets list scroll to the top, and
  immediately shows loading so files from the previous source are never shown
  under the new label.

### Changed Files Screen

- Keep the Changes top bar, active-source subtitle, and overflow source menu.
- Pull-to-refresh exists only on this screen. Refresh preserves the visible
  snapshot and filter until replacement data arrives.
- Show a pluralized filtered summary: `1 changed file` or `N changed files`,
  followed by always-visible semantic `+N −N` totals, including zero.
- Filtering updates the visible rows and all summary totals.
- Preserve source, filter, and list scroll when a file is opened and the user
  returns.
- Switching source preserves the filter but resets list scroll.
- Show matching files in one grouped, bordered container with shared corner
  radius and separators between rows.
- Each row shows only the full relative path, semantic `+N −N` counts, and a
  chevron. Added/Modified/Deleted remains part of accessibility, not visible
  row copy.
- Files remain in the current relative-path sort order.
- No matches replaces the group with `No matching files` and `Clear filter`;
  filtered totals resolve to zero.
- Source-specific empty, loading, retryable error, retry, and unsupported-Git
  behavior remain available without entering the viewer.

### File Viewer Screen

- Use native push/back navigation. The top bar contains Back and the full
  relative path truncated to one line.
- The body contains only the unified diff and a bottom-centered floating Hunk
  Navigation Pill.
- Remove the aggregate header, file picker, file navigation, separate context
  button, reviewed control, and hunk-position row.
- Opening or reopening a file starts at the top, selects the first hunk, and
  uses collapsed context.
- The pill has three icon-only 44-point/dp actions: Previous hunk,
  Expand/Collapse context, and Next hunk. Visible labels and a hunk counter are
  omitted; accessibility labels are required.
- Previous and Next remain visible, disable at boundaries, preserve the current
  context mode, and scroll the selected hunk to the top.
- Expand and Collapse apply to every hunk. The center action uses a mode-specific
  icon and label and is disabled when no additional context can be expanded.
- One-hunk files show the pill with both navigation directions disabled.
- Unrenderable files remain actionable and show a Preview unavailable state
  without the pill.
- Reserve bottom content space for the pill and safe area so final diff lines
  remain readable.
- The viewer inherits a stable snapshot and performs no refresh or source
  switch. Foreground events do not mutate it.

### Removed Behavior

- Remove Main branch source selection and branch-diff loading from this feature.
- Remove viewer-local reviewed state, UI, semantics, copy, and tests.
- Remove previous/next file stepping and filtered file-navigation state.
- Delete the unused File Navigation Pill implementations, tokens, component
  documentation, galleries, previews, and tests.

## State And Navigation Model

The Review Changes flow owns one stable snapshot through a native coordinator
or parent Screen Model. The viewer route references a file path in that owned
snapshot rather than serializing or reloading the patch.

### Changed Files State

- phase: loading, content, source-specific empty, or retryable error;
- available and selected source;
- stable Change Set snapshot and refresh-in-progress state;
- filter text, filtered files, and filtered aggregate totals;
- retained list scroll position;
- captured latest-user-message identifier for Last turn changes.

Actions are load, retry, refresh, select source, update/clear filter, open file,
and navigate back.

### File Viewer State

- selected file path and renderable/unrenderable presentation;
- typed unified diff document;
- selected hunk index or no hunk;
- collapsed/expanded context mode;
- previous/next availability and expandable-context availability.

Actions are select previous hunk, select next hunk, toggle all context, and
navigate back. Viewer-local state is discarded on exit.

## Design-System Work

1. Add reusable `OpenCodeListGroup` implementations for iOS and Android. The
   component owns the outer surface, border, corner radius, clipping, and row
   separators while accepting List Item rows as content.
2. Add reusable `OpenCodeHunkNavigationPill` implementations with three equal
   icon targets, boundary and no-context disabling, native pressed states,
   accessibility labels, galleries, and light/dark previews.
3. Keep `OpenCodeListItem` for row content and semantic addition/deletion
   segments; the feature supplies the trailing chevron.
4. Keep `OpenCodeUnifiedDiff` for typed rows and selected-hunk focus, but remove
   its reviewed presentation and reviewed-state contract.
5. Remove `OpenCodeFileNavigationPill` and its component tokens and review
   surfaces after all feature references are removed.
6. Reuse OpenCodeTopBar, OpenCodeOverflowMenu, OpenCodeTextInput, semantic diff
   tokens, icons, and typography roles. The filtered summary and screen-level
   empty/error compositions remain feature-local.

## Implementation Sequence

### Phase 1: Shared Contract And Reusable UI

- Document List Group and Hunk Navigation Pill APIs, tokens, states,
  accessibility, and review surfaces.
- Implement and validate both components in iOS and Android galleries/previews.
- Remove reviewed state from Unified Diff only after dependent feature code is
  ready to migrate.

### Phase 2: Shared Feature State

- Reduce Change Source to Git changes and Last turn changes in feature-facing
  state and copy.
- Split list state/actions from viewer state/actions while retaining one stable
  snapshot owner.
- Add filtered aggregate derivation and expandable-context capability.
- Remove reviewed paths and previous/next file state/actions.

### Phase 3: iOS Navigation And Screens

- Refactor Changes into a Changed Files screen and pushed File Viewer.
- Preserve list source, filter, and scroll through native navigation, including
  native swipe-back behavior where supported.
- Integrate List Group and Hunk Navigation Pill, empty/error states, pull-to-
  refresh, no-preview handling, focus scrolling, and bottom inset.

### Phase 4: Android Navigation And Screens

- Refactor Changes into equivalent Changed Files and File Viewer destinations.
- Preserve list state through Navigation Compose back-stack behavior.
- Integrate the same component, refresh, error, focus-scroll, and inset
  contracts using native Compose patterns.

### Phase 5: Retirement, Tests, And Documentation

- Remove Main branch, reviewed, file picker, and file-navigation UI/state.
- Remove File Navigation Pill code, tokens, docs, galleries, previews, and tests.
- Update feature and component documentation to the shipped contract.
- Run focused builds, formatting checks, state-model tests, component tests,
  and native screen UI tests on both platforms.

## Acceptance Criteria

- Both entry points open the correct initial source and Git unavailability falls
  back cleanly.
- Only Git changes and Last turn changes appear, in the agreed order and copy.
- Filtering changes rows and totals together; clearing restores the full group.
- Opening a file pushes the viewer; Back restores source, filter, and list
  scroll without reloading.
- Viewer source and snapshot remain stable until the user returns to the list.
- File rows, filtered summary, source menu, no-match state, and unavailable
  preview expose complete accessibility meaning.
- Hunk controls remain visible at boundaries, dispatch only when enabled,
  focus the correct hunk, preserve context mode, and avoid covering content.
- Reviewed and previous/next file behavior are absent from state, UI, semantics,
  documentation, and tests.
- iOS and Android have no observable parity gap in behavior, copy, semantic
  roles, error handling, or accessibility.

## Risks And Mitigations

- **Snapshot lifetime across navigation:** keep snapshot ownership above both
  screens and route by file path; do not duplicate or refetch patches.
- **List restoration drift:** make filter/source durable Screen Model state and
  verify scroll restoration through native back navigation.
- **Unsupported Git inference:** hide Git only for confirmed unsupported/non-Git
  capability; keep transient failures retryable.
- **Large or malformed patches:** preserve deterministic parsing, horizontal
  line scrolling, and the no-preview state.
- **Overlay obstruction:** reserve a tested bottom inset including safe-area or
  navigation-bar space.
- **Component retirement:** remove File Navigation Pill only after repository-
  wide reference checks confirm no remaining consumers.

## Verification Matrix

| Area | iOS | Android |
| --- | --- | --- |
| Source order, defaults, fallback, loading, retry | State and UI tests | State and UI tests |
| Filtered rows and aggregate totals | Unit and UI tests | Unit and UI tests |
| List restoration after viewer Back | Simulator UI test | Emulator UI test |
| Grouped list visual states | Gallery and previews | Gallery and previews |
| Hunk pill actions and disabled states | Component and UI tests | Component and UI tests |
| Context expansion capability | Unit and UI tests | Unit and UI tests |
| Unavailable preview and absent pill | UI test | UI test |
| Removed reviewed/file navigation behavior | Reference and UI assertions | Reference and UI assertions |
| Builds and formatting | Focused Xcode build/tests | Focused Gradle build/tests and touched-source formatting |

## Scope Boundaries

No editing, patch application, revert, shell execution, live diff updates,
background polling, split diff, historical-turn browser, or file browsing
outside the selected Change Set is added.
