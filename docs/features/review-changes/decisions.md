# Review Changes Decisions

## Change Sources

- Decision: expose exactly Git changes and Last turn changes, in that menu order;
  remove Main branch from the mobile feature.
- Reason: this matches the official OpenCode Desktop source model and removes a
  source that is not required for the simplified mobile review flow.

## Entry Defaults

- Decision: the Session Chat overflow opens Git changes when supported and falls
  back to Last turn changes; the Review Changes Pill opens Last turn changes.
- Reason: the overflow is the general worktree-review entry while the pill is
  contextual to one completed conversation turn.

## Two-Screen Structure

- Decision: separate a filterable Changed Files list from a pushed File Viewer.
- Reason: source browsing and focused diff reading have different state and
  controls; splitting them removes the dense picker/toolbars from the viewer.

## List Presentation

- Decision: present filtered rows in one reusable grouped container. Show path,
  semantic `+N −N` totals, and chevron; keep status accessibility-only.
- Reason: the compact group matches the intended hierarchy without selected-row
  styling or repeated cards.

## Viewer Tools

- Decision: the File Viewer contains only its top bar, unified diff, and a
  floating Previous hunk / Expand-Collapse / Next hunk pill.
- Reason: those are the controls needed to inspect one file; file selection,
  source selection, filtering, and refresh belong to the list.

## Removed Review And File Navigation

- Decision: remove reviewed markers and previous/next file navigation,
  including the File Navigation Pill component.
- Reason: neither behavior is needed in the simplified two-screen flow; Back to
  the preserved list is the file-selection model.

## State Restoration

- Decision: preserve list source, filter, snapshot, and scroll across File
  Viewer navigation; reset viewer-local hunk and context state on each entry.
- Reason: Back should return to the browsing task exactly where it was left,
  while every file review begins predictably at its top in collapsed mode.

## Stable Snapshots

- Decision: do not update or refresh an open File Viewer. Entry, source switch,
  refresh, and retry on the Changed Files screen are the only load boundaries.
- Reason: code and hunk position must not move while the user is reading.

## Read-Only Boundary

- Decision: Review Changes does not edit, apply, or revert patches.
- Reason: v1 scope is inspection, and mobile file mutation would expand the
  product into file-editing and shell-control behavior.
