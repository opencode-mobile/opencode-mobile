# Sync Probe Progress

Status: inline home and Connection Profile probing is implemented on Android
and iOS. No implementation work remains in this slice.

## Shared Planning

- [x] Feature slice documentation scaffold created.
- [x] Shared probe intent aligned with `CONTEXT.md` and feasibility assumptions.

## Design System

- [x] Confirmed no new reusable component is required for initial probe states.
- [x] Reused existing loading, error, and Retry semantics in project home on
  both platforms.

## Android

- [x] Added probing and probe-failure states to the home ViewModel state model.
- [x] Call `GET /global/health` before treating the connection as usable.
- [x] Map transport, authentication, and unhealthy responses to shared product
  probe language.
- [x] Wire Retry from the probe-failure UI back through the home load path.

## iOS

- [x] Added probing and probe-failure states to the home Screen Model.
- [x] Call `GET /global/health` before treating the connection as usable.
- [x] Map transport, authentication, and unhealthy responses to shared product
  probe language.
- [x] Wire Retry from the probe-failure UI back through the home load path.

## Remaining Work

- No remaining implementation work.
