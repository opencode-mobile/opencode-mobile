# Session List Project Picker — Progress

Linear: [OPE-160](https://linear.app/opencode-mobile/issue/OPE-160/session-list-project-picker),
[OPE-176](https://linear.app/opencode-mobile/issue/OPE-176/gate-session-list-project-picker-behind-a-setting)

## Shared planning

- complete — prototype B (scope picker) locked; hold-to-remove + confirm
  dialog; no sheet checkmarks.
- complete — OPE-176 gates the picker behind Settings; chips remain default.
- Prototype (throwaway): `docs/prototypes/dock-many-projects/`.

## Design system

- complete — Session List Dock keeps chips and the scope-control variant.
  Filter Chip is the default home dock filter.

## iOS

- complete — chips default; Settings → Appearance **Project Picker** enables
  the picker list (no search). Gallery has a local Picker preview switch.
  Overlay tap dismisses the sheet (interactive dismiss kept enabled for the
  50% form presentation).

## Android

- complete — same behavior as iOS.

## Remaining

- none.
