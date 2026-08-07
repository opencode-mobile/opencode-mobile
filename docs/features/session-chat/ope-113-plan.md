# OPE-113 — Session working indicator

## Goal

When a Session Chat turn is running, mobile always shows clear waiting chrome
(Stop and/or an in-chat Working/Retrying signal), closing the gap with
web/desktop `session_working` feedback.

## Research

[research-session-running-indicator-gap.md](./research-session-running-indicator-gap.md)

## In scope

1. Apply `session.status` / `session.idle` SSE payloads locally in
   `SessionStatusLiveStore`; keep `GET /session/status` for bootstrap and
   reconnect catch-up.
2. Android: do not emit an empty map when a status GET fails (keep prior).
3. Optimistic busy on prompt/command send; clear on send failure or authoritative
   idle.
4. Drive Stop / `isTurnActive` / todos live from effective working label
   (authoritative ∪ optimistic).
5. In-chat Working/Retrying row when the session is working and the active turn
   has no visible assistant content yet.
6. Unit tests on both platforms for LiveStore apply, failed GET, optimistic
   busy, and Working row gating.

## Out of scope

- Web-style tab progress-dot grid (`SessionProgressIndicatorV2`)
- MCP/LSP status popover
- Design-system shimmer primitive (use existing muted text; no new component)
- Changing home list Running semantics beyond shared label source

## Approach

- LiveStore mirrors web’s immediate status write; REST remains catch-up.
- SessionInfo owns optimistic busy; UI reads one effective `sessionStatusLabel`.
- Timeline appends a synthetic Working/Retrying footer row from Screen Model
  state (feature-local, not a new design-system component).

## Native entry points

- iOS / Android Session Chat composer Stop + timeline
- `DefaultSessionStatusLiveStore` (shared behavior, separate implementations)

## Design-system

- Existing Stop control on Session Composer
- Feature-local muted Working/Retrying text row (not reusable; no shimmer in
  Component Collection)
