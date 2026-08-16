# OPE-165 Slow reconnect after resume from inactivity

Parent feature: [Foreground Event Stream](plan.md)
Linear: [OPE-165](https://linear.app/opencode-mobile/issue/OPE-165/slow-reconnect-after-resume-from-inactivity)

## Goal

After returning from inactivity, Session Chat keeps the last messages on
screen, does not flicker through **offline**, catch-up of missed messages
starts as soon as HTTP works, and the status bar reaches **live** once.

## Symptom

Status chrome goes **live → connecting → offline → connecting → live**.
(There is no `disconnecting` label; `Disconnected` / `Idle` map to **offline**.)
New messages wait on that last live hop.

## Cause

- Cold resume always tears down SSE. `stop()` clears `wasConnected`.
- iOS `start()` always calls `stop()` first, so every `.active` (and
  `enterHome()`) is a full reconnect. Android `start()` is idempotent and
  has a 5s stop grace.
- MessageSync often runs two authoritative reloads: TransportResumed on
  Connected, plus a 300ms-debounced `server.connected` fullReload.
  [OPE-119](https://linear.app/opencode-mobile/issue/OPE-119/stale-live-session-after-background-network-changes)
  added the Connected-after-drop path; it overlaps with `server.connected`.
- Home Sync Probe does not run on foreground. Protocol is usually cached, so
  health probing is not the resume delay.

## In scope

1. iOS `start()` must not tear down a healthy stream; a cancelled task must
   not overwrite a new Connecting/Connected with Idle/Disconnected.
2. While a foreground retry is still in flight, do not publish **offline**.
3. One MessageSync catch-up per resume generation (coalesce TransportResumed
   and `server.connected`). Keep OPE-119: cold resume still refreshes when
   `server.connected` is missed.
4. Start REST catch-up when HTTP is usable, not after SSE is **live**.
   Cached / last frames stay visible (ADR 0006). Mutations stay gated on live.
5. Tests for the snapshot trace: cached messages stay, one list fetch before
   live, no offline hop on a successful resume.

## Implementation status

- [x] Idempotent iOS `start()`; generation-guarded cancel/idle publishing
- [x] No Disconnected while foreground SSE retry is in flight (iOS + Android)
- [x] MessageSync catch-up on Connecting; one claim per ConnectionEpoch
- [x] Snapshot tests (stream + MessageSync) on both platforms

## Out of scope

- App-managed tunnel, background SSE, or push.
- Changing product labels (`live` / `connecting` / `offline`).
- Home re-probing on every foreground.

## Follow-up (not this ticket unless it falls out cheaply)

A small Foreground Presence module: lifecycle occupies the connection instead
of `start()` / `stop()`; chrome (live) and data catch-up are separate signals;
synthetic reconnect events are not catch-up triggers.

## Expected result

Warm server, Session Chat already open, app resumed from inactivity:

1. Last messages stay on screen.
2. Status is **connecting**, then **live** — no **offline** hop.
3. Missed messages appear from one REST catch-up, which may complete while
   status is still **connecting**.
4. Composer stays disabled until **live**.
