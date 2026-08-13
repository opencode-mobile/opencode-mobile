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
