# Skip All Chip And New-Session Picker — Decisions

## Home owns the < 2 rule

- Decision: Tab construction, New session gating, and filter fallback live in
  `SessionListHomeChrome` on each platform. The dock only renders the tabs it
  is given and collapses an empty chip row.
- Reason: The dock stays a dumb renderer. Callers already built All
  unconditionally; putting the count rule in home keeps Session List reusable
  for gallery and 2+ cases.
- Alternatives considered: Hide All inside the dock from a `showsAll` flag;
  disable New session at zero projects. Rejected — the dock should not know
  home curation policy, and the ticket keeps New session enabled.

## Fallback is first remaining tab, not All

- Decision: If the stored filter id is missing from tabs, select
  `tabs.first` (sole project when All is hidden; All when 2+). Never resolve to
  `all` when that tab is absent.
- Reason: [OPE-108](https://linear.app/opencode-mobile/issue/OPE-108/remove-project-from-home)
  snapped to All after remove. That id is not in the row once All is hidden,
  so × remove and the selected chip would desync.
- Alternatives considered: Persist All even when hidden; special-case remove
  only. Rejected — a hidden selected id is the same bug as the current
  fallback.
