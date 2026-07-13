# Review Changes Decisions

## Change Sources

- Decision: support Last turn, Working tree, and conditional Main branch.
- Reason: these sources distinguish assistant-attributed work, uncommitted work,
  and all current-branch work without using the ambiguous label “Git changes.”

## Entry Defaults

- Decision: the Session Chat overflow action opens Working tree by default,
  while the Review Changes Pill opens Last turn.
- Reason: the menu is the general review entry point; the pill is contextual to
  the completed conversation turn.

## Pill Visibility

- Decision: show the pill only at the conversation end after the latest turn
  completes and reports changed files.
- Reason: the count and resulting snapshot should represent a stable turn, and
  the pill should not compete with Scroll to end.

## File Presentation

- Decision: show one file at a time with a filterable picker and previous/next
  navigation.
- Reason: this keeps narrow mobile layouts readable and bounds rendering work.

## Review Tools

- Decision: provide unified hunks, file and hunk navigation, collapsed/expanded
  context, filtering, syntax roles, and viewer-local reviewed markers.
- Reason: this is practical OpenCode review parity without importing desktop
  split-view density into the phone layout.

## Stable Snapshots

- Decision: do not update an open Change Set from events or polling.
- Reason: code, scroll position, and temporary reviewed markers should not move
  while the user is reading. Entry, source switch, refresh, and retry are the
  only load boundaries.

## Read-Only Boundary

- Decision: Review Changes does not edit, apply, or revert patches.
- Reason: v1 scope is inspection, and mobile file mutation would expand the
  product into file-editing and shell-control behavior.
