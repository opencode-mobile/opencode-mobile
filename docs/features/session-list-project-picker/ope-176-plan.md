# OPE-176 Gate Session List project picker behind a setting

Linear: [OPE-176](https://linear.app/opencode-mobile/issue/OPE-176/gate-session-list-project-picker-behind-a-setting)

## Intent

Keep the shipped Session List **chip scroller** as the default home filter.
The project picker stays in the product, enabled from Settings, so
both versions can be compared without replacing chips for everyone.

## In Scope

- Settings → Appearance toggle **Project Picker** (off by default).
- Home Session List Dock shows chips when the toggle is off and the scope
  control + Filter by project sheet when it is on.
- iOS and Android, including Session List / Session List Dock galleries with a
  local Picker preview switch.

## Out Of Scope

- Removing either chrome.
- Changing hold-to-remove or OPE-156 All-tab policy.
- Making the picker the default home filter.
