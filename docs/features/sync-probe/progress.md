# Sync Probe Progress

## Shared Planning

- [x] Feature slice documentation scaffold created.
- [x] Shared probe intent aligned with `CONTEXT.md` and feasibility assumptions.

## Design System

- [x] Confirmed no new reusable component is required for initial probe states.
- [ ] Validate loading/error visuals against existing component semantics on both
  platforms during implementation.

## Android

- [ ] Add probe status to home ViewModel state model.
- [ ] Call probe endpoint before treating connection as usable.
- [ ] Map transport/auth failures to shared product probe statuses.
- [ ] Add retry interaction coverage in tests.

## iOS

- [ ] Add probe status to home Screen Model state.
- [ ] Call probe endpoint before treating connection as usable.
- [ ] Map transport/auth failures to shared product probe statuses.
- [ ] Add retry interaction coverage in tests.

## Validation Status

- [ ] Android compile/test checks after implementation.
- [ ] iOS simulator build/test checks after implementation.
- [ ] Manual probe validation against `opencode serve` on `:4096`.

## Remaining Work

- Implement probe repository/service adapter on both platforms.
- Integrate probe state into home entry flow.
- Close or document any parity gaps discovered during implementation.
