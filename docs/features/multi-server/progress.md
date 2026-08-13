# Multiple Connection Profiles — Progress

## Shared planning

- [x] UX prototype rounds 1–4; locked title + sheet, Settings → Servers
      management.
- [x] Plan, decisions, and parity gaps started.
- [ ] Persist multiple profiles (id, name, credentials, added projects, active
      id) behind `ConnectionProfileStore`.
- [ ] Rebind `ServerConnection` and clear/scope caches on switch and remove.
- [ ] Home title switcher + workspace sheet.
- [ ] Connection setup Name field; add vs edit vs first-connect modes.
- [ ] Settings one Servers row + Servers management (add / edit / remove).
- [ ] Probe failure and empty-home paths still keyed to the active profile (or
      none).
- [ ] CONTEXT.md Connection Profile glossary when this ships (still v1
      one-profile until then).

## Design-system

- [ ] Tappable home title (extend `OpenCodeTopBar` or equivalent).
- [ ] Servers workspace sheet composition (`OpenCodeBottomSheet`).
- [ ] Name field on connection setup (existing text input).

## iOS

- Not started.

## Android

- Not started.

## Blockers

None.

## Remaining

- Persistence and active-profile switching.
- Home title + sheet.
- Setup Name + add/edit/remove.
- Settings → Servers management.
- Cache / store scoping so profiles cannot leak sessions.
- Glossary update when the slice ships.
