# Multiple Connection Profiles — Parity Gaps

## iOS not implemented

- Expected shared intent: save more than one Connection Profile, switch from
  the home title sheet, and manage add/edit/remove under Settings → Servers.
- Current iOS state: still the v1 single-profile Server Connection flow.
- Current Android state: multi-profile persistence, home switcher, setup Name,
  and Settings → Servers shipped on this branch.
- Affected area: missing implementation.
- Reason: OPE-159 implementation started Android-first.
- Owner or next action: implement the same slice on iOS, including the
  `OpenCodeTopBar` title-switcher API.
- Status: temporary.
