# Sync Probe Progress

Status: inline home and Connection Profile probing is implemented on Android
and iOS. Remaining work is focused Retry coverage and verification, not a new
probe repository or home integration.

## Shared Planning

- [x] Feature slice documentation scaffold created.
- [x] Shared probe intent aligned with `CONTEXT.md` and feasibility assumptions.

## Design System

- [x] Confirmed no new reusable component is required for initial probe states.
- [x] Reused existing loading, error, and Retry semantics in project home on
  both platforms.
- [ ] Complete final light/dark visual review of probe failure states on both
  platforms.

## Android

- [x] Added probing and probe-failure states to the home ViewModel state model.
- [x] Call `GET /global/health` before treating the connection as usable.
- [x] Map transport, authentication, and unhealthy responses to shared product
  probe language.
- [x] Wire Retry from the probe-failure UI back through the home load path.
- [ ] Add a focused test that starts in probe failure and verifies Retry reaches
  the next home state.

## iOS

- [x] Added probing and probe-failure states to the home Screen Model.
- [x] Call `GET /global/health` before treating the connection as usable.
- [x] Map transport, authentication, and unhealthy responses to shared product
  probe language.
- [x] Wire Retry from the probe-failure UI back through the home load path.
- [ ] Add a focused test that starts in probe failure and verifies Retry reaches
  the next home state.

## Validation Status

- [x] Focused Android home/probe unit tests passed on 2026-07-13.
- [ ] iOS simulator build/test checks after implementation.
- [ ] Manual probe validation against `opencode serve` on `:4096`.

## Remaining Work

- Add explicit Retry transition coverage on both platforms.
- Complete iOS simulator verification and manual live-server validation.
- Complete final light/dark visual review of probe failure states.
