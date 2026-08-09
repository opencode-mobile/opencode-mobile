# App Onboarding

## User Goal

On first install, introduce OpenCode Mobile with a short classic pager, then
route the user into Connection setup (or home) without implying the app is an
official OpenCode product.

## In Scope

- Full-screen **App onboarding** classic pager shown once before the user has
  completed or skipped it.
- Three pager pages with locked copy beats (see [Locked product rules](#locked-product-rules)).
- Nested **Set up opencode serve** screen opened from page 2’s
  **Need the server first?** link; short in-app steps plus one CTA that opens
  https://opencode.ai/docs/server/ in the system browser.
- Skip on every pager page; final primary CTA **Connect to server** opens the
  existing Connection setup flow.
- Completing Skip or Connect marks App onboarding done so it does not show again
  until local completion state is cleared (e.g. Clear Data).
- After App onboarding is done and no Connection Profile exists, existing
  **Connection onboarding** on home still applies.

## Out Of Scope

- Replacing Connection setup form behavior (URL, credentials, health test, save).
- App-managed Tailscale, tunnel, relay, QR, or mDNS setup.
- Push / background prompts to finish onboarding.
- Multi-profile or multi-server education.
- In-app WebView of the server docs (system browser only).
- Redesigning the home **Connection onboarding** empty state beyond remaining
  the post-pager path when no profile is saved.

## Locked Product Rules

Prototype verdict (classic pager) is recorded in
[`decisions.md`](decisions.md).

| Rule | Locked behavior |
| --- | --- |
| Structure | Classic pager: illustration area, title, body/list, page dots, Skip, Next / Connect |
| Page count | 3 pager pages + 1 nested setup screen |
| Page 1 | Unofficial native client, WIP; not an official OpenCode product |
| Page 2 | Connect to your servers; recommend Tailscale; **Need the server first?** opens nested setup (no extra setup copy on the pager page) |
| Page 3 | Main features list → **Connect to server** |
| Nested setup | Short steps retained; single primary CTA **Open server docs** → https://opencode.ai/docs/server/ |
| Exit | Skip or Connect marks onboarding completed; Connect opens Connection setup |
| Relation | Distinct from **Connection onboarding** (home empty state when no profile) |

### Page copy (canonical)

**Page 1 — Unofficial**

- Title: An unofficial opencode client
- Body: OpenCode Mobile is a native client for opencode sessions — work in
  progress. It is not an official OpenCode product.

**Page 2 — Connect**

- Title: Connect to your servers
- Body: Point the app at an opencode server on your machine. We recommend
  Tailscale so your phone can reach it securely from anywhere.
- Link: Need the server first?

**Page 3 — Features**

- Title: What you can do from here
- Rows:
  - Sessions — List, open, create, rename, fork, archive, delete
  - Chat & abort — Send prompts and stop work in flight
  - Permissions — Approve or deny requests (no lasting remember)
  - Changes — Inspect session diffs
  - Live while open — Foreground event stream; refresh after reconnect
- Primary CTA: Connect to server

**Nested — Set up opencode serve**

- Back returns to the pager (page 2).
- Steps:
  1. Start the server on your machine (`opencode serve`)
  2. Require a password (`OPENCODE_SERVER_PASSWORD`; username defaults to
     `opencode`)
  3. Reach it from your phone (Tailscale recommended; LAN / VPN / HTTPS /
     user-managed tunnel also fine; app does not create a relay)
  4. Connect in the app (URL + password; health check; save Connection Profile)
- Primary CTA: Open server docs → https://opencode.ai/docs/server/

## Server APIs Involved

None for App onboarding itself. Connection setup continues to use
`GET /global/health` after the user exits into that flow.

## Expected Platform Logs

- App onboarding presented (first-run gate).
- Pager next / skip / connect.
- Nested setup opened / closed.
- Server docs URL opened.
- Completion flag written.

## Domain Objects

- **App onboarding** — first-install classic pager + nested setup guide (this
  feature).
- **App onboarding completion** — local boolean (or equivalent) that suppresses
  the pager after Skip or Connect.
- **Connection onboarding** — existing home empty state when no Connection
  Profile is saved (unchanged contract; see
  [server-connection](../server-connection/plan.md)).
- **Connection Profile** — unchanged.

## Screen Model

**UI states**

- `pager(pageIndex)` — one of the three pager pages
- `setupGuide` — nested Set up opencode serve screen
- Not presented when completion is true or when a Connection Profile already
  exists at cold start (profile implies prior setup)

**User actions**

- `skipTapped` — mark completed; dismiss to home (Connection onboarding if no
  profile)
- `nextTapped` — advance page index
- `needServerFirstTapped` — push/present setup guide
- `setupBackTapped` — return to pager page 2
- `openServerDocsTapped` — open https://opencode.ai/docs/server/ externally
- `connectToServerTapped` — mark completed; present Connection setup

## Native Entry Points

- iOS: cold-start / root gate before project list home when App onboarding is
  not completed and no Connection Profile is saved (exact host view TBD in
  implementation; must not replace probe-failure UI).
- Android: same gate in root navigation before home content.

## Error States

- Failure to open the external docs URL: platform-default handling (toast or
  silent); do not block returning via Back.
- No server APIs in this flow; connection errors remain in Connection setup.

## Permission Behavior

Not applicable — App onboarding does not request OS permissions.

## Design-System Components

| Element | Classification |
| --- | --- |
| Primary / full-width buttons | Existing `OpenCodeButton` |
| Typography / colors | Existing OpenCode typography and semantic colors |
| Pager chrome (dots, Skip, illustration area, feature rows) | Feature-local for v1 unless a reusable onboarding primitive is extracted later |
| Nested setup scroll + steps | Feature-local |
| External docs open | Platform browser / `Link` / `Intent.ACTION_VIEW` |

## Relationship To Server Connection

```text
Fresh install
  → App onboarding (this feature), if not completed
      → Skip → home + Connection onboarding (no profile)
      → Connect → Connection setup
  → After profile saved → project list home (existing)
```

Probe failure and Edit connection never re-enter App onboarding.
