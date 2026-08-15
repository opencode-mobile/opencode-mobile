# Multiple Connection Profiles — Decisions

## Switch servers; do not merge session feeds

- Date: 2026-08-13
- Decision: home always shows sessions for the **active** Connection Profile
  only. There is no all-servers feed or server-grouped mixed list.
- Reason: prototype prompt and round-1 rejection of a unified feed. Switching
  is the product; aggregation is not.
- Prototype: `docs/prototypes/multi-server-switcher/`.

## Home title is the active server; tap opens the workspace sheet

- Date: 2026-08-13
- Decision: the home top-bar title **is** the profile name (with chevron). Tap
  opens a workspace sheet: Using now, Switch to, Add server. A compact
  overflow menu of servers was rejected.
- Reason: round-1 title picker (A) plus round-2 sheet (A) won. The title always
  answers “which server is this?”, and the sheet is the fuller picker from
  rounds 1B/1C.
- Prototypes: `multi-server-switcher-d2` (locked A), `multi-server-switcher-d3`.

## Settings is one Servers entry, not the switcher

- Date: 2026-08-13
- Decision: Settings keeps a single **Servers** row. Add, edit, and remove live
  on the **Servers** management screen behind that row. Home sheet switches;
  Settings manages.
- Reason: round-4 prototype. Putting the full list on Settings mixed switch
  and manage; one entry matches today’s Server Connection row.
- Prototype: `docs/prototypes/multi-server-switcher-d4/`.

## First launch still creates the first profile

- Date: 2026-08-13
- Decision: App onboarding and Connection onboarding still open the connection
  setup form. The form is not a server list. The switcher appears once at
  least one profile exists (useful once two exist).
- Reason: first connect is still “point the app at a server”; management is a
  later job.

## Connection setup gains a Name

- Date: 2026-08-13
- Decision: each profile has a user-visible **Name** used in the home title
  and management list. Host/URL remains the connection target.
- Reason: the locked title chrome needs a short label; raw host:port is a
  poor title.

## Revises the v1 single-profile rule

- Date: 2026-08-13
- Decision: this slice revisits [Server Connection — Single active Connection
  Profile for v1](../server-connection/decisions.md). v1 shipped one profile;
  this feature makes multiple saved profiles in-scope as a follow-up.
- See: [OPE-5](https://linear.app/opencode-mobile/issue/OPE-5/server-connection).

## Migrate the singleton profile in place

- Date: 2026-08-15
- Decision: On first read, if `active_profile_base_url` is set and no multi-profile catalog exists, create one profile with a new opaque id, persist `host[:port]` as the name, copy worktrees onto `worktrees.{id}`, re-key the password from the URL to the id, and make that profile active.
- Reason: Existing installs already have one profile; the slice adds id + name without a reset.

## Name is optional; identity is id; URL+username is unique

- Date: 2026-08-15
- Decision: Name may be blank; display falls back to `host[:port]` or `"Server"`. Duplicate names are allowed. Saving a second profile with the same normalized URL and username is rejected (edit of that row is allowed). Same URL with a different username is allowed.
- Reason: The home title needs a label, but identity is the opaque id. `(url, username)` is today’s credential/cache pair.

## Successor after removing the active profile

- Date: 2026-08-15
- Decision: Removing a non-active profile leaves active unchanged. Removing the active one activates `remaining[min(removedIndex, last)]`. Removing the last profile clears active and lands on Connection onboarding.
- Reason: No last-used timestamp is persisted; insertion order is the only order.

## Key credentials, worktrees, and session cache by profile id

- Date: 2026-08-15
- Decision: New writes use the opaque profile id. Dual-read the old URL keys only for the migrated profile.
- Reason: URL-only keys cannot scope two profiles or survive a URL edit.

## Add-save activates; edit-save does not switch

- Date: 2026-08-15
- Decision: Saving a new profile (first, sheet Add, or Settings Add) applies it as active. Editing a non-active profile updates it in place and does not switch.
- Reason: The locked prototype adds and switches to the new server; editing another row is management, not a switch.
