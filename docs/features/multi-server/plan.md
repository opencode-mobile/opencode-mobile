# Multiple Connection Profiles

Linear: [OPE-159](https://linear.app/opencode-mobile/issue/OPE-159/multiple-connection-profiles)

Prototype (locked UX):
[`docs/prototypes/multi-server-switcher-d4/index.html`](../../prototypes/multi-server-switcher-d4/index.html)

## User Goal

Save more than one opencode **Connection Profile**, switch which server home
shows, and manage (add, edit, remove) profiles without mixing sessions from
every server on one screen.

## In Scope

- Persist multiple Connection Profiles; exactly **one is active** at a time.
- Home top-bar **title is the active profile name**. Tap opens a workspace
  sheet: Using now, Switch to, Add server, plus a path into Settings.
- Session List, project filters, New session, and Add project stay scoped to
  the **active** profile. No unified cross-server session feed.
- Connection setup form is reused for first connect, add, and edit. Add a
  **Name** field (shown in the home title).
- First launch and empty home (no profiles) stay: App onboarding → Connect to
  server, or Connection onboarding → the same form. After the first save, home
  titles that server.
- Settings has **one Servers** entry (count + active name). That opens a
  **Servers** management screen: list, Add server, remove (× + confirm), tap
  row to edit.
- Probe failure still names the **active** server. Retry or Edit connection
  (does not open the switcher).
- Removing the active profile switches to another saved profile, or lands on
  Connection onboarding if none remain.
- Added projects, session cache, and credentials stay scoped per profile.

## Out Of Scope

- Showing sessions from all servers at once (rejected in the prototype).
- App-managed tunnel, relay, QR pairing, or mDNS discovery.
- Push / background sync across servers.
- Multi-project switching beyond today’s added-project filters on the active
  server.
- Changing App onboarding copy beyond what already says “servers”.
- Export / sync of profiles between devices.

## Server APIs Involved

- Same as [Server Connection](../server-connection/plan.md): `GET /global/health`
  (and v2 `GET /api/health` via existing protocol detection) when testing or
  probing a profile. No new server endpoints. Switching profiles rebinds the
  runtime `ServerConnection` and reloads home for that server.

## Expected Platform Logs

- Profile save / apply / switch / remove (profile id, not password).
- Health probe for the profile being tested or the active profile on home.
- Existing session/project load logs after a switch, tagged with the active
  profile.

## Domain Objects

- **Connection Profile** — base URL, username, password, **name**, opaque id,
  ordered added projects. Extends the v1 single-profile model.
- **Connection Profile Store** — list, active id, save, apply (switch), remove.
- **ServerConnection** — still the runtime client for the **active** profile.
- Probe / session cache / message store keys remain profile-scoped (today they
  already assume one profile; they must not leak across profiles).

## Screen Model UI States And User Actions

**Home**

- Title = active profile name; subtitle = live/offline for that server.
- `titleTapped` — open servers sheet.
- `switchProfile(id)` — make active, dismiss sheet, reload home for that
  server.
- `addServerTapped` — present connection setup in add mode.
- `manageInSettingsTapped` — push Settings (one Servers row).

**Connection setup** (first / add / edit)

- Fields: Name, host, username, password.
- `testConnectionTapped` is a text link under the fields; `saveTapped` is
  the only full-width footer action. Save still requires a successful test
  unless the connection is unchanged (rename-only).
- Edit mode: `removeServerTapped` is the top-bar trash icon → confirm.

**Settings**

- One `serversTapped` action → Servers management.

**Servers management**

- List of saved profiles (active labeled).
- `addServerTapped`, `editProfile(id)`, `removeProfile(id)` (confirm).

**Empty / probe**

- No profiles: existing Connection onboarding.
- Active unreachable: existing Unable to connect + Retry + Edit connection.

## Native Navigation Entry Points

- iOS: home `OpenCodeTopBar` title; Settings → Servers; sheet Add server;
  probe-failure Edit connection.
- Android: same entry points on `MainScreen` / Settings / connection setup
  route.

## Error States

- Test / probe auth failure, unreachable, unhealthy — existing connection
  setup and home probe copy, on the profile being tested or the active one.
- Remove last profile → Connection onboarding.
- Switch to an offline profile → home probe-failure for that profile (cached
  sessions may still show).

## Permission Behavior

- Not applicable.

## Design-System Components

- `OpenCodeTopBar` — extend so the title can be a switcher control (chevron,
  tap). Existing component, extended.
- `OpenCodeBottomSheet` — servers workspace sheet (Using now + list + Add).
  Existing, likely extended or composed; do not invent a second sheet system.
- `OpenCodeListItem` — Settings Servers row and management list. Existing.
- `OpenCodeTextInput` — Name on connection setup. Existing.
- `OpenCodeServerAddressInput`, Save `OpenCodeButton` — existing setup.
- Test connection — feature-local text link (`text-interactive-base`); a
  filled `OpenCodeButton` would compete with Save.
- `delete` catalog icon — Edit server top-bar remove.
- `OpenCodeDialog` — remove-profile confirm (destructive primary). Existing.
- Session List + dock — existing; callers pass only the active profile’s
  sessions and projects.
