# Multiple Connection Profiles — Progress

## Shared planning

- [x] UX prototype rounds 1–4; locked title + sheet, Settings → Servers
      management.
- [x] Plan, decisions, and parity gaps started.
- [x] Persist multiple profiles (id, name, credentials, added projects, active
      id) behind `ConnectionProfileStore`.
- [x] Rebind `ServerConnection` and clear/scope caches on switch and remove
      (Android).
- [x] Home title switcher + workspace sheet (Android).
- [x] Connection setup Name field; add vs edit vs first-connect modes
      (Android).
- [x] Settings one Servers row + Servers management (add / edit / remove)
      (Android).
- [x] Probe failure and empty-home paths still keyed to the active profile (or
      none) (Android).
- [ ] CONTEXT.md Connection Profile glossary when this ships (still v1
      one-profile until iOS lands).

## Design-system

- [x] Tappable home title (extend `OpenCodeTopBar` on Android; iOS pending).
- [x] Servers workspace sheet composition (`OpenCodeBottomSheet`) on Android.
- [x] Name field on connection setup (existing text input) on Android.

## iOS

- Persistence catalog, migration, switch, and remove.
- Home title switcher + Servers sheet.
- Connection setup Name + first / add / edit / remove.
- Settings → Servers management.
- Edit chrome: top-bar trash, Test as a text link, one Save.

## Android

- Persistence catalog, migration, switch, and remove.
- Home title switcher + Servers sheet.
- Connection setup Name + first / add / edit / remove.
- Settings → Servers management.
- Edit chrome: top-bar trash, Test as a text link, one Save.

## Blockers

None.

## Remaining

- CONTEXT.md glossary when both platforms ship.
